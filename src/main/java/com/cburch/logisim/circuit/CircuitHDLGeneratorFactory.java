/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.circuit;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.fpga.data.MapComponent;
import com.cburch.logisim.fpga.data.MappableResourcesContainer;
import com.cburch.logisim.fpga.designrulecheck.ConnectionPoint;
import com.cburch.logisim.fpga.designrulecheck.CorrectLabel;
import com.cburch.logisim.fpga.designrulecheck.Net;
import com.cburch.logisim.fpga.designrulecheck.Netlist;
import com.cburch.logisim.fpga.designrulecheck.netlistComponent;
import com.cburch.logisim.fpga.gui.Reporter;
import com.cburch.logisim.fpga.hdlgenerator.AbstractHdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.Hdl;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.TickComponentHdlGeneratorFactory;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.ClockHDLGeneratorFactory;
import com.cburch.logisim.util.LineBuffer;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class CircuitHDLGeneratorFactory extends AbstractHdlGeneratorFactory {

  private final Circuit MyCircuit;

  public CircuitHDLGeneratorFactory(Circuit source) {
    MyCircuit = source;
    getWiresPortsDuringHDLWriting = true;
  }

  @Override
  public void getGenerationTimeWiresPorts(Netlist theNetlist, AttributeSet attrs) {
    final var inOutBubbles = theNetlist.numberOfInOutBubbles();
    final var inputBubbles = theNetlist.getNumberOfInputBubbles();
    final var outputBubbles = theNetlist.numberOfOutputBubbles();
    // First we add the wires
    for (final var wire : theNetlist.getAllNets())
      if (!wire.isBus())
        myWires.addWire(String.format("%s%d", NET_NAME, theNetlist.getNetId(wire)), 1);
    // Now we add the busses
    for (final var wire : theNetlist.getAllNets())
      if (wire.isBus() && wire.isRootNet())
        myWires.addWire(String.format("%s%d", BUS_NAME, theNetlist.getNetId(wire)), wire.getBitWidth());
    if (inOutBubbles > 0)
      myPorts.add(Port.INOUT, LOCAL_INOUT_BUBBLE_BUS_NAME, inOutBubbles > 1 ? inOutBubbles : 0, 0);
    for (var clock = 0; clock < theNetlist.numberOfClockTrees(); clock++)
      myPorts.add(Port.INPUT, String.format("%s%d", CLOCK_TREE_NAME, clock), ClockHDLGeneratorFactory.NR_OF_CLOCK_BITS, 0);
    if (theNetlist.requiresGlobalClockConnection())
      myPorts.add(Port.INPUT, TickComponentHdlGeneratorFactory.FPGA_CLOCK, 1, 0);
    if (inputBubbles > 0)
      myPorts.add(Port.INPUT, LOCAL_INPUT_BUBBLE_BUS_NAME, inputBubbles > 1 ? inputBubbles : 0, 0);
    for (var input = 0; input < theNetlist.getNumberOfInputPorts(); input++) {
      final var selectedInput = theNetlist.getInputPin(input);
      if (selectedInput != null)  {
        final var name = selectedInput.getComponent().getAttributeSet().getValue(StdAttr.LABEL);
        final var nrOfBits = selectedInput.getComponent().getAttributeSet().getValue(StdAttr.WIDTH).getWidth();
        myPorts.add(Port.INPUT, CorrectLabel.getCorrectLabel(name), nrOfBits, 0);
      }
    }
    if (outputBubbles > 0)
      myPorts.add(Port.OUTPUT, LOCAL_OUTPUT_BUBBLE_BUS_NAME, outputBubbles > 1 ? outputBubbles : 0, 0);
    for (var output = 0; output < theNetlist.numberOfOutputPorts(); output++) {
      final var selectedInput = theNetlist.getOutputPin(output);
      if (selectedInput != null)  {
        final var name = selectedInput.getComponent().getAttributeSet().getValue(StdAttr.LABEL);
        final var nrOfBits = selectedInput.getComponent().getAttributeSet().getValue(StdAttr.WIDTH).getWidth();
        myPorts.add(Port.OUTPUT, CorrectLabel.getCorrectLabel(name), nrOfBits, 0);
      }
    }
  }

  @Override
  public boolean generateAllHDLDescriptions(
      Set<String> HandledComponents, String WorkingDir, ArrayList<String> Hierarchy) {
    return generateAllHDLDescriptions(HandledComponents, WorkingDir, Hierarchy, false);
  }

  public boolean generateAllHDLDescriptions(
      Set<String> HandledComponents,
      String WorkingDir,
      ArrayList<String> Hierarchy,
      boolean gatedInstance) {
    if (MyCircuit == null) {
      return false;
    }
    if (Hierarchy == null) {
      Hierarchy = new ArrayList<>();
    }
    Netlist MyNetList = MyCircuit.getNetList();
    if (MyNetList == null) {
      return false;
    }
    String WorkPath = WorkingDir;
    if (!WorkPath.endsWith(File.separator)) {
      WorkPath += File.separator;
    }
    MyNetList.setCurrentHierarchyLevel(Hierarchy);
    /* First we handle the normal components */
    for (netlistComponent ThisComponent : MyNetList.getNormalComponents()) {
      String ComponentName =
          ThisComponent.getComponent()
              .getFactory()
              .getHDLName(ThisComponent.getComponent().getAttributeSet());
      if (!HandledComponents.contains(ComponentName)) {
        HdlGeneratorFactory Worker =
            ThisComponent.getComponent()
                .getFactory()
                .getHDLGenerator(ThisComponent.getComponent().getAttributeSet());
        if (Worker == null) {
          Reporter.report.addFatalError(
              "INTERNAL ERROR: Cannot find the VHDL generator factory for component "
                  + ComponentName);
          return false;
        }
        if (!Worker.isOnlyInlined()) {
          if (!Hdl.writeEntity(
              WorkPath + Worker.getRelativeDirectory(),
              Worker.getEntity(
                  MyNetList,
                  ThisComponent.getComponent().getAttributeSet(),
                  ComponentName),
              ComponentName)) {
            return false;
          }
          if (!Hdl.writeArchitecture(
              WorkPath + Worker.getRelativeDirectory(),
              Worker.getArchitecture(
                  MyNetList,
                  ThisComponent.getComponent().getAttributeSet(),
                  ComponentName),
              ComponentName)) {
            return false;
          }
        }
        HandledComponents.add(ComponentName);
      }
    }
    /* Now we go down the hierarchy to get all other components */
    for (netlistComponent ThisCircuit : MyNetList.getSubCircuits()) {
      CircuitHDLGeneratorFactory Worker =
          (CircuitHDLGeneratorFactory)
              ThisCircuit.getComponent()
                  .getFactory()
                  .getHDLGenerator(ThisCircuit.getComponent().getAttributeSet());
      if (Worker == null) {
        Reporter.report.addFatalError(
            "INTERNAL ERROR: Unable to get a subcircuit VHDL generator for '"
                + ThisCircuit.getComponent().getFactory().getName()
                + "'");
        return false;
      }
      Hierarchy.add(
          CorrectLabel.getCorrectLabel(
              ThisCircuit.getComponent().getAttributeSet().getValue(StdAttr.LABEL)));
      if (!Worker.generateAllHDLDescriptions(
          HandledComponents, WorkingDir, Hierarchy, ThisCircuit.isGatedInstance())) {
        return false;
      }
      Hierarchy.remove(Hierarchy.size() - 1);
    }
    /* I also have to generate myself */
    String ComponentName = CorrectLabel.getCorrectLabel(MyCircuit.getName());
    if (gatedInstance) ComponentName = ComponentName.concat("_gated");
    if (!HandledComponents.contains(ComponentName)) {
      if (!Hdl.writeEntity(
          WorkPath + getRelativeDirectory(),
          getEntity(MyNetList, null, ComponentName),
          ComponentName)) {
        return false;
      }

      if (!Hdl.writeArchitecture(
          WorkPath + getRelativeDirectory(),
          getArchitecture(MyNetList, null, ComponentName),
          ComponentName)) {
        return false;
      }
    }
    HandledComponents.add(ComponentName);
    return true;
  }

  /* here the private handles are defined */
  private String GetBubbleIndex(netlistComponent comp, int type) {
    switch (type) {
      case 0:
        return Hdl.bracketOpen()
            + comp.getLocalBubbleInputEndId()
            + Hdl.vectorLoopId()
            + comp.getLocalBubbleInputStartId()
            + Hdl.bracketClose();
      case 1:
        return Hdl.bracketOpen()
            + comp.getLocalBubbleOutputEndId()
            + Hdl.vectorLoopId()
            + comp.getLocalBubbleOutputStartId()
            + Hdl.bracketClose();
      case 2:
        return Hdl.bracketOpen()
            + comp.getLocalBubbleInOutEndId()
            + Hdl.vectorLoopId()
            + comp.getLocalBubbleInOutStartId()
            + Hdl.bracketClose();
    }
    return "";
  }

  @Override
  public ArrayList<String> getComponentDeclarationSection(Netlist TheNetlist, AttributeSet attrs) {
    ArrayList<String> Components = new ArrayList<>();
    Set<String> InstantiatedComponents = new HashSet<>();
    for (netlistComponent Gate : TheNetlist.getNormalComponents()) {
      String CompName =
          Gate.getComponent().getFactory().getHDLName(Gate.getComponent().getAttributeSet());
      if (!InstantiatedComponents.contains(CompName)) {
        InstantiatedComponents.add(CompName);
        HdlGeneratorFactory Worker =
            Gate.getComponent()
                .getFactory()
                .getHDLGenerator(Gate.getComponent().getAttributeSet());
        if (Worker != null) {
          if (!Worker.isOnlyInlined()) {
            Components.addAll(
                Worker.getComponentInstantiation(
                    TheNetlist,
                    Gate.getComponent().getAttributeSet(),
                    CompName));
          }
        }
      }
    }
    InstantiatedComponents.clear();
    for (netlistComponent Gate : TheNetlist.getSubCircuits()) {
      String CompName =
          Gate.getComponent().getFactory().getHDLName(Gate.getComponent().getAttributeSet());
      if (Gate.isGatedInstance()) CompName = CompName.concat("_gated");
      if (!InstantiatedComponents.contains(CompName)) {
        InstantiatedComponents.add(CompName);
        HdlGeneratorFactory Worker =
            Gate.getComponent()
                .getFactory()
                .getHDLGenerator(Gate.getComponent().getAttributeSet());
        SubcircuitFactory sub = (SubcircuitFactory) Gate.getComponent().getFactory();
        if (Worker != null) {
          Components.addAll(
              Worker.getComponentInstantiation(
                  sub.getSubcircuit().getNetList(),
                  Gate.getComponent().getAttributeSet(),
                  CompName));
        }
      }
    }
    return Components;
  }

  public ArrayList<String> GetHDLWiring(Netlist TheNets) {
    final var Contents = LineBuffer.getHdlBuffer();
    final StringBuilder OneLine = new StringBuilder();
    /* we cycle through all nets with a forcedrootnet annotation */
    for (Net ThisNet : TheNets.getAllNets()) {
      if (ThisNet.isForcedRootNet()) {
        /* now we cycle through all the bits */
        for (int bit = 0; bit < ThisNet.getBitWidth(); bit++) {
          /* First we perform all source connections */
          for (ConnectionPoint Source : ThisNet.getSourceNets(bit)) {
            OneLine.setLength(0);
            if (ThisNet.isBus()) {
              OneLine.append(BUS_NAME)
                  .append(TheNets.getNetId(ThisNet))
                  .append(Hdl.bracketOpen())
                  .append(bit)
                  .append(Hdl.bracketClose());
            } else {
              OneLine.append(NET_NAME).append(TheNets.getNetId(ThisNet));
            }
            while (OneLine.length() < SIGNAL_ALLIGNMENT_SIZE) OneLine.append(" ");

            Contents.addUnique(LineBuffer.format("   {{assign}} {{1}} {{=}} {{2}}{{3}}{{<}}{{4}}{{>}};",
                OneLine, BUS_NAME, TheNets.getNetId(Source.getParentNet()), Source.getParentNetBitIndex()));
          }
          /* Next we perform all sink connections */
          for (ConnectionPoint Source : ThisNet.getSinkNets(bit)) {
            OneLine.setLength(0);
            OneLine.append(BUS_NAME)
                .append(TheNets.getNetId(Source.getParentNet()))
                .append(Hdl.bracketOpen())
                .append(Source.getParentNetBitIndex())
                .append(Hdl.bracketClose());
            while (OneLine.length() < SIGNAL_ALLIGNMENT_SIZE) OneLine.append(" ");
            OneLine.append(Hdl.assignOperator());
            if (ThisNet.isBus()) {
              OneLine.append(BUS_NAME)
                  .append(TheNets.getNetId(ThisNet))
                  .append(Hdl.bracketOpen())
                  .append(bit)
                  .append(Hdl.bracketClose());
            } else {
              OneLine.append(NET_NAME).append(TheNets.getNetId(ThisNet));
            }
            Contents.addUnique(LineBuffer.format("   {{1}}{{2}};", Hdl.assignPreamble(), OneLine));
          }
        }
      }
    }
    return Contents.get();
  }

  @Override
  public ArrayList<String> getModuleFunctionality(Netlist theNetlist, AttributeSet attrs) {
    final var contents = LineBuffer.getHdlBuffer();
    var isFirstLine = true;
    final var temp = new StringBuilder();
    final var compIds = new HashMap<String, Long>();
    /* we start with the connection of the clock sources */
    for (final var clockSource : theNetlist.getClockSources()) {
      if (isFirstLine) {
        contents.add("");
        contents.addRemarkBlock("Here all clock generator connections are defined");
        isFirstLine = false;
      }
      if (!clockSource.isEndConnected(0)) {
        // FIXME: hardcoded string
        final var msg = String.format("Clock component found with no connection, skipping: '%s'",
                clockSource.getComponent().getAttributeSet().getValue(StdAttr.LABEL));
        if (clockSource.getComponent().getAttributeSet().getValue(StdAttr.LABEL).equals("sysclk")) {
          Reporter.report.addInfo(msg);
        } else {
          Reporter.report.addWarning(msg);
        }
        continue;
      }
      final var clockNet = Hdl.getClockNetName(clockSource, 0, theNetlist);
      if (clockNet.isEmpty()) {
        // FIXME: hardcoded string
        Reporter.report.addFatalError("INTERNAL ERROR: Cannot find clocknet!");
      }
      String ConnectedNet = Hdl.getNetName(clockSource, 0, true, theNetlist);
      temp.setLength(0);
      temp.append(ConnectedNet);
      // Padding
      while (temp.length() < SIGNAL_ALLIGNMENT_SIZE) {
        temp.append(" ");
      }
      if (!theNetlist.requiresGlobalClockConnection()) {
        contents.add("   {{assign}} {{1}} {{=}} {{2}}{{<}}{{3}}{{>}};", temp, clockNet, ClockHDLGeneratorFactory.DERIVED_CLOCK_INDEX);
      } else {
        contents.add("   {{assign}} {{1}} {{=}} {{2}};", temp, TickComponentHdlGeneratorFactory.FPGA_CLOCK);
      }
    }
    /* Here we define all wiring; hence all complex splitter connections */
    final var wiring = GetHDLWiring(theNetlist);
    if (!wiring.isEmpty()) {
      contents.add("");
      contents.addRemarkBlock("Here all wiring is defined");
      contents.add(wiring);
    }
    /* Now we define all input signals; hence Input port -> Internal Net */
    isFirstLine = true;
    for (var i = 0; i < theNetlist.getNumberOfInputPorts(); i++) {
      if (isFirstLine) {
        contents.add("").addRemarkBlock("Here all input connections are defined");
        isFirstLine = false;
      }
      final var myInput = theNetlist.getInputPin(i);
      contents.add(
          getSignalMap(
              CorrectLabel.getCorrectLabel(myInput.getComponent().getAttributeSet().getValue(StdAttr.LABEL)),
              myInput,
              0,
              3,
              theNetlist));
    }
    /* Now we define all output signals; hence Internal Net -> Input port */
    isFirstLine = true;
    for (var i = 0; i < theNetlist.numberOfOutputPorts(); i++) {
      if (isFirstLine) {
        contents.add("");
        contents.addRemarkBlock("Here all output connections are defined");
        isFirstLine = false;
      }
      netlistComponent MyOutput = theNetlist.getOutputPin(i);
      contents.add(
          getSignalMap(
              CorrectLabel.getCorrectLabel(MyOutput.getComponent().getAttributeSet().getValue(StdAttr.LABEL)),
              MyOutput,
              0,
              3,
              theNetlist));
    }
    /* Here all in-lined components are generated */
    isFirstLine = true;
    for (final var comp : theNetlist.getNormalComponents()) {
      var worker = comp.getComponent().getFactory().getHDLGenerator(comp.getComponent().getAttributeSet());
      if (worker != null) {
        if (worker.isOnlyInlined()) {
          final var inlinedName = comp.getComponent().getFactory().getHDLName(comp.getComponent().getAttributeSet());
          final var InlinedId = "InlinedComponent";
          var id = (compIds.containsKey(InlinedId)) ? compIds.get(InlinedId) : (long) 1;
          if (isFirstLine) {
            contents.add("");
            contents.addRemarkBlock("Here all in-lined components are defined");
            isFirstLine = false;
          }
          contents.add(worker.getInlinedCode(theNetlist, id++, comp, inlinedName));
          compIds.put(InlinedId, id);
        }
      }
    }
    /* Here all "normal" components are generated */
    isFirstLine = true;
    for (final var comp : theNetlist.getNormalComponents()) {
      var worker = comp.getComponent().getFactory().getHDLGenerator(comp.getComponent().getAttributeSet());
      if (worker != null) {
        if (!worker.isOnlyInlined()) {
          final var compName = comp.getComponent().getFactory().getHDLName(comp.getComponent().getAttributeSet());
          final var compId = "NormalComponent";
          var id = (compIds.containsKey(compId)) ? compIds.get(compId) : (long) 1;
          if (isFirstLine) {
            contents.add("").addRemarkBlock("Here all normal components are defined");
            isFirstLine = false;
          }
          contents.add(worker.getComponentMap(theNetlist, id++, comp, compName));
          compIds.put(compId, id);
        }
      }
    }
    /* Finally we instantiate all sub-circuits */
    isFirstLine = true;
    for (final var comp : theNetlist.getSubCircuits()) {
      final var worker = comp.getComponent().getFactory().getHDLGenerator(comp.getComponent().getAttributeSet());
      if (worker != null) {
        var compName = comp.getComponent().getFactory().getHDLName(comp.getComponent().getAttributeSet());
        if (comp.isGatedInstance())  compName = compName.concat("_gated");
        final var CompId = "SubCircuits";
        var id = (compIds.containsKey(CompId)) ? compIds.get(CompId) : (long) 1;
        final var compMap = worker.getComponentMap(theNetlist, id++, comp, compName);
        if (!compMap.isEmpty()) {
          if (isFirstLine) {
            contents.add("").addRemarkBlock("Here all sub-circuits are defined");
            isFirstLine = false;
          }
          compIds.remove(CompId);
          compIds.put(CompId, id);
          contents.add(compMap);
        }
      }
    }
    contents.add("");
    return contents.get();
  }

  @Override
  public SortedMap<String, String> getPortMap(Netlist nets, Object MapInfo) {
    final var PortMap = new TreeMap<String, String>();
    if (MapInfo == null) return null;
    final var topLevel = MapInfo instanceof MappableResourcesContainer;
    final var componentInfo = topLevel ? null : (netlistComponent) MapInfo;
    var mapInfo = topLevel ? (MappableResourcesContainer) MapInfo : null;
    final var Preamble = topLevel ? "s_" : "";
    final var sub = topLevel ? null : (SubcircuitFactory) componentInfo.getComponent().getFactory();
    final var myNetList = topLevel ? nets : sub.getSubcircuit().getNetList();

    /* First we instantiate the Clock tree busses when present */
    for (var i = 0; i < myNetList.numberOfClockTrees(); i++) {
      PortMap.put(CLOCK_TREE_NAME + i, Preamble + CLOCK_TREE_NAME + i);
    }
    if (myNetList.requiresGlobalClockConnection()) {
      PortMap.put(TickComponentHdlGeneratorFactory.FPGA_CLOCK, TickComponentHdlGeneratorFactory.FPGA_CLOCK);
    }
    if (myNetList.getNumberOfInputBubbles() > 0) {
      // FIXME: remove + by concatination.
      PortMap.put(LOCAL_INPUT_BUBBLE_BUS_NAME,
          topLevel ? Preamble + LOCAL_INPUT_BUBBLE_BUS_NAME : LOCAL_INPUT_BUBBLE_BUS_NAME + GetBubbleIndex(componentInfo, 0));
    }
    if (myNetList.numberOfOutputBubbles() > 0) {
      // FIXME: remove + by concatination.
      PortMap.put(LOCAL_OUTPUT_BUBBLE_BUS_NAME,
          topLevel ? Preamble + LOCAL_OUTPUT_BUBBLE_BUS_NAME : LOCAL_OUTPUT_BUBBLE_BUS_NAME + GetBubbleIndex(componentInfo, 1));
    }

    final var nrOfIOBubbles = myNetList.numberOfInOutBubbles();
    if (nrOfIOBubbles > 0) {
      if (topLevel) {
        final var vector = new StringBuilder();
        for (var i = nrOfIOBubbles - 1; i >= 0; i--) {
          /* first pass find the component which is connected to this io */
          var compPin = -1;
          MapComponent map = null;
          for (final var key : mapInfo.getMappableResources().keySet()) {
            final var comp = mapInfo.getMappableResources().get(key);
            if (comp.hasIos()) {
              final var id = comp.getIoBubblePinId(i);
              if (id >= 0) {
                compPin = id;
                map = comp;
                break;
              }
            }
          }
          if (map == null || compPin < 0) {
            // FIXME: hardcoded string
            Reporter.report.addError("BUG: did not find IOpin");
            continue;
          }
          if (!map.isMapped(compPin) || map.isOpenMapped(compPin)) {
            // FIXME: rewrite using LineBuffer
            if (Hdl.isVhdl())
              PortMap.put(LOCAL_INOUT_BUBBLE_BUS_NAME + "(" + i + ")", "OPEN");
            else {
              if (vector.length() != 0) vector.append(",");
              vector.append("OPEN"); // still not found the correct method but this seems to work
            }
          } else {
            if (Hdl.isVhdl())
              PortMap.put(
                  LOCAL_INOUT_BUBBLE_BUS_NAME + "(" + i + ")",
                  (map.isExternalInverted(compPin) ? "n_" : "") + map.getHdlString(compPin));
            else {
              if (vector.length() != 0) vector.append(",");
              vector
                  .append(map.isExternalInverted(compPin) ? "n_" : "")
                  .append(map.getHdlString(compPin));
            }
          }
        }
        if (Hdl.isVerilog())
          PortMap.put(LOCAL_INOUT_BUBBLE_BUS_NAME, vector.toString());
      } else {
        PortMap.put(LOCAL_INOUT_BUBBLE_BUS_NAME, LOCAL_INOUT_BUBBLE_BUS_NAME + GetBubbleIndex(componentInfo, 2));
      }
    }

    final var nrOfInputPorts = myNetList.getNumberOfInputPorts();
    if (nrOfInputPorts > 0) {
      for (var i = 0; i < nrOfInputPorts; i++) {
        netlistComponent selected = myNetList.getInputPin(i);
        if (selected != null) {
          final var pinLabel = CorrectLabel.getCorrectLabel(selected.getComponent().getAttributeSet().getValue(StdAttr.LABEL));
          if (topLevel) {
            PortMap.put(pinLabel, Preamble + pinLabel);
          } else {
            final var endId = nets.getEndIndex(componentInfo, pinLabel, false);
            if (endId < 0) {
              // FIXME: hardcoded string
              Reporter.report.addFatalError(
                  String.format("INTERNAL ERROR! Could not find the end-index of a sub-circuit component: '%s'", pinLabel));
            } else {
              PortMap.putAll(Hdl.getNetMap(pinLabel, true, componentInfo, endId, nets));
            }
          }
        }
      }
    }

    final var nrOfInOutPorts = myNetList.numberOfInOutPorts();
    if (nrOfInOutPorts > 0) {
      for (var i = 0; i < nrOfInOutPorts; i++) {
        final var selected = myNetList.getInOutPin(i);
        if (selected != null) {
          final var pinLabel = CorrectLabel.getCorrectLabel(selected.getComponent().getAttributeSet().getValue(StdAttr.LABEL));
          if (topLevel) {
            /* Do not exist yet in logisim */
            /* TODO: implement by going over each bit */
          } else {
            final var endId = nets.getEndIndex(componentInfo, pinLabel, false);
            if (endId < 0) {
              // FIXME: hardcoded string
              Reporter.report.addFatalError(
                      String.format("INTERNAL ERROR! Could not find the end-index of a sub-circuit component: '%s'", pinLabel));
            } else {
              PortMap.putAll(Hdl.getNetMap(pinLabel, true, componentInfo, endId, nets));
            }
          }
        }
      }
    }

    final var nrOfOutputPorts = myNetList.numberOfOutputPorts();
    if (nrOfOutputPorts > 0) {
      for (var i = 0; i < nrOfOutputPorts; i++) {
        final var selected = myNetList.getOutputPin(i);
        if (selected != null) {
          final var pinLabel = CorrectLabel.getCorrectLabel(selected.getComponent().getAttributeSet().getValue(StdAttr.LABEL));
          if (topLevel) {
            PortMap.put(pinLabel, Preamble + pinLabel);
          } else {
            final var endid = nets.getEndIndex(componentInfo, pinLabel, true);
            if (endid < 0) {
              // FIXME: hardcoded string
              Reporter.report.addFatalError(
                      String.format("INTERNAL ERROR! Could not find the end-index of a sub-circuit component: '%s'", pinLabel));
            } else {
              PortMap.putAll(Hdl.getNetMap(pinLabel, true, componentInfo, endid, nets));
            }
          }
        }
      }
    }
    return PortMap;
  }

  private String getSignalMap(String portName, netlistComponent comp, int endIndex, int tabSize, Netlist TheNets) {
    final var contents = new StringBuilder();
    final var source = new StringBuilder();
    final var destination = new StringBuilder();
    final var tab = new StringBuilder();
    if ((endIndex < 0) || (endIndex >= comp.nrOfEnds())) {
      // FIXME: hardcoded string
      Reporter.report.addFatalError(
          String.format(
              "INTERNAL ERROR: Component tried to index non-existing SolderPoint: '%s'",
              comp.getComponent().getAttributeSet().getValue(StdAttr.LABEL)));
      return "";
    }
    tab.append(" ".repeat(tabSize));
    final var connectionInformation = comp.getEnd(endIndex);
    final var isOutput = connectionInformation.isOutputEnd();
    final var nrOfBits = connectionInformation.getNrOfBits();
    if (nrOfBits == 1) {
      /* Here we have the easy case, just a single bit net */
      if (isOutput) {
        if (!comp.isEndConnected(endIndex)) return " ";
        source.append(portName);
        destination.append(Hdl.getNetName(comp, endIndex, true, TheNets));
      } else {
        if (!comp.isEndConnected(endIndex)) {
          // FIXME: hardcoded string
          Reporter.report.addSevereWarning(
              "Found an unconnected output pin, tied the pin to ground!");
        }
        source.append(Hdl.getNetName(comp, endIndex, true, TheNets));
        destination.append(portName);
        if (!comp.isEndConnected(endIndex)) return contents.toString();
      }
      while (destination.length() < SIGNAL_ALLIGNMENT_SIZE) destination.append(" ");
      contents
          .append(tab)
          .append(Hdl.assignPreamble())
          .append(destination)
          .append(Hdl.assignOperator())
          .append(source)
          .append(";");
    } else {
      /*
       * Here we have the more difficult case, it is a bus that needs to
       * be mapped
       */
      /* First we check if the bus has a connection */
      var connected = false;
      for (var i = 0; i < nrOfBits; i++) {
        if (connectionInformation.get((byte) i).getParentNet() != null) connected = true;
      }
      if (!connected) {
        /* Here is the easy case, the bus is unconnected */
        if (isOutput) return contents.toString();
        // FIXME: hardcoded string
        Reporter.report.addSevereWarning("Found an unconnected output bus pin, tied all the pin bits to ground!");
        destination.append(portName);
        while (destination.length() < SIGNAL_ALLIGNMENT_SIZE) destination.append(" ");
        contents
            .append(tab)
            .append(Hdl.assignPreamble())
            .append(destination)
            .append(Hdl.assignOperator())
            .append(Hdl.getZeroVector(nrOfBits, true))
            .append(";");
      } else {
        /*
         * There are connections, we detect if it is a continues bus
         * connection
         */
        if (TheNets.isContinuesBus(comp, endIndex)) {
          destination.setLength(0);
          source.setLength(0);
          /* Another easy case, the continues bus connection */
          if (isOutput) {
            source.append(portName);
            destination.append(Hdl.getBusNameContinues(comp, endIndex, TheNets));
          } else {
            destination.append(portName);
            source.append(Hdl.getBusNameContinues(comp, endIndex, TheNets));
          }
          while (destination.length() < SIGNAL_ALLIGNMENT_SIZE) destination.append(" ");
          contents
              .append(tab)
              .append(Hdl.assignPreamble())
              .append(destination)
              .append(Hdl.assignOperator())
              .append(source)
              .append(";");
        } else {
          /* The last case, we have to enumerate through each bit */
          for (int bit = 0; bit < nrOfBits; bit++) {
            source.setLength(0);
            destination.setLength(0);
            if (isOutput) {
              source
                  .append(portName)
                  .append(Hdl.bracketOpen())
                  .append(bit)
                  .append(Hdl.bracketClose());
            } else {
              destination
                  .append(portName)
                  .append(Hdl.bracketOpen())
                  .append(bit)
                  .append(Hdl.bracketClose());
            }
            final var solderPoint = connectionInformation.get((byte) bit);
            if (solderPoint.getParentNet() == null) {
              /* The net is not connected */
              if (isOutput) continue;
              // FIXME: hardcoded string
              Reporter.report.addSevereWarning(String.format("Found an unconnected output bus pin, tied bit %d to ground!", bit));
              source.append(Hdl.getZeroVector(1, true));
            } else {
              /*
               * The net is connected, we have to find out if the
               * connection is to a bus or to a normal net
               */
              if (solderPoint.getParentNet().getBitWidth() == 1) {
                /* The connection is to a Net */
                if (isOutput) {
                  destination.append(NET_NAME).append(TheNets.getNetId(solderPoint.getParentNet()));
                } else {
                  source.append(NET_NAME).append(TheNets.getNetId(solderPoint.getParentNet()));
                }
              } else {
                /* The connection is to an entry of a bus */
                if (isOutput) {
                  destination
                      .append(BUS_NAME)
                      .append(TheNets.getNetId(solderPoint.getParentNet()))
                      .append(Hdl.bracketOpen())
                      .append(solderPoint.getParentNetBitIndex())
                      .append(Hdl.bracketClose());
                } else {
                  source
                      .append(BUS_NAME)
                      .append(TheNets.getNetId(solderPoint.getParentNet()))
                      .append(Hdl.bracketOpen())
                      .append(solderPoint.getParentNetBitIndex())
                      .append(Hdl.bracketClose());
                }
              }
            }
            while (destination.length() < SIGNAL_ALLIGNMENT_SIZE) destination.append(" ");
            if (bit != 0) contents.append("\n");
            contents
                .append(tab)
                .append(Hdl.assignPreamble())
                .append(destination)
                .append(Hdl.assignOperator())
                .append(source)
                .append(";");
          }
        }
      }
    }
    return contents.toString();
  }
}
