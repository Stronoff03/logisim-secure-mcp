package logisimmcp;

import com.cburch.hex.HexModel;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SimulatorEvent;
import com.cburch.logisim.circuit.SimulatorListener;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.log.Loggable;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.std.memory.Rom;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.util.StringUtil;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simulation execution, deep state inspection (Pins, Registers, FlipFlops, RAM/ROM, Subcircuits),
 * multi-cycle testbench runner, truth-table/expression synthesis, and annotated screenshots.
 */
public final class SimAndSynthOps {
    private static final JPanel DUMMY_DEST = new JPanel();

    private SimAndSynthOps() {}

    public static void waitForPropagation(Project proj) {
        Simulator sim = proj.getSimulator();
        if (sim == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        SimulatorListener listener = new SimulatorListener() {
            @Override
            public void propagationCompleted(SimulatorEvent e) {
                latch.countDown();
            }
            @Override
            public void tickCompleted(SimulatorEvent e) {}
            @Override
            public void simulatorStateChanged(SimulatorEvent e) {}
        };
        sim.addSimulatorListener(listener);
        try {
            if (!sim.isRunning()) {
                sim.setIsRunning(true);
            }
            sim.requestPropagate();
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sim.removeSimulatorListener(listener);
        }
    }

    public static void tickHalfCycles(Project proj, int halfTicks) {
        Simulator sim = proj.getSimulator();
        if (sim == null) return;
        if (!sim.isRunning()) sim.setIsRunning(true);
        for (int i = 0; i < halfTicks; i++) {
            final CountDownLatch tickLatch = new CountDownLatch(1);
            SimulatorListener listener = new SimulatorListener() {
                @Override
                public void propagationCompleted(SimulatorEvent e) {
                    tickLatch.countDown();
                }
                @Override
                public void tickCompleted(SimulatorEvent e) {
                    tickLatch.countDown();
                }
                @Override
                public void simulatorStateChanged(SimulatorEvent e) {}
            };
            sim.addSimulatorListener(listener);
            try {
                sim.tick();
                tickLatch.await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                sim.removeSimulatorListener(listener);
            }
        }
        waitForPropagation(proj);
    }

    public static void resetSimulation(Project proj) {
        Simulator sim = proj.getSimulator();
        if (sim == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        SimulatorListener listener = new SimulatorListener() {
            @Override
            public void propagationCompleted(SimulatorEvent e) {
                latch.countDown();
            }
            @Override
            public void tickCompleted(SimulatorEvent e) {}
            @Override
            public void simulatorStateChanged(SimulatorEvent e) {}
        };
        sim.addSimulatorListener(listener);
        try {
            sim.requestReset();
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sim.removeSimulatorListener(listener);
        }
        waitForPropagation(proj);
    }

    public static HexModel extractHexModel(Component comp, CircuitState state) {
        String fname = comp.getFactory().getName();
        if ("ROM".equals(fname)) {
            Object obj = comp.getAttributeSet().getValue(Rom.CONTENTS_ATTR);
            return (obj instanceof HexModel) ? (HexModel) obj : null;
        }
        if ("RAM".equals(fname) && state != null) {
            // Ensure MemState initialized by triggering instance propagation if needed
            Object data = state.getData(comp);
            if (data == null) {
                try {
                    comp.propagate(state);
                    data = state.getData(comp);
                } catch (Throwable ignored) {}
            }
            if (data != null) {
                for (Class<?> cls = data.getClass(); cls != null; cls = cls.getSuperclass()) {
                    try {
                        Method m = cls.getDeclaredMethod("getContents");
                        m.setAccessible(true);
                        Object contents = m.invoke(data);
                        if (contents instanceof HexModel) return (HexModel) contents;
                    } catch (NoSuchMethodException ignored) {
                        // Keep walking superclass chain (RamState extends MemState)
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }
        return null;
    }

    public static Value readLoggableValue(Component comp, CircuitState state) {
        if (state == null) return Value.NIL;
        Object feat = comp.getFeature(Loggable.class);
        if (feat instanceof Loggable) {
            try {
                return ((Loggable) feat).getLogValue(state, null);
            } catch (Throwable ignored) {}
        }
        return state.getValue(comp.getLocation());
    }

    public static void pokeComponentValue(Component comp, CircuitState state, Value val) {
        String fname = comp.getFactory().getName();
        if ("Pin".equals(fname)) {
            Instance inst = Instance.getInstanceFor(comp);
            if (inst != null) {
                InstanceState is = state.getInstanceState(inst);
                Pin.FACTORY.setValue(is, val);
                state.setValue(comp.getLocation(), val, comp, 1);
                state.markPointAsDirty(comp.getLocation());
                return;
            }
        }
        state.setValue(comp.getLocation(), val, comp, 1);
        state.markPointAsDirty(comp.getLocation());
    }

    // =========================================================================
    // Deep Recursive State Inspection
    // =========================================================================
    public static Map<String, Object> inspectState(Project proj, Circuit circ, Map<String, Object> params) {
        waitForPropagation(proj);
        CircuitState state = (circ == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(circ);
        if (state == null) {
            state = new CircuitState(proj, circ);
        }

        boolean includeSubcircuits = !Boolean.FALSE.equals(params.get("recursive"));
        int maxDepth = params.get("max_depth") instanceof Number ? ((Number) params.get("max_depth")).intValue() : 4;
        boolean includeRam = !Boolean.FALSE.equals(params.get("include_memory"));
        int ramStart = params.get("memory_start") instanceof Number ? Math.max(0, ((Number) params.get("memory_start")).intValue()) : 0;
        int ramCount = params.get("memory_count") instanceof Number ? Math.max(1, Math.min(256, ((Number) params.get("memory_count")).intValue())) : 32;
        String filterPath = params.get("path") instanceof String ? ((String) params.get("path")).trim() : "";

        Map<String, Object> rootSnap = inspectSingleState(circ, state, includeSubcircuits, maxDepth, 0, includeRam, ramStart, ramCount, filterPath);
        if (proj.getSimulator() != null) {
            rootSnap.put("oscillating", Boolean.valueOf(proj.getSimulator().isOscillating()));
        }
        return rootSnap;
    }

    private static Map<String, Object> inspectSingleState(
            Circuit circ, CircuitState state, boolean recursive, int maxDepth, int depth,
            boolean includeRam, int ramStart, int ramCount, String filterPath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("circuit", circ.getName());

        List<Map<String, Object>> pins = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> storage = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> memories = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> subcircuits = new ArrayList<Map<String, Object>>();

        for (Component comp : circ.getNonWires()) {
            String fname = comp.getFactory().getName();
            String id = ComponentMeta.componentId(comp);
            String label = ComponentMeta.getComponentLabel(comp);

            if ("Pin".equals(fname) || "Probe".equals(fname) || "Clock".equals(fname)) {
                Value v = state.getValue(comp.getLocation());
                Map<String, Object> item = ComponentMeta.formatRichValue(v);
                item.put("id", id);
                item.put("type", fname);
                if (!label.isEmpty()) item.put("label", label);
                if ("Pin".equals(fname)) {
                    Boolean isOut = comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
                    item.put("dir", Boolean.TRUE.equals(isOut) ? "output" : "input");
                }
                pins.add(item);
            } else if ("Register".equals(fname) || "Counter".equals(fname)
                    || "D Flip-Flop".equals(fname) || "T Flip-Flop".equals(fname)
                    || "J-K Flip-Flop".equals(fname) || "S-R Flip-Flop".equals(fname)) {
                Value stored = readLoggableValue(comp, state);
                Map<String, Object> item = ComponentMeta.formatRichValue(stored);
                item.put("id", id);
                item.put("type", fname);
                if (!label.isEmpty()) item.put("label", label);
                storage.add(item);
            } else if (includeRam && ("RAM".equals(fname) || "ROM".equals(fname))) {
                HexModel hex = extractHexModel(comp, state);
                Map<String, Object> memInfo = new LinkedHashMap<String, Object>();
                memInfo.put("id", id);
                memInfo.put("type", fname);
                if (!label.isEmpty()) memInfo.put("label", label);
                if (hex != null) {
                    long last = hex.getLastOffset();
                    memInfo.put("size", Long.valueOf(last + 1));
                    memInfo.put("data_width", Integer.valueOf(hex.getValueWidth()));
                    Map<String, String> nonZero = new LinkedHashMap<String, String>();
                    long scanMax = Math.min(last, 4095);
                    for (long a = 0; a <= scanMax && nonZero.size() < 64; a++) {
                        int val = hex.get(a);
                        if (val != 0) {
                            nonZero.put("0x" + Long.toHexString(a).toUpperCase(),
                                    "0x" + Integer.toHexString(val).toUpperCase());
                        }
                    }
                    memInfo.put("nonzero", nonZero);
                    List<String> window = new ArrayList<String>();
                    long wEnd = Math.min(last, (long) ramStart + ramCount - 1);
                    for (long a = ramStart; a <= wEnd; a++) {
                        window.add("0x" + Integer.toHexString(hex.get(a)).toUpperCase());
                    }
                    memInfo.put("window_start", Integer.valueOf(ramStart));
                    memInfo.put("window", window);
                }
                memories.add(memInfo);
            } else if (recursive && depth < maxDepth && comp.getFactory() instanceof SubcircuitFactory) {
                SubcircuitFactory sf = (SubcircuitFactory) comp.getFactory();
                String subName = sf.getSubcircuit().getName();
                if (!filterPath.isEmpty()) {
                    String prefix = filterPath.toLowerCase();
                    if (!subName.toLowerCase().contains(prefix) && !label.toLowerCase().contains(prefix)) {
                        continue;
                    }
                }
                CircuitState subState = sf.getSubstate(state, comp);
                if (subState != null) {
                    Map<String, Object> sub = inspectSingleState(
                            sf.getSubcircuit(), subState, true, maxDepth, depth + 1,
                            includeRam, ramStart, ramCount, "");
                    sub.put("instance_id", id);
                    if (!label.isEmpty()) sub.put("label", label);
                    subcircuits.add(sub);
                }
            }
        }

        out.put("pins", pins);
        if (!storage.isEmpty()) out.put("storage", storage);
        if (!memories.isEmpty()) out.put("memories", memories);
        if (!subcircuits.isEmpty()) out.put("subcircuits", subcircuits);
        return out;
    }

    // =========================================================================
    // Multi-Cycle Test Bench Runner
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> runTestBench(Project proj, Circuit circ, Map<String, Object> params) {
        if (!circ.equals(proj.getCurrentCircuit())) {
            proj.setCurrentCircuit(circ);
        }
        if (Boolean.TRUE.equals(params.get("reset_first"))) {
            resetSimulation(proj);
        }
        int halfTicksPerCycle = params.get("half_ticks_per_cycle") instanceof Number
                ? Math.max(1, ((Number) params.get("half_ticks_per_cycle")).intValue())
                : 2;

        Object vectorsObj = params.get("vectors");
        if (!(vectorsObj instanceof List<?>) || ((List<?>) vectorsObj).isEmpty()) {
            throw new IllegalArgumentException("'vectors' must be a non-empty array of test steps");
        }
        List<?> vectors = (List<?>) vectorsObj;

        List<String> watchList = new ArrayList<String>();
        if (params.get("watch") instanceof List<?>) {
            for (Object w : (List<?>) params.get("watch")) {
                watchList.add(String.valueOf(w));
            }
        } else {
            // Auto-watch labeled Pins, Probes, Registers, Counters, FlipFlops in root circuit
            for (Component c : circ.getNonWires()) {
                String fn = c.getFactory().getName();
                String lbl = ComponentMeta.getComponentLabel(c);
                if (!lbl.isEmpty() && ("Pin".equals(fn) || "Probe".equals(fn)
                        || "Register".equals(fn) || "Counter".equals(fn)
                        || fn.contains("Flip-Flop"))) {
                    watchList.add(lbl);
                }
            }
        }

        List<Map<String, Object>> trace = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> failures = new ArrayList<Map<String, Object>>();
        int totalAssertions = 0;
        int passedAssertions = 0;

        for (int sIdx = 0; sIdx < vectors.size(); sIdx++) {
            Object rawStep = vectors.get(sIdx);
            if (!(rawStep instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Vector step " + sIdx + " must be an object");
            }
            Map<String, Object> step = (Map<String, Object>) rawStep;
            String stepName = step.get("name") instanceof String ? (String) step.get("name") : ("step_" + sIdx);

            CircuitState curState = proj.getCircuitState();

            // 1. Apply signal inputs
            if (step.get("set") instanceof Map<?, ?>) {
                Map<String, Object> setMap = (Map<String, Object>) step.get("set");
                for (Map.Entry<String, Object> e : setMap.entrySet()) {
                    Component comp = ComponentMeta.resolveComponent(circ, e.getKey(), null);
                    int w = comp.getEnds().isEmpty() ? 1 : comp.getEnd(0).getWidth().getWidth();
                    Value val = ComponentMeta.parseValue(e.getValue(), w);
                    pokeComponentValue(comp, curState, val);
                }
            }

            // 2. Apply memory preloads for step
            if (step.get("set_mem") instanceof List<?>) {
                for (Object mo : (List<?>) step.get("set_mem")) {
                    Map<String, Object> mm = (Map<String, Object>) mo;
                    Component mc = ComponentMeta.resolveComponent(circ, String.valueOf(mm.get("comp")), null);
                    HexModel hm = extractHexModel(mc, curState);
                    if (hm == null) throw new IllegalArgumentException("Memory model unavailable on " + mm.get("comp"));
                    long addr = parseLongAddr(mm.get("addr"));
                    long val = parseLongAddr(mm.get("value"));
                    hm.set(addr, (int) val);
                }
            }

            // 3. Propagate combinational logic before clock if needed, then tick if requested
            waitForPropagation(proj);
            Object tickObj = step.get("tick");
            int halfTicks = 0;
            if (Boolean.TRUE.equals(tickObj)) {
                halfTicks = halfTicksPerCycle;
            } else if (tickObj instanceof Number) {
                halfTicks = Math.max(0, ((Number) tickObj).intValue()) * halfTicksPerCycle;
            } else if (step.get("half_ticks") instanceof Number) {
                halfTicks = Math.max(0, ((Number) step.get("half_ticks")).intValue());
            }
            if (halfTicks > 0) {
                tickHalfCycles(proj, halfTicks);
            }

            curState = proj.getCircuitState();
            boolean stepPassed = true;

            // 4. Evaluate signal/register assertions (supports hierarchical SubcircuitLabel.SignalLabel paths!)
            if (step.get("expect") instanceof Map<?, ?>) {
                Map<String, Object> expMap = (Map<String, Object>) step.get("expect");
                for (Map.Entry<String, Object> e : expMap.entrySet()) {
                    totalAssertions++;
                    Value actual = resolveSignalValueByPath(circ, curState, e.getKey());
                    if (ComponentMeta.matchesExpectedValue(actual, e.getValue())) {
                        passedAssertions++;
                    } else {
                        stepPassed = false;
                        Map<String, Object> fail = new LinkedHashMap<String, Object>();
                        fail.put("step", Integer.valueOf(sIdx));
                        fail.put("step_name", stepName);
                        fail.put("target", e.getKey());
                        fail.put("expected", String.valueOf(e.getValue()));
                        fail.put("actual", ComponentMeta.formatValue(actual));
                        fail.put("actual_binary", actual.toDisplayString());
                        failures.add(fail);
                    }
                }
            }

            // 5. Evaluate memory assertions
            if (step.get("expect_mem") instanceof List<?>) {
                for (Object eo : (List<?>) step.get("expect_mem")) {
                    Map<String, Object> em = (Map<String, Object>) eo;
                    totalAssertions++;
                    Component mc = resolveComponentByPath(circ, curState, String.valueOf(em.get("comp")));
                    CircuitState mState = resolveStateForPath(circ, curState, String.valueOf(em.get("comp")));
                    HexModel hm = extractHexModel(mc, mState);
                    if (hm == null) throw new IllegalArgumentException("Memory unavailable on " + em.get("comp"));
                    long addr = parseLongAddr(em.get("addr"));
                    int actualInt = hm.get(addr);
                    Value actualVal = Value.createKnown(com.cburch.logisim.data.BitWidth.create(hm.getValueWidth()), actualInt);
                    if (ComponentMeta.matchesExpectedValue(actualVal, em.get("value"))) {
                        passedAssertions++;
                    } else {
                        stepPassed = false;
                        Map<String, Object> fail = new LinkedHashMap<String, Object>();
                        fail.put("step", Integer.valueOf(sIdx));
                        fail.put("step_name", stepName);
                        fail.put("target", em.get("comp") + "[0x" + Long.toHexString(addr).toUpperCase() + "]");
                        fail.put("expected", String.valueOf(em.get("value")));
                        fail.put("actual", ComponentMeta.formatValue(actualVal));
                        failures.add(fail);
                    }
                }
            }

            // 6. Snapshot watched signals
            Map<String, Object> stepTrace = new LinkedHashMap<String, Object>();
            stepTrace.put("step", Integer.valueOf(sIdx));
            stepTrace.put("name", stepName);
            stepTrace.put("pass", Boolean.valueOf(stepPassed));
            Map<String, String> vals = new LinkedHashMap<String, String>();
            for (String wKey : watchList) {
                try {
                    vals.put(wKey, ComponentMeta.formatValue(resolveSignalValueByPath(circ, curState, wKey)));
                } catch (Throwable err) {
                    vals.put(wKey, "<err>");
                }
            }
            stepTrace.put("values", vals);
            trace.add(stepTrace);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("passed", Boolean.valueOf(failures.isEmpty()));
        out.put("steps_run", Integer.valueOf(vectors.size()));
        out.put("total_assertions", Integer.valueOf(totalAssertions));
        out.put("passed_assertions", Integer.valueOf(passedAssertions));
        out.put("failed_assertions", Integer.valueOf(failures.size()));
        out.put("failures", failures);
        out.put("trace", trace);
        return out;
    }

    private static long parseLongAddr(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.startsWith("0x")) return Long.parseLong(s.substring(2), 16);
        if (s.startsWith("0b")) return Long.parseLong(s.substring(2), 2);
        return Long.parseLong(s);
    }

    private static CircuitState resolveStateForPath(Circuit rootCirc, CircuitState rootState, String path) {
        String[] parts = path.split("\\.");
        Circuit c = rootCirc;
        CircuitState st = rootState;
        for (int i = 0; i < parts.length - 1; i++) {
            Component subComp = ComponentMeta.resolveComponent(c, parts[i], null);
            if (!(subComp.getFactory() instanceof SubcircuitFactory)) {
                throw new IllegalArgumentException("'" + parts[i] + "' in path '" + path + "' is not a subcircuit");
            }
            SubcircuitFactory sf = (SubcircuitFactory) subComp.getFactory();
            st = sf.getSubstate(st, subComp);
            c = sf.getSubcircuit();
        }
        return st;
    }

    private static Component resolveComponentByPath(Circuit rootCirc, CircuitState rootState, String path) {
        String[] parts = path.split("\\.");
        Circuit c = rootCirc;
        CircuitState st = rootState;
        for (int i = 0; i < parts.length - 1; i++) {
            Component subComp = ComponentMeta.resolveComponent(c, parts[i], null);
            if (!(subComp.getFactory() instanceof SubcircuitFactory)) {
                throw new IllegalArgumentException("'" + parts[i] + "' in path '" + path + "' is not a subcircuit");
            }
            SubcircuitFactory sf = (SubcircuitFactory) subComp.getFactory();
            st = sf.getSubstate(st, subComp);
            c = sf.getSubcircuit();
        }
        return ComponentMeta.resolveComponent(c, parts[parts.length - 1], null);
    }

    private static Value resolveSignalValueByPath(Circuit rootCirc, CircuitState rootState, String path) {
        String[] parts = path.split("\\.");
        Circuit c = rootCirc;
        CircuitState st = rootState;
        for (int i = 0; i < parts.length - 1; i++) {
            Component subComp = ComponentMeta.resolveComponent(c, parts[i], null);
            if (!(subComp.getFactory() instanceof SubcircuitFactory)) {
                throw new IllegalArgumentException("'" + parts[i] + "' in path '" + path + "' is not a subcircuit");
            }
            SubcircuitFactory sf = (SubcircuitFactory) subComp.getFactory();
            st = sf.getSubstate(st, subComp);
            c = sf.getSubcircuit();
        }
        Component comp = ComponentMeta.resolveComponent(c, parts[parts.length - 1], null);
        String fn = comp.getFactory().getName();
        if ("Register".equals(fn) || "Counter".equals(fn) || fn.contains("Flip-Flop")) {
            return readLoggableValue(comp, st);
        }
        return st.getValue(comp.getLocation());
    }

    // =========================================================================
    // Boolean Expression OR Truth-Table Synthesis (Quine-McCluskey + Don't-Cares)
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> synthesizeCircuit(Project proj, Circuit targetCirc, Map<String, Object> params) {
        boolean twoInputs = Boolean.TRUE.equals(params.get("two_inputs"));
        boolean useNands = Boolean.TRUE.equals(params.get("use_nands"));

        AnalyzerModel model = new AnalyzerModel();
        model.setCurrentCircuit(proj, targetCirc);

        List<String> inputVars = new ArrayList<String>();
        if (params.get("inputs") instanceof List<?>) {
            for (Object iv : (List<?>) params.get("inputs")) {
                inputVars.add(String.valueOf(iv).trim());
            }
        }

        if (params.get("truth_table") instanceof Map<?, ?>) {
            Map<String, Object> ttSpec = (Map<String, Object>) params.get("truth_table");
            if (inputVars.isEmpty() && ttSpec.get("inputs") instanceof List<?>) {
                for (Object iv : (List<?>) ttSpec.get("inputs")) inputVars.add(String.valueOf(iv).trim());
            }
            if (inputVars.isEmpty() || inputVars.size() > AnalyzerModel.MAX_INPUTS) {
                throw new IllegalArgumentException("truth_table requires 1.." + AnalyzerModel.MAX_INPUTS + " 'inputs'");
            }
            Object outsObj = ttSpec.get("outputs");
            if (!(outsObj instanceof Map<?, ?>) || ((Map<?, ?>) outsObj).isEmpty()) {
                throw new IllegalArgumentException("truth_table.outputs must be a map of {outputName: [0/1/x per row]}");
            }
            Map<String, Object> outsMap = (Map<String, Object>) outsObj;
            List<String> outVars = new ArrayList<String>(outsMap.keySet());
            model.setVariables(inputVars, outVars);
            TruthTable table = model.getTruthTable();
            int expectedRows = 1 << inputVars.size();

            for (int col = 0; col < outVars.size(); col++) {
                String oName = outVars.get(col);
                Object rawCol = outsMap.get(oName);
                Entry[] entries = new Entry[expectedRows];
                if (rawCol instanceof String) {
                    String s = ((String) rawCol).replaceAll("\\s+", "");
                    if (s.length() != expectedRows) {
                        throw new IllegalArgumentException("Output '" + oName + "' string length (" + s.length()
                                + ") must equal 2^" + inputVars.size() + " = " + expectedRows);
                    }
                    for (int r = 0; r < expectedRows; r++) {
                        entries[r] = parseTruthEntry(String.valueOf(s.charAt(r)));
                    }
                } else if (rawCol instanceof List<?>) {
                    List<?> list = (List<?>) rawCol;
                    if (list.size() != expectedRows) {
                        throw new IllegalArgumentException("Output '" + oName + "' array length (" + list.size()
                                + ") must equal 2^" + inputVars.size() + " = " + expectedRows);
                    }
                    for (int r = 0; r < expectedRows; r++) {
                        entries[r] = parseTruthEntry(String.valueOf(list.get(r)));
                    }
                } else {
                    throw new IllegalArgumentException("Output '" + oName + "' must be a binary/x string or list of 0/1/x");
                }
                table.setOutputColumn(col, entries);
            }
            // Promote Quine-McCluskey minimal expression as active expression for each output
            for (String oName : outVars) {
                Expression minExpr = model.getOutputExpressions().getMinimalExpression(oName);
                model.getOutputExpressions().setExpression(oName, minExpr);
            }
        } else if (params.get("expressions") instanceof Map<?, ?>) {
            Map<String, Object> exprMap = (Map<String, Object>) params.get("expressions");
            if (exprMap.isEmpty()) {
                throw new IllegalArgumentException("'expressions' must contain at least one output expression");
            }
            if (inputVars.isEmpty()) {
                inputVars = extractExpressionVariables(exprMap);
            }
            if (inputVars.isEmpty() || inputVars.size() > AnalyzerModel.MAX_INPUTS) {
                throw new IllegalArgumentException("Provide 1.." + AnalyzerModel.MAX_INPUTS + " valid inputs");
            }
            List<String> outVars = new ArrayList<String>(exprMap.keySet());
            model.setVariables(inputVars, outVars);
            for (String oName : outVars) {
                String exprStr = String.valueOf(exprMap.get(oName));
                try {
                    Expression parsed = Parser.parse(exprStr, model);
                    model.getOutputExpressions().setExpression(oName, parsed, exprStr);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Parse error in expression for '" + oName + "': " + ex.getMessage(), ex);
                }
            }
        } else {
            throw new IllegalArgumentException("Provide either 'expressions' or 'truth_table'");
        }

        CircuitMutation mut = CircuitBuilder.build(targetCirc, model, twoInputs, useNands);
        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Synthesize Circuit")));

        Map<String, String> minimized = new LinkedHashMap<String, String>();
        for (int i = 0; i < model.getOutputs().size(); i++) {
            String oName = model.getOutputs().get(i);
            minimized.put(oName, model.getOutputExpressions().getMinimalExpression(oName).toString());
        }

        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("circuit", targetCirc.getName());
        res.put("inputs", inputVars);
        res.put("expressions", minimized);
        res.put("minimized_expressions", minimized);
        res.put("component_count", Integer.valueOf(targetCirc.getNonWires().size()));
        res.put("wire_count", Integer.valueOf(targetCirc.getWires().size()));
        res.put("two_inputs", Boolean.valueOf(twoInputs));
        res.put("use_nands", Boolean.valueOf(useNands));
        return res;
    }

    private static Entry parseTruthEntry(String tok) {
        String s = tok.trim().toLowerCase();
        if ("1".equals(s) || "true".equals(s)) return Entry.ONE;
        if ("0".equals(s) || "false".equals(s)) return Entry.ZERO;
        if ("x".equals(s) || "-".equals(s) || "?".equals(s)) return Entry.DONT_CARE;
        throw new IllegalArgumentException("Invalid truth table entry '" + tok + "' (use 0, 1, or x)");
    }

    private static List<String> extractExpressionVariables(Map<String, Object> exprMap) {
        LinkedHashSet<String> vars = new LinkedHashSet<String>();
        for (Object raw : exprMap.values()) {
            String expr = String.valueOf(raw);
            StringBuilder tok = new StringBuilder();
            for (int i = 0; i <= expr.length(); i++) {
                char c = i < expr.length() ? expr.charAt(i) : ' ';
                if (Character.isLetterOrDigit(c) || c == '_') {
                    tok.append(c);
                } else if (tok.length() > 0) {
                    String t = tok.toString();
                    tok.setLength(0);
                    if (!t.equalsIgnoreCase("AND") && !t.equalsIgnoreCase("OR")
                            && !t.equalsIgnoreCase("XOR") && !t.equalsIgnoreCase("NOT")
                            && !t.equals("0") && !t.equals("1")) {
                        vars.add(t);
                    }
                }
            }
        }
        return new ArrayList<String>(vars);
    }

    // =========================================================================
    // Paginated Combinational Truth Table
    // =========================================================================
    public static Map<String, Object> computeTruthTable(Project proj, Circuit circ, Map<String, Object> params) {
        int offset = params.get("offset") instanceof Number ? Math.max(0, ((Number) params.get("offset")).intValue()) : 0;
        int limit = params.get("limit") instanceof Number ? Math.max(1, Math.min(256, ((Number) params.get("limit")).intValue())) : 64;

        AnalyzerModel model = new AnalyzerModel();
        model.setCurrentCircuit(proj, circ);
        Analyze.computeTable(model, proj, circ, Analyze.getPinLabels(circ));

        TruthTable tt = model.getTruthTable();
        int totalRows = tt.getRowCount();
        int inCols = tt.getInputColumnCount();
        int outCols = tt.getOutputColumnCount();

        List<String> inputHeaders = new ArrayList<String>(inCols);
        for (int c = 0; c < inCols; c++) inputHeaders.add(tt.getInputHeader(c));
        List<String> outputHeaders = new ArrayList<String>(outCols);
        for (int c = 0; c < outCols; c++) outputHeaders.add(tt.getOutputHeader(c));

        Map<String, String> expressions = new LinkedHashMap<String, String>();
        for (String o : outputHeaders) {
            try {
                expressions.put(o, model.getOutputExpressions().getMinimalExpression(o).toString());
            } catch (Throwable ignored) {}
        }

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int end = Math.min(totalRows, offset + limit);
        for (int r = offset; r < end; r++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            StringBuilder inBits = new StringBuilder();
            for (int c = 0; c < inCols; c++) inBits.append(tt.getInputEntry(r, c).getDescription());
            row.put("row", Integer.valueOf(r));
            row.put("inputs", inBits.toString());
            Map<String, String> outs = new LinkedHashMap<String, String>();
            for (int c = 0; c < outCols; c++) outs.put(outputHeaders.get(c), tt.getOutputEntry(r, c).getDescription());
            row.put("outputs", outs);
            rows.add(row);
        }

        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("circuit", circ.getName());
        res.put("input_names", inputHeaders);
        res.put("output_names", outputHeaders);
        res.put("expressions", expressions);
        res.put("total_rows", Integer.valueOf(totalRows));
        res.put("offset", Integer.valueOf(offset));
        res.put("returned_rows", Integer.valueOf(rows.size()));
        res.put("rows", rows);
        return res;
    }

    // =========================================================================
    // Annotated PNG Screenshot Renderer
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> renderScreenshot(Project proj, Circuit circ, Map<String, Object> params) throws Exception {
        double scale = params.get("scale") instanceof Number ? ((Number) params.get("scale")).doubleValue()
                : (params.get("zoom") instanceof Number ? ((Number) params.get("zoom")).doubleValue() : 1.5);
        scale = Math.max(0.5, Math.min(4.0, scale));
        boolean annotate = Boolean.TRUE.equals(params.get("annotate"));
        boolean showPorts = Boolean.TRUE.equals(params.get("show_ports"));
        boolean showErrors = annotate || Boolean.TRUE.equals(params.get("show_errors"));

        Canvas canvas = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        CircuitState state = (circ == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(circ);
        if (state == null) state = new CircuitState(proj, circ);
        waitForPropagation(proj);

        Bounds bds = circ.getBounds();
        if (bds == null || bds.getWidth() <= 0 || bds.getHeight() <= 0) {
            bds = Bounds.create(0, 0, 400, 300);
        }
        int pad = annotate ? 50 : 30;
        int minX = bds.getX() - pad;
        int minY = bds.getY() - pad;
        int worldW = Math.max(240, bds.getWidth() + pad * 2);
        int worldH = Math.max(180, bds.getHeight() + pad * 2);

        int imgW = Math.max(200, Math.min(4096, (int) Math.ceil(worldW * scale)));
        int imgH = Math.max(150, Math.min(4096, (int) Math.ceil(worldH * scale)));

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgW, imgH);

            g.scale(scale, scale);
            g.translate(-minX, -minY);

            // Subtle 10px Logisim dot grid
            g.setColor(new Color(220, 224, 230));
            int gx0 = ((minX / 10) * 10);
            int gy0 = ((minY / 10) * 10);
            for (int gx = gx0; gx <= minX + worldW; gx += 10) {
                for (int gy = gy0; gy <= minY + worldH; gy += 10) {
                    g.fillRect(gx, gy, 1, 1);
                }
            }

            ComponentDrawContext ctx = new ComponentDrawContext(DUMMY_DEST, circ, state, g, g, false);
            circ.draw(ctx, Collections.<Component>emptySet());

            if (annotate) {
                drawAnnotations(g, circ, canvas, showPorts);
            }
            if (showErrors) {
                Map<String, Object> audit = CircuitAuditor.audit(proj, circ, false, false);
                List<Map<String, Object>> issues = (List<Map<String, Object>>) audit.get("issues");
                drawAuditHighlights(g, issues);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] pngBytes = baos.toByteArray();

        String savedPath = null;
        if (params.get("save_path") instanceof String && !((String) params.get("save_path")).trim().isEmpty()) {
            File f = new File(((String) params.get("save_path")).trim());
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), pngBytes);
            savedPath = f.getAbsolutePath();
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("circuit", circ.getName());
        out.put("width", Integer.valueOf(imgW));
        out.put("height", Integer.valueOf(imgH));
        out.put("format", "png");
        out.put("base64", Base64.getEncoder().encodeToString(pngBytes));
        if (savedPath != null) out.put("saved_path", savedPath);
        return out;
    }

    private static void drawAnnotations(Graphics2D g, Circuit circ, Canvas canvas, boolean showPorts) {
        Font idFont = new Font("SansSerif", Font.BOLD, 8);
        Font portFont = new Font("Monospaced", Font.BOLD, 7);
        g.setStroke(new BasicStroke(0.8f));

        for (Component c : circ.getNonWires()) {
            Bounds cb = c.getBounds();
            String id = ComponentMeta.componentId(c);
            g.setFont(idFont);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(id) + 6;
            int bx = cb.getX();
            int by = cb.getY() - 12;
            g.setColor(new Color(15, 23, 42, 200));
            g.fillRoundRect(bx, by, tw, 11, 4, 4);
            g.setColor(Color.WHITE);
            g.drawString(id, bx + 3, by + 9);

            if (showPorts) {
                g.setFont(portFont);
                List<ComponentMeta.ResolvedPort> ports = ComponentMeta.inspectPorts(c, circ, canvas);
                for (ComponentMeta.ResolvedPort rp : ports) {
                    int px = rp.location.getX();
                    int py = rp.location.getY();
                    String tag = rp.index + ":" + rp.name;
                    int pw = g.getFontMetrics().stringWidth(tag) + 4;
                    g.setColor(new Color(37, 99, 235, 210));
                    g.fillRect(px + 2, py - 9, pw, 9);
                    g.setColor(Color.WHITE);
                    g.drawString(tag, px + 4, py - 1);
                }
            }
        }
    }

    private static void drawAuditHighlights(Graphics2D g, List<Map<String, Object>> issues) {
        if (issues == null || issues.isEmpty()) return;
        Font f = new Font("SansSerif", Font.BOLD, 8);
        g.setFont(f);
        g.setStroke(new BasicStroke(1.8f));
        for (Map<String, Object> iss : issues) {
            if (!(iss.get("x") instanceof Number) || !(iss.get("y") instanceof Number)) continue;
            int x = ((Number) iss.get("x")).intValue();
            int y = ((Number) iss.get("y")).intValue();
            boolean isErr = "error".equals(iss.get("severity"));
            Color col = isErr ? new Color(220, 38, 38, 220) : new Color(217, 119, 6, 220);
            g.setColor(col);
            g.drawOval(x - 8, y - 8, 16, 16);
            String rule = String.valueOf(iss.get("rule"));
            g.drawString("!" + rule, x + 10, y + 3);
        }
    }
}
