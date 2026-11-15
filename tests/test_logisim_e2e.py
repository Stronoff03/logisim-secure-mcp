"""Live end-to-end tests driving real Logisim 2.7.1 + v2.0.0 JVM bridge via MCP stdio."""

from __future__ import annotations

import base64
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List

ROOT = Path(__file__).resolve().parent.parent
SERVER_SCRIPT = ROOT / "server" / "logisim_mcp_server.py"


class McpStdioClient:
    """Line-delimited JSON-RPC client talking to logisim_mcp_server.py."""

    def __init__(self, mcp_dir: str) -> None:
        env = os.environ.copy()
        env["LOGISIM_MCP_DIR"] = mcp_dir
        env["PYTHONUNBUFFERED"] = "1"
        self.proc = subprocess.Popen(
            [sys.executable, str(SERVER_SCRIPT)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        self._next_id = 1

    def request(self, method: str, params: Dict[str, Any] | None = None) -> Dict[str, Any]:
        req_id = self._next_id
        self._next_id += 1
        msg: Dict[str, Any] = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        assert self.proc.stdin is not None and self.proc.stdout is not None
        line = (json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8")
        self.proc.stdin.write(line)
        self.proc.stdin.flush()

        while True:
            raw = self.proc.stdout.readline()
            if not raw:
                stderr_txt = self.proc.stderr.read().decode("utf-8", errors="replace") if self.proc.stderr else ""
                raise RuntimeError(f"MCP server terminated unexpectedly. stderr={stderr_txt}")
            resp = json.loads(raw.decode("utf-8"))
            # Ignore notifications (no id) such as progress notifications
            if "id" not in resp or resp["id"] is None:
                continue
            if resp["id"] == req_id:
                return resp

    def call_tool(self, name: str, arguments: Dict[str, Any] | None = None) -> Dict[str, Any]:
        resp = self.request("tools/call", {"name": name, "arguments": arguments or {}})
        if "error" in resp:
            raise AssertionError(f"JSON-RPC protocol error calling {name}: {resp['error']}")
        result = resp["result"]
        if result.get("isError"):
            text = result["content"][0]["text"]
            raise AssertionError(f"Tool {name} failed with error: {text}")
        return result

    def call_json(self, name: str, arguments: Dict[str, Any] | None = None) -> Dict[str, Any]:
        res = self.call_tool(name, arguments)
        if "structuredContent" in res:
            return res["structuredContent"]
        return json.loads(res["content"][0]["text"])

    def close(self) -> None:
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=5.0)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=3.0)


def _kill_pid(pid: int | None) -> None:
    if not pid:
        return
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(pid), "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            os.kill(pid, signal.SIGTERM)
    except Exception:
        pass


class LogisimE2ETests(unittest.TestCase):
    temp_dir: tempfile.TemporaryDirectory[str]
    client: McpStdioClient
    jvm_pid: int | None = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = tempfile.TemporaryDirectory(prefix="logisim_mcp_e2e_")
        cls.client = McpStdioClient(cls.temp_dir.name)
        init_res = cls.client.request(
            "initialize",
            {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "1.0"}},
        )
        assert init_res["result"]["serverInfo"]["version"] == "2.0.0"

    @classmethod
    def tearDownClass(cls) -> None:
        try:
            cls.client.close()
        finally:
            _kill_pid(cls.jvm_pid)
            cls.temp_dir.cleanup()

    def test_01_launch_status_and_new_project(self) -> None:
        st_before = self.client.call_json("logisim_status")
        self.assertFalse(st_before.get("connected"))

        launch_res = self.client.call_json("logisim_launch", {"wait_seconds": 30})
        type(self).jvm_pid = int(launch_res.get("launched_pid") or launch_res.get("pid"))
        self.assertEqual(launch_res.get("protocol"), 2)
        self.assertEqual(launch_res.get("version"), "2.0.0")

        st_after = self.client.call_json("logisim_status")
        self.assertEqual(st_after.get("protocol"), 2)
        self.assertEqual(st_after.get("version"), "2.0.0")

        proj = self.client.call_json("logisim_new_project", {"name": "e2e_v2_cpu"})
        self.assertEqual(proj.get("project_name"), "e2e_v2_cpu")
        self.assertGreater(proj.get("toolbar_items", 0), 0)

    def test_02_discovery_libraries_and_describe(self) -> None:
        libs = self.client.call_json("logisim_list_libraries", {"search": "Register"})
        found_names = [
            comp["name"]
            for lib in libs.get("libraries", [])
            for comp in lib.get("components", [])
        ]
        self.assertIn("Register", found_names)

        desc_reg = self.client.call_json(
            "logisim_describe_component",
            {"type": "Register", "attrs": {"width": 8}},
        )
        port_names = {p["name"] for p in desc_reg.get("ports", [])}
        self.assertTrue({"D", "Q", "clk", "en", "clr"}.issubset(port_names))
        trigger_attr = [a for a in desc_reg.get("attributes", []) if a["name"] == "trigger"]
        self.assertTrue(trigger_attr and trigger_attr[0].get("options"))

        desc_ram = self.client.call_json(
            "logisim_describe_component",
            {"type": "RAM", "attrs": {"addrWidth": 4, "dataWidth": 8, "bus": "separate"}},
        )
        ram_ports = {p["name"] for p in desc_ram.get("ports", [])}
        self.assertTrue({"A", "D", "din", "we", "oe", "clk"}.issubset(ram_ports), f"RAM ports were: {ram_ports}")

    def test_03_subcircuit_batch_astar_tunnel_and_ic_appearance(self) -> None:
        self.client.call_json("logisim_create_circuit", {"name": "AddSub8", "switch_to": True})

        batch_res = self.client.call_tool(
            "logisim_batch",
            {
                "circuit": "AddSub8",
                "stop_on_error": True,
                "audit_after": True,
                "screenshot_after": True,
                "operations": [
                    {"op": "add_component", "type": "Pin", "x": 80, "y": 100, "ref": "pin_a",
                     "attrs": {"facing": "east", "output": False, "tristate": False, "width": 8, "label": "A"}},
                    {"op": "add_component", "type": "Pin", "x": 80, "y": 180, "ref": "pin_b",
                     "attrs": {"facing": "east", "output": False, "tristate": False, "width": 8, "label": "B"}},
                    {"op": "add_component", "type": "Pin", "x": 80, "y": 260, "ref": "pin_sub",
                     "attrs": {"facing": "east", "output": False, "tristate": False, "width": 1, "label": "Sub"}},

                    {"op": "add_component", "type": "Adder", "x": 220, "y": 120, "ref": "adder",
                     "attrs": {"width": 8}},
                    {"op": "add_component", "type": "Subtractor", "x": 220, "y": 220, "ref": "subber",
                     "attrs": {"width": 8}},
                    {"op": "add_component", "type": "Multiplexer", "x": 360, "y": 170, "ref": "mux_y",
                     "attrs": {"width": 8, "select": 1, "enable": False}},
                    {"op": "add_component", "type": "Multiplexer", "x": 360, "y": 270, "ref": "mux_c",
                     "attrs": {"width": 1, "select": 1, "enable": False}},

                    {"op": "add_component", "type": "Comparator", "x": 490, "y": 250, "ref": "cmp_z",
                     "attrs": {"width": 8, "mode": "unsigned"}},
                    {"op": "add_component", "type": "Constant", "x": 420, "y": 280, "ref": "zero_k",
                     "attrs": {"facing": "east", "width": 8, "value": "0x0"}},

                    {"op": "add_component", "type": "Pin", "x": 600, "y": 170, "ref": "pin_y",
                     "attrs": {"facing": "west", "output": True, "width": 8, "label": "Y"}},
                    {"op": "add_component", "type": "Pin", "x": 600, "y": 270, "ref": "pin_carry",
                     "attrs": {"facing": "west", "output": True, "width": 1, "label": "Carry"}},
                    {"op": "add_component", "type": "Pin", "x": 600, "y": 250, "ref": "pin_zero",
                     "attrs": {"facing": "west", "output": True, "width": 1, "label": "Zero"}},

                    # Connect inputs via clean tunnels so buses never tangle
                    {"op": "connect", "from": "pin_a", "to": "adder", "to_port": "A", "mode": "tunnel", "tunnel_name": "inA"},
                    {"op": "connect", "from": "pin_b", "to": "adder", "to_port": "B", "mode": "tunnel", "tunnel_name": "inB"},
                    {"op": "connect", "from": "pin_a", "to": "subber", "to_port": "A", "mode": "tunnel", "tunnel_name": "inA"},
                    {"op": "connect", "from": "pin_b", "to": "subber", "to_port": "B", "mode": "tunnel", "tunnel_name": "inB"},

                    # A* routed wires from adder/subber to muxes and outputs
                    {"op": "connect", "from": "adder", "from_port": "sum", "to": "mux_y", "to_port": "in0", "route": "auto"},
                    {"op": "connect", "from": "subber", "from_port": "diff", "to": "mux_y", "to_port": "in1", "route": "auto"},
                    {"op": "connect", "from": "adder", "from_port": "cout", "to": "mux_c", "to_port": "in0", "mode": "tunnel", "tunnel_name": "add_c"},
                    {"op": "connect", "from": "subber", "from_port": "bout", "to": "mux_c", "to_port": "in1", "mode": "tunnel", "tunnel_name": "sub_b"},

                    {"op": "connect", "from": "pin_sub", "to": "mux_y", "to_port": "sel", "mode": "tunnel", "tunnel_name": "sel_sub"},
                    {"op": "connect", "from": "pin_sub", "to": "mux_c", "to_port": "sel", "mode": "tunnel", "tunnel_name": "sel_sub"},

                    {"op": "connect", "from": "mux_y", "from_port": "out", "to": "pin_y", "route": "auto"},
                    {"op": "connect", "from": "mux_c", "from_port": "out", "to": "pin_carry", "route": "auto"},

                    {"op": "connect", "from": "mux_y", "from_port": "out", "to": "cmp_z", "to_port": "A", "mode": "tunnel", "tunnel_name": "y_bus"},
                    {"op": "connect", "from": "zero_k", "to": "cmp_z", "to_port": "B", "route": "auto"},
                    {"op": "connect", "from": "cmp_z", "from_port": "eq", "to": "pin_zero", "route": "auto"},

                    # Package clean custom IC chip symbol
                    {
                        "op": "configure_appearance",
                        "title": "ALU8",
                        "width": 90,
                        "height": 80,
                        "shape": "roundrect",
                        "west": ["A", "B"],
                        "south": ["Sub"],
                        "east": ["Y", "Carry", "Zero"],
                    },
                ],
            },
        )
        body = batch_res["structuredContent"]
        self.assertTrue(body.get("ok"), f"Batch failed: {body}")
        self.assertTrue(body["audit"]["ok"], f"Unexpected DRC errors in AddSub8: {body['audit']}")
        # Verify screenshot_after attached genuine ImageContent
        self.assertEqual(len(batch_res["content"]), 2)
        self.assertEqual(batch_res["content"][1]["type"], "image")

        # Verify AddSub8 functionally via test_bench
        tb = self.client.call_json(
            "logisim_test_bench",
            {
                "circuit": "AddSub8",
                "vectors": [
                    {"name": "10+5", "set": {"A": 10, "B": 5, "Sub": 0}, "tick": False, "expect": {"Y": 15, "Zero": 0, "Carry": 0}},
                    {"name": "250+10_carry", "set": {"A": 250, "B": 10, "Sub": 0}, "tick": False, "expect": {"Y": 4, "Carry": 1, "Zero": 0}},
                    {"name": "7-7_zero", "set": {"A": 7, "B": 7, "Sub": 1}, "tick": False, "expect": {"Y": 0, "Zero": 1}},
                ],
            },
        )
        self.assertTrue(tb.get("passed"), f"AddSub8 testbench failed: {tb}")

    def test_04_synthesize_truth_table_and_analyze(self) -> None:
        self.client.call_json("logisim_create_circuit", {"name": "CtrlDec", "switch_to": True})
        syn = self.client.call_json(
            "logisim_synthesize_circuit",
            {
                "circuit": "CtrlDec",
                "truth_table": {
                    "inputs": ["Op1", "Op0"],
                    "outputs": {
                        "RegWrite": ["1", "0", "1", "x"],
                        "ALUOp": ["0", "1", "x", "1"],
                    },
                },
            },
        )
        self.assertIn("RegWrite", syn.get("expressions", {}))

        tt = self.client.call_json("logisim_truth_table", {"circuit": "CtrlDec", "offset": 0, "limit": 8})
        self.assertEqual(tt.get("total_rows"), 4)
        self.assertEqual(len(tt.get("rows", [])), 4)
        # Row 0 (0,0) -> RegWrite=1, ALUOp=0
        self.assertEqual(tt["rows"][0]["outputs"]["RegWrite"], "1")
        self.assertEqual(tt["rows"][0]["outputs"]["ALUOp"], "0")
        # Row 1 (0,1) -> RegWrite=0, ALUOp=1
        self.assertEqual(tt["rows"][1]["outputs"]["RegWrite"], "0")
        self.assertEqual(tt["rows"][1]["outputs"]["ALUOp"], "1")

        ana = self.client.call_json("logisim_analyze_circuit", {"circuit": "CtrlDec"})
        self.assertIn("outputs", ana)

        drc = self.client.call_json("logisim_audit_circuit", {"circuit": "CtrlDec"})
        self.assertTrue(drc.get("ok"), f"Synthesized circuit failed DRC: {drc}")

    def test_05_drc_detects_defects(self) -> None:
        self.client.call_json("logisim_create_circuit", {"name": "BuggyTest", "switch_to": True})
        # Create unclocked Register + orphan/typo tunnel + width-mismatched tunnel + wire over foreign port
        self.client.call_json(
            "logisim_batch",
            {
                "circuit": "BuggyTest",
                "operations": [
                    {"op": "add_component", "type": "Register", "x": 140, "y": 140, "attrs": {"width": 8, "label": "BADREG"}},
                    {"op": "add_component", "type": "Pin", "x": 240, "y": 100, "attrs": {"facing": "east", "width": 8, "label": "P8"}},
                    {"op": "add_component", "type": "Tunnel", "x": 260, "y": 100, "attrs": {"facing": "west", "width": 8, "label": "alu_result"}},
                    {"op": "add_wires", "segments": [{"x1": 240, "y1": 100, "x2": 260, "y2": 100}]},
                    {"op": "add_component", "type": "Pin", "x": 240, "y": 160, "attrs": {"facing": "east", "width": 4, "label": "P4"}},
                    {"op": "add_component", "type": "Tunnel", "x": 260, "y": 160, "attrs": {"facing": "west", "width": 4, "label": "alu_result"}},
                    {"op": "add_wires", "segments": [{"x1": 240, "y1": 160, "x2": 260, "y2": 160}]},
                    {"op": "add_component", "type": "Tunnel", "x": 260, "y": 220, "attrs": {"facing": "west", "width": 8, "label": "alu_reslt"}},
                    {"op": "add_component", "type": "Pin", "x": 240, "y": 220, "attrs": {"facing": "west", "output": True, "width": 8}},
                    {"op": "add_wires", "segments": [{"x1": 240, "y1": 220, "x2": 260, "y2": 220}]},
                    # Wire laid down BEFORE foreign component placed on top triggers wire_passes_through_port
                    {"op": "add_wires", "segments": [{"x1": 320, "y1": 300, "x2": 420, "y2": 300}]},
                    {"op": "add_component", "type": "NOT Gate", "x": 380, "y": 300},
                ],
            },
        )
        audit = self.client.call_json("logisim_audit_circuit", {"circuit": "BuggyTest", "include_info": True})
        self.assertFalse(audit.get("ok"))
        rules = {iss["rule"] for iss in audit.get("issues", [])}
        self.assertIn("unclocked_sequential", rules)
        self.assertIn("tunnel_width_mismatch", rules)
        self.assertIn("tunnel_orphan", rules)
        self.assertIn("wire_passes_through_port", rules)

        orphan_texts = [
            f"{iss.get('message', '')} {iss.get('suggestion', '')}"
            for iss in audit["issues"]
            if iss["rule"] == "tunnel_orphan"
        ]
        self.assertTrue(any("alu_result" in t for t in orphan_texts), f"Missing fuzzy suggestion: {orphan_texts}")

        self.client.call_json("logisim_delete_circuit", {"circuit": "BuggyTest"})

    def test_06_cpu_style_fanout_tunnels_memory_testbench_and_screenshot(self) -> None:
        self.client.call_json("logisim_switch_circuit", {"circuit": "main"})
        self.client.call_json("logisim_clear_circuit", {"circuit": "main"})

        # Build a clean 17-bit instruction-driven datapath using AddSub8 + ROM + RAM + PC + Accumulator
        build_res = self.client.call_json(
            "logisim_batch",
            {
                "circuit": "main",
                "stop_on_error": True,
                "operations": [
                    {"op": "add_component", "type": "Clock", "x": 60, "y": 60, "ref": "clk",
                     "attrs": {"facing": "east", "label": "CLK"}},
                    {"op": "add_component", "type": "Counter", "x": 140, "y": 140, "ref": "pc",
                     "attrs": {"width": 4, "max": "0xf", "label": "PC"}},
                    {"op": "add_component", "type": "ROM", "x": 360, "y": 140, "ref": "imem",
                     "attrs": {"addr": 4, "data": 17}},

                    {"op": "connect", "from": "pc", "from_port": "Q", "to": "imem", "to_port": "A", "route": "auto"},
                    {"op": "connect", "from": "clk", "to": "pc", "to_port": "clk", "mode": "tunnel", "tunnel_name": "clk_sig"},

                    # Slice 17-bit instruction: [16]=sub, [8-15]=imm_a, [0-7]=imm_b
                    {
                        "op": "fanout_tunnels",
                        "from": "imem",
                        "from_port": "D",
                        "splitter_x": 420,
                        "splitter_y": 140,
                        "width": 17,
                        "facing": "east",
                        "ref": "dec_split",
                        "slices": [
                            {"name": "dec_sub", "bits": 16},
                            {"name": "dec_a", "bits": "8-15"},
                            {"name": "dec_b", "bits": "0-7"},
                        ],
                    },

                    # Drop custom-appearance AddSub8 instance
                    {"op": "add_component", "type": "AddSub8", "x": 620, "y": 240, "ref": "alu",
                     "attrs": {"label": "ALU_MOD"}},
                    {"op": "add_component", "type": "Tunnel", "x": 510, "y": 200, "ref": "ta",
                     "attrs": {"facing": "east", "width": 8, "label": "dec_a"}},
                    {"op": "add_component", "type": "Tunnel", "x": 510, "y": 230, "ref": "tb",
                     "attrs": {"facing": "east", "width": 8, "label": "dec_b"}},
                    {"op": "add_component", "type": "Tunnel", "x": 570, "y": 320, "ref": "tsub",
                     "attrs": {"facing": "north", "width": 1, "label": "dec_sub"}},
                    {"op": "connect", "from": "ta", "to": "alu", "to_port": "A", "route": "auto"},
                    {"op": "connect", "from": "tb", "to": "alu", "to_port": "B", "route": "auto"},
                    {"op": "connect", "from": "tsub", "to": "alu", "to_port": "Sub", "route": "auto"},

                    # Accumulator register + Data RAM (bus=separate!)
                    {"op": "add_component", "type": "Register", "x": 760, "y": 240, "ref": "acc",
                     "attrs": {"width": 8, "label": "ACC"}},
                    {"op": "connect", "from": "alu", "from_port": "Y", "to": "acc", "to_port": "D", "route": "auto"},
                    {"op": "connect", "from": "clk", "to": "acc", "to_port": "clk", "mode": "tunnel", "tunnel_name": "clk_sig"},

                    {"op": "add_component", "type": "RAM", "x": 780, "y": 420, "ref": "dmem",
                     "attrs": {"addr": 4, "data": 8, "bus": "separate"}},
                    {"op": "add_component", "type": "Constant", "x": 660, "y": 500, "ref": "we_hi",
                     "attrs": {"facing": "east", "width": 1, "value": "0x1"}},
                    {"op": "connect", "from": "pc", "from_port": "Q", "to": "dmem", "to_port": "A", "mode": "tunnel", "tunnel_name": "pc_bus"},
                    {"op": "connect", "from": "alu", "from_port": "Y", "to": "dmem", "to_port": "din", "mode": "tunnel", "tunnel_name": "alu_y"},
                    {"op": "connect", "from": "clk", "to": "dmem", "to_port": "clk", "mode": "tunnel", "tunnel_name": "clk_sig"},
                    {"op": "connect", "from": "we_hi", "to": "dmem", "to_port": "we", "mode": "tunnel", "tunnel_name": "we_one"},

                    # Observable Probes/Output Pins
                    {"op": "add_component", "type": "Pin", "x": 880, "y": 240, "ref": "out_acc",
                     "attrs": {"facing": "west", "output": True, "width": 8, "label": "OUT_ACC"}},
                    {"op": "add_component", "type": "Pin", "x": 720, "y": 140, "ref": "out_alu",
                     "attrs": {"facing": "west", "output": True, "width": 8, "label": "OUT_ALU"}},
                    {"op": "add_component", "type": "Pin", "x": 720, "y": 180, "ref": "out_zero",
                     "attrs": {"facing": "west", "output": True, "width": 1, "label": "OUT_ZERO"}},
                    {"op": "connect", "from": "acc", "from_port": "Q", "to": "out_acc", "route": "auto"},
                    {"op": "connect", "from": "alu", "from_port": "Y", "to": "out_alu", "mode": "tunnel", "tunnel_name": "alu_y"},
                    {"op": "connect", "from": "alu", "from_port": "Zero", "to": "out_zero", "route": "auto"},
                ],
            },
        )
        self.assertTrue(build_res.get("ok"), f"Main build failed: {build_res}")

        # Project-wide recursive DRC audit must have zero errors!
        audit_all = self.client.call_json("logisim_audit_circuit", {"circuit": "main", "recursive": True})
        self.assertTrue(audit_all.get("ok"), f"Recursive audit failed: {audit_all}")

        # Program IMEM via sparse entries + verify readback
        prog = {
            "0": (0 << 16) | (10 << 8) | 5,   # 10 + 5 = 15
            "1": (1 << 16) | (20 << 8) | 6,   # 20 - 6 = 14
            "2": (1 << 16) | (7 << 8) | 7,    # 7 - 7 = 0 (Zero=1)
        }
        self.client.call_json("logisim_set_memory", {"circuit": "main", "component": "ROM", "entries": prog})
        imem_read = self.client.call_json("logisim_get_memory", {"circuit": "main", "component": "ROM", "start": 0, "count": 4})
        self.assertEqual([int(v, 16) for v in imem_read["values"][:3]], [prog["0"], prog["1"], prog["2"]])

        # Multi-cycle testbench verifying PC=0 -> tick -> PC=1 -> tick -> PC=2 -> tick -> PC=3 + RAM writes
        tb = self.client.call_json(
            "logisim_test_bench",
            {
                "circuit": "main",
                "reset_first": True,
                "half_ticks_per_cycle": 2,
                "watch": ["PC", "OUT_ALU", "ACC", "OUT_ZERO"],
                "vectors": [
                    {
                        "name": "cycle0_add_comb",
                        "tick": False,
                        "expect": {"PC": 0, "OUT_ALU": 15, "OUT_ZERO": 0, "ACC": 0},
                    },
                    {
                        "name": "cycle1_latch15_store_mem0",
                        "tick": True,
                        "expect": {"PC": 1, "ACC": 15, "OUT_ACC": 15, "OUT_ALU": 14},
                        "expect_mem": [{"comp": "RAM", "addr": 0, "value": 15}],
                    },
                    {
                        "name": "cycle2_latch14_store_mem1_zero_comb",
                        "tick": True,
                        "expect": {"PC": 2, "ACC": 14, "OUT_ALU": 0, "OUT_ZERO": 1},
                        "expect_mem": [{"comp": "RAM", "addr": 1, "value": 14}],
                    },
                    {
                        "name": "cycle3_latch0_store_mem2",
                        "tick": True,
                        "expect": {"PC": 3, "ACC": 0},
                        "expect_mem": [{"comp": "RAM", "addr": 2, "value": 0}],
                    },
                ],
            },
        )
        self.assertTrue(tb.get("passed"), f"CPU testbench failed: {tb}")
        self.assertEqual(len(tb.get("trace", [])), 4)

        # Recursive state inspection
        state = self.client.call_json("logisim_inspect_state", {"circuit": "main", "recursive": True})
        self.assertTrue(state.get("storage"))
        self.assertTrue(state.get("memories"))
        self.assertTrue(state.get("subcircuits"))

        # Annotated screenshot saved to file + delivered as PNG ImageContent
        png_path = Path(self.temp_dir.name) / "main_annotated.png"
        shot_tool = self.client.call_tool(
            "logisim_screenshot",
            {"circuit": "main", "annotate": True, "show_ports": False, "save_path": str(png_path)},
        )
        self.assertEqual(shot_tool["content"][1]["type"], "image")
        png_bytes = base64.b64decode(shot_tool["content"][1]["data"])
        self.assertTrue(png_bytes.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(png_path.is_file() and png_path.stat().st_size > 1000)

        # Save + reload roundtrip
        circ_path = Path(self.temp_dir.name) / "e2e_v2_cpu.circ"
        saved = self.client.call_json("logisim_save_file", {"path": str(circ_path)})
        self.assertTrue(Path(saved["path"]).is_file())
        opened = self.client.call_json("logisim_open_file", {"path": str(circ_path)})
        self.assertIn("AddSub8", opened.get("circuits", []))


if __name__ == "__main__":
    unittest.main()
