"""MCP tool definitions, annotations, and strict validation schemas."""

from __future__ import annotations

import copy
from typing import Any, Dict, List

_RO = {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}
_MUT = {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False}
_MUT_IDEM = {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}
_DESTR = {"readOnlyHint": False, "destructiveHint": True, "idempotentHint": False, "openWorldHint": False}

_FACING = ["east", "west", "north", "south"]


def _obj(
    props: Dict[str, Any],
    required: List[str] | None = None,
    additional: bool = False,
) -> Dict[str, Any]:
    s: Dict[str, Any] = {
        "type": "object",
        "properties": props,
        "additionalProperties": additional,
    }
    if required:
        s["required"] = required
    return s


def _tool(
    name: str,
    description: str,
    schema: Dict[str, Any],
    annotations: Dict[str, Any],
    cmd: str | None = None,
) -> Dict[str, Any]:
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": annotations,
        "_cmd": cmd or name.removeprefix("logisim_"),
    }


_CIRCUIT_OPT = {
    "type": "string",
    "minLength": 1,
    "description": "Target circuit name (defaults to currently active circuit).",
}

_PORT_REF = {
    "type": ["string", "integer"],
    "description": "Semantic port name/alias (e.g. 'A','B','Q','clk','sum','in0') or 0-based port index.",
}

_VAL_ATOM = {
    "type": ["integer", "string"],
    "description": "Integer, hex string ('0x...'), or binary string ('0b...'; wildcards 'x' allowed in assertions).",
}

_WAYPOINT_SCHEMA = {
    "anyOf": [
        _obj({"x": {"type": "integer"}, "y": {"type": "integer"}}, ["x", "y"]),
        {"type": "array", "items": {"type": "integer"}, "minItems": 2, "maxItems": 2},
    ]
}

_WIRE_SEG_SCHEMA = _obj(
    {
        "x1": {"type": "integer"},
        "y1": {"type": "integer"},
        "x2": {"type": "integer"},
        "y2": {"type": "integer"},
    },
    ["x1", "y1", "x2", "y2"],
)

_MEM_ASSERT_SCHEMA = _obj(
    {
        "comp": {"type": "string", "minLength": 1, "description": "RAM/ROM component label, id, or '@x,y'."},
        "addr": _VAL_ATOM,
        "value": _VAL_ATOM,
    },
    ["comp", "addr", "value"],
)

_ADD_ITEM_SCHEMA = _obj(
    {
        "type": {"type": "string", "minLength": 1, "description": "Logisim component factory name or circuit name."},
        "lib": {"type": "string", "minLength": 1, "description": "Optional library name (e.g. 'Wiring','Gates','Memory')."},
        "x": {"type": "integer", "description": "Grid X coordinate (snapped to nearest 10)."},
        "y": {"type": "integer", "description": "Grid Y coordinate (snapped to nearest 10)."},
        "attrs": {"type": "object", "additionalProperties": True, "description": "Attribute overrides (bits, facing, label, data, addr, etc.)."},
        "ref": {"type": "string", "minLength": 1, "description": "Short alias resolvable inside the same batch."},
    },
    ["type", "x", "y"],
)

_TOOL_DEFS: List[Dict[str, Any]] = [
    _tool(
        "logisim_guide",
        "Get built-in Logisim v2 engineering guidance on workflow, readable layout spacing, semantic port names, A* wiring, tunnels, custom IC packaging, memory setup, testbenches, and DRC rules.",
        _obj(
            {
                "topic": {
                    "type": "string",
                    "enum": [
                        "overview",
                        "all",
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
                }
            }
        ),
        _RO,
        cmd="_local_guide",
    ),
    _tool(
        "logisim_status",
        "Check live JVM bridge connectivity, protocol version, current project/circuit, loaded libraries, and simulation state.",
        _obj({}),
        _RO,
        cmd="status",
    ),
    _tool(
        "logisim_launch",
        "Launch Logisim 2.7.1 with the secure v2.0.0 MCP bridge JAR (optional .circ file) and complete mutual HMAC authentication.",
        _obj(
            {
                "file_path": {"type": "string", "minLength": 1, "description": "Optional .circ file path to open at startup."},
                "wait_seconds": {"type": "number", "minimum": 1, "maximum": 60},
            }
        ),
        _MUT_IDEM,
        cmd="_local_launch",
    ),
    _tool(
        "logisim_attach",
        "Attach the v2.0.0 MCP bridge JAR dynamically to an already-running Logisim JVM process.",
        _obj(
            {
                "pid": {"type": "integer", "minimum": 1, "maximum": 4194304},
                "wait_seconds": {"type": "number", "minimum": 1, "maximum": 30},
            }
        ),
        _MUT_IDEM,
        cmd="_local_attach",
    ),
    _tool(
        "logisim_new_project",
        "Create a fresh empty Logisim project with standard toolbar (Hand/Edit/Wiring) initialized.",
        _obj({"name": {"type": "string", "minLength": 1}}),
        _DESTR,
    ),
    _tool(
        "logisim_open_file",
        "Open an existing .circ project file in Logisim.",
        _obj({"path": {"type": "string", "minLength": 1}}, ["path"]),
        _DESTR,
    ),
    _tool(
        "logisim_save_file",
        "Save the current Logisim project to a .circ XML file.",
        _obj({"path": {"type": "string", "minLength": 1}}, ["path"]),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_list_libraries",
        "List available built-in libraries, components, and project subcircuits (optionally filtered by search substring).",
        _obj({"search": {"type": "string", "description": "Case-insensitive substring filter."}}),
        _RO,
    ),
    _tool(
        "logisim_describe_component",
        "Inspect exact port names/offsets/directions/widths and all valid attributes & enum choices for any Logisim component or subcircuit.",
        _obj(
            {
                "type": {"type": "string", "minLength": 1},
                "lib": {"type": "string", "minLength": 1},
                "attrs": {"type": "object", "additionalProperties": True, "description": "Test attributes (e.g. bits, fanout, inputs) to see resulting port layout."},
            },
            ["type"],
        ),
        _RO,
    ),
    _tool(
        "logisim_get_circuit",
        "Inspect a circuit's components, semantic ports, attributes, and wires with pagination and optional type filtering.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "offset": {"type": "integer", "minimum": 0},
                "limit": {"type": "integer", "minimum": 1, "maximum": 500},
                "include_ports": {"type": "boolean"},
                "include_wires": {"type": "boolean"},
                "filter_type": {"type": "string"},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_add_component",
        "Place a single component or subcircuit instance on the grid and inspect its resolved ports.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "type": {"type": "string", "minLength": 1},
                "lib": {"type": "string", "minLength": 1},
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "attrs": {"type": "object", "additionalProperties": True},
                "ref": {"type": "string", "minLength": 1, "description": "Optional alias within logisim_batch."},
                "auto_space": {"type": "boolean", "description": "If true, gently nudge colliding coordinates to keep ~40x30px breathing room."},
            },
            ["type", "x", "y"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_add_components",
        "Add multiple components in one undoable transaction, optionally assigning batch 'ref' aliases.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "items": {"type": "array", "items": _ADD_ITEM_SCHEMA, "minItems": 1},
                "auto_space": {"type": "boolean", "description": "If true, gently nudge colliding items to keep ~40x30px breathing room."},
            },
            ["items"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_connect",
        "Connect two ports/coordinates either via clearance-aware A* wire routing ('wire') or spaced outward-oriented matched-width Tunnels ('tunnel').",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "from": {"type": "string", "minLength": 1, "description": "Source component ref, id, label, or '@x,y'."},
                "from_port": _PORT_REF,
                "from_x": {"type": "integer"},
                "from_y": {"type": "integer"},
                "to": {"type": "string", "minLength": 1, "description": "Destination component ref, id, label, or '@x,y'."},
                "to_port": _PORT_REF,
                "to_x": {"type": "integer"},
                "to_y": {"type": "integer"},
                "mode": {"type": "string", "enum": ["wire", "tunnel"], "description": "wire (clearance A* orthogonal trace) or tunnel (spaced auto-oriented matched pair)."},
                "route": {"type": "string", "enum": ["auto", "hv", "vh", "direct", "waypoints"]},
                "tunnel_name": {"type": "string", "minLength": 1, "description": "Name for tunnel pair when mode='tunnel'."},
                "allow_collision": {"type": "boolean", "description": "Allow wires to pass across foreign pins (default false to prevent shorts)."},
                "wire_clearance": {"type": "integer", "minimum": 0, "maximum": 60, "description": "Preferred halo margin around unrelated components and parallel wires (default 10)."},
                "tight": {"type": "boolean", "description": "If true, bypass clearance spacing/tunnel padding for ultra-compact placement."},
                "waypoints": {"type": "array", "items": _WAYPOINT_SCHEMA, "minItems": 1},
            }
        ),
        _MUT,
    ),
    _tool(
        "logisim_add_wires",
        "Add raw orthogonal/Manhattan wire segments by coordinates.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "segments": {"type": "array", "items": _WIRE_SEG_SCHEMA, "minItems": 1},
            },
            ["segments"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_fanout_tunnels",
        "Create a Splitter plus comfortably spaced, cleanly labeled matching-width Tunnels (default 30px pitch, 40px offset) for bus/instruction slicing.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "from": {"type": "string", "minLength": 1, "description": "Optional bus source component to auto-connect Splitter 'com' port."},
                "from_port": _PORT_REF,
                "splitter_x": {"type": "integer"},
                "splitter_y": {"type": "integer"},
                "width": {"type": "integer", "minimum": 1, "maximum": 32},
                "facing": {"type": "string", "enum": _FACING},
                "appear": {"type": "string", "enum": ["left", "center", "right"]},
                "tunnel_offset": {"type": "integer", "minimum": 10, "maximum": 200, "description": "Horizontal/vertical offset from splitter branch to tunnel column (default 40px)."},
                "pitch": {"type": "integer", "minimum": 20, "maximum": 100, "description": "Spacing between adjacent fanout tunnels (default 30px so labels never touch)."},
                "ref": {"type": "string", "minLength": 1},
                "slices": {
                    "type": "array",
                    "minItems": 1,
                    "items": _obj(
                        {
                            "name": {"type": "string", "minLength": 1},
                            "bits": {
                                "anyOf": [
                                    {"type": "string", "minLength": 1},
                                    {"type": "integer", "minimum": 0, "maximum": 31},
                                    {"type": "array", "items": {"type": "integer", "minimum": 0, "maximum": 31}, "minItems": 1},
                                ],
                                "description": "Bit index or slice like '20-25', '0-3', 3, or [0,1].",
                            },
                        },
                        ["name", "bits"],
                    ),
                },
            },
            ["slices"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_delete",
        "Delete specific components (by id, label, ref, or '@x,y') and/or wire segments.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "ids": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "wires": {"type": "array", "items": _WIRE_SEG_SCHEMA},
            }
        ),
        _DESTR,
    ),
    _tool(
        "logisim_set_attributes",
        "Update attributes on an existing component (structural attributes like 'fanout'/'width' apply before dependent attributes).",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "component": {"type": "string", "minLength": 1},
                "id": {"type": "string", "minLength": 1},
                "attrs": {"type": "object", "additionalProperties": True},
            },
            ["attrs"],
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_move_component",
        "Move an existing component to new snapped (x, y) grid coordinates.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "component": {"type": "string", "minLength": 1},
                "id": {"type": "string", "minLength": 1},
                "x": {"type": "integer"},
                "y": {"type": "integer"},
            },
            ["x", "y"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_clear_circuit",
        "Delete all components and wires in a circuit.",
        _obj({"circuit": _CIRCUIT_OPT}),
        _DESTR,
    ),
    _tool(
        "logisim_undo",
        "Undo the last edit action in the active Logisim project.",
        _obj({}),
        _MUT,
    ),
    _tool(
        "logisim_arrange",
        "Tidy component placement on the grid via smart spacing/de-crowding ('tidy'), align, distribute, regular grid layout, or snap-to-10.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "components": {"type": "array", "items": {"type": "string", "minLength": 1}, "minItems": 1, "description": "Optional list of components; if omitted in 'tidy' mode, tidies the entire circuit."},
                "mode": {"type": "string", "enum": ["align", "distribute", "grid", "snap", "tidy"]},
                "align": {"type": "string", "enum": ["left", "right", "top", "bottom", "center_x", "center_y"]},
                "axis": {"type": "string", "enum": ["horizontal", "vertical", "x", "y"]},
                "spacing": {"type": "integer", "minimum": -2000, "maximum": 2000},
                "columns": {"type": "integer", "minimum": 1, "maximum": 64},
                "gap_x": {"type": "integer", "minimum": 10, "maximum": 2000, "description": "Horizontal breathing room between boxes (default 40 for 'tidy')."},
                "gap_y": {"type": "integer", "minimum": 10, "maximum": 2000, "description": "Vertical breathing room between boxes (default 30 for 'tidy')."},
                "origin_x": {"type": "integer"},
                "origin_y": {"type": "integer"},
                "reroute_wires": {"type": "boolean", "description": "For mode='tidy', re-route connected wires with clearance A* after moving components (default true)."},
            }
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_configure_appearance",
        "Design a clean, grid-aligned custom IC chip appearance for a subcircuit with labeled west/east/north/south pins, notch, and title.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "title": {"type": "string"},
                "width": {"type": "integer", "minimum": 30, "maximum": 2000},
                "height": {"type": "integer", "minimum": 30, "maximum": 2000},
                "pin_spacing": {"type": "integer", "minimum": 10, "maximum": 200},
                "show_pin_labels": {"type": "boolean"},
                "shape": {"type": "string", "enum": ["rect", "roundrect", "rounded"]},
                "west": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "east": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "north": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "south": {"type": "array", "items": {"type": "string", "minLength": 1}},
            }
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_create_circuit",
        "Create a new subcircuit (optionally switching the canvas to it immediately).",
        _obj(
            {
                "name": {"type": "string", "minLength": 1},
                "switch_to": {"type": "boolean"},
            },
            ["name"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_switch_circuit",
        "Switch the active Logisim canvas view to the named circuit.",
        _obj({"circuit": {"type": "string", "minLength": 1}}, ["circuit"]),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_rename_circuit",
        "Rename a circuit cleanly across project references.",
        _obj(
            {
                "circuit": {"type": "string", "minLength": 1},
                "new_name": {"type": "string", "minLength": 1},
            },
            ["circuit", "new_name"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_delete_circuit",
        "Delete a circuit from the project (fails if it is the last circuit).",
        _obj({"circuit": {"type": "string", "minLength": 1}}, ["circuit"]),
        _DESTR,
    ),
    _tool(
        "logisim_set_main_circuit",
        "Set the project's top-level main simulation circuit.",
        _obj({"circuit": {"type": "string", "minLength": 1}}, ["circuit"]),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_tick",
        "Advance simulation clocks cleanly ('cycles' = full clock cycles; 'ticks' = half-cycles) and wait for propagation.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "cycles": {"type": "integer", "minimum": 1, "maximum": 10000},
                "ticks": {"type": "integer", "minimum": 1, "maximum": 20000},
            }
        ),
        _MUT,
    ),
    _tool(
        "logisim_reset_simulation",
        "Reset all simulation states, registers, flip-flops, and volatile state.",
        _obj({}),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_set_simulation",
        "Configure continuous simulation running state, auto-ticking, and clock frequency (Hz).",
        _obj(
            {
                "running": {"type": "boolean"},
                "ticking": {"type": "boolean"},
                "frequency": {"type": "number", "minimum": 0.25, "maximum": 4096},
            }
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_read_values",
        "Read live Pin and Probe values (hex, binary, unsigned/signed decimal, width, error/unknown flags).",
        _obj({"circuit": _CIRCUIT_OPT}),
        _RO,
    ),
    _tool(
        "logisim_set_inputs",
        "Drive input Pin values (accepts integers, '0x...' hex, '0b...' binary, 'X'/'U') and wait for propagation.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "values": {"type": "object", "additionalProperties": True},
            },
            ["values"],
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_inspect_state",
        "Recursively inspect live sequential elements (Registers, Counters, Flip-Flops), Pins, Probes, and RAM/ROM memory slices across subcircuit hierarchy.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "recursive": {"type": "boolean"},
                "max_depth": {"type": "integer", "minimum": 0, "maximum": 16},
                "include_memory": {"type": "boolean"},
                "memory_start": {"type": "integer", "minimum": 0, "maximum": 65535},
                "memory_count": {"type": "integer", "minimum": 1, "maximum": 256},
                "path": {"type": "string", "description": "Optional subcircuit path prefix filter."},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_test_bench",
        "Execute multi-cycle verification vectors with input stimulus, RAM/ROM setup, clock ticking, hierarchical state/memory assertions ('0b1x0' wildcards supported), and waveform trace.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "reset_first": {"type": "boolean"},
                "half_ticks_per_cycle": {"type": "integer", "minimum": 1, "maximum": 16},
                "watch": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "vectors": {
                    "type": "array",
                    "minItems": 1,
                    "items": _obj(
                        {
                            "name": {"type": "string"},
                            "set": {"type": "object", "additionalProperties": True},
                            "set_mem": {"type": "array", "items": _MEM_ASSERT_SCHEMA},
                            "tick": {"type": "boolean"},
                            "half_ticks": {"type": "integer", "minimum": 0, "maximum": 200},
                            "expect": {"type": "object", "additionalProperties": True},
                            "expect_mem": {"type": "array", "items": _MEM_ASSERT_SCHEMA},
                        }
                    ),
                },
            },
            ["vectors"],
        ),
        _MUT,
    ),
    _tool(
        "logisim_truth_table",
        "Compute a paginated combinational truth table (requires <= 12 total input bits).",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "offset": {"type": "integer", "minimum": 0},
                "limit": {"type": "integer", "minimum": 1, "maximum": 256},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_analyze_circuit",
        "Extract minimized Boolean expressions for combinational circuits (or cleanly report sequential elements present).",
        _obj({"circuit": _CIRCUIT_OPT}),
        _RO,
    ),
    _tool(
        "logisim_synthesize_circuit",
        "Synthesize an optimized gate-level combinational circuit directly from Boolean expressions OR a truth table (supporting 'x' don't-cares).",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "inputs": {"type": "array", "items": {"type": "string", "minLength": 1}},
                "expressions": {"type": "object", "additionalProperties": {"type": "string"}},
                "truth_table": _obj(
                    {
                        "inputs": {"type": "array", "items": {"type": "string", "minLength": 1}},
                        "outputs": {"type": "object", "additionalProperties": {"type": "array", "items": _VAL_ATOM}},
                    },
                    ["outputs"],
                ),
                "two_inputs": {"type": "boolean", "description": "Restrict synthesized gates to 2-input gates."},
                "use_nands": {"type": "boolean", "description": "Synthesize using NAND-only logic."},
                "auto_tidy": {"type": "boolean", "description": "Spread tightly packed synthesized gates with comfortable ~40x30px spacing (default true)."},
            }
        ),
        _DESTR,
    ),
    _tool(
        "logisim_audit_circuit",
        "Run comprehensive Design Rule Checks (DRC) + live simulation error detection across a circuit or recursively across the entire project, with severity levels and fix suggestions.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "recursive": {"type": "boolean"},
                "include_info": {"type": "boolean"},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_check_circuit",
        "Backward-compatible quick circuit check powered by the v2 DRC engine.",
        _obj({"circuit": _CIRCUIT_OPT}),
        _RO,
    ),
    _tool(
        "logisim_get_memory",
        "Read a slice of RAM or ROM words starting at 'start' (up to 512 words per call).",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "component": {"type": "string", "minLength": 1},
                "id": {"type": "string", "minLength": 1},
                "start": {"type": "integer", "minimum": 0},
                "count": {"type": "integer", "minimum": 1, "maximum": 512},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_set_memory",
        "Program ROM or RAM words via contiguous 'values' starting at 'start' OR sparse address->value 'entries' map.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "component": {"type": "string", "minLength": 1},
                "id": {"type": "string", "minLength": 1},
                "start": {"type": "integer", "minimum": 0},
                "values": {"type": "array", "items": _VAL_ATOM},
                "entries": {"type": "object", "additionalProperties": _VAL_ATOM},
            }
        ),
        _MUT_IDEM,
    ),
    _tool(
        "logisim_screenshot",
        "Render a crisp live simulation screenshot (wire colors, Register/Pin values visible) with optional DRC error markers and port overlays.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "scale": {"type": "number", "minimum": 0.25, "maximum": 4.0},
                "zoom": {"type": "number", "minimum": 0.25, "maximum": 4.0},
                "annotate": {"type": "boolean", "description": "Overlay live DRC error callouts onto the image."},
                "show_ports": {"type": "boolean", "description": "Overlay port coordinates and labels."},
                "show_errors": {"type": "boolean"},
                "save_path": {"type": "string", "minLength": 1},
            }
        ),
        _RO,
    ),
    _tool(
        "logisim_batch",
        "Execute a multi-step build/wiring/config sequence inside a single undoable transaction with 'ref' aliases, spaced clearance routing, optional tidy pass, DRC audit, and annotated screenshot.",
        _obj(
            {
                "circuit": _CIRCUIT_OPT,
                "stop_on_error": {"type": "boolean"},
                "tidy_after": {"type": "boolean", "description": "Automatically run `arrange(mode='tidy')` after ops succeed to guarantee readable spacing."},
                "audit_after": {"type": "boolean"},
                "screenshot_after": {"type": "boolean"},
                "operations": {
                    "type": "array",
                    "minItems": 1,
                    "items": {
                        "type": "object",
                        "required": ["op"],
                        "additionalProperties": True,
                    },
                },
            },
            ["operations"],
        ),
        _MUT,
    ),
]

TOOLS: List[Dict[str, Any]] = [
    {
        "name": t["name"],
        "description": t["description"],
        "inputSchema": t["inputSchema"],
        "annotations": t["annotations"],
    }
    for t in _TOOL_DEFS
]

TOOL_SCHEMAS: Dict[str, Dict[str, Any]] = {t["name"]: t["inputSchema"] for t in _TOOL_DEFS}
TOOL_TO_CMD: Dict[str, str] = {t["name"]: t["_cmd"] for t in _TOOL_DEFS}

_NON_BATCHABLE = {
    "logisim_guide",
    "logisim_launch",
    "logisim_attach",
    "logisim_status",
    "logisim_batch",
}

BATCH_OP_SCHEMAS: Dict[str, Dict[str, Any]] = {
    t["_cmd"]: copy.deepcopy(t["inputSchema"])
    for t in _TOOL_DEFS
    if t["name"] not in _NON_BATCHABLE
}
