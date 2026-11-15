package logisimmcp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.RadixOption;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.ComponentUserEvent;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.tools.ToolTipMaker;

import java.awt.Color;
import java.awt.Font;
import java.util.*;

/**
 * Component lookup, semantic port extraction/resolution, attribute conversion,
 * and value formatting helpers.
 */
public final class ComponentMeta {
    private ComponentMeta() {}

    public static final class ResolvedPort {
        public final int index;
        public final Location location;
        public final EndData endData;
        public final String name;
        public final List<String> aliases;
        public final String tooltip;
        public final String subcircuitPinLabel;

        public ResolvedPort(int index, Location location, EndData endData, String name,
                            List<String> aliases, String tooltip, String subcircuitPinLabel) {
            this.index = index;
            this.location = location;
            this.endData = endData;
            this.name = name;
            this.aliases = aliases;
            this.tooltip = tooltip;
            this.subcircuitPinLabel = subcircuitPinLabel;
        }

        public String dirString() {
            if (endData.isInput() && endData.isOutput()) return "inout";
            if (endData.isOutput()) return "out";
            return "in";
        }
    }

    public static int snap(int v) {
        return Math.round(v / 10.0f) * 10;
    }

    public static String componentId(Component comp) {
        String name = comp.getFactory().getName();
        if (name == null || name.isEmpty()) name = comp.getFactory().getDisplayName();
        Location loc = comp.getLocation();
        return name + "@" + loc.getX() + "," + loc.getY();
    }

    public static String getComponentLabel(Component comp) {
        AttributeSet attrs = comp.getAttributeSet();
        if (attrs == null) return "";
        if (attrs.containsAttribute(StdAttr.LABEL)) {
            String lbl = attrs.getValue(StdAttr.LABEL);
            if (lbl != null) return lbl;
        }
        Attribute<?> a = attrs.getAttribute("label");
        if (a != null) {
            Object v = attrs.getValue(a);
            return v != null ? v.toString() : "";
        }
        return "";
    }

    public static List<Library> getAllLibraries(Project proj) {
        List<Library> libs = new ArrayList<Library>();
        if (proj != null && proj.getLogisimFile() != null) {
            LogisimFile lf = proj.getLogisimFile();
            libs.add(lf);
            for (Library lib : lf.getLibraries()) {
                libs.add(lib);
            }
        }
        return libs;
    }

    public static AddTool findTool(Project proj, String componentType, String preferredLib) {
        if (componentType == null || componentType.trim().isEmpty()) {
            throw new IllegalArgumentException("Component type must not be empty");
        }
        String q = componentType.trim();
        List<Library> libs = getAllLibraries(proj);
        List<AddTool> exact = new ArrayList<AddTool>();
        List<AddTool> prefixOrContains = new ArrayList<AddTool>();
        List<String> allNames = new ArrayList<String>();

        for (Library lib : libs) {
            if (preferredLib != null && !preferredLib.trim().isEmpty()) {
                String pl = preferredLib.trim();
                if (!lib.getName().equalsIgnoreCase(pl) && !lib.getDisplayName().equalsIgnoreCase(pl)) {
                    continue;
                }
            }
            for (Tool tool : lib.getTools()) {
                if (!(tool instanceof AddTool)) continue;
                AddTool at = (AddTool) tool;
                ComponentFactory f = at.getFactory();
                String fname = f.getName();
                String dname = f.getDisplayName();
                String tname = at.getName();
                allNames.add(fname);
                if (fname.equalsIgnoreCase(q) || dname.equalsIgnoreCase(q) || tname.equalsIgnoreCase(q)) {
                    exact.add(at);
                } else if (normKey(fname).equals(normKey(q)) || normKey(dname).equals(normKey(q))) {
                    exact.add(at);
                } else if (fname.toLowerCase().contains(q.toLowerCase()) || dname.toLowerCase().contains(q.toLowerCase())) {
                    prefixOrContains.add(at);
                }
            }
        }

        if (!exact.isEmpty()) return exact.get(0);
        if (prefixOrContains.size() == 1) return prefixOrContains.get(0);

        StringBuilder msg = new StringBuilder();
        msg.append("Unknown component type '").append(componentType).append("'");
        if (preferredLib != null && !preferredLib.trim().isEmpty()) {
            msg.append(" in library '").append(preferredLib).append("'");
        }
        if (!prefixOrContains.isEmpty()) {
            msg.append(". Ambiguous matches: ");
            for (int i = 0; i < Math.min(8, prefixOrContains.size()); i++) {
                if (i > 0) msg.append(", ");
                msg.append(prefixOrContains.get(i).getFactory().getName());
            }
        } else {
            List<String> near = closestNames(q, allNames, 6);
            if (!near.isEmpty()) {
                msg.append(". Did you mean: ").append(String.join(", ", near));
            }
        }
        throw new IllegalArgumentException(msg.toString());
    }

    public static Component resolveComponent(Circuit circ, String spec, Map<String, Component> batchRefs) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Component identifier/label/ref is required");
        }
        String key = spec.trim();

        // 1. Batch reference alias
        if (batchRefs != null && batchRefs.containsKey(key)) {
            return batchRefs.get(key);
        }

        // 2. Exact ID, @x,y suffix, unique label, or unique factory type fallback
        List<Component> locMatches = new ArrayList<Component>();
        List<Component> labelMatches = new ArrayList<Component>();
        List<Component> typeMatches = new ArrayList<Component>();
        List<String> availableIds = new ArrayList<String>();
        for (Component c : circ.getNonWires()) {
            String id = componentId(c);
            availableIds.add(id);
            if (id.equals(key) || id.equalsIgnoreCase(key)) {
                return c;
            }
            if (key.startsWith("@")) {
                String coords = "@" + c.getLocation().getX() + "," + c.getLocation().getY();
                if (coords.equals(key)) locMatches.add(c);
            }
            String lbl = getComponentLabel(c);
            if (!lbl.isEmpty() && lbl.equalsIgnoreCase(key)) {
                labelMatches.add(c);
            }
            String fname = c.getFactory().getName();
            if (fname != null && fname.equalsIgnoreCase(key)) {
                typeMatches.add(c);
            }
        }

        if (locMatches.size() == 1) return locMatches.get(0);
        if (locMatches.size() > 1) {
            throw new IllegalArgumentException("Multiple components at " + key + ": " + joinIds(locMatches));
        }
        if (labelMatches.size() == 1) return labelMatches.get(0);
        if (labelMatches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous label '" + key + "' matches " + labelMatches.size()
                    + " components: " + joinIds(labelMatches) + ". Specify exact id instead.");
        }
        if (typeMatches.size() == 1) return typeMatches.get(0);
        if (typeMatches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous component type '" + key + "' matches " + typeMatches.size()
                    + " instances: " + joinIds(typeMatches) + ". Specify exact id or '@x,y'.");
        }

        StringBuilder err = new StringBuilder();
        err.append("Component not found in circuit '").append(circ.getName()).append("': '").append(spec).append("'.");
        if (!availableIds.isEmpty()) {
            err.append(" Available (first ").append(Math.min(12, availableIds.size())).append("): ");
            for (int i = 0; i < Math.min(12, availableIds.size()); i++) {
                if (i > 0) err.append(", ");
                err.append(availableIds.get(i));
            }
        }
        throw new IllegalArgumentException(err.toString());
    }

    private static String joinIds(List<Component> comps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < comps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(componentId(comps.get(i)));
        }
        return sb.toString();
    }

    public static List<ResolvedPort> inspectPorts(Component comp, Circuit circ, Canvas canvas) {
        List<EndData> ends = comp.getEnds();
        List<ResolvedPort> out = new ArrayList<ResolvedPort>(ends.size());
        ComponentFactory factory = comp.getFactory();
        String fname = factory.getName() != null ? factory.getName() : "";

        // Prepare Subcircuit pin mapping in exact SubcircuitFactory order
        List<Instance> subPins = null;
        if (factory instanceof SubcircuitFactory) {
            SubcircuitFactory sf = (SubcircuitFactory) factory;
            Direction facing = comp.getAttributeSet().getValue(StdAttr.FACING);
            if (facing == null) facing = Direction.EAST;
            Map<Location, Instance> portMap = sf.getSubcircuit().getAppearance().getPortOffsets(facing);
            if (portMap.size() == ends.size()) {
                subPins = new ArrayList<Instance>(portMap.values());
            }
        }

        // Capture Logisim native ToolTipMaker tooltips
        ToolTipMaker tipMaker = null;
        try {
            Object feat = comp.getFeature(ToolTipMaker.class);
            if (feat instanceof ToolTipMaker) {
                tipMaker = (ToolTipMaker) feat;
            }
        } catch (Throwable ignored) {}

        int inputOrdinal = 0;
        int outputOrdinal = 0;
        for (int i = 0; i < ends.size(); i++) {
            EndData end = ends.get(i);
            Location pt = end.getLocation();
            String tooltip = null;
            if (tipMaker != null) {
                try {
                    tooltip = tipMaker.getToolTip(new ComponentUserEvent(canvas, pt.getX(), pt.getY()));
                } catch (Throwable ignored) {}
            }

            String subLabel = null;
            if (subPins != null && i < subPins.size()) {
                Instance pinInst = subPins.get(i);
                if (pinInst != null && pinInst.getAttributeSet().containsAttribute(StdAttr.LABEL)) {
                    String l = pinInst.getAttributeValue(StdAttr.LABEL);
                    if (l != null && !l.trim().isEmpty()) {
                        subLabel = l.trim();
                    }
                }
            }

            List<String> aliases = new ArrayList<String>();
            String canonical = deriveCanonicalPortName(comp, fname, i, ends.size(), end, tooltip, subLabel,
                    inputOrdinal, outputOrdinal, aliases);
            if (end.isInput() && !end.isOutput()) inputOrdinal++;
            else if (end.isOutput() && !end.isInput()) outputOrdinal++;

            out.add(new ResolvedPort(i, pt, end, canonical, aliases, tooltip, subLabel));
        }
        return out;
    }

    private static String deriveCanonicalPortName(
            Component comp, String fname, int idx, int total, EndData end,
            String tooltip, String subLabel, int inOrd, int outOrd, List<String> aliases) {
        aliases.add(String.valueOf(idx));
        aliases.add("port" + idx);
        aliases.add("port_" + idx);

        if (subLabel != null && !subLabel.isEmpty()) {
            aliases.add(subLabel);
            aliases.add("pin_" + idx);
            return subLabel;
        }

        if ("Register".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "Q", "out"); return "Q";
                case 1: addAliases(aliases, "D", "in"); return "D";
                case 2: addAliases(aliases, "clk", "clock"); return "clk";
                case 3: addAliases(aliases, "clr", "clear", "reset"); return "clr";
                case 4: addAliases(aliases, "en", "enable"); return "en";
            }
        } else if ("Counter".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "Q", "out"); return "Q";
                case 1: addAliases(aliases, "D", "in", "data"); return "D";
                case 2: addAliases(aliases, "clk", "clock"); return "clk";
                case 3: addAliases(aliases, "clr", "clear", "reset"); return "clr";
                case 4: addAliases(aliases, "ld", "load"); return "ld";
                case 5: addAliases(aliases, "ct", "en", "enable"); return "en";
                case 6: addAliases(aliases, "carry", "cout"); return "carry";
            }
        } else if ("RAM".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "D", "data", "dout"); return "D";
                case 1: addAliases(aliases, "A", "addr", "address"); return "A";
                case 2: addAliases(aliases, "cs", "sel", "select"); return "cs";
                case 3: addAliases(aliases, "oe", "ld", "out_enable"); return "oe";
                case 4: addAliases(aliases, "clr", "clear", "reset"); return "clr";
                case 5: addAliases(aliases, "clk", "clock"); return "clk";
                case 6: addAliases(aliases, "we", "str", "write", "store"); return "we";
                case 7: addAliases(aliases, "din", "data_in"); return "din";
            }
        } else if ("ROM".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "D", "data", "out"); return "D";
                case 1: addAliases(aliases, "A", "addr", "address"); return "A";
                case 2: addAliases(aliases, "cs", "sel", "select"); return "cs";
            }
        } else if ("Adder".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "A", "in0"); return "A";
                case 1: addAliases(aliases, "B", "in1"); return "B";
                case 2: addAliases(aliases, "sum", "out", "S"); return "out";
                case 3: addAliases(aliases, "cin", "carry_in"); return "cin";
                case 4: addAliases(aliases, "cout", "carry_out"); return "cout";
            }
        } else if ("Subtractor".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "A", "minuend", "in0"); return "A";
                case 1: addAliases(aliases, "B", "subtrahend", "in1"); return "B";
                case 2: addAliases(aliases, "diff", "out", "D"); return "out";
                case 3: addAliases(aliases, "bin", "borrow_in"); return "bin";
                case 4: addAliases(aliases, "bout", "borrow_out"); return "bout";
            }
        } else if ("Multiplier".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "A", "in0"); return "A";
                case 1: addAliases(aliases, "B", "in1"); return "B";
                case 2: addAliases(aliases, "prod", "out"); return "out";
                case 3: addAliases(aliases, "cin", "upper_in"); return "cin";
                case 4: addAliases(aliases, "cout", "upper_out"); return "cout";
            }
        } else if ("Divider".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "A", "dividend", "in0"); return "dividend";
                case 1: addAliases(aliases, "B", "divisor", "in1"); return "divisor";
                case 2: addAliases(aliases, "quot", "out"); return "out";
                case 3: addAliases(aliases, "upper", "dividend_upper"); return "upper";
                case 4: addAliases(aliases, "rem", "remainder"); return "rem";
            }
        } else if ("Negator".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "in", "A"); return "in";
                case 1: addAliases(aliases, "out", "Q"); return "out";
            }
        } else if ("Comparator".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "A", "in0"); return "A";
                case 1: addAliases(aliases, "B", "in1"); return "B";
                case 2: addAliases(aliases, "gt", "greater"); return "gt";
                case 3: addAliases(aliases, "eq", "equal"); return "eq";
                case 4: addAliases(aliases, "lt", "less"); return "lt";
            }
        } else if ("Shifter".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "in", "data", "A"); return "in";
                case 1: addAliases(aliases, "dist", "shift", "shamt", "B"); return "shift";
                case 2: addAliases(aliases, "out", "Q"); return "out";
            }
        } else if ("Bit Extender".equals(fname)) {
            if (idx == 0) { addAliases(aliases, "out"); return "out"; }
            if (idx == 1) { addAliases(aliases, "in"); return "in"; }
        } else if ("BitSelector".equals(fname)) {
            switch (idx) {
                case 0: addAliases(aliases, "out"); return "out";
                case 1: addAliases(aliases, "in", "data"); return "in";
                case 2: addAliases(aliases, "sel", "select"); return "sel";
            }
        } else if ("D Flip-Flop".equals(fname) || "T Flip-Flop".equals(fname)
                || "J-K Flip-Flop".equals(fname) || "S-R Flip-Flop".equals(fname)) {
            int nInputs = Math.max(1, total - 6);
            if ("D Flip-Flop".equals(fname) && idx == 0) { addAliases(aliases, "D", "in"); return "D"; }
            if ("T Flip-Flop".equals(fname) && idx == 0) { addAliases(aliases, "T", "in"); return "T"; }
            if ("J-K Flip-Flop".equals(fname)) {
                if (idx == 0) { addAliases(aliases, "J"); return "J"; }
                if (idx == 1) { addAliases(aliases, "K"); return "K"; }
            }
            if ("S-R Flip-Flop".equals(fname)) {
                if (idx == 0) { addAliases(aliases, "S"); return "S"; }
                if (idx == 1) { addAliases(aliases, "R"); return "R"; }
            }
            int rel = idx - nInputs;
            switch (rel) {
                case 0: addAliases(aliases, "clk", "clock"); return "clk";
                case 1: addAliases(aliases, "Q", "out"); return "Q";
                case 2: addAliases(aliases, "nQ", "!Q", "qbar"); return "nQ";
                case 3: addAliases(aliases, "clr", "reset", "0"); return "clr";
                case 4: addAliases(aliases, "pre", "preset", "1"); return "pre";
                case 5: addAliases(aliases, "en", "enable"); return "en";
            }
        } else if ("Multiplexer".equals(fname)) {
            boolean hasEnable = isEnableTrue(comp);
            int numInputs = total - (hasEnable ? 3 : 2);
            if (idx < numInputs) {
                String nm = "in" + idx;
                addAliases(aliases, nm);
                return nm;
            }
            if (idx == numInputs) { addAliases(aliases, "sel", "select"); return "sel"; }
            if (hasEnable && idx == numInputs + 1) { addAliases(aliases, "en", "enable"); return "en"; }
            if (idx == total - 1) { addAliases(aliases, "out", "Q"); return "out"; }
        } else if ("Demultiplexer".equals(fname)) {
            boolean hasEnable = isEnableTrue(comp);
            int numOuts = total - (hasEnable ? 3 : 2);
            if (idx < numOuts) {
                String nm = "out" + idx;
                addAliases(aliases, nm);
                return nm;
            }
            if (idx == numOuts) { addAliases(aliases, "sel", "select"); return "sel"; }
            if (hasEnable && idx == numOuts + 1) { addAliases(aliases, "en", "enable"); return "en"; }
            if (idx == total - 1) { addAliases(aliases, "in", "data"); return "in"; }
        } else if ("Decoder".equals(fname)) {
            boolean hasEnable = isEnableTrue(comp);
            int numOuts = total - (hasEnable ? 2 : 1);
            if (idx < numOuts) {
                String nm = "out" + idx;
                addAliases(aliases, nm);
                return nm;
            }
            if (idx == numOuts) { addAliases(aliases, "sel", "select"); return "sel"; }
            if (hasEnable && idx == numOuts + 1) { addAliases(aliases, "en", "enable"); return "en"; }
        } else if ("Priority Encoder".equals(fname)) {
            int numIns = total - 4;
            if (idx < numIns) {
                String nm = "in" + idx;
                addAliases(aliases, nm);
                return nm;
            }
            switch (idx - numIns) {
                case 0: addAliases(aliases, "out"); return "out";
                case 1: addAliases(aliases, "en_in", "ei"); return "en_in";
                case 2: addAliases(aliases, "en_out", "eo"); return "en_out";
                case 3: addAliases(aliases, "gs", "group"); return "gs";
            }
        } else if ("Splitter".equals(fname)) {
            if (idx == 0) {
                addAliases(aliases, "combined", "bus", "in");
                return "combined";
            }
            addAliases(aliases, "split" + (idx - 1), "out" + (idx - 1));
            if (tooltip != null && !tooltip.isEmpty()) {
                // e.g. "Bits 0-3" or "Bit 5"
                String bits = extractBitsToken(tooltip);
                if (bits != null && !bits.isEmpty()) {
                    addAliases(aliases, "bits_" + bits, "bit_" + bits, bits);
                    return "bits_" + bits;
                }
            }
            return "split" + (idx - 1);
        } else if ("Pin".equals(fname) || "Clock".equals(fname) || "Probe".equals(fname)
                || "Tunnel".equals(fname) || "Constant".equals(fname)
                || "Power".equals(fname) || "Ground".equals(fname)) {
            String dirAlias = end.isOutput() && !end.isInput() ? "out" : (end.isInput() && !end.isOutput() ? "in" : "io");
            addAliases(aliases, dirAlias, "pin");
            String lbl = getComponentLabel(comp);
            if (!lbl.isEmpty()) addAliases(aliases, lbl);
            return dirAlias;
        }

        if (end.isOutput() && !end.isInput()) {
            String nm = outOrd == 0 && total <= 6 ? "out" : ("out" + outOrd);
            addAliases(aliases, nm, "out" + outOrd);
            return nm;
        }
        if (end.isInput() && !end.isOutput()) {
            String nm = "in" + inOrd;
            addAliases(aliases, nm);
            return nm;
        }
        String nm = "io" + idx;
        addAliases(aliases, nm);
        return nm;
    }

    private static boolean isEnableTrue(Component comp) {
        AttributeSet attrs = comp.getAttributeSet();
        if (attrs == null) return false;
        Attribute<?> a = attrs.getAttribute("enable");
        if (a == null) return false;
        Object v = attrs.getValue(a);
        return Boolean.TRUE.equals(v);
    }

    private static void addAliases(List<String> list, String... items) {
        for (String s : items) {
            if (s != null && !s.isEmpty() && !list.contains(s)) list.add(s);
        }
    }

    private static String extractBitsToken(String tooltip) {
        // Extract e.g. "20-25" or "5" or "0,1" from Logisim's "Bit 5" / "Bits 20-25"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tooltip.length(); i++) {
            char c = tooltip.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == ',') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static ResolvedPort resolvePort(Component comp, Object portSpec, Circuit circ, Canvas canvas) {
        List<ResolvedPort> ports = inspectPorts(comp, circ, canvas);
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("Component " + componentId(comp) + " has no ports");
        }
        if (portSpec == null) {
            return ports.get(0);
        }
        if (portSpec instanceof Number) {
            int idx = ((Number) portSpec).intValue();
            if (idx >= 0 && idx < ports.size()) return ports.get(idx);
            throw new IllegalArgumentException(portNotFoundMsg(comp, portSpec, ports));
        }
        String s = String.valueOf(portSpec).trim();
        try {
            int idx = Integer.parseInt(s);
            if (idx >= 0 && idx < ports.size()) return ports.get(idx);
        } catch (NumberFormatException ignored) {}

        // Exact match on canonical name, subcircuitPinLabel, aliases, or normalized forms
        for (ResolvedPort rp : ports) {
            if (rp.name.equalsIgnoreCase(s)) return rp;
            if (rp.subcircuitPinLabel != null && rp.subcircuitPinLabel.equalsIgnoreCase(s)) return rp;
            for (String al : rp.aliases) {
                if (al.equalsIgnoreCase(s)) return rp;
            }
        }
        String ns = normKey(s);
        for (ResolvedPort rp : ports) {
            if (normKey(rp.name).equals(ns)) return rp;
            if (rp.subcircuitPinLabel != null && normKey(rp.subcircuitPinLabel).equals(ns)) return rp;
            for (String al : rp.aliases) {
                if (normKey(al).equals(ns)) return rp;
            }
            if (rp.tooltip != null && rp.tooltip.equalsIgnoreCase(s)) return rp;
        }
        throw new IllegalArgumentException(portNotFoundMsg(comp, portSpec, ports));
    }

    private static String portNotFoundMsg(Component comp, Object spec, List<ResolvedPort> ports) {
        StringBuilder sb = new StringBuilder();
        sb.append("Port '").append(spec).append("' not found on ").append(componentId(comp)).append(". Valid ports: [");
        for (int i = 0; i < ports.size(); i++) {
            ResolvedPort rp = ports.get(i);
            if (i > 0) sb.append(", ");
            sb.append(rp.index).append(":").append(rp.name).append("(").append(rp.dirString())
              .append(",").append(rp.endData.getWidth().getWidth()).append("b)");
        }
        sb.append("]");
        return sb.toString();
    }

    public static Map<String, Object> portToMap(Component comp, ResolvedPort rp, Circuit circ, CircuitState state) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("index", Integer.valueOf(rp.index));
        m.put("name", rp.name);
        m.put("dir", rp.dirString());
        m.put("width", Integer.valueOf(rp.endData.getWidth().getWidth()));
        m.put("x", Integer.valueOf(rp.location.getX()));
        m.put("y", Integer.valueOf(rp.location.getY()));
        if (rp.tooltip != null && !rp.tooltip.isEmpty()) m.put("tooltip", rp.tooltip);
        if (rp.subcircuitPinLabel != null) m.put("pin_label", rp.subcircuitPinLabel);
        if (circ != null) {
            m.put("connected", Boolean.valueOf(isPortConnected(circ, comp, rp.location)));
        }
        if (state != null) {
            m.put("value", formatValue(state.getValue(rp.location)));
        }
        return m;
    }

    public static boolean isPortConnected(Circuit circ, Component selfComp, Location pt) {
        for (Wire w : circ.getWires()) {
            if (w.getEnd0().equals(pt) || w.getEnd1().equals(pt)) {
                return true;
            }
        }
        int otherEnds = 0;
        for (Component c : circ.getComponents(pt)) {
            if (c instanceof Wire || c == selfComp) continue;
            if (c.endsAt(pt)) {
                otherEnds++;
            }
        }
        return otherEnds > 0;
    }

    public static String formatValue(Value v) {
        if (v == null || v == Value.NIL) return "NIL";
        int w = v.getWidth();
        if (v.isErrorValue()) return "ERROR(" + v.toDisplayString() + ")";
        if (v.isUnknown()) return "X(" + w + "b)";
        if (v.isFullyDefined()) {
            long unsigned = v.toIntValue() & ((w >= 32) ? 0xFFFFFFFFL : ((1L << w) - 1L));
            if (w == 1) return unsigned == 1L ? "1" : "0";
            return "0x" + Long.toHexString(unsigned).toUpperCase();
        }
        return v.toDisplayString();
    }

    public static Map<String, Object> formatRichValue(Value v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (v == null || v == Value.NIL) {
            m.put("hex", "NIL");
            m.put("binary", "");
            m.put("defined", Boolean.FALSE);
            m.put("error", Boolean.FALSE);
            return m;
        }
        int w = v.getWidth();
        m.put("width", Integer.valueOf(w));
        m.put("binary", v.toDisplayString());
        m.put("error", Boolean.valueOf(v.isErrorValue()));
        m.put("defined", Boolean.valueOf(v.isFullyDefined()));
        if (v.isFullyDefined()) {
            long unsigned = v.toIntValue() & ((w >= 32) ? 0xFFFFFFFFL : ((1L << w) - 1L));
            m.put("unsigned", Long.valueOf(unsigned));
            m.put("hex", "0x" + Long.toHexString(unsigned).toUpperCase());
        } else {
            m.put("hex", v.toDisplayString());
        }
        return m;
    }

    public static Value parseValue(Object raw, int width) {
        if (width < 1 || width > 32) {
            throw new IllegalArgumentException("Invalid bit width: " + width);
        }
        if (raw == null) throw new IllegalArgumentException("Value cannot be null");
        if (raw instanceof Number) {
            return Value.createKnown(BitWidth.create(width), ((Number) raw).intValue());
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Empty signal value");
        if (s.equalsIgnoreCase("x") || s.equalsIgnoreCase("unknown") || s.equalsIgnoreCase("z")) {
            return Value.createUnknown(BitWidth.create(width));
        }
        if (s.toLowerCase().startsWith("0x")) {
            long v = Long.parseLong(s.substring(2).replace("_", ""), 16);
            return Value.createKnown(BitWidth.create(width), (int) v);
        }
        if (s.toLowerCase().startsWith("0b")) {
            String bits = s.substring(2).replace("_", "");
            return parseBitPatternValue(bits, width);
        }
        // Check if explicit multi-bit binary/tristate pattern (contains only 0/1/x when length == width > 1)
        if (width > 1 && s.length() == width && isTristateString(s)) {
            return parseBitPatternValue(s, width);
        }
        long v = Long.parseLong(s.replace("_", ""), 10);
        return Value.createKnown(BitWidth.create(width), (int) v);
    }

    private static boolean isTristateString(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c != '0' && c != '1' && c != 'x' && c != '?') return false;
        }
        return true;
    }

    private static Value parseBitPatternValue(String bits, int width) {
        Value[] arr = new Value[width];
        // MSB first in string, index 0 = LSB in Value.create(Value[])
        for (int bitIdx = 0; bitIdx < width; bitIdx++) {
            int strIdx = bits.length() - 1 - bitIdx;
            char c = strIdx >= 0 ? Character.toLowerCase(bits.charAt(strIdx)) : '0';
            if (c == '1') arr[bitIdx] = Value.TRUE;
            else if (c == '0') arr[bitIdx] = Value.FALSE;
            else if (c == 'x' || c == '?' || c == 'z') arr[bitIdx] = Value.UNKNOWN;
            else throw new IllegalArgumentException("Invalid bit char '" + c + "' in " + bits);
        }
        return Value.create(arr);
    }

    public static boolean matchesExpectedValue(Value actual, Object expectedSpec) {
        String s = String.valueOf(expectedSpec).trim();
        if (s.equalsIgnoreCase("x") || s.equalsIgnoreCase("*")) return true;
        int w = actual.getWidth();
        if (s.toLowerCase().startsWith("0b")) {
            String pat = s.substring(2).replace("_", "");
            return matchesBitMask(actual, pat);
        }
        if (isTristateString(s) && (w == 1 || s.length() == w)) {
            return matchesBitMask(actual, s);
        }
        Value expectedVal = parseValue(expectedSpec, w);
        return actual.equals(expectedVal);
    }

    private static boolean matchesBitMask(Value actual, String patMsbFirst) {
        int w = actual.getWidth();
        for (int bitIdx = 0; bitIdx < w; bitIdx++) {
            int strIdx = patMsbFirst.length() - 1 - bitIdx;
            char c = strIdx >= 0 ? Character.toLowerCase(patMsbFirst.charAt(strIdx)) : '0';
            if (c == 'x' || c == '?' || c == '*') continue;
            Value actualBit = actual.get(bitIdx);
            if (c == '1' && actualBit != Value.TRUE) return false;
            if (c == '0' && actualBit != Value.FALSE) return false;
        }
        return true;
    }

    public static void applyAttributes(AttributeSet attrs, Map<String, Object> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        // Deterministic ordering: apply structural width/fanout/inputs attributes BEFORE dependent bit* attrs
        List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(incoming.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Object>>() {
            @Override
            public int compare(Map.Entry<String, Object> a, Map.Entry<String, Object> b) {
                return Integer.compare(attrPriority(a.getKey()), attrPriority(b.getKey()));
            }
        });

        for (Map.Entry<String, Object> entry : entries) {
            Attribute<?> attr = findAttribute(attrs, entry.getKey());
            if (attr == null) {
                throw new IllegalArgumentException(unknownAttrMsg(attrs, entry.getKey()));
            }
            @SuppressWarnings("unchecked")
            Attribute<Object> typed = (Attribute<Object>) attr;
            Object parsed = parseAttributeValue(typed, entry.getValue());
            attrs.setValue(typed, parsed);
        }
    }

    private static int attrPriority(String key) {
        String k = key.toLowerCase();
        if (k.equals("fanout") || k.equals("incoming") || k.equals("width") || k.equals("data") || k.equals("inputs") || k.equals("select") || k.equals("bus")) {
            return 0;
        }
        if (k.startsWith("bit")) return 2;
        return 1;
    }

    public static Attribute<?> findAttribute(AttributeSet attrs, String name) {
        if (name == null) return null;
        Attribute<?> direct = attrs.getAttribute(name);
        if (direct != null) return direct;
        String norm = normKey(name);
        for (Attribute<?> a : attrs.getAttributes()) {
            if (a.getName().equalsIgnoreCase(name)
                    || a.getDisplayName().equalsIgnoreCase(name)
                    || normKey(a.getName()).equals(norm)
                    || normKey(a.getDisplayName()).equals(norm)) {
                return a;
            }
        }
        // Helpful common aliases
        if ("trigger".equals(norm)) return findAttribute(attrs, "trigger");
        if ("tristate".equals(norm) || "threestate".equals(norm)) {
            Attribute<?> ts = attrs.getAttribute("tristate");
            if (ts != null) return ts;
            return attrs.getAttribute("threeState");
        }
        if ("addr".equals(norm) || "addrwidth".equals(norm)) {
            Attribute<?> aw = attrs.getAttribute("addrWidth");
            if (aw != null) return aw;
        }
        if ("data".equals(norm)) {
            Attribute<?> dw = attrs.getAttribute("dataWidth");
            if (dw != null) return dw;
        }
        if ("bits".equals(norm) || "bitwidth".equals(norm) || "datawidth".equals(norm)) {
            Attribute<?> w = attrs.getAttribute("width");
            if (w != null) return w;
            Attribute<?> inc = attrs.getAttribute("incoming");
            if (inc != null) return inc;
            Attribute<?> dw = attrs.getAttribute("dataWidth");
            if (dw != null) return dw;
        }
        return null;
    }

    private static String unknownAttrMsg(AttributeSet attrs, String name) {
        StringBuilder sb = new StringBuilder("Unknown attribute '").append(name).append("'. Valid attributes: ");
        List<Attribute<?>> list = attrs.getAttributes();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getName());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Object parseAttributeValue(Attribute<?> attr, Object raw) {
        if (raw == null) throw new IllegalArgumentException("Null value for attribute " + attr.getName());
        String s = String.valueOf(raw).trim();

        // Standard string parsing handles BitWidth, Direction, Integer, Boolean, Color, RadixOption, AttributeOption XML names, Splitter BitOut
        if (raw instanceof Boolean && "Boolean".equals(attr.getClass().getSimpleName())) {
            return raw;
        }
        // Special handling for Direction shortcuts
        if (StdAttr.FACING.equals(attr) || Pin.ATTR_LABEL_LOC.equals(attr) || "facing".equals(attr.getName()) || "labelloc".equals(attr.getName())) {
            String low = s.toLowerCase();
            if ("right".equals(low)) s = "east";
            else if ("left".equals(low)) s = "west";
            else if ("up".equals(low)) s = "north";
            else if ("down".equals(low)) s = "south";
            else s = low;
        }
        // Special handling for Constant ATTR_VALUE allowing 0x / 0b
        if ("value".equals(attr.getName()) && (s.toLowerCase().startsWith("0x") || s.toLowerCase().startsWith("0b"))) {
            try {
                if (s.toLowerCase().startsWith("0x")) {
                    return Integer.valueOf((int) Long.parseLong(s.substring(2), 16));
                }
                return Integer.valueOf((int) Long.parseLong(s.substring(2), 2));
            } catch (Exception ignored) {}
        }
        // Special handling for Color hex/name
        if ("color".equals(attr.getName()) || "offcolor".equals(attr.getName())) {
            try {
                return attr.parse(s);
            } catch (Exception e) {
                try {
                    return Color.decode(s);
                } catch (Exception ignored) {}
            }
        }
        // Special handling for RadixOption aliases
        if ("radix".equals(attr.getName())) {
            if ("hex".equalsIgnoreCase(s) || "16".equals(s)) return RadixOption.RADIX_16;
            if ("bin".equalsIgnoreCase(s) || "binary".equalsIgnoreCase(s) || "2".equals(s)) return RadixOption.RADIX_2;
            if ("dec".equalsIgnoreCase(s) || "unsigned".equalsIgnoreCase(s) || "10".equals(s)) return RadixOption.RADIX_10_UNSIGNED;
            if ("signed".equalsIgnoreCase(s)) return RadixOption.RADIX_10_SIGNED;
            if ("oct".equalsIgnoreCase(s) || "8".equals(s)) return RadixOption.RADIX_8;
        }

        try {
            Object parsed = attr.parse(s);
            if (parsed != null) return parsed;
        } catch (Throwable primaryErr) {
            // Try matching Font cleanly if "Name Style Size"
            if (StdAttr.LABEL_FONT.equals(attr)) {
                return Font.decode(s);
            }
            // Give informative error if AttributeOption
            Object editor = null;
            try {
                Attribute<Object> typed = (Attribute<Object>) attr;
                Object cur = typed.parse(s);
                if (cur != null) return cur;
            } catch (Throwable ignored) {}
            throw new IllegalArgumentException(
                    "Invalid value '" + raw + "' for attribute '" + attr.getName() + "': " + primaryErr.getMessage(),
                    primaryErr);
        }
        throw new IllegalArgumentException("Could not parse '" + raw + "' for attribute '" + attr.getName() + "'");
    }

    public static String serializeAttributeValue(Attribute<?> attr, Object val) {
        if (val == null) return "";
        if (val instanceof AttributeOption) {
            return ((AttributeOption) val).toString();
        }
        if (val instanceof BitWidth) {
            return String.valueOf(((BitWidth) val).getWidth());
        }
        if (val instanceof Direction) {
            return val.toString();
        }
        try {
            @SuppressWarnings("unchecked")
            Attribute<Object> typed = (Attribute<Object>) attr;
            return typed.toStandardString(val);
        } catch (Throwable ignored) {
            return String.valueOf(val);
        }
    }

    public static Map<String, String> serializeAttributes(AttributeSet attrs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (attrs == null) return map;
        for (Attribute<?> a : attrs.getAttributes()) {
            // Skip bulk ROM contents string in brief listing
            if ("contents".equals(a.getName())) {
                map.put("contents", "<HexContents>");
            } else {
                map.put(a.getName(), serializeAttributeValue(a, attrs.getValue(a)));
            }
        }
        return map;
    }

    public static String normKey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    public static List<String> closestNames(String query, Collection<String> candidates, int limit) {
        final String q = query.toLowerCase();
        List<String> list = new ArrayList<String>(new LinkedHashSet<String>(candidates));
        Collections.sort(list, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(levenshtein(q, a.toLowerCase()), levenshtein(q, b.toLowerCase()));
            }
        });
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) dp[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = dp[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j] + 1, dp[j - 1] + 1), prev + cost);
                prev = tmp;
            }
        }
        return dp[b.length()];
    }
}
