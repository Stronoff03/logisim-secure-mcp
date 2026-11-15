"""Logisim Secure MCP v2.0.0 stdio server (pure Python 3.12 stdlib)."""

from __future__ import annotations

import json
import sys
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from server import __version__ as SERVER_VERSION
from server.bridge_client import BridgeUnavailableError, LogisimBridgeClient
from server.guide import get_guide
from server.layout_helper import (
    add_component_spaced,
    add_components_spaced,
    configure_appearance_spaced,
    connect_tunnel_spaced,
    execute_batch_spaced,
    fanout_tunnels_spaced,
    route_wire_spaced,
    synthesize_circuit_spaced,
    tidy_circuit,
)
from server.prompts import get_prompt, list_prompts
from server.schema_check import SchemaValidationError, validate_tool_call
from server.tool_schemas import BATCH_OP_SCHEMAS, TOOL_SCHEMAS, TOOL_TO_CMD, TOOLS
from server.wire import AuthError, WireError

SUPPORTED_PROTOCOL_VERSIONS = ("2025-03-26", "2024-11-05", "2024-10-07")

SERVER_INSTRUCTIONS = (
    "Logisim Secure MCP v2.0.0 builds, audits, testbenches, and visualizes digital circuits "
    "in a live Logisim 2.7.1 JVM over a mutually authenticated loopback bridge. "
    "For higher circuit quality and clean readable schematics: call `logisim_guide` for best practices, "
    "inspect unfamiliar parts via `logisim_describe_component`, leave comfortable spacing (~30-40px between "
    "components, ~30px vertical tunnel pitch) instead of cramming parts edge-to-edge, build modules atomically "
    "with `logisim_batch` using `ref` aliases and `from_port`/`to_port` (`route='auto'` clearance routing or "
    "`mode='tunnel'`), slice wide buses with `logisim_fanout_tunnels`, tidy crowded layouts with "
    "`logisim_arrange(mode='tidy')`, package subcircuits with `logisim_configure_appearance`, run "
    "`logisim_audit_circuit(recursive=true)` before simulating, and verify behavior with multi-cycle "
    "`logisim_test_bench` + `logisim_screenshot(annotate=true)`."
)


def _pop_b64_image(obj: Dict[str, Any]) -> Tuple[Dict[str, Any], Optional[Dict[str, Any]]]:
    for key in ("image_base64", "base64"):
        if isinstance(obj.get(key), str):
            copy_obj = dict(obj)
            b64 = copy_obj.pop(key)
            mime = str(copy_obj.get("mime_type") or (f"image/{copy_obj['format']}" if copy_obj.get("format") else "image/png"))
            copy_obj["image_attached"] = True
            return copy_obj, {"type": "image", "data": b64, "mimeType": mime}
    return obj, None


def _extract_images(payload: Any) -> Tuple[Any, List[Dict[str, Any]]]:
    """Strip inline base64 PNG payloads from JSON text and lift them to ImageContent."""
    images: List[Dict[str, Any]] = []
    if not isinstance(payload, dict):
        return payload, images

    cleaned, img = _pop_b64_image(dict(payload))
    if img is not None:
        images.append(img)

    shot = cleaned.get("screenshot")
    if isinstance(shot, dict):
        shot_clean, shot_img = _pop_b64_image(shot)
        if shot_img is not None:
            images.append(shot_img)
            cleaned["screenshot"] = shot_clean

    return cleaned, images


def _format_tool_success(result: Any) -> Dict[str, Any]:
    cleaned, images = _extract_images(result)
    text = json.dumps(cleaned, ensure_ascii=False)
    content: List[Dict[str, Any]] = [{"type": "text", "text": text}]
    content.extend(images)
    out: Dict[str, Any] = {"content": content, "isError": False}
    if isinstance(cleaned, dict):
        out["structuredContent"] = cleaned
    return out


def _format_tool_error(message: str, hint: str | None = None) -> Dict[str, Any]:
    payload: Dict[str, Any] = {"error": message}
    if hint:
        payload["hint"] = hint
    return {
        "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
        "isError": True,
    }


def _read_message(stdin_buf: Any) -> Tuple[Optional[Dict[str, Any]], str]:
    """Read one JSON-RPC message (supports newline-delimited or Content-Length framing)."""
    while True:
        line = stdin_buf.readline()
        if not line:
            return None, "jsonl"
        stripped = line.strip()
        if not stripped:
            continue

        low = stripped.lower()
        if low.startswith(b"content-length:"):
            try:
                content_length = int(stripped.split(b":", 1)[1].strip())
            except ValueError as exc:
                raise ValueError(f"Invalid Content-Length header: {stripped!r}") from exc
            while True:
                hdr = stdin_buf.readline()
                if not hdr or hdr in (b"\r\n", b"\n"):
                    break
            body = stdin_buf.read(content_length)
            if len(body) < content_length:
                return None, "lsp"
            return json.loads(body.decode("utf-8")), "lsp"

        return json.loads(stripped.decode("utf-8")), "jsonl"


def _write_message(stdout_buf: Any, msg: Dict[str, Any], framing: str) -> None:
    raw = json.dumps(msg, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if framing == "lsp":
        header = f"Content-Length: {len(raw)}\r\n\r\n".encode("ascii")
        stdout_buf.write(header)
        stdout_buf.write(raw)
    else:
        stdout_buf.write(raw + b"\n")
    stdout_buf.flush()


class LogisimMcpServer:
    """Stateless JSON-RPC dispatcher backed by a persistent LogisimBridgeClient."""

    def __init__(self, client: Optional[LogisimBridgeClient] = None) -> None:
        self.client = client or LogisimBridgeClient()
        self.cancelled_ids: set[Any] = set()

    def _emit_progress(
        self,
        stdout_buf: Any,
        framing: str,
        token: Any,
        progress: int,
        total: int,
        message: str,
    ) -> None:
        if token is None:
            return
        _write_message(
            stdout_buf,
            {
                "jsonrpc": "2.0",
                "method": "notifications/progress",
                "params": {
                    "progressToken": token,
                    "progress": progress,
                    "total": total,
                    "message": message,
                },
            },
            framing,
        )

    def _dispatch_single_command(
        self,
        cmd: str,
        params: Dict[str, Any],
        batch_refs: Optional[Dict[str, str]] = None,
    ) -> Any:
        if cmd == "add_component":
            return add_component_spaced(self.client, params, batch_refs)
        if cmd == "add_components":
            return add_components_spaced(self.client, params, batch_refs)
        if cmd == "connect":
            mode = str(params.get("mode") or "wire").strip().lower()
            if mode == "tunnel":
                res = connect_tunnel_spaced(self.client, params, batch_refs)
                if res is not None:
                    return res
            else:
                res = route_wire_spaced(self.client, params, batch_refs)
                if res is not None:
                    return res
            clean_params = {k: v for k, v in params.items() if k not in ("tight", "wire_clearance")}
            return self.client.call("connect", clean_params, timeout=60.0)
        if cmd == "fanout_tunnels":
            return fanout_tunnels_spaced(self.client, params, batch_refs)
        if cmd == "arrange":
            mode = str(params.get("mode") or "snap").strip().lower()
            if mode == "tidy":
                return tidy_circuit(self.client, params, batch_refs)
            return self.client.call("arrange", params, timeout=60.0)
        if cmd == "configure_appearance":
            return configure_appearance_spaced(self.client, params)
        if cmd == "synthesize_circuit":
            return synthesize_circuit_spaced(self.client, params)
        if cmd == "move_component":
            res = self.client.call("move_component", params, timeout=60.0)
            if batch_refs is not None and isinstance(res, dict) and res.get("old_id") and res.get("new_id"):
                old_id = str(res["old_id"])
                new_id = str(res["new_id"])
                for k, v in list(batch_refs.items()):
                    if v == old_id:
                        batch_refs[k] = new_id
            return res

        timeout = 120.0 if cmd in ("test_bench", "truth_table") else 60.0
        return self.client.call(cmd, params, timeout=timeout)

    def _dispatch_tool(
        self,
        name: str,
        arguments: Any,
        req_id: Any = None,
        progress_token: Any = None,
        stdout_buf: Any = None,
        framing: str = "jsonl",
    ) -> Dict[str, Any]:
        if req_id is not None and req_id in self.cancelled_ids:
            self.cancelled_ids.discard(req_id)
            return _format_tool_error("Request was cancelled before execution.")

        try:
            validate_tool_call(name, arguments, TOOL_SCHEMAS, BATCH_OP_SCHEMAS)
        except SchemaValidationError as exc:
            return _format_tool_error(str(exc), "Check tool parameter names and types via tools/list.")

        args: Dict[str, Any] = {} if arguments is None else dict(arguments)
        cmd = TOOL_TO_CMD[name]

        if cmd == "_local_guide":
            return _format_tool_success(get_guide(str(args.get("topic") or "overview")))

        if cmd == "status":
            return _format_tool_success(self.client.try_status())

        if stdout_buf is not None and progress_token is not None:
            self._emit_progress(stdout_buf, framing, progress_token, 0, 100, f"Executing {name}")

        try:
            if cmd == "_local_launch":
                res = self.client.launch(
                    file_path=args.get("file_path"),
                    wait_seconds=float(args.get("wait_seconds", 20.0)),
                )
            elif cmd == "_local_attach":
                res = self.client.attach(
                    pid=args.get("pid"),
                    wait_seconds=float(args.get("wait_seconds", 12.0)),
                )
            elif cmd == "batch":
                res = execute_batch_spaced(self.client, args, self._dispatch_single_command)
            else:
                res = self._dispatch_single_command(cmd, args)
        except BridgeUnavailableError as exc:
            return _format_tool_error(
                str(exc),
                "Call `logisim_launch` to start Logisim with the v2 bridge, or `logisim_attach` if Logisim is already running.",
            )
        except AuthError as exc:
            return _format_tool_error(
                str(exc),
                "Handshake secret mismatch. Close any stale Logisim JVM and run `logisim_launch` cleanly.",
            )
        except (WireError, RuntimeError, ValueError, FileNotFoundError) as exc:
            return _format_tool_error(str(exc))

        if stdout_buf is not None and progress_token is not None:
            self._emit_progress(stdout_buf, framing, progress_token, 100, 100, f"Completed {name}")

        return _format_tool_success(res)

    def handle_request(
        self,
        req: Dict[str, Any],
        stdout_buf: Any = None,
        framing: str = "jsonl",
    ) -> Optional[Dict[str, Any]]:
        method = req.get("method")
        req_id = req.get("id")
        params = req.get("params") if isinstance(req.get("params"), dict) else {}

        # Notifications have no id and expect no JSON-RPC response
        if req_id is None:
            if method == "notifications/cancelled":
                target_id = params.get("requestId")
                if target_id is not None:
                    self.cancelled_ids.add(target_id)
            return None

        if method == "initialize":
            client_proto = params.get("protocolVersion")
            chosen = (
                client_proto
                if isinstance(client_proto, str) and client_proto in SUPPORTED_PROTOCOL_VERSIONS
                else SUPPORTED_PROTOCOL_VERSIONS[0]
            )
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": chosen,
                    "capabilities": {
                        "tools": {"listChanged": False},
                        "prompts": {"listChanged": False},
                    },
                    "serverInfo": {
                        "name": "logisim-secure-mcp",
                        "version": SERVER_VERSION,
                    },
                    "instructions": SERVER_INSTRUCTIONS,
                },
            }

        if method == "ping":
            return {"jsonrpc": "2.0", "id": req_id, "result": {}}

        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}

        if method == "tools/call":
            name = params.get("name")
            if not isinstance(name, str) or not name:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32602, "message": "Missing tool 'name'"},
                }
            meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
            result = self._dispatch_tool(
                name=name,
                arguments=params.get("arguments"),
                req_id=req_id,
                progress_token=meta.get("progressToken"),
                stdout_buf=stdout_buf,
                framing=framing,
            )
            if req_id in self.cancelled_ids:
                self.cancelled_ids.discard(req_id)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32800, "message": "Request cancelled"},
                }
            return {"jsonrpc": "2.0", "id": req_id, "result": result}

        if method == "prompts/list":
            return {"jsonrpc": "2.0", "id": req_id, "result": {"prompts": list_prompts()}}

        if method == "prompts/get":
            pname = params.get("name")
            if not isinstance(pname, str):
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32602, "message": "Missing prompt 'name'"},
                }
            try:
                pargs = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
                return {"jsonrpc": "2.0", "id": req_id, "result": get_prompt(pname, pargs)}
            except KeyError as exc:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32602, "message": str(exc)},
                }

        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "error": {"code": -32601, "message": f"Method not found: {method}"},
        }


def serve_stdio() -> int:
    stdin_buf = sys.stdin.buffer
    stdout_buf = sys.stdout.buffer
    server = LogisimMcpServer()
    try:
        while True:
            try:
                msg, framing = _read_message(stdin_buf)
            except Exception as exc:
                err_resp = {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32700, "message": f"Parse error: {exc}"},
                }
                _write_message(stdout_buf, err_resp, "jsonl")
                continue

            if msg is None:
                break

            if isinstance(msg, list):
                replies = []
                for item in msg:
                    if isinstance(item, dict):
                        rep = server.handle_request(item, stdout_buf=stdout_buf, framing=framing)
                        if rep is not None:
                            replies.append(rep)
                if replies:
                    raw = json.dumps(replies, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                    if framing == "lsp":
                        stdout_buf.write(f"Content-Length: {len(raw)}\r\n\r\n".encode("ascii") + raw)
                    else:
                        stdout_buf.write(raw + b"\n")
                    stdout_buf.flush()
            elif isinstance(msg, dict):
                try:
                    rep = server.handle_request(msg, stdout_buf=stdout_buf, framing=framing)
                except Exception as exc:
                    traceback.print_exc(file=sys.stderr)
                    rep = (
                        {
                            "jsonrpc": "2.0",
                            "id": msg.get("id"),
                            "error": {"code": -32603, "message": f"Internal error: {exc}"},
                        }
                        if msg.get("id") is not None
                        else None
                    )
                if rep is not None:
                    _write_message(stdout_buf, rep, framing)
    finally:
        server.client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(serve_stdio())
