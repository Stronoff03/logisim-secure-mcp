"""Built-in engineering handbook for high-quality Logisim 2.7.1 circuit design."""

from __future__ import annotations

from typing import Any, Dict, List

TOPICS: Dict[str, Dict[str, Any]] = {
    "overview": {
        "title": "Logisim Secure MCP v2.0.0 Tool Map",
        "summary": (
            "Designed for high-precision digital design: inspect exact port geometry, "
            "build circuits atomically in batches, avoid wire collisions with A* routing "
            "or auto-oriented tunnels, package subcircuits cleanly, run multi-rule DRC, "
            "and verify stateful hardware/CPU datapaths via cycle-accurate testbenches."
        ),
        "recommended_tools": {
            "discover": ["logisim_list_libraries", "logisim_describe_component", "logisim_get_circuit"],
            "build": [
                "logisim_batch",
                "logisim_add_component",
                "logisim_connect",
                "logisim_fanout_tunnels",
                "logisim_arrange",
                "logisim_configure_appearance",
                "logisim_synthesize_circuit",
            ],
            "verify": [
                "logisim_audit_circuit",
                "logisim_test_bench",
                "logisim_inspect_state",
                "logisim_analyze_circuit",
                "logisim_truth_table",
                "logisim_screenshot",
            ],
            "memory": ["logisim_set_memory", "logisim_get_memory"],
        },
        "topics": [
            "overview",
            "workflow",
            "layout",
            "ports",
            "wiring",
            "tunnels",
            "subcircuits",
            "memory",
            "testing",
            "drc",
        ],
    },
    "layout": {
        "title": "Comfortable Schematic Spacing & Visual Readability",
        "details": [
            "Never cram components edge-to-edge ('dip dibe')—leave clean, moderate breathing room so wires and labels are effortless to read without exaggerating canvas size.",
            "Target component gap: ~40px horizontal and ~30px vertical between component bounding boxes (use `auto_space=true` on add calls or run `logisim_arrange(mode='tidy')` / `tidy_after=true` in batch).",
            "Stacked I/O Pins & Tunnels: space vertically by >= 30px center-to-center (`pitch=30`, `tunnel_offset=40` in `logisim_fanout_tunnels`, default `pin_spacing=30` on compact custom ICs).",
            "Wire clearance: default `logisim_connect(route='auto')` keeps a ~20px aisle away from unrelated chip walls and avoids running parallel wires tightly next to each other (pass `tight=true` only if an ultra-dense layout is intentional).",
            "Keep dataflow left-to-right (inputs/ROM/PC -> decode/ALU -> registers/RAM -> outputs/Probes) in aligned columns spaced ~40-60px apart.",
        ],
    },
    "workflow": {
        "title": "High-Quality Circuit Design Workflow",
        "steps": [
            "1. Plan & Partition: Split complex designs (ALUs, RegFiles, Control Units, CPUs) into reusable subcircuits using logisim_create_circuit.",
            "2. Inspect Unknown Components First: Call logisim_describe_component(type=..., attrs=...) whenever unsure of port names, widths, or valid attribute enum values (e.g. RAM 'bus' = 'separate').",
            "3. Build Modules Atomically with Clean Spacing: Inside each subcircuit, leave ~30-40px gaps between components (never edge-to-edge). Use logisim_batch with short 'ref' IDs, set 'tristate': false on input Pins unless a tri-state bus is needed, wire with from_port/to_port (`route='auto'` clearance routing or `mode='tunnel'`), pass `tidy_after=true` or run `logisim_arrange(mode='tidy')` if layout warnings appear, then call logisim_configure_appearance.",
            "4. Keep Schematics Readable: Label every Pin, Register, Counter, RAM, ROM, and Probe. Use logisim_fanout_tunnels (defaults to 30px pitch and 40px offset) on instruction/bus Splitters so bit-slices become clean named tunnels instead of overlapping bundles.",
            "5. Audit Before Simulating: Run logisim_audit_circuit(recursive=true) to catch floating required inputs, unclocked sequential elements, tunnel typos/width mismatches, wire-through-port shorts, and oscillations.",
            "6. Verify Behavior & Inspect Visuals: Load ROM/RAM if applicable, run logisim_test_bench with multi-cycle expect and expect_mem assertions, inspect deep state via logisim_inspect_state, and take logisim_screenshot(annotate=true) to confirm visual cleanliness.",
        ],
    },
    "ports": {
        "title": "Semantic Port Resolution & Attribute Ordering",
        "details": [
            "All grid coordinates snap to multiples of 10 (e.g., 100, 120). Prefer referencing ports semantically by name instead of hardcoding pixel offsets.",
            "Canonical port names (always inspect with logisim_describe_component if in doubt):",
            "- Arithmetic: Adder (A, B, cin, sum, cout), Subtractor (A, B, bin, diff, bout), Multiplier (A, B, cin, prod, cout), Divider (A, B, upper, quot, rem), Comparator (A, B, gt, eq, lt), Negator (in, out), Shifter (in, shamt, out).",
            "- Memory: Register (D, Q, clk, en, clr), Counter (D, ld/load, clk, en, clr, Q, carry), RAM (A, D/dout, din, cs/sel, oe/ld, we/str, clk, clr; attributes 'addrWidth', 'dataWidth', 'bus'), ROM (A, D, cs/sel; attributes 'addrWidth', 'dataWidth').",
            "- NOTE on ROM/RAM: Logisim 2.7.1 ROM/RAM components do NOT have a 'label' attribute. Reference them by returned 'id' (e.g. 'ROM@360,140'), batch 'ref', '@x,y', or unique type ('ROM'/'RAM').",
            "- Flip-Flops: D/T/J-K/S-R Flip-Flop (D/T/J/K/S/R, clk, en, pre, clr, Q, nQ).",
            "- Plexers: Multiplexer (in0..inK, sel, en, out), Demultiplexer (in, sel, en, out0..outK), Decoder (sel, en, out0..outK), Priority Encoder (in0..inK, en_in, out, en_out, gs).",
            "- Gates & Wiring: 0-based input/output or aliases ('in0','in1',...,'out'). Splitter: 'com' (or 'in', '0') for combined end, slice names like '0', '1', '0-3', 'out0'.",
            "- Subcircuits: Resolved dynamically from the subcircuit's Pin Label (case-insensitive) or Pin location/side!",
            "Attribute order: structural attributes such as 'fanout', 'incoming', and 'width' are automatically applied before dependent 'bit0'..'bitN' splitter attributes.",
        ],
    },
    "wiring": {
        "title": "Clearance-Aware A* Routing & Wire Rules",
        "details": [
            "Prefer logisim_connect(from=ref, from_port='Q', to=ref2, to_port='D', route='auto').",
            "route='auto' uses a clearance-aware grid A* router that steers clear of component bodies, leaves a breathing halo around chip walls (~20px aisle), prefers spacing between parallel wires, and avoids crossing foreign pins.",
            "Use wire_clearance (default 10) to tune halo strength, or tight=true only when intentionally packing wires densely.",
            "Do NOT set allow_collision=true unless intentionally tapping into an existing compatible net.",
            "Alternative explicit routes: 'hv' (horizontal then vertical), 'vh' (vertical then horizontal), 'waypoints' (with waypoints=[{x,y}, ...]), 'direct'.",
            "Note on T-junctions: Logisim joins wires wherever two perpendicular segments meet cleanly at a vertex OR at a component pin; running a wire straight across a foreign component pin creates an unintended electrical short—audit_circuit flags this as 'wire_passes_through_port'.",
        ],
    },
    "tunnels": {
        "title": "Clean Datapaths with Tunnels & Bus Fanout",
        "details": [
            "For medium/large circuits (CPUs, ALUs, Control Units), prefer short local stubs + labeled Tunnels instead of long cross-sheet wires.",
            "logisim_connect(..., mode='tunnel', tunnel_name='alu_a') creates a matched pair of spaced outward-facing Tunnels (default 30px stub + label box collision avoidance) wired cleanly to source and destination pins with matching bit widths.",
            "Note on Logisim Tunnel facing: a Tunnel's facing attribute points toward its connection pin; Logisim renders the text box behind it. The server computes outward orientation and non-overlapping label placement automatically.",
            "For instruction decoding or register/bus slicing, use logisim_fanout_tunnels: in ONE call it places a Splitter connected to your bus port, assigns bit0..bitN mappings, and attaches staggered matching-width Tunnels spaced at pitch=30px and tunnel_offset=40px so tunnel labels never touch.",
        ],
    },
    "subcircuits": {
        "title": "Hierarchical Subcircuits & Custom IC Appearances",
        "details": [
            "Always build subcircuits in their own circuit tab (logisim_create_circuit), place input/output Pins with clear unique Labels (e.g. 'A', 'B', 'Op', 'Result', 'Zero'), and set 'output': false on input Pins and 'output': true on output Pins.",
            "Call logisim_configure_appearance to replace Logisim's tiny default DIP box with a legible, grid-aligned IC symbol: assign pins to 'west', 'east', 'north', and 'south' lists, set 'title', 'width', 'height', and optionally 'shape': 'roundrect'.",
            "All appearance ports stay snapped to 10px grid increments so instances dropped into parent circuits align cleanly on wires.",
            "Note: if you add or rename Pins in a subcircuit later, re-run logisim_configure_appearance to regenerate matching appearance ports.",
        ],
    },
    "memory": {
        "title": "RAM, ROM & Program/Data Loading",
        "details": [
            "For CPU/Harvard or clean synchronous designs, configure RAM with attrs={'addr': <addr_bits>, 'data': <data_bits>, 'bus': 'separate'} so Din ('D') and Dout ('Dout') use separate ports and do not contend on a bidirectional bus.",
            "Load ROM instructions or initialize RAM contents cleanly with logisim_set_memory using either sparse 'entries': {'0': '0x2100005', '1': 1234} or contiguous 'values': [...] from 'start'.",
            "Verify contents with logisim_get_memory or inside logisim_test_bench via set_mem and expect_mem assertions.",
        ],
    },
    "testing": {
        "title": "Multi-Cycle Testbenches & State Inspection",
        "details": [
            "logisim_test_bench runs deterministic vectors sequentially and stops propagating between steps:",
            "Each vector supports: 'name', 'set' (Pin label/id -> value), 'set_mem' ([{comp, addr, value}]), 'tick': true (or 'half_ticks': N, defaults to half_ticks_per_cycle=2), 'expect' (Pin/Probe/Register/Counter/Subcircuit.Reg path -> expected value or '0b10x1' bitmask), and 'expect_mem' ([{comp, addr, value}]).",
            "Value formats accepted in set/expect/set_mem: integers (5, -1), hex ('0x0F'), binary ('0b1010'), and bitmask patterns in expect ('0b1x01', '0bxx11').",
            "Use 'watch': ['PC', 'ALU_OUT', 'RegFile.R1'] to record a compact cycle-by-cycle waveform trace across all vectors.",
            "For deep inspection after ticks, call logisim_inspect_state(recursive=true) to read Registers, Counters, Flip-Flops, Pins, Probes, and RAM/ROM slices across subcircuit hierarchy.",
        ],
    },
    "drc": {
        "title": "Design Rule Checks (logisim_audit_circuit)",
        "rules": [
            "signal_error (error): Wire/pin currently evaluates to Error ('E', conflicting drivers) or unexpected Unknown ('U') under live simulation.",
            "oscillation_detected (error): Combinational feedback loop oscillating without stabilizing.",
            "floating_required_input (error/warning): Required component input pin has no connected wire (optional pins like Adder.cin, Reg.en/clr are intentionally suppressed).",
            "unclocked_sequential (error): Register, Counter, or Flip-Flop has an unconnected 'clk' port.",
            "tunnel_orphan (warning): Tunnel name appears only once or only on sources/receivers; bridge suggests closest matching tunnel names to catch typos like 'alu_res' vs 'alu_result'.",
            "tunnel_width_mismatch (error): Two same-named tunnels connect to ports of different bit widths.",
            "wire_passes_through_port (error): A wire segment crosses straight across a foreign component pin without intent, risking a silent electrical short.",
            "component_overlap (warning): Two non-wire components occupy identical/colliding coordinates.",
            "subcircuit_pin_mismatch (warning): Subcircuit definition pins appear poorly configured (e.g. duplicate labels).",
        ],
    },
}


def get_guide(topic: str = "overview") -> Dict[str, Any]:
    key = (topic or "overview").strip().lower()
    if key == "all":
        return {
            "topic": "all",
            "available_topics": list(TOPICS.keys()),
            "sections": TOPICS,
        }
    if key not in TOPICS:
        return {
            "error": f"Unknown guide topic '{topic}'",
            "available_topics": list(TOPICS.keys()) + ["all"],
        }
    return {
        "topic": key,
        "available_topics": list(TOPICS.keys()) + ["all"],
        **TOPICS[key],
    }
