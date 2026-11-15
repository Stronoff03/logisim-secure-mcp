"""Unit tests for wire protocol, mutual HMAC auth, schemas, handbook, and MCP JSON-RPC server."""

from __future__ import annotations

import json
import os
import socket
import struct
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from server import __version__ as SERVER_VERSION
from server.guide import TOPICS, get_guide
from server.logisim_mcp_server import LogisimMcpServer, _read_message, _write_message
from server.prompts import get_prompt, list_prompts
from server.schema_check import SchemaValidationError, validate_tool_call
from server.tool_schemas import BATCH_OP_SCHEMAS, TOOL_SCHEMAS, TOOL_TO_CMD, TOOLS
from server.wire import (
    MAX_FRAME_BYTES,
    PROTOCOL_VERSION,
    AuthError,
    WireError,
    hmac_hex,
    load_or_create_key,
    perform_client_handshake,
    read_discovery_info,
    recv_frame,
    recv_json_frame,
    send_frame,
    send_json_frame,
)


class WireProtocolTests(unittest.TestCase):
    def test_frame_roundtrip_and_limits(self) -> None:
        a, b = socket.socketpair() if hasattr(socket, "socketpair") else self._tcp_pair()
        try:
            send_json_frame(a, {"hello": "world", "n": 42})
            self.assertEqual(recv_json_frame(b), {"hello": "world", "n": 42})

            # Oversized outgoing frame rejected
            with self.assertRaises(WireError):
                send_frame(a, b"x" * (MAX_FRAME_BYTES + 1))

            # Zero-length header rejected cleanly
            a.sendall(struct.pack(">I", 0))
            with self.assertRaises(WireError):
                recv_frame(b)

            # Truncated header after closing mid-frame
            a.sendall(struct.pack(">I", 10) + b"abc")
            a.close()
            with self.assertRaises(WireError):
                recv_frame(b)
        finally:
            try:
                a.close()
            except OSError:
                pass
            try:
                b.close()
            except OSError:
                pass

    @staticmethod
    def _tcp_pair() -> tuple[socket.socket, socket.socket]:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        cli = socket.create_connection(("127.0.0.1", srv.getsockname()[1]), timeout=2.0)
        acc, _ = srv.accept()
        srv.close()
        cli.settimeout(2.0)
        acc.settimeout(2.0)
        return cli, acc

    def test_mutual_hmac_handshake_and_rogue_rejection(self) -> None:
        key = b"\xab" * 32
        nonce_s = "0123456789abcdef" * 4

        # Valid mutual handshake
        cli, srv = self._tcp_pair()
        try:
            def run_valid_srv() -> None:
                send_json_frame(srv, {"op": "challenge", "protocol": PROTOCOL_VERSION, "nonce_s": nonce_s})
                auth = recv_json_frame(srv)
                nonce_c = auth["nonce_c"]
                expected_c = hmac_hex(key, f"logisim-mcp:c2s:{nonce_s}:{nonce_c}")
                self.assertEqual(auth["proof_c"], expected_c)
                proof_s = hmac_hex(key, f"logisim-mcp:s2c:{nonce_c}:{nonce_s}")
                send_json_frame(srv, {"op": "auth_ok", "protocol": PROTOCOL_VERSION, "proof_s": proof_s})

            t = threading.Thread(target=run_valid_srv, daemon=True)
            t.start()
            res = perform_client_handshake(cli, key)
            self.assertEqual(res["op"], "auth_ok")
            t.join(timeout=2.0)
        finally:
            cli.close()
            srv.close()

        # Rogue server returning wrong proof_s must fail with AuthError
        cli, srv = self._tcp_pair()
        try:
            def run_rogue_srv() -> None:
                send_json_frame(srv, {"op": "challenge", "protocol": PROTOCOL_VERSION, "nonce_s": nonce_s})
                _ = recv_json_frame(srv)
                send_json_frame(srv, {"op": "auth_ok", "protocol": PROTOCOL_VERSION, "proof_s": "0" * 64})

            t = threading.Thread(target=run_rogue_srv, daemon=True)
            t.start()
            with self.assertRaises(AuthError):
                perform_client_handshake(cli, key)
            t.join(timeout=2.0)
        finally:
            cli.close()
            srv.close()

    def test_discovery_security_guards(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            old_env = os.environ.get("LOGISIM_MCP_DIR")
            os.environ["LOGISIM_MCP_DIR"] = tmp
            try:
                key1 = load_or_create_key()
                self.assertEqual(len(key1), 32)
                self.assertEqual(load_or_create_key(), key1)

                disc = Path(tmp) / "bridge.json"
                # Reject non-loopback host
                disc.write_text(
                    json.dumps({"host": "0.0.0.0", "port": 12345, "protocol": PROTOCOL_VERSION}),
                    encoding="utf-8",
                )
                with self.assertRaises(WireError):
                    read_discovery_info()

                # Reject v1 protocol version
                disc.write_text(
                    json.dumps({"host": "127.0.0.1", "port": 12345, "protocol": 1}),
                    encoding="utf-8",
                )
                with self.assertRaises(WireError):
                    read_discovery_info()

                # Accept valid loopback v2
                disc.write_text(
                    json.dumps({"host": "127.0.0.1", "port": 12345, "protocol": PROTOCOL_VERSION}),
                    encoding="utf-8",
                )
                info = read_discovery_info()
                self.assertEqual(info["port"], 12345)
            finally:
                if old_env is None:
                    os.environ.pop("LOGISIM_MCP_DIR", None)
                else:
                    os.environ["LOGISIM_MCP_DIR"] = old_env


class SchemaAndToolTests(unittest.TestCase):
    def test_all_tools_have_complete_metadata(self) -> None:
        names = [t["name"] for t in TOOLS]
        self.assertEqual(len(names), len(set(names)))
        self.assertGreaterEqual(len(names), 40)
        for tool in TOOLS:
            self.assertIn(tool["name"], TOOL_TO_CMD)
            self.assertIn("annotations", tool)
            for key in ("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"):
                self.assertIn(key, tool["annotations"])

    def test_validation_guards_and_batch_ops(self) -> None:
        # Boolean disguised as integer rejected
        with self.assertRaises(SchemaValidationError):
            validate_tool_call(
                "logisim_add_component",
                {"type": "Pin", "x": True, "y": 100},
                TOOL_SCHEMAS,
                BATCH_OP_SCHEMAS,
            )

        # Unknown property rejected
        with self.assertRaises(SchemaValidationError):
            validate_tool_call(
                "logisim_connect",
                {"from": "a", "to": "b", "unknown_flag": 1},
                TOOL_SCHEMAS,
                BATCH_OP_SCHEMAS,
            )

        # Valid complex batch accepted
        validate_tool_call(
            "logisim_batch",
            {
                "circuit": "ALU_26",
                "audit_after": True,
                "operations": [
                    {"op": "add_component", "type": "Adder", "x": 200, "y": 100, "ref": "add1", "attrs": {"width": 26}},
                    {"op": "connect", "from": "a", "to": "add1", "to_port": "A", "route": "auto"},
                    {"op": "fanout_tunnels", "from": "instr", "slices": [{"name": "op", "bits": "20-25"}]},
                ],
            },
            TOOL_SCHEMAS,
            BATCH_OP_SCHEMAS,
        )

        # Duplicate ref inside batch rejected
        with self.assertRaises(SchemaValidationError):
            validate_tool_call(
                "logisim_batch",
                {
                    "operations": [
                        {"op": "add_component", "type": "Pin", "x": 100, "y": 100, "ref": "dup"},
                        {"op": "add_component", "type": "Pin", "x": 100, "y": 140, "ref": "dup"},
                    ]
                },
                TOOL_SCHEMAS,
                BATCH_OP_SCHEMAS,
            )

        # Unknown op inside batch rejected
        with self.assertRaises(SchemaValidationError):
            validate_tool_call(
                "logisim_batch",
                {"operations": [{"op": "nonexistent_op"}]},
                TOOL_SCHEMAS,
                BATCH_OP_SCHEMAS,
            )


class McpServerTests(unittest.TestCase):
    def test_guide_and_prompts(self) -> None:
        for topic in list(TOPICS.keys()) + ["all"]:
            g = get_guide(topic)
            self.assertNotIn("error", g)

        prompts = list_prompts()
        self.assertGreaterEqual(len(prompts), 4)
        for p in prompts:
            res = get_prompt(p["name"], {"module_name": "ALU", "specification": "Add/Sub", "architecture": "26-bit MIPS"})
            self.assertIn("messages", res)
            self.assertGreaterEqual(len(res["messages"]), 1)

    def test_mcp_jsonrpc_lifecycle_and_image_extraction(self) -> None:
        mock_client = MagicMock()
        mock_client.call.return_value = {
            "circuit": "main",
            "width": 320,
            "height": 240,
            "mime_type": "image/png",
            "image_base64": "iVBORw0KGgoFakePngData==",
        }
        server = LogisimMcpServer(client=mock_client)

        init_resp = server.handle_request(
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}}
        )
        assert init_resp is not None
        self.assertEqual(init_resp["result"]["protocolVersion"], "2025-03-26")
        self.assertEqual(init_resp["result"]["serverInfo"]["version"], SERVER_VERSION)

        list_resp = server.handle_request({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        assert list_resp is not None
        self.assertEqual(len(list_resp["result"]["tools"]), len(TOOLS))

        guide_resp = server.handle_request(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": "logisim_guide", "arguments": {"topic": "wiring"}},
            }
        )
        assert guide_resp is not None
        self.assertFalse(guide_resp["result"]["isError"])
        self.assertIn("structuredContent", guide_resp["result"])

        shot_resp = server.handle_request(
            {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "tools/call",
                "params": {"name": "logisim_screenshot", "arguments": {"annotate": True}},
            }
        )
        assert shot_resp is not None
        content = shot_resp["result"]["content"]
        self.assertEqual(len(content), 2)
        self.assertEqual(content[0]["type"], "text")
        self.assertNotIn("image_base64", content[0]["text"])
        self.assertEqual(content[1]["type"], "image")
        self.assertEqual(content[1]["mimeType"], "image/png")

        # Cancelled request notification suppresses execution
        server.handle_request(
            {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 99}}
        )
        cancel_resp = server.handle_request(
            {"jsonrpc": "2.0", "id": 99, "method": "tools/call", "params": {"name": "logisim_guide", "arguments": {}}}
        )
        assert cancel_resp is not None
        self.assertTrue(cancel_resp["result"]["isError"])

    def test_stdio_framing_both_modes(self) -> None:
        import io

        buf = io.BytesIO()
        msg = {"jsonrpc": "2.0", "id": 7, "method": "ping"}
        _write_message(buf, msg, "jsonl")
        buf.seek(0)
        parsed, framing = _read_message(buf)
        self.assertEqual(framing, "jsonl")
        self.assertEqual(parsed, msg)

        buf2 = io.BytesIO()
        _write_message(buf2, msg, "lsp")
        buf2.seek(0)
        parsed2, framing2 = _read_message(buf2)
        self.assertEqual(framing2, "lsp")
        self.assertEqual(parsed2, msg)


if __name__ == "__main__":
    unittest.main()
