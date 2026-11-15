"""Pure-stdlib localhost framed wire protocol + mutual HMAC-SHA256 authentication."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import struct
from pathlib import Path
from typing import Any, Dict, Tuple

PROTOCOL_VERSION = 2
SERVER_VERSION = "2.0.0"
MAX_FRAME_BYTES = 32 * 1024 * 1024
_HEX_RE = re.compile(r"^[0-9a-fA-F]+$")


class WireError(RuntimeError):
    """Protocol or framing error."""


class AuthError(PermissionError):
    """Mutual HMAC-SHA256 authentication failure."""


def get_state_dir() -> Path:
    custom = os.environ.get("LOGISIM_MCP_DIR", "").strip()
    d = Path(custom) if custom else (Path.home() / ".logisim-mcp")
    d.mkdir(parents=True, exist_ok=True)
    return d


def get_key_path() -> Path:
    return get_state_dir() / "bridge.key"


def get_discovery_path() -> Path:
    return get_state_dir() / "bridge.json"


def _restrict_owner_only(path: Path) -> None:
    if os.name == "posix":
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass


def load_or_create_key() -> bytes:
    """Load 256-bit shared HMAC key from bridge.key or create one atomically."""
    key_path = get_key_path()
    if key_path.is_file():
        try:
            raw = key_path.read_text(encoding="utf-8").strip()
            if len(raw) == 64 and _HEX_RE.match(raw):
                return bytes.fromhex(raw)
        except OSError:
            pass

    key = secrets.token_bytes(32)
    tmp = key_path.with_name(f"bridge.key.tmp.{os.getpid()}")
    tmp.write_text(key.hex() + "\n", encoding="utf-8")
    _restrict_owner_only(tmp)
    os.replace(tmp, key_path)
    _restrict_owner_only(key_path)
    return key


def read_discovery_info() -> Dict[str, Any]:
    """Read and validate ~/.logisim-mcp/bridge.json."""
    disc_path = get_discovery_path()
    if not disc_path.is_file():
        raise WireError("Logisim bridge discovery file not found (~/.logisim-mcp/bridge.json)")
    try:
        info = json.loads(disc_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise WireError(f"Corrupt discovery JSON: {exc}") from exc

    if not isinstance(info, dict):
        raise WireError("Discovery file must contain a JSON object")

    host = str(info.get("host", "127.0.0.1")).strip()
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise WireError(f"Refusing non-loopback bridge host: {host!r}")

    port = info.get("port")
    if isinstance(port, bool) or not isinstance(port, int) or not (1 <= port <= 65535):
        raise WireError(f"Invalid bridge port in discovery file: {port!r}")

    proto = info.get("protocol")
    if proto != PROTOCOL_VERSION:
        raise WireError(
            f"Unsupported bridge protocol version {proto!r} (expected {PROTOCOL_VERSION}). "
            "Restart Logisim via logisim_launch so it loads the v2.0.0 bridge."
        )
    return info


def send_frame(sock: socket.socket, payload: bytes) -> None:
    if len(payload) > MAX_FRAME_BYTES:
        raise WireError(f"Frame exceeds MAX_FRAME_BYTES ({len(payload)} > {MAX_FRAME_BYTES})")
    header = struct.pack(">I", len(payload))
    sock.sendall(header + payload)


def send_json_frame(sock: socket.socket, obj: Any) -> None:
    data = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    send_frame(sock, data)


def _recv_exact(sock: socket.socket, n: int) -> bytes | None:
    chunks = []
    remaining = n
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            if not chunks:
                return None
            raise WireError("Connection closed mid-frame")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def recv_frame(sock: socket.socket) -> bytes | None:
    header = _recv_exact(sock, 4)
    if header is None:
        return None
    (length,) = struct.unpack(">I", header)
    if length == 0 or length > MAX_FRAME_BYTES:
        raise WireError(f"Invalid incoming frame length: {length}")
    payload = _recv_exact(sock, length)
    if payload is None:
        raise WireError("Unexpected EOF reading frame payload")
    return payload


def recv_json_frame(sock: socket.socket) -> Dict[str, Any]:
    raw = recv_frame(sock)
    if raw is None:
        raise WireError("Bridge closed connection cleanly")
    try:
        obj = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        raise WireError(f"Malformed JSON frame: {exc}") from exc
    if not isinstance(obj, dict):
        raise WireError("Expected JSON object frame")
    return obj


def hmac_hex(key: bytes, message: str) -> str:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()


def perform_client_handshake(sock: socket.socket, key: bytes) -> Dict[str, Any]:
    """Execute mutual HMAC-SHA256 challenge-response handshake."""
    challenge = recv_json_frame(sock)
    if challenge.get("op") != "challenge":
        raise WireError(f"Expected challenge frame, got {challenge.get('op')!r}")

    nonce_s = challenge.get("nonce_s")
    if not isinstance(nonce_s, str) or len(nonce_s) < 32 or not _HEX_RE.match(nonce_s):
        raise WireError("Invalid server nonce in challenge")

    nonce_c = secrets.token_hex(32)
    proof_c = hmac_hex(key, f"logisim-mcp:c2s:{nonce_s}:{nonce_c}")
    send_json_frame(
        sock,
        {
            "op": "auth",
            "protocol": PROTOCOL_VERSION,
            "nonce_c": nonce_c,
            "proof_c": proof_c,
        },
    )

    reply = recv_json_frame(sock)
    if reply.get("op") == "auth_error":
        raise AuthError(str(reply.get("error", "Authentication rejected by bridge")))
    if reply.get("op") != "auth_ok":
        raise WireError(f"Expected auth_ok frame, got {reply.get('op')!r}")

    expected_s = hmac_hex(key, f"logisim-mcp:s2c:{nonce_c}:{nonce_s}")
    proof_s = str(reply.get("proof_s", "")).lower()
    if not hmac.compare_digest(proof_s, expected_s):
        raise AuthError("Server HMAC verification failed (rogue or mismatched bridge process)")

    return reply
