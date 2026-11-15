package logisimmcp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;

import java.util.*;

/**
 * High-precision Design Rule Checker (DRC) & live simulation error auditor.
 */
public final class CircuitAuditor {
    private CircuitAuditor() {}

    public static Map<String, Object> audit(
            Project proj, Circuit targetCircuit, boolean recursive, boolean includeInfo) {
        Canvas canvas = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        List<Circuit> circuits = new ArrayList<Circuit>();
        if (recursive && proj.getLogisimFile() != null) {
            circuits.addAll(proj.getLogisimFile().getCircuits());
        } else {
            circuits.add(targetCircuit);
        }

        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        int errors = 0;
        int warnings = 0;
        int infos = 0;

        for (Circuit c : circuits) {
            CircuitState st = null;
            try {
                st = (c == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(c);
                if (st != null && st.getPropagator() != null) {
                    st.getPropagator().propagate();
                }
            } catch (Throwable ignored) {}

            List<Map<String, Object>> cIssues = auditSingleCircuit(c, st, canvas, includeInfo);
            for (Map<String, Object> iss : cIssues) {
                issues.add(iss);
                String sev = String.valueOf(iss.get("severity"));
                if ("error".equals(sev)) errors++;
                else if ("warning".equals(sev)) warnings++;
                else infos++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("errors", Integer.valueOf(errors));
        summary.put("warnings", Integer.valueOf(warnings));
        summary.put("info", Integer.valueOf(infos));
        summary.put("circuits_audited", Integer.valueOf(circuits.size()));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(errors == 0));
        out.put("clean", Boolean.valueOf(errors == 0 && warnings == 0));
        out.put("summary", summary);
        out.put("issues", issues);
        return out;
    }

    private static List<Map<String, Object>> auditSingleCircuit(
            Circuit circ, CircuitState state, Canvas canvas, boolean includeInfo) {
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();

        // 1. Live oscillation check
        if (state != null && state.getPropagator() != null && state.getPropagator().isOscillating()) {
            issues.add(issue("error", "oscillation_detected", circ, null, null,
                    "Simulation is oscillating (unstable combinational feedback loop detected).",
                    "Break combinational loop with a clocked Register/Flip-Flop or inspect feedback wires."));
        }

        Map<String, List<Component>> tunnelsByLabel = new LinkedHashMap<String, List<Component>>();
        Map<Location, List<ComponentMeta.ResolvedPort>> portsByLoc = new HashMap<Location, List<ComponentMeta.ResolvedPort>>();
        Map<Location, List<Component>> ownersByLoc = new HashMap<Location, List<Component>>();
        List<Component> nonWires = new ArrayList<Component>(circ.getNonWires());

        // Gather ports & tunnel groups
        for (Component comp : nonWires) {
            String fname = comp.getFactory().getName();
            List<ComponentMeta.ResolvedPort> ports = ComponentMeta.inspectPorts(comp, circ, canvas);

            if ("Tunnel".equals(fname)) {
                String lbl = ComponentMeta.getComponentLabel(comp);
                if (lbl.isEmpty()) {
                    issues.add(issue("error", "tunnel_unlabelled", circ, comp, comp.getLocation(),
                            "Tunnel has an empty label and will never connect to anything.",
                            "Set a non-empty 'label' attribute on " + ComponentMeta.componentId(comp) + "."));
                } else {
                    List<Component> grp = tunnelsByLabel.get(lbl);
                    if (grp == null) {
                        grp = new ArrayList<Component>();
                        tunnelsByLabel.put(lbl, grp);
                    }
                    grp.add(comp);
                }
            }

            // Output connectivity check helper
            int totalOuts = 0;
            int connectedOuts = 0;
            List<ComponentMeta.ResolvedPort> floatingOutputs = new ArrayList<ComponentMeta.ResolvedPort>();

            for (ComponentMeta.ResolvedPort rp : ports) {
                Location pt = rp.location;
                List<ComponentMeta.ResolvedPort> plist = portsByLoc.get(pt);
                if (plist == null) {
                    plist = new ArrayList<ComponentMeta.ResolvedPort>();
                    portsByLoc.put(pt, plist);
                }
                plist.add(rp);
                List<Component> olist = ownersByLoc.get(pt);
                if (olist == null) {
                    olist = new ArrayList<Component>();
                    ownersByLoc.put(pt, olist);
                }
                olist.add(comp);

                boolean conn = ComponentMeta.isPortConnected(circ, comp, pt);
                boolean isIn = rp.endData.isInput() && !rp.endData.isOutput();
                boolean isOut = rp.endData.isOutput() && !rp.endData.isInput();
                boolean optional = isOptionalPort(comp, fname, rp);

                if (isIn && !conn) {
                    if ("clk".equals(rp.name)) {
                        issues.add(issue("error", "unclocked_sequential", circ, comp, pt,
                                "Clock input 'clk' on " + ComponentMeta.componentId(comp) + " is unconnected.",
                                "Connect a Clock or clock tunnel to port '" + rp.name + "' (index " + rp.index + ")."));
                    } else if (!optional) {
                        issues.add(issue("error", "floating_required_input", circ, comp, pt,
                                "Required input port '" + rp.name + "' (" + rp.endData.getWidth().getWidth()
                                + "-bit, index " + rp.index + ") on " + ComponentMeta.componentId(comp) + " is floating.",
                                "Wire a driver or Constant/Tunnel to (" + pt.getX() + "," + pt.getY() + ")."));
                    } else if (includeInfo) {
                        issues.add(issue("info", "optional_input_unconnected", circ, comp, pt,
                                "Optional port '" + rp.name + "' on " + ComponentMeta.componentId(comp) + " is unconnected.",
                                null));
                    }
                }

                if (isOut) {
                    totalOuts++;
                    if (conn) connectedOuts++;
                    else if (!optional) floatingOutputs.add(rp);
                }

                if (state != null) {
                    Value val = state.getValue(pt);
                    if (val != null && val.isErrorValue()) {
                        issues.add(issue("error", "signal_error", circ, comp, pt,
                                "Live ERROR signal on " + ComponentMeta.componentId(comp) + " port '" + rp.name
                                + "' (width mismatch or bus contention at " + pt.getX() + "," + pt.getY() + ").",
                                "Check bit widths and ensure only one output drives this net."));
                    }
                }
            }

            // Warn on components whose primary outputs are completely floating (skip Output Pin/Probe)
            if (totalOuts > 0 && connectedOuts == 0 && !floatingOutputs.isEmpty()
                    && !"Probe".equals(fname) && !"Pin".equals(fname) && !"LED".equals(fname)) {
                ComponentMeta.ResolvedPort fp = floatingOutputs.get(0);
                issues.add(issue("warning", "floating_output", circ, comp, fp.location,
                        "Component " + ComponentMeta.componentId(comp) + " has no connected outputs (dead logic).",
                        "Connect output port '" + fp.name + "' or remove unused component."));
            }
            if ("Pin".equals(fname) && !ComponentMeta.isPortConnected(circ, comp, comp.getLocation())) {
                String lbl = ComponentMeta.getComponentLabel(comp);
                issues.add(issue("warning", "unconnected_pin", circ, comp, comp.getLocation(),
                        "Pin" + (lbl.isEmpty() ? "" : " '" + lbl + "'") + " at " + comp.getLocation() + " is unconnected.",
                        "Connect a wire or Tunnel to this Pin."));
            }
            if ("Tunnel".equals(fname) && !ComponentMeta.isPortConnected(circ, comp, comp.getLocation())) {
                String lbl = ComponentMeta.getComponentLabel(comp);
                issues.add(issue("warning", "floating_tunnel", circ, comp, comp.getLocation(),
                        "Tunnel '" + lbl + "' at " + comp.getLocation() + " is not attached to any wire or component port.",
                        "Connect Tunnel to a wire/port (remember facing points toward the incoming wire)."));
            }

            // Subcircuit stale pin count check
            if (comp.getFactory() instanceof SubcircuitFactory) {
                SubcircuitFactory sf = (SubcircuitFactory) comp.getFactory();
                int subPinCount = sf.getSubcircuit().getAppearance().getCircuitPins().getPins().size();
                if (ports.size() != subPinCount) {
                    issues.add(issue("error", "subcircuit_pin_mismatch", circ, comp, comp.getLocation(),
                            "Subcircuit instance " + ComponentMeta.componentId(comp) + " exposes " + ports.size()
                            + " ports, but circuit '" + sf.getSubcircuit().getName() + "' defines " + subPinCount + " Pins.",
                            "Reconfigure appearance or recreate subcircuit instance."));
                }
            }
        }

        // 2. Tunnel health: orphan & width mismatch
        Set<String> allTunnelNames = tunnelsByLabel.keySet();
        for (Map.Entry<String, List<Component>> entry : tunnelsByLabel.entrySet()) {
            String lbl = entry.getKey();
            List<Component> grp = entry.getValue();
            if (grp.size() == 1) {
                Component lone = grp.get(0);
                List<String> others = new ArrayList<String>(allTunnelNames);
                others.remove(lbl);
                List<String> near = ComponentMeta.closestNames(lbl, others, 3);
                String sug = "Add matching Tunnel '" + lbl + "' at destination"
                        + (!near.isEmpty() ? ", or check typo (similar labels: " + String.join(", ", near) + ")." : ".");
                issues.add(issue("error", "tunnel_orphan", circ, lone, lone.getLocation(),
                        "Orphan Tunnel '" + lbl + "' appears only once in circuit '" + circ.getName() + "'.",
                        sug));
            }
            Set<Integer> widths = new LinkedHashSet<Integer>();
            for (Component tc : grp) {
                BitWidth bw = tc.getAttributeSet().getValue(StdAttr.WIDTH);
                if (bw != null) widths.add(Integer.valueOf(bw.getWidth()));
            }
            if (widths.size() > 1) {
                issues.add(issue("error", "tunnel_width_mismatch", circ, grp.get(0), grp.get(0).getLocation(),
                        "Tunnels named '" + lbl + "' have conflicting bit widths: " + widths + ".",
                        "Set identical 'width' on all '" + lbl + "' Tunnels."));
            }
        }

        // 3. Port conflicts & wire-through-port collisions
        for (Map.Entry<Location, List<ComponentMeta.ResolvedPort>> e : portsByLoc.entrySet()) {
            List<ComponentMeta.ResolvedPort> atLoc = e.getValue();
            if (atLoc.size() <= 1) continue;
            List<Component> owners = ownersByLoc.get(e.getKey());
            int outCount = 0;
            Set<Integer> wSet = new HashSet<Integer>();
            for (ComponentMeta.ResolvedPort rp : atLoc) {
                if (rp.endData.isOutput() && !rp.endData.isInput()) outCount++;
                wSet.add(Integer.valueOf(rp.endData.getWidth().getWidth()));
            }
            if (outCount > 1) {
                issues.add(issue("error", "multi_driver_at_point", circ, owners.get(0), e.getKey(),
                        "Multiple driving outputs colocated at " + e.getKey() + ".",
                        "Separate driving components or use a Multiplexer/tri-state bus."));
            }
            if (wSet.size() > 1) {
                issues.add(issue("error", "port_width_mismatch", circ, owners.get(0), e.getKey(),
                        "Ports with incompatible widths " + wSet + " meet directly at " + e.getKey() + ".",
                        "Match component bit widths or insert a Splitter/Bit Extender."));
            }
        }

        // Check wires passing straight through unrelated ports OR straight across component bodies
        Set<Wire> wires = circ.getWires();
        Set<String> warnedPassThrough = new HashSet<String>();
        for (Wire w : wires) {
            Location w0 = w.getEnd0();
            Location w1 = w.getEnd1();
            if (state != null) {
                Value vw = state.getValue(w0);
                if (vw != null && vw.isErrorValue()) {
                    issues.add(issue("error", "wire_error_value", circ, null, w0,
                            "Wire " + w0 + "->" + w1 + " carries ERROR value (" + vw.toDisplayString() + ").",
                            "Inspect connected port widths and multiple drivers along this net."));
                }
            }
            for (Map.Entry<Location, List<ComponentMeta.ResolvedPort>> pe : portsByLoc.entrySet()) {
                Location pt = pe.getKey();
                if (pt.equals(w0) || pt.equals(w1)) continue;
                if (CircuitLayoutOps.pointOnSegment(pt, w0, w1)) {
                    Component owner = ownersByLoc.get(pt).get(0);
                    String k = ComponentMeta.componentId(owner) + ":" + pt;
                    if (warnedPassThrough.add(k)) {
                        issues.add(issue("warning", "wire_passes_through_port", circ, owner, pt,
                                "Wire " + w0 + "->" + w1 + " passes directly across port '"
                                + pe.getValue().get(0).name + "' of " + ComponentMeta.componentId(owner)
                                + " without terminating at it.",
                                "Reroute wire using connect(route='auto') or waypoints to prevent unintended visual/electrical shorts."));
                    }
                }
            }
            // Catch Logisim-split wires running directly inside/across a multi-port component body (e.g. input shorted straight to output)
            for (Component comp : nonWires) {
                if (comp.getEnds().size() < 2) continue;
                String cname = comp.getFactory().getName();
                if ("Splitter".equals(cname) || "Tunnel".equals(cname)) continue;
                Bounds cb = comp.getBounds();
                if (cb.getWidth() < 15 || cb.getHeight() < 15) continue;
                if (segmentCrossesInterior(w0, w1, cb)) {
                    String k = ComponentMeta.componentId(comp) + ":body:" + w0 + "-" + w1;
                    if (warnedPassThrough.add(k)) {
                        issues.add(issue("warning", "wire_passes_through_port", circ, comp, w0,
                                "Wire " + w0 + "->" + w1 + " runs straight across the body of "
                                + ComponentMeta.componentId(comp) + ", shorting/crossing component pins.",
                                "Reroute wire around component using connect(route='auto')."));
                    }
                }
            }
        }

        // 4. Component bounding-box overlap check
        for (int i = 0; i < nonWires.size(); i++) {
            Component a = nonWires.get(i);
            Bounds ba = a.getBounds();
            if (ba.getWidth() < 12 || ba.getHeight() < 12) continue;
            for (int j = i + 1; j < nonWires.size(); j++) {
                Component b = nonWires.get(j);
                Bounds bb = b.getBounds();
                if (bb.getWidth() < 12 || bb.getHeight() < 12) continue;
                Bounds inter = ba.intersect(bb);
                if (inter.getWidth() > 10 && inter.getHeight() > 10) {
                    issues.add(issue("warning", "component_overlap", circ, a, a.getLocation(),
                            ComponentMeta.componentId(a) + " overlaps bounding box of " + ComponentMeta.componentId(b) + ".",
                            "Move one component with logisim_arrange or logisim_move_component."));
                }
            }
        }

        return issues;
    }

    private static boolean segmentCrossesInterior(Location a, Location b, Bounds cb) {
        int bx0 = cb.getX();
        int bx1 = bx0 + cb.getWidth();
        int by0 = cb.getY();
        int by1 = by0 + cb.getHeight();
        int margin = 4;
        if (a.getY() == b.getY()) {
            int y = a.getY();
            if (y <= by0 + margin || y >= by1 - margin) return false;
            int minX = Math.min(a.getX(), b.getX());
            int maxX = Math.max(a.getX(), b.getX());
            int ov = Math.min(maxX, bx1) - Math.max(minX, bx0);
            return ov >= 10;
        }
        if (a.getX() == b.getX()) {
            int x = a.getX();
            if (x <= bx0 + margin || x >= bx1 - margin) return false;
            int minY = Math.min(a.getY(), b.getY());
            int maxY = Math.max(a.getY(), b.getY());
            int ov = Math.min(maxY, by1) - Math.max(minY, by0);
            return ov >= 10;
        }
        return false;
    }

    private static boolean isOptionalPort(Component comp, String fname, ComponentMeta.ResolvedPort rp) {
        String pname = rp.name;
        if ("Adder".equals(fname)) return "cin".equals(pname) || "cout".equals(pname);
        if ("Subtractor".equals(fname)) return "bin".equals(pname) || "bout".equals(pname);
        if ("Multiplier".equals(fname)) return "cin".equals(pname) || "cout".equals(pname);
        if ("Divider".equals(fname)) return "upper".equals(pname) || "rem".equals(pname);
        if ("Comparator".equals(fname)) return "gt".equals(pname) || "eq".equals(pname) || "lt".equals(pname);
        if ("Register".equals(fname)) return "clr".equals(pname) || "en".equals(pname);
        if ("Counter".equals(fname)) return "D".equals(pname) || "clr".equals(pname) || "ld".equals(pname) || "en".equals(pname) || "carry".equals(pname);
        if ("D Flip-Flop".equals(fname) || "T Flip-Flop".equals(fname)
                || "J-K Flip-Flop".equals(fname) || "S-R Flip-Flop".equals(fname)) {
            return "nQ".equals(pname) || "clr".equals(pname) || "pre".equals(pname) || "en".equals(pname);
        }
        if ("RAM".equals(fname)) return "cs".equals(pname) || "oe".equals(pname) || "clr".equals(pname);
        if ("ROM".equals(fname)) return "cs".equals(pname);
        if ("Multiplexer".equals(fname) || "Demultiplexer".equals(fname)) return "en".equals(pname);
        if ("Decoder".equals(fname)) return "en".equals(pname) || rp.endData.isOutput();
        if ("Priority Encoder".equals(fname)) return "en_in".equals(pname) || "en_out".equals(pname) || "gs".equals(pname);
        if ("Splitter".equals(fname)) return rp.index > 0;
        return false;
    }

    private static Map<String, Object> issue(
            String severity, String rule, Circuit circ, Component comp,
            Location loc, String message, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("severity", severity);
        m.put("rule", rule);
        m.put("circuit", circ.getName());
        if (comp != null) m.put("component", ComponentMeta.componentId(comp));
        if (loc != null) {
            m.put("x", Integer.valueOf(loc.getX()));
            m.put("y", Integer.valueOf(loc.getY()));
        }
        m.put("message", message);
        if (suggestion != null && !suggestion.isEmpty()) m.put("suggestion", suggestion);
        return m;
    }
}
