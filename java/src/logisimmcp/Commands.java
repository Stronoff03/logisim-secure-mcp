package logisimmcp;

import com.cburch.hex.HexModel;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.StringUtil;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatcher for all Logisim MCP bridge commands.
 * Safely serializes UI/circuit mutations on the Swing EDT while running
 * simulator synchronization off the EDT to prevent deadlocks.
 */
public final class Commands {
    private static volatile Project activeProject;

    private Commands() {}

    public static Object dispatch(String cmd, Map<String, Object> params) throws Exception {
        return dispatchSingle(cmd, params, null);
    }

    @SuppressWarnings("unchecked")
    private static Object dispatchSingle(
            final String cmd, final Map<String, Object> params, final Map<String, Component> batchRefs) throws Exception {

        if ("status".equals(cmd)) return cmdStatus();
        if ("new_project".equals(cmd)) return cmdNewProject(params);
        if ("open_file".equals(cmd)) return cmdOpenFile(params);
        if ("save_file".equals(cmd)) return cmdSaveFile(params);

        final Project proj = requireProject();

        if ("list_libraries".equals(cmd)) return cmdListLibraries(proj, params);
        if ("describe_component".equals(cmd)) return onEdt(new Callable<Object>() {
            @Override public Object call() { return cmdDescribeComponent(proj, params); }
        });

        // Simulation-only / mixed off-EDT + on-EDT commands
        if ("tick".equals(cmd)) return cmdTick(proj, params);
        if ("reset_simulation".equals(cmd)) return cmdResetSimulation(proj);
        if ("set_simulation".equals(cmd)) return cmdSetSimulation(proj, params);
        if ("read_values".equals(cmd)) return cmdReadValues(proj, params);
        if ("set_inputs".equals(cmd)) return cmdSetInputs(proj, params);
        if ("inspect_state".equals(cmd)) {
            Circuit c = resolveCircuit(proj, params);
            return SimAndSynthOps.inspectState(proj, c, params);
        }
        if ("test_bench".equals(cmd)) {
            Circuit c = resolveCircuit(proj, params);
            return SimAndSynthOps.runTestBench(proj, c, params);
        }
        if ("audit_circuit".equals(cmd) || "check_circuit".equals(cmd)) {
            SimAndSynthOps.waitForPropagation(proj);
            return onEdt(new Callable<Object>() {
                @Override
                public Object call() {
                    Circuit c = resolveCircuit(proj, params);
                    boolean rec = Boolean.TRUE.equals(params.get("recursive"));
                    boolean info = Boolean.TRUE.equals(params.get("include_info"));
                    return CircuitAuditor.audit(proj, c, rec, info);
                }
            });
        }
        if ("screenshot".equals(cmd)) {
            SimAndSynthOps.waitForPropagation(proj);
            return onEdt(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    Circuit c = resolveCircuit(proj, params);
                    return SimAndSynthOps.renderScreenshot(proj, c, params);
                }
            });
        }
        if ("batch".equals(cmd)) return cmdBatch(proj, params);

        // Circuit model/UI commands executed cleanly on Swing EDT
        return onEdt(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                if ("create_circuit".equals(cmd)) return cmdCreateCircuit(proj, params);
                if ("switch_circuit".equals(cmd)) return cmdSwitchCircuit(proj, params);
                if ("rename_circuit".equals(cmd)) return cmdRenameCircuit(proj, params);
                if ("delete_circuit".equals(cmd)) return cmdDeleteCircuit(proj, params);
                if ("set_main_circuit".equals(cmd)) return cmdSetMainCircuit(proj, params);
                if ("undo".equals(cmd)) return cmdUndo(proj);

                Circuit circ = resolveCircuit(proj, params);
                if ("get_circuit".equals(cmd)) return cmdGetCircuit(proj, circ, params);
                if ("add_component".equals(cmd)) return cmdAddComponent(proj, circ, params, batchRefs);
                if ("add_components".equals(cmd)) return cmdAddComponents(proj, circ, params, batchRefs);
                if ("connect".equals(cmd)) return cmdConnect(proj, circ, params, batchRefs);
                if ("add_wires".equals(cmd)) return cmdAddWires(proj, circ, params);
                if ("fanout_tunnels".equals(cmd)) {
                    Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
                    return CircuitLayoutOps.fanoutTunnels(proj, circ, cv, params, batchRefs);
                }
                if ("delete".equals(cmd)) return cmdDelete(proj, circ, params, batchRefs);
                if ("set_attributes".equals(cmd)) return cmdSetAttributes(proj, circ, params, batchRefs);
                if ("move_component".equals(cmd)) return cmdMoveComponent(proj, circ, params, batchRefs);
                if ("clear_circuit".equals(cmd)) return cmdClearCircuit(proj, circ);
                if ("arrange".equals(cmd)) return CircuitLayoutOps.arrangeComponents(proj, circ, params);
                if ("configure_appearance".equals(cmd)) return CircuitLayoutOps.configureAppearance(proj, circ, params);
                if ("truth_table".equals(cmd)) return SimAndSynthOps.computeTruthTable(proj, circ, params);
                if ("analyze_circuit".equals(cmd)) return cmdAnalyzeCircuit(proj, circ);
                if ("synthesize_circuit".equals(cmd)) return SimAndSynthOps.synthesizeCircuit(proj, circ, params);
                if ("audit_circuit".equals(cmd) || "check_circuit".equals(cmd)) {
                    boolean rec = Boolean.TRUE.equals(params.get("recursive"));
                    boolean info = Boolean.TRUE.equals(params.get("include_info"));
                    return CircuitAuditor.audit(proj, circ, rec, info);
                }
                if ("get_memory".equals(cmd)) return cmdGetMemory(proj, circ, params, batchRefs);
                if ("set_memory".equals(cmd)) return cmdSetMemory(proj, circ, params, batchRefs);
                if ("screenshot".equals(cmd)) return SimAndSynthOps.renderScreenshot(proj, circ, params);

                throw new IllegalArgumentException("Unknown command: " + cmd);
            }
        });
    }

    private static <T> T onEdt(final Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.call();
        }
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.set(task.call());
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            });
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
        if (error.get() != null) {
            Throwable t = error.get();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return result.get();
    }

    private static Project getActiveProjectOrNull() {
        List<Project> open = Projects.getOpenProjects();
        Frame top = Projects.getTopFrame();
        if (top != null && top.getProject() != null) {
            return top.getProject();
        }
        Project ap = activeProject;
        if (ap != null && (open == null || open.isEmpty() || open.contains(ap))) {
            return ap;
        }
        if (open != null && !open.isEmpty()) {
            return open.get(open.size() - 1);
        }
        return null;
    }

    private static Project requireProject() {
        Project p = getActiveProjectOrNull();
        if (p == null) {
            throw new IllegalStateException("No open Logisim project. Call new_project or open_file first.");
        }
        return p;
    }

    private static Circuit resolveCircuit(Project proj, Map<String, Object> params) {
        Object cnameObj = params != null ? params.get("circuit") : null;
        if (cnameObj instanceof String && !((String) cnameObj).trim().isEmpty()) {
            String cname = ((String) cnameObj).trim();
            Circuit c = proj.getLogisimFile().getCircuit(cname);
            if (c == null) {
                List<String> avail = new ArrayList<String>();
                for (Circuit ci : proj.getLogisimFile().getCircuits()) avail.add(ci.getName());
                throw new IllegalArgumentException("Circuit '" + cname + "' not found. Available circuits: " + avail);
            }
            return c;
        }
        Circuit cur = proj.getCurrentCircuit();
        if (cur == null) throw new IllegalStateException("No active circuit in project");
        return cur;
    }

    // =========================================================================
    // Project / Status / Library Commands
    // =========================================================================
    private static Map<String, Object> cmdStatus() throws Exception {
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                Map<String, Object> res = new LinkedHashMap<String, Object>();
                res.put("connected", Boolean.TRUE);
                res.put("protocol", Integer.valueOf(WireProtocol.PROTOCOL_VERSION));
                res.put("version", WireProtocol.SERVER_VERSION);
                Project proj = getActiveProjectOrNull();
                res.put("has_project", Boolean.valueOf(proj != null));
                if (proj != null && proj.getLogisimFile() != null) {
                    LogisimFile lf = proj.getLogisimFile();
                    res.put("project_name", lf.getName());
                    res.put("dirty", Boolean.valueOf(lf.isDirty()));
                    Circuit main = lf.getMainCircuit();
                    if (main != null) res.put("main_circuit", main.getName());
                    Circuit cur = proj.getCurrentCircuit();
                    if (cur != null) {
                        res.put("current_circuit", cur.getName());
                        res.put("component_count", Integer.valueOf(cur.getNonWires().size()));
                        res.put("wire_count", Integer.valueOf(cur.getWires().size()));
                    }
                    List<String> circNames = new ArrayList<String>();
                    for (Circuit c : lf.getCircuits()) circNames.add(c.getName());
                    res.put("circuits", circNames);
                    if (proj.getSimulator() != null) {
                        res.put("sim_running", Boolean.valueOf(proj.getSimulator().isRunning()));
                        res.put("sim_ticking", Boolean.valueOf(proj.getSimulator().isTicking()));
                        res.put("oscillating", Boolean.valueOf(proj.getSimulator().isOscillating()));
                    }
                }
                return res;
            }
        });
    }

    private static Map<String, Object> cmdNewProject(final Map<String, Object> params) throws Exception {
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                Project existing = getActiveProjectOrNull();
                Project np = ProjectActions.doNew(existing);
                activeProject = np;
                LogisimFile lf = np.getLogisimFile();
                if (params.get("name") instanceof String && !((String) params.get("name")).trim().isEmpty()) {
                    lf.setName(((String) params.get("name")).trim());
                }
                if (np.getFrame() != null) {
                    np.getFrame().toFront();
                }
                if (existing != null && existing != np && existing.getFrame() != null) {
                    LogisimFile ef = existing.getLogisimFile();
                    boolean blank = ef != null && !ef.isDirty() && ef.getCircuitCount() == 1
                            && ef.getCircuit("main") != null
                            && ef.getCircuit("main").getNonWires().isEmpty()
                            && ef.getCircuit("main").getWires().isEmpty();
                    if (blank) {
                        existing.getFrame().dispose();
                    }
                }

                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("project_name", lf.getName());
                out.put("main_circuit", lf.getMainCircuit() != null ? lf.getMainCircuit().getName() : "main");
                out.put("toolbar_items", Integer.valueOf(lf.getOptions().getToolbarData().size()));
                return out;
            }
        });
    }

    private static Map<String, Object> cmdOpenFile(final Map<String, Object> params) throws Exception {
        String path = strParam(params, "path");
        final File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + file.getAbsolutePath());
        }
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                Project existing = getActiveProjectOrNull();
                Project opened = ProjectActions.doOpen(null, file, Collections.<File, File>emptyMap());
                activeProject = opened;
                if (existing != null && existing != opened && existing.getFrame() != null) {
                    LogisimFile ef = existing.getLogisimFile();
                    boolean blank = ef != null && !ef.isDirty() && ef.getCircuitCount() == 1
                            && ef.getCircuit("main") != null
                            && ef.getCircuit("main").getNonWires().isEmpty()
                            && ef.getCircuit("main").getWires().isEmpty();
                    if (blank) {
                        existing.getFrame().dispose();
                    }
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("path", file.getAbsolutePath());
                out.put("project_name", opened.getLogisimFile().getName());
                List<String> circs = new ArrayList<String>();
                for (Circuit c : opened.getLogisimFile().getCircuits()) circs.add(c.getName());
                out.put("circuits", circs);
                return out;
            }
        });
    }

    private static Map<String, Object> cmdSaveFile(final Map<String, Object> params) throws Exception {
        final Project proj = requireProject();
        String path = strParam(params, "path");
        final File file = new File(path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                Loader loader = proj.getLogisimFile().getLoader();
                boolean ok = loader.save(proj.getLogisimFile(), file);
                if (!ok) {
                    throw new RuntimeException("Failed to save Logisim file to " + file.getAbsolutePath());
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", Boolean.TRUE);
                out.put("path", file.getAbsolutePath());
                out.put("bytes", Long.valueOf(file.length()));
                return out;
            }
        });
    }

    private static Map<String, Object> cmdListLibraries(final Project proj, final Map<String, Object> params) throws Exception {
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                String search = params.get("search") instanceof String ? ((String) params.get("search")).trim().toLowerCase() : "";
                List<Map<String, Object>> libsOut = new ArrayList<Map<String, Object>>();
                for (Library lib : ComponentMeta.getAllLibraries(proj)) {
                    List<Map<String, Object>> toolsOut = new ArrayList<Map<String, Object>>();
                    for (Tool t : lib.getTools()) {
                        if (!(t instanceof AddTool)) continue;
                        AddTool at = (AddTool) t;
                        ComponentFactory f = at.getFactory();
                        String fname = f.getName();
                        String dname = f.getDisplayName();
                        if (!search.isEmpty()
                                && !fname.toLowerCase().contains(search)
                                && !dname.toLowerCase().contains(search)
                                && !lib.getName().toLowerCase().contains(search)) {
                            continue;
                        }
                        Map<String, Object> tm = new LinkedHashMap<String, Object>();
                        tm.put("name", fname);
                        if (!dname.equals(fname)) tm.put("display", dname);
                        tm.put("is_subcircuit", Boolean.valueOf(f instanceof SubcircuitFactory));
                        toolsOut.add(tm);
                    }
                    if (!toolsOut.isEmpty() || search.isEmpty()) {
                        Map<String, Object> lm = new LinkedHashMap<String, Object>();
                        lm.put("name", lib.getName());
                        lm.put("display", lib.getDisplayName());
                        lm.put("components", toolsOut);
                        libsOut.add(lm);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("libraries", libsOut);
                return out;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdDescribeComponent(Project proj, Map<String, Object> params) {
        String type = strParam(params, "type");
        String lib = params.get("lib") instanceof String ? (String) params.get("lib") : null;
        AddTool tool = ComponentMeta.findTool(proj, type, lib);
        ComponentFactory factory = tool.getFactory();
        AttributeSet attrs = factory.createAttributeSet();
        if (params.get("attrs") instanceof Map<?, ?>) {
            ComponentMeta.applyAttributes(attrs, (Map<String, Object>) params.get("attrs"));
        }

        Component sample = factory.createComponent(Location.create(0, 0), attrs);
        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        Circuit dummyCirc = proj.getCurrentCircuit();
        List<ComponentMeta.ResolvedPort> ports = ComponentMeta.inspectPorts(sample, dummyCirc, cv);

        List<Map<String, Object>> portList = new ArrayList<Map<String, Object>>(ports.size());
        for (ComponentMeta.ResolvedPort rp : ports) {
            Map<String, Object> pm = ComponentMeta.portToMap(sample, rp, null, null);
            pm.put("dx", Integer.valueOf(rp.location.getX()));
            pm.put("dy", Integer.valueOf(rp.location.getY()));
            pm.put("aliases", rp.aliases);
            portList.add(pm);
        }

        List<Map<String, Object>> attrList = new ArrayList<Map<String, Object>>();
        for (Attribute<?> a : attrs.getAttributes()) {
            Map<String, Object> am = new LinkedHashMap<String, Object>();
            am.put("name", a.getName());
            am.put("display", a.getDisplayName());
            if ("contents".equals(a.getName())) {
                am.put("default", "<HexContents>");
            } else {
                am.put("default", ComponentMeta.serializeAttributeValue(a, attrs.getValue(a)));
            }
            // Extract valid option strings when editor uses JComboBox
            try {
                @SuppressWarnings("rawtypes")
                Attribute rawAttr = a;
                Object ed = rawAttr.getCellEditor(proj.getFrame(), attrs.getValue(rawAttr));
                if (ed instanceof JComboBox<?>) {
                    JComboBox<?> box = (JComboBox<?>) ed;
                    List<String> opts = new ArrayList<String>();
                    for (int i = 0; i < box.getItemCount(); i++) {
                        Object item = box.getItemAt(i);
                        if (item instanceof AttributeOption) {
                            opts.add(item.toString());
                        } else if (item != null) {
                            opts.add(item.toString());
                        }
                    }
                    if (!opts.isEmpty()) am.put("options", opts);
                }
            } catch (Throwable ignored) {}
            attrList.add(am);
        }

        Bounds bds = sample.getBounds();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", factory.getName());
        out.put("display", factory.getDisplayName());
        out.put("offset_bounds", boundsToMap(bds));
        out.put("ports", portList);
        out.put("attributes", attrList);
        return out;
    }

    // =========================================================================
    // Circuit Queries & Mutations
    // =========================================================================
    private static Map<String, Object> cmdGetCircuit(Project proj, Circuit circ, Map<String, Object> params) {
        int offset = params.get("offset") instanceof Number ? Math.max(0, ((Number) params.get("offset")).intValue()) : 0;
        int limit = params.get("limit") instanceof Number ? Math.max(1, Math.min(500, ((Number) params.get("limit")).intValue())) : 200;
        boolean includePorts = !Boolean.FALSE.equals(params.get("include_ports"));
        boolean includeWires = !Boolean.FALSE.equals(params.get("include_wires"));
        String filterType = params.get("filter_type") instanceof String ? ((String) params.get("filter_type")).trim().toLowerCase() : "";

        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        CircuitState state = (circ == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(circ);

        List<Component> matching = new ArrayList<Component>();
        for (Component c : circ.getNonWires()) {
            String fn = c.getFactory().getName();
            if (filterType.isEmpty() || fn.toLowerCase().contains(filterType)) {
                matching.add(c);
            }
        }

        List<Map<String, Object>> compList = new ArrayList<Map<String, Object>>();
        int end = Math.min(matching.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            Component c = matching.get(i);
            Map<String, Object> cm = new LinkedHashMap<String, Object>();
            cm.put("id", ComponentMeta.componentId(c));
            cm.put("type", c.getFactory().getName());
            String lbl = ComponentMeta.getComponentLabel(c);
            if (!lbl.isEmpty()) cm.put("label", lbl);
            cm.put("x", Integer.valueOf(c.getLocation().getX()));
            cm.put("y", Integer.valueOf(c.getLocation().getY()));
            cm.put("bounds", boundsToMap(c.getBounds()));
            cm.put("attrs", ComponentMeta.serializeAttributes(c.getAttributeSet()));
            if (includePorts) {
                List<ComponentMeta.ResolvedPort> rps = ComponentMeta.inspectPorts(c, circ, cv);
                List<Map<String, Object>> pmList = new ArrayList<Map<String, Object>>(rps.size());
                for (ComponentMeta.ResolvedPort rp : rps) {
                    pmList.add(ComponentMeta.portToMap(c, rp, circ, state));
                }
                cm.put("ports", pmList);
            }
            compList.add(cm);
        }

        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("circuit", circ.getName());
        res.put("bounds", boundsToMap(circ.getBounds()));
        res.put("total_components", Integer.valueOf(matching.size()));
        res.put("offset", Integer.valueOf(offset));
        res.put("returned_components", Integer.valueOf(compList.size()));
        res.put("components", compList);

        Set<Wire> wires = circ.getWires();
        res.put("total_wires", Integer.valueOf(wires.size()));
        if (includeWires) {
            List<Map<String, Object>> wireList = new ArrayList<Map<String, Object>>(Math.min(wires.size(), 500));
            int count = 0;
            for (Wire w : wires) {
                if (count++ >= 500) break;
                Map<String, Object> wm = new LinkedHashMap<String, Object>();
                wm.put("x1", Integer.valueOf(w.getEnd0().getX()));
                wm.put("y1", Integer.valueOf(w.getEnd0().getY()));
                wm.put("x2", Integer.valueOf(w.getEnd1().getX()));
                wm.put("y2", Integer.valueOf(w.getEnd1().getY()));
                wm.put("len", Integer.valueOf(w.getLength()));
                wireList.add(wm);
            }
            res.put("wires", wireList);
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdAddComponent(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        String type = strParam(params, "type");
        String lib = params.get("lib") instanceof String ? (String) params.get("lib") : null;
        int x = ComponentMeta.snap(intParam(params, "x"));
        int y = ComponentMeta.snap(intParam(params, "y"));

        AddTool tool = ComponentMeta.findTool(proj, type, lib);
        ComponentFactory factory = tool.getFactory();
        AttributeSet attrs = factory.createAttributeSet();
        if (params.get("attrs") instanceof Map<?, ?>) {
            ComponentMeta.applyAttributes(attrs, (Map<String, Object>) params.get("attrs"));
        }

        Component comp = factory.createComponent(Location.create(x, y), attrs);
        CircuitMutation mut = new CircuitMutation(circ);
        mut.add(comp);
        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Add " + factory.getName())));

        String ref = params.get("ref") instanceof String ? ((String) params.get("ref")).trim() : "";
        if (!ref.isEmpty() && batchRefs != null) {
            batchRefs.put(ref, comp);
        }

        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        List<ComponentMeta.ResolvedPort> ports = ComponentMeta.inspectPorts(comp, circ, cv);
        List<Map<String, Object>> portList = new ArrayList<Map<String, Object>>(ports.size());
        for (ComponentMeta.ResolvedPort rp : ports) {
            portList.add(ComponentMeta.portToMap(comp, rp, circ, null));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ComponentMeta.componentId(comp));
        if (!ref.isEmpty()) out.put("ref", ref);
        out.put("type", factory.getName());
        String lbl = ComponentMeta.getComponentLabel(comp);
        if (!lbl.isEmpty()) out.put("label", lbl);
        out.put("x", Integer.valueOf(x));
        out.put("y", Integer.valueOf(y));
        out.put("bounds", boundsToMap(comp.getBounds()));
        out.put("ports", portList);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdAddComponents(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        Object itemsObj = params.get("items");
        if (!(itemsObj instanceof List<?>)) {
            throw new IllegalArgumentException("'items' array is required");
        }
        List<?> items = (List<?>) itemsObj;
        CircuitMutation mut = new CircuitMutation(circ);
        List<Map<String, Object>> created = new ArrayList<Map<String, Object>>(items.size());
        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = (Map<String, Object>) items.get(i);
            String type = strParam(item, "type");
            String lib = item.get("lib") instanceof String ? (String) item.get("lib") : null;
            int x = ComponentMeta.snap(intParam(item, "x"));
            int y = ComponentMeta.snap(intParam(item, "y"));

            AddTool tool = ComponentMeta.findTool(proj, type, lib);
            ComponentFactory factory = tool.getFactory();
            AttributeSet attrs = factory.createAttributeSet();
            if (item.get("attrs") instanceof Map<?, ?>) {
                ComponentMeta.applyAttributes(attrs, (Map<String, Object>) item.get("attrs"));
            }
            Component comp = factory.createComponent(Location.create(x, y), attrs);
            mut.add(comp);

            String ref = item.get("ref") instanceof String ? ((String) item.get("ref")).trim() : "";
            if (!ref.isEmpty() && batchRefs != null) batchRefs.put(ref, comp);

            Map<String, Object> info = new LinkedHashMap<String, Object>();
            info.put("id", ComponentMeta.componentId(comp));
            if (!ref.isEmpty()) info.put("ref", ref);
            info.put("type", factory.getName());
            info.put("x", Integer.valueOf(x));
            info.put("y", Integer.valueOf(y));
            List<ComponentMeta.ResolvedPort> ports = ComponentMeta.inspectPorts(comp, circ, cv);
            List<Map<String, Object>> pList = new ArrayList<Map<String, Object>>(ports.size());
            for (ComponentMeta.ResolvedPort rp : ports) pList.add(ComponentMeta.portToMap(comp, rp, circ, null));
            info.put("ports", pList);
            created.add(info);
        }

        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Add Components (" + items.size() + ")")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("added", Integer.valueOf(created.size()));
        out.put("components", created);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdConnect(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;

        Location p1;
        Location p2;
        int w1 = -1;
        int w2 = -1;
        Component srcComp = null;
        Component dstComp = null;

        if (params.get("from") instanceof String) {
            srcComp = ComponentMeta.resolveComponent(circ, (String) params.get("from"), batchRefs);
            ComponentMeta.ResolvedPort rp = ComponentMeta.resolvePort(srcComp, params.get("from_port"), circ, cv);
            p1 = rp.location;
            w1 = rp.endData.getWidth().getWidth();
        } else if (params.get("from_x") instanceof Number && params.get("from_y") instanceof Number) {
            p1 = Location.create(ComponentMeta.snap(((Number) params.get("from_x")).intValue()),
                    ComponentMeta.snap(((Number) params.get("from_y")).intValue()));
        } else {
            throw new IllegalArgumentException("Provide 'from' (+ 'from_port') or 'from_x'/'from_y'");
        }

        if (params.get("to") instanceof String) {
            dstComp = ComponentMeta.resolveComponent(circ, (String) params.get("to"), batchRefs);
            ComponentMeta.ResolvedPort rp = ComponentMeta.resolvePort(dstComp, params.get("to_port"), circ, cv);
            p2 = rp.location;
            w2 = rp.endData.getWidth().getWidth();
        } else if (params.get("to_x") instanceof Number && params.get("to_y") instanceof Number) {
            p2 = Location.create(ComponentMeta.snap(((Number) params.get("to_x")).intValue()),
                    ComponentMeta.snap(((Number) params.get("to_y")).intValue()));
        } else {
            throw new IllegalArgumentException("Provide 'to' (+ 'to_port') or 'to_x'/'to_y'");
        }

        String mode = params.get("mode") instanceof String ? (String) params.get("mode") : "wire";
        String route = params.get("route") instanceof String ? (String) params.get("route") : "auto";
        String tunnelName = params.get("tunnel_name") instanceof String ? (String) params.get("tunnel_name") : null;
        boolean allowCollision = Boolean.TRUE.equals(params.get("allow_collision"));

        List<Location> waypoints = new ArrayList<Location>();
        if (params.get("waypoints") instanceof List<?>) {
            for (Object wpObj : (List<?>) params.get("waypoints")) {
                if (wpObj instanceof Map<?, ?>) {
                    Map<String, Object> wm = (Map<String, Object>) wpObj;
                    waypoints.add(Location.create(intParam(wm, "x"), intParam(wm, "y")));
                } else if (wpObj instanceof List<?> && ((List<?>) wpObj).size() >= 2) {
                    List<?> wl = (List<?>) wpObj;
                    waypoints.add(Location.create(((Number) wl.get(0)).intValue(), ((Number) wl.get(1)).intValue()));
                }
            }
        }

        CircuitMutation mut = new CircuitMutation(circ);
        CircuitLayoutOps.ConnectResult cres = CircuitLayoutOps.connectSmart(
                proj, circ, mut, cv, p1, p2, w1, w2, srcComp, dstComp,
                mode, route, tunnelName, waypoints, allowCollision);
        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Connect")));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", mode);
        out.put("route_used", cres.routeUsed);
        out.put("from", Arrays.asList(Integer.valueOf(p1.getX()), Integer.valueOf(p1.getY())));
        out.put("to", Arrays.asList(Integer.valueOf(p2.getX()), Integer.valueOf(p2.getY())));
        out.put("wires_added", Integer.valueOf(cres.wiresAdded.size()));
        if (cres.tunnelLabel != null) out.put("tunnel_name", cres.tunnelLabel);
        if (!cres.componentsAdded.isEmpty()) {
            List<String> ids = new ArrayList<String>();
            for (Component c : cres.componentsAdded) ids.add(ComponentMeta.componentId(c));
            out.put("tunnels_added", ids);
        }
        if (!cres.warnings.isEmpty()) out.put("warnings", cres.warnings);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdAddWires(Project proj, Circuit circ, Map<String, Object> params) {
        Object segsObj = params.get("segments");
        if (!(segsObj instanceof List<?>)) throw new IllegalArgumentException("'segments' array required");
        CircuitMutation mut = new CircuitMutation(circ);
        int count = 0;
        for (Object item : (List<?>) segsObj) {
            Map<String, Object> m = (Map<String, Object>) item;
            int x1 = ComponentMeta.snap(intParam(m, "x1"));
            int y1 = ComponentMeta.snap(intParam(m, "y1"));
            int x2 = ComponentMeta.snap(intParam(m, "x2"));
            int y2 = ComponentMeta.snap(intParam(m, "y2"));
            if (x1 != x2 && y1 != y2) {
                Location corner = Location.create(x2, y1);
                mut.add(Wire.create(Location.create(x1, y1), corner));
                mut.add(Wire.create(corner, Location.create(x2, y2)));
                count += 2;
            } else if (x1 != x2 || y1 != y2) {
                mut.add(Wire.create(Location.create(x1, y1), Location.create(x2, y2)));
                count++;
            }
        }
        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Add Wires")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("wires_added", Integer.valueOf(count));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdDelete(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        CircuitMutation mut = new CircuitMutation(circ);
        int compRemoved = 0;
        int wireRemoved = 0;
        if (params.get("ids") instanceof List<?>) {
            for (Object idObj : (List<?>) params.get("ids")) {
                Component c = ComponentMeta.resolveComponent(circ, String.valueOf(idObj), batchRefs);
                mut.remove(c);
                compRemoved++;
            }
        }
        if (params.get("wires") instanceof List<?>) {
            for (Object wObj : (List<?>) params.get("wires")) {
                Map<String, Object> wm = (Map<String, Object>) wObj;
                Location a = Location.create(ComponentMeta.snap(intParam(wm, "x1")), ComponentMeta.snap(intParam(wm, "y1")));
                Location b = Location.create(ComponentMeta.snap(intParam(wm, "x2")), ComponentMeta.snap(intParam(wm, "y2")));
                for (Wire w : circ.getWires()) {
                    if ((w.getEnd0().equals(a) && w.getEnd1().equals(b))
                            || (w.getEnd0().equals(b) && w.getEnd1().equals(a))) {
                        mut.remove(w);
                        wireRemoved++;
                    }
                }
            }
        }
        if (compRemoved + wireRemoved > 0) {
            proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Delete")));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("components_removed", Integer.valueOf(compRemoved));
        out.put("wires_removed", Integer.valueOf(wireRemoved));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdSetAttributes(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        String target = params.get("component") instanceof String ? (String) params.get("component")
                : (params.get("id") instanceof String ? (String) params.get("id") : null);
        Component comp = ComponentMeta.resolveComponent(circ, target, batchRefs);
        if (!(params.get("attrs") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("'attrs' map is required");
        }
        Map<String, Object> updates = (Map<String, Object>) params.get("attrs");
        AttributeSet attrs = comp.getAttributeSet();

        // Apply structurally ordered updates inside a CircuitMutation so undo works
        CircuitMutation mut = new CircuitMutation(circ);
        List<Map.Entry<String, Object>> ordered = new ArrayList<Map.Entry<String, Object>>(updates.entrySet());
        // Apply directly cleanly after validating all attrs via ComponentMeta
        AttributeSet copy = (AttributeSet) attrs.clone();
        ComponentMeta.applyAttributes(copy, updates);
        for (Attribute<?> a : copy.getAttributes()) {
            Object newV = copy.getValue(a);
            Object oldV = attrs.getValue(a);
            if (!Objects.equals(newV, oldV)) {
                mut.set(comp, a, newV);
            }
        }
        if (!mut.isEmpty()) {
            proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Set Attributes")));
        }

        Canvas cv = proj.getFrame() != null ? proj.getFrame().getCanvas() : null;
        List<Map<String, Object>> portList = new ArrayList<Map<String, Object>>();
        for (ComponentMeta.ResolvedPort rp : ComponentMeta.inspectPorts(comp, circ, cv)) {
            portList.add(ComponentMeta.portToMap(comp, rp, circ, null));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ComponentMeta.componentId(comp));
        out.put("attrs", ComponentMeta.serializeAttributes(comp.getAttributeSet()));
        out.put("ports", portList);
        return out;
    }

    private static Map<String, Object> cmdMoveComponent(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        String target = params.get("component") instanceof String ? (String) params.get("component")
                : (params.get("id") instanceof String ? (String) params.get("id") : null);
        Component oldC = ComponentMeta.resolveComponent(circ, target, batchRefs);
        int nx = ComponentMeta.snap(intParam(params, "x"));
        int ny = ComponentMeta.snap(intParam(params, "y"));
        Location dst = Location.create(nx, ny);
        Component newC = oldC;
        if (!oldC.getLocation().equals(dst)) {
            AttributeSet copyAttrs = (AttributeSet) oldC.getAttributeSet().clone();
            newC = oldC.getFactory().createComponent(dst, copyAttrs);
            CircuitMutation mut = new CircuitMutation(circ);
            mut.replace(oldC, newC);
            proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Move " + oldC.getFactory().getName())));
            if (batchRefs != null) {
                for (Map.Entry<String, Component> e : batchRefs.entrySet()) {
                    if (e.getValue() == oldC) e.setValue(newC);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("old_id", ComponentMeta.componentId(oldC));
        out.put("new_id", ComponentMeta.componentId(newC));
        out.put("x", Integer.valueOf(nx));
        out.put("y", Integer.valueOf(ny));
        return out;
    }

    private static Map<String, Object> cmdClearCircuit(Project proj, Circuit circ) {
        CircuitMutation mut = new CircuitMutation(circ);
        mut.clear();
        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Clear Circuit")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("cleared", circ.getName());
        return out;
    }

    private static Map<String, Object> cmdUndo(Project proj) {
        if (proj.getLastAction() != null) {
            String desc = proj.getLastAction().getName();
            proj.undoAction();
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("undone", desc);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("undone", null);
        return out;
    }

    // =========================================================================
    // Subcircuit / Circuit Management
    // =========================================================================
    private static Map<String, Object> cmdCreateCircuit(Project proj, Map<String, Object> params) {
        String name = strParam(params, "name").trim();
        if (proj.getLogisimFile().getCircuit(name) != null) {
            throw new IllegalArgumentException("Circuit already exists: " + name);
        }
        Circuit circ = new Circuit(name);
        proj.doAction(LogisimFileActions.addCircuit(circ));
        if (!Boolean.FALSE.equals(params.get("switch_to"))) {
            proj.setCurrentCircuit(circ);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("created", name);
        out.put("current_circuit", proj.getCurrentCircuit().getName());
        return out;
    }

    private static Map<String, Object> cmdSwitchCircuit(Project proj, Map<String, Object> params) {
        Circuit circ = resolveCircuit(proj, params);
        proj.setCurrentCircuit(circ);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("current_circuit", circ.getName());
        return out;
    }

    private static Map<String, Object> cmdRenameCircuit(Project proj, Map<String, Object> params) {
        Circuit circ = resolveCircuit(proj, params);
        String oldName = circ.getName();
        String newName = strParam(params, "new_name").trim();
        if (proj.getLogisimFile().getCircuit(newName) != null) {
            throw new IllegalArgumentException("Circuit named '" + newName + "' already exists");
        }
        circ.setName(newName);
        proj.getLogisimFile().setDirty(true);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("old_name", oldName);
        out.put("new_name", newName);
        return out;
    }

    private static Map<String, Object> cmdDeleteCircuit(Project proj, Map<String, Object> params) {
        Circuit circ = resolveCircuit(proj, params);
        if (proj.getLogisimFile().getCircuitCount() <= 1) {
            throw new IllegalStateException("Cannot delete the last remaining circuit in the project");
        }
        String name = circ.getName();
        proj.doAction(LogisimFileActions.removeCircuit(circ));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", name);
        out.put("current_circuit", proj.getCurrentCircuit().getName());
        return out;
    }

    private static Map<String, Object> cmdSetMainCircuit(Project proj, Map<String, Object> params) {
        Circuit circ = resolveCircuit(proj, params);
        proj.doAction(LogisimFileActions.setMainCircuit(circ));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("main_circuit", circ.getName());
        return out;
    }

    // =========================================================================
    // Simulation & Memory Commands
    // =========================================================================
    private static Map<String, Object> cmdTick(Project proj, Map<String, Object> params) throws Exception {
        Circuit c = onEdt(new Callable<Circuit>() {
            @Override
            public Circuit call() {
                Circuit tc = resolveCircuit(proj, params);
                if (!tc.equals(proj.getCurrentCircuit())) proj.setCurrentCircuit(tc);
                return tc;
            }
        });
        int halfTicks;
        if (params.get("cycles") instanceof Number) {
            halfTicks = Math.max(1, ((Number) params.get("cycles")).intValue() * 2);
        } else if (params.get("ticks") instanceof Number) {
            halfTicks = Math.max(1, ((Number) params.get("ticks")).intValue());
        } else {
            halfTicks = 2;
        }
        SimAndSynthOps.tickHalfCycles(proj, halfTicks);
        Map<String, Object> out = SimAndSynthOps.inspectState(proj, c, params);
        out.put("half_ticks_executed", Integer.valueOf(halfTicks));
        return out;
    }

    private static Map<String, Object> cmdResetSimulation(Project proj) {
        SimAndSynthOps.resetSimulation(proj);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("reset", Boolean.TRUE);
        return out;
    }

    private static Map<String, Object> cmdSetSimulation(final Project proj, final Map<String, Object> params) throws Exception {
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                Simulator sim = proj.getSimulator();
                if (params.get("running") instanceof Boolean) sim.setIsRunning(((Boolean) params.get("running")).booleanValue());
                if (params.get("ticking") instanceof Boolean) sim.setIsTicking(((Boolean) params.get("ticking")).booleanValue());
                if (params.get("frequency") instanceof Number) sim.setTickFrequency(((Number) params.get("frequency")).doubleValue());
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("running", Boolean.valueOf(sim.isRunning()));
                out.put("ticking", Boolean.valueOf(sim.isTicking()));
                out.put("frequency", Double.valueOf(sim.getTickFrequency()));
                return out;
            }
        });
    }

    private static Map<String, Object> cmdReadValues(final Project proj, final Map<String, Object> params) throws Exception {
        Circuit circ = onEdt(new Callable<Circuit>() {
            @Override
            public Circuit call() {
                return resolveCircuit(proj, params);
            }
        });
        return SimAndSynthOps.inspectState(proj, circ, params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdSetInputs(final Project proj, final Map<String, Object> params) throws Exception {
        final Map<String, Object> valuesMap = params.get("values") instanceof Map<?, ?>
                ? (Map<String, Object>) params.get("values")
                : Collections.<String, Object>emptyMap();
        if (valuesMap.isEmpty()) {
            throw new IllegalArgumentException("'values' map of {pin_label_or_id: value} is required");
        }
        Circuit circ = onEdt(new Callable<Circuit>() {
            @Override
            public Circuit call() {
                Circuit tc = resolveCircuit(proj, params);
                if (!tc.equals(proj.getCurrentCircuit())) proj.setCurrentCircuit(tc);
                CircuitState state = proj.getCircuitState();
                for (Map.Entry<String, Object> e : valuesMap.entrySet()) {
                    Component comp = ComponentMeta.resolveComponent(tc, e.getKey(), null);
                    int w = comp.getEnds().isEmpty() ? 1 : comp.getEnd(0).getWidth().getWidth();
                    Value v = ComponentMeta.parseValue(e.getValue(), w);
                    SimAndSynthOps.pokeComponentValue(comp, state, v);
                }
                return tc;
            }
        });
        SimAndSynthOps.waitForPropagation(proj);
        return SimAndSynthOps.inspectState(proj, circ, params);
    }

    private static Map<String, Object> cmdAnalyzeCircuit(Project proj, Circuit circ) {
        AnalyzerModel model = new AnalyzerModel();
        model.setCurrentCircuit(proj, circ);
        Map<String, String> exprs = new LinkedHashMap<String, String>();
        String mode = "expression";
        try {
            Analyze.computeExpression(model, circ, Analyze.getPinLabels(circ));
        } catch (Throwable fallbackNeeded) {
            mode = "truth_table_fallback";
            Analyze.computeTable(model, proj, circ, Analyze.getPinLabels(circ));
        }
        for (int i = 0; i < model.getOutputs().size(); i++) {
            String outName = model.getOutputs().get(i);
            exprs.put(outName, model.getOutputExpressions().getMinimalExpression(outName).toString());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("circuit", circ.getName());
        out.put("mode", mode);
        out.put("inputs", new ArrayList<String>(model.getInputs().getAll()));
        out.put("outputs", new ArrayList<String>(model.getOutputs().getAll()));
        out.put("minimized_expressions", exprs);
        return out;
    }

    private static Map<String, Object> cmdGetMemory(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        String compSpec = params.get("component") instanceof String ? (String) params.get("component")
                : (params.get("id") instanceof String ? (String) params.get("id") : null);
        Component comp = ComponentMeta.resolveComponent(circ, compSpec, batchRefs);
        CircuitState state = (circ == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(circ);
        if (state == null) state = new CircuitState(proj, circ);
        HexModel hex = SimAndSynthOps.extractHexModel(comp, state);
        if (hex == null) {
            throw new IllegalArgumentException(ComponentMeta.componentId(comp) + " is not an initialized RAM/ROM component");
        }
        long start = params.get("start") instanceof Number ? Math.max(0L, ((Number) params.get("start")).longValue()) : 0L;
        int count = params.get("count") instanceof Number ? Math.max(1, Math.min(512, ((Number) params.get("count")).intValue())) : 64;
        long end = Math.min(hex.getLastOffset(), start + count - 1);

        List<String> words = new ArrayList<String>();
        for (long a = start; a <= end; a++) {
            words.add("0x" + Integer.toHexString(hex.get(a)).toUpperCase());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ComponentMeta.componentId(comp));
        out.put("start", Long.valueOf(start));
        out.put("count", Integer.valueOf(words.size()));
        out.put("addr_bits", Integer.valueOf(64 - Long.numberOfLeadingZeros(Math.max(1L, hex.getLastOffset()))));
        out.put("data_width", Integer.valueOf(hex.getValueWidth()));
        out.put("values", words);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdSetMemory(
            Project proj, Circuit circ, Map<String, Object> params, Map<String, Component> batchRefs) {
        String compSpec = params.get("component") instanceof String ? (String) params.get("component")
                : (params.get("id") instanceof String ? (String) params.get("id") : null);
        Component comp = ComponentMeta.resolveComponent(circ, compSpec, batchRefs);
        CircuitState state = (circ == proj.getCurrentCircuit()) ? proj.getCircuitState() : proj.getCircuitState(circ);
        if (state == null) state = new CircuitState(proj, circ);
        HexModel hex = SimAndSynthOps.extractHexModel(comp, state);
        if (hex == null) {
            throw new IllegalArgumentException(ComponentMeta.componentId(comp) + " is not an initialized RAM/ROM component");
        }
        int written = 0;
        if (params.get("values") instanceof List<?>) {
            long start = params.get("start") instanceof Number ? Math.max(0L, ((Number) params.get("start")).longValue()) : 0L;
            List<?> vals = (List<?>) params.get("values");
            int[] arr = new int[vals.size()];
            for (int i = 0; i < vals.size(); i++) arr[i] = parseMemWord(vals.get(i));
            hex.set(start, arr);
            written = arr.length;
        }
        if (params.get("entries") instanceof Map<?, ?>) {
            Map<String, Object> entries = (Map<String, Object>) params.get("entries");
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                long addr = parseMemWord(e.getKey()) & 0xFFFFFFFFL;
                int val = parseMemWord(e.getValue());
                hex.set(addr, val);
                written++;
            }
        }
        proj.getLogisimFile().setDirty(true);
        state.markComponentAsDirty(comp);
        if (proj.getFrame() != null && proj.getFrame().getCanvas() != null) {
            proj.getFrame().getCanvas().repaint();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ComponentMeta.componentId(comp));
        out.put("written", Integer.valueOf(written));
        return out;
    }

    private static int parseMemWord(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        String s = String.valueOf(raw).trim().toLowerCase();
        if (s.startsWith("0x")) return (int) Long.parseLong(s.substring(2), 16);
        if (s.startsWith("0b")) return (int) Long.parseLong(s.substring(2), 2);
        return (int) Long.parseLong(s);
    }

    // =========================================================================
    // Atomic / Multi-Step Batch Runner with Ref Aliases
    // =========================================================================
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cmdBatch(Project proj, Map<String, Object> params) throws Exception {
        Object opsObj = params.get("operations");
        if (!(opsObj instanceof List<?>) || ((List<?>) opsObj).isEmpty()) {
            throw new IllegalArgumentException("'operations' must be a non-empty array");
        }
        List<?> ops = (List<?>) opsObj;
        boolean stopOnError = !Boolean.FALSE.equals(params.get("stop_on_error"));
        String defaultCircuit = params.get("circuit") instanceof String ? (String) params.get("circuit") : null;

        Map<String, Component> batchRefs = new LinkedHashMap<String, Component>();
        List<Map<String, Object>> stepResults = new ArrayList<Map<String, Object>>(ops.size());
        int succeeded = 0;
        int failed = 0;

        for (int i = 0; i < ops.size(); i++) {
            Map<String, Object> step = new LinkedHashMap<String, Object>((Map<String, Object>) ops.get(i));
            String opName = strParam(step, "op");
            if ("batch".equals(opName)) {
                throw new IllegalArgumentException("Nested 'batch' operations are not permitted (op index " + i + ")");
            }
            if (defaultCircuit != null && !step.containsKey("circuit")) {
                step.put("circuit", defaultCircuit);
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("index", Integer.valueOf(i));
            entry.put("op", opName);
            try {
                Object res = dispatchSingle(opName, step, batchRefs);
                entry.put("ok", Boolean.TRUE);
                entry.put("result", res);
                succeeded++;
            } catch (Throwable t) {
                failed++;
                entry.put("ok", Boolean.FALSE);
                String msg = t.getMessage();
                entry.put("error", (msg != null && !msg.isEmpty()) ? msg : t.getClass().getSimpleName());
                stepResults.add(entry);
                if (stopOnError) break;
                continue;
            }
            stepResults.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(failed == 0));
        out.put("total_ops", Integer.valueOf(ops.size()));
        out.put("executed", Integer.valueOf(stepResults.size()));
        out.put("succeeded", Integer.valueOf(succeeded));
        out.put("failed", Integer.valueOf(failed));
        out.put("results", stepResults);

        if (Boolean.TRUE.equals(params.get("audit_after"))
                || Boolean.TRUE.equals(params.get("screenshot_after"))) {
            SimAndSynthOps.waitForPropagation(proj);
        }
        if (Boolean.TRUE.equals(params.get("audit_after"))) {
            final Map<String, Object> auditParams = new LinkedHashMap<String, Object>();
            if (defaultCircuit != null) auditParams.put("circuit", defaultCircuit);
            out.put("audit", onEdt(new Callable<Object>() {
                @Override
                public Object call() {
                    return CircuitAuditor.audit(proj, resolveCircuit(proj, auditParams), false, false);
                }
            }));
        }
        if (Boolean.TRUE.equals(params.get("screenshot_after"))) {
            final Map<String, Object> shotParams = new LinkedHashMap<String, Object>();
            if (defaultCircuit != null) shotParams.put("circuit", defaultCircuit);
            shotParams.put("annotate", Boolean.TRUE);
            out.put("screenshot", onEdt(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    return SimAndSynthOps.renderScreenshot(proj, resolveCircuit(proj, shotParams), shotParams);
                }
            }));
        }
        return out;
    }

    private static String strParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required string parameter '" + key + "'");
        }
        return (String) v;
    }

    private static int intParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("Missing required integer parameter '" + key + "'");
        }
        return ((Number) v).intValue();
    }

    private static Map<String, Object> boundsToMap(Bounds b) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (b == null) return m;
        m.put("x", Integer.valueOf(b.getX()));
        m.put("y", Integer.valueOf(b.getY()));
        m.put("width", Integer.valueOf(b.getWidth()));
        m.put("height", Integer.valueOf(b.getHeight()));
        return m;
    }
}
