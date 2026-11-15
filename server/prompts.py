"""Reusable MCP prompt templates for digital logic and CPU design in Logisim."""

from __future__ import annotations

from typing import Any, Dict, List


PROMPT_SPECS: List[Dict[str, Any]] = [
    {
        "name": "design_subcircuit",
        "description": "Design, package as a clean IC symbol, DRC-audit, and verify a reusable subcircuit module.",
        "arguments": [
            {
                "name": "module_name",
                "description": "Name of the subcircuit to create (e.g., ALU_26, RegFile_16x26)",
                "required": True,
            },
            {
                "name": "specification",
                "description": "Inputs, outputs, bit widths, and functional behavior",
                "required": True,
            },
        ],
    },
    {
        "name": "build_cpu_or_datapath",
        "description": "Architect and verify a complete multi-module CPU or datapath with clean tunnels, custom IC packaging, program ROM, and cycle-accurate testbench.",
        "arguments": [
            {
                "name": "architecture",
                "description": "Bit width, ISA, registers, control signals, and target test program",
                "required": True,
            },
        ],
    },
    {
        "name": "verify_digital_system",
        "description": "Run comprehensive static DRC, truth-table/expression analysis, multi-cycle testbenches, deep state inspection, and annotated screenshots.",
        "arguments": [
            {
                "name": "circuit",
                "description": "Target circuit name (defaults to active circuit if omitted)",
                "required": False,
            },
        ],
    },
    {
        "name": "audit_and_fix_circuit",
        "description": "Diagnose wiring, floating-input, clock, tunnel, and signal-error issues across a circuit and repair them cleanly.",
        "arguments": [
            {
                "name": "circuit",
                "description": "Circuit name to audit (optional)",
                "required": False,
            },
        ],
    },
]


def list_prompts() -> List[Dict[str, Any]]:
    return PROMPT_SPECS


def get_prompt(name: str, arguments: Dict[str, Any] | None = None) -> Dict[str, Any]:
    args = arguments or {}
    if name == "design_subcircuit":
        mod = str(args.get("module_name") or "MyModule")
        spec = str(args.get("specification") or "Define clear inputs, outputs, and widths.")
        text = (
            f"Design the Logisim subcircuit `{mod}` according to:\n{spec}\n\n"
            "Execution checklist:\n"
            "1. Call `logisim_guide(topic='workflow')` if you need a refresher.\n"
            "2. Create/switch to `{mod}` using `logisim_create_circuit` and inspect non-trivial components via `logisim_describe_component`.\n"
            "3. Label every input Pin (`output: false`) and output Pin (`output: true`) uniquely and clearly.\n"
            "4. Build components and connections in `logisim_batch` using comfortable spacing (~40px X, ~30px Y gaps—never edge-to-edge), `ref` aliases, `from_port`/`to_port`, `route='auto'`, or `mode='tunnel'`. Run `logisim_arrange(mode='tidy')` if layout warnings appear.\n"
            "5. Package `{mod}` with `logisim_configure_appearance` (explicit west/east/north/south pin ordering and readable width/height).\n"
            "6. Run `logisim_audit_circuit` (must report 0 errors) and verify all edge cases with `logisim_test_bench` (or `logisim_truth_table` for pure combinational logic).\n"
            "7. Finish with `logisim_screenshot(annotate=true)` to inspect visual clarity."
        )
        return {
            "description": f"Guided subcircuit design for {mod}",
            "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
        }

    if name == "build_cpu_or_datapath":
        arch = str(args.get("architecture") or "Describe the ISA, register count, word width, and memory map.")
        text = (
            f"Build and verify a complete datapath / CPU in Logisim:\n{arch}\n\n"
            "Engineering standards:\n"
            "1. Bottom-up hierarchy: build and testbench each datapath block (ALU, Control ROM/Logic, Register File, PC/NextPC) in its own subcircuit first, packaging each with `logisim_configure_appearance`.\n"
            "2. Clean visual layout: keep ~30-40px breathing room between components and use `logisim_fanout_tunnels` (30px tunnel pitch) on Splitters so every instruction slice (`opcode`, `rs`, `rt`, `rd`, `imm`, etc.) becomes a spaced, legible tunnel. Run `logisim_arrange(mode='tidy')` if any area looks crowded.\n"
            "3. Memory hygiene: configure RAM with `bus='separate'` unless a tristate bus is strictly required; load the test program cleanly via `logisim_set_memory`.\n"
            "4. Run `logisim_audit_circuit(recursive=true)` until there are ZERO errors (no unclocked FFs/Regs, no orphan tunnels, no width mismatches, no wire-over-port shorts).\n"
            "5. Run a multi-cycle `logisim_test_bench` with `reset_first=true`, `watch` trace, hierarchical `RegFile.*` / `PC` assertions, and `expect_mem` RAM verification.\n"
            "6. Inspect `logisim_inspect_state(recursive=true)` and `logisim_screenshot(annotate=true)` for main + key subcircuits."
        )
        return {
            "description": "Guided CPU/datapath architecture and verification",
            "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
        }

    if name == "verify_digital_system":
        circ = str(args.get("circuit") or "")
        scope = f"circuit `{circ}`" if circ else "the active circuit and all subcircuits"
        text = (
            f"Verify {scope} thoroughly:\n"
            "1. Run `logisim_audit_circuit(recursive=true, include_info=true)` to detect static and live DRC defects.\n"
            "2. Run `logisim_analyze_circuit` (and `logisim_truth_table` for small combinational blocks).\n"
            "3. Construct a deterministic `logisim_test_bench` exercising normal paths, edge cases (overflow/zero/carry/branch), and memory writes/reads.\n"
            "4. Call `logisim_inspect_state(recursive=true)` and `logisim_screenshot(annotate=true)` and report exact observed values."
        )
        return {
            "description": "Full digital system verification workflow",
            "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
        }

    if name == "audit_and_fix_circuit":
        circ = str(args.get("circuit") or "")
        circ_arg = f", circuit='{circ}'" if circ else ""
        text = (
            f"Audit and repair issues in {'`' + circ + '`' if circ else 'the active project'}:\n"
            f"1. Call `logisim_audit_circuit(recursive=true, include_info=true{circ_arg})` and `logisim_screenshot(annotate=true{circ_arg})`.\n"
            "2. Prioritize fixing `error` rules first (`signal_error`, `oscillation_detected`, `unclocked_sequential`, `tunnel_width_mismatch`, `wire_passes_through_port`, `floating_required_input`), then resolve `tunnel_orphan` and `component_overlap` warnings.\n"
            "3. Re-run `logisim_audit_circuit` and a sanity `logisim_test_bench` or `logisim_read_values` after fixes."
        )
        return {
            "description": "Guided DRC audit and circuit repair",
            "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
        }

    raise KeyError(f"Unknown prompt: {name}")
