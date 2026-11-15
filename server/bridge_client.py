"""Thread-safe authenticated client for Logisim Java bridge v2.0.0."""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import wire


DEFAULT_BRIDGE_JAR = Path(__file__).resolve().parent.parent / "logisim-mcp-bridge.jar"
_LOGISIM_CANDIDATE_NAMES = (
    "logisim-win-2.7.1.exe",
    "logisim-generic-2.7.1.jar",
    "logisim.exe",
    "logisim.jar",
)


class BridgeUnavailableError(RuntimeError):
    """Raised when Logisim is not running or bridge cannot be reached."""


class LogisimBridgeClient:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._sock: Optional[socket.socket] = None
        self._req_seq = 1

    def close(self) -> None:
        with self._lock:
            self._close_unlocked()

    def _close_unlocked(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def _connect_unlocked(self, timeout: float = 10.0) -> socket.socket:
        info = wire.read_discovery_info()
        key = wire.load_or_create_key()
        host = str(info.get("host", "127.0.0.1"))
        port = int(info["port"])

        sock = socket.create_connection((host, port), timeout=timeout)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            wire.perform_client_handshake(sock, key)
            sock.settimeout(timeout)
            return sock
        except Exception:
            try:
                sock.close()
            except OSError:
                pass
            raise

    def call(self, cmd: str, params: Optional[Dict[str, Any]] = None, timeout: float = 60.0) -> Any:
        payload_params = params if params is not None else {}
        with self._lock:
            last_err: Optional[Exception] = None
            for attempt in range(2):
                try:
                    if self._sock is None:
                        self._sock = self._connect_unlocked(timeout=min(timeout, 10.0))
                    self._sock.settimeout(timeout)
                    req_id = self._req_seq
                    self._req_seq += 1
                    wire.send_json_frame(self._sock, {"id": req_id, "cmd": cmd, "params": payload_params})
                    resp = wire.recv_json_frame(self._sock)
                    if not resp.get("ok"):
                        raise RuntimeError(str(resp.get("error", f"Command '{cmd}' failed")))
                    return resp.get("result")
                except (OSError, wire.WireError) as exc:
                    last_err = exc
                    self._close_unlocked()
                    if attempt == 0:
                        continue
                    raise BridgeUnavailableError(
                        f"Could not communicate with Logisim bridge ({exc}). "
                        "Call logisim_launch to start Logisim or logisim_attach if already open."
                    ) from exc
            raise BridgeUnavailableError(str(last_err))

    def try_status(self) -> Dict[str, Any]:
        try:
            res = self.call("status", {}, timeout=5.0)
            if isinstance(res, dict):
                return res
        except Exception as exc:
            return {
                "connected": False,
                "error": str(exc),
                "discovery_exists": wire.get_discovery_path().is_file(),
            }
        return {"connected": False}

    def launch(self, file_path: Optional[str] = None, wait_seconds: float = 20.0) -> Dict[str, Any]:
        st = self.try_status()
        if st.get("connected"):
            if file_path:
                opened = self.call("open_file", {"path": file_path}, timeout=30.0)
                st = self.try_status()
                st["opened_file"] = opened
            st["already_running"] = True
            return st

        java_exe = self._find_java_gui_exe()
        logisim_exe = self._find_logisim_exe()
        bridge_jar = Path(os.environ.get("LOGISIM_BRIDGE_JAR", str(DEFAULT_BRIDGE_JAR)))

        if not logisim_exe.is_file():
            raise FileNotFoundError(f"Logisim executable not found: {logisim_exe}")
        if not bridge_jar.is_file():
            raise FileNotFoundError(
                f"Bridge JAR not found: {bridge_jar}. Run 'python build.py' (or 'build.ps1' on Windows) first."
            )

        # Pre-create secret key and remove stale discovery file before launching JVM
        wire.load_or_create_key()
        disc = wire.get_discovery_path()
        if disc.is_file():
            try:
                disc.unlink()
            except OSError:
                pass

        cmd: List[str] = [
            str(java_exe),
            "-cp",
            f"{logisim_exe};{bridge_jar}" if os.name == "nt" else f"{logisim_exe}:{bridge_jar}",
            "logisimmcp.Launcher",
        ]
        if file_path:
            cmd.append(str(Path(file_path).resolve()))

        creationflags = 0
        if os.name == "nt":
            creationflags = (
                getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
                | getattr(subprocess, "DETACHED_PROCESS", 0)
            )
        proc = subprocess.Popen(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=creationflags,
        )

        deadline = time.monotonic() + wait_seconds
        while time.monotonic() < deadline:
            if disc.is_file():
                try:
                    info = wire.read_discovery_info()
                    if int(info.get("pid", -1)) in (-1, proc.pid):
                        status = self.call("status", {}, timeout=5.0)
                        status["launched_pid"] = proc.pid
                        return status
                except Exception:
                    pass
            time.sleep(0.25)

        raise TimeoutError(f"Logisim launched (pid={proc.pid}) but MCP bridge did not become ready within {wait_seconds}s")

    def attach(self, pid: Optional[int] = None, wait_seconds: float = 12.0) -> Dict[str, Any]:
        st = self.try_status()
        if st.get("connected"):
            st["already_connected"] = True
            return st

        target_pid = pid if pid is not None else self._discover_logisim_pid()
        if target_pid is None:
            raise BridgeUnavailableError("No running Logisim JVM process found. Use logisim_launch instead.")

        jdk_java = self._find_jdk_java_exe()
        bridge_jar = Path(os.environ.get("LOGISIM_BRIDGE_JAR", str(DEFAULT_BRIDGE_JAR)))
        if not bridge_jar.is_file():
            raise FileNotFoundError(f"Bridge JAR not found: {bridge_jar}")

        wire.load_or_create_key()
        proc = subprocess.run(
            [str(jdk_java), "-cp", str(bridge_jar), "logisimmcp.Launcher", "--attach", str(target_pid), str(bridge_jar)],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"Attach failed (rc={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}")

        deadline = time.monotonic() + wait_seconds
        while time.monotonic() < deadline:
            s = self.try_status()
            if s.get("connected"):
                s["attached_pid"] = target_pid
                return s
            time.sleep(0.25)
        raise TimeoutError(f"Attached to Logisim pid={target_pid}, but bridge did not respond in {wait_seconds}s")

    @staticmethod
    def _find_logisim_exe() -> Path:
        env = os.environ.get("LOGISIM_EXE", "").strip()
        if env:
            return Path(env)
        repo_root = Path(__file__).resolve().parent.parent
        search_dirs = [
            repo_root,
            Path.home() / "Desktop" / "All",
            Path.home() / "Desktop",
            Path.home() / "Downloads",
            Path(r"C:\Program Files\Logisim"),
            Path(r"C:\Program Files (x86)\Logisim"),
        ]
        for base in search_dirs:
            for name in _LOGISIM_CANDIDATE_NAMES:
                cand = base / name
                if cand.is_file():
                    return cand
        for cmd in ("logisim", "logisim-generic"):
            found = shutil.which(cmd)
            if found:
                return Path(found)
        raise FileNotFoundError(
            "Logisim executable/jar not found. Set the LOGISIM_EXE environment variable "
            "or place logisim-win-2.7.1.exe / logisim-generic-2.7.1.jar in the repository root."
        )

    @staticmethod
    def _find_java_gui_exe() -> Path:
        env = os.environ.get("LOGISIM_JAVA_EXE", "").strip()
        if env and Path(env).is_file():
            return Path(env)
        java_home = os.environ.get("JAVA_HOME", "").strip()
        if java_home:
            for name in ("javaw.exe", "java.exe", "javaw", "java"):
                cand = Path(java_home) / "bin" / name
                if cand.is_file():
                    return cand
        java_root = Path(r"C:\Program Files\Java")
        if java_root.is_dir():
            for pat in ("jre*", "jdk*"):
                for folder in sorted(java_root.glob(pat), reverse=True):
                    for name in ("javaw.exe", "java.exe"):
                        cand = folder / "bin" / name
                        if cand.is_file():
                            return cand
        found = shutil.which("javaw") or shutil.which("java")
        if found:
            return Path(found)
        raise FileNotFoundError("Java runtime (javaw/java) not found. Set LOGISIM_JAVA_EXE or JAVA_HOME.")

    @staticmethod
    def _find_jdk_java_exe() -> Path:
        java_home = os.environ.get("JAVA_HOME", "").strip()
        if java_home:
            for name in ("java.exe", "java"):
                cand = Path(java_home) / "bin" / name
                if cand.is_file():
                    return cand
        java_root = Path(r"C:\Program Files\Java")
        if java_root.is_dir():
            for folder in sorted(java_root.glob("jdk*"), reverse=True):
                cand = folder / "bin" / "java.exe"
                if cand.is_file():
                    return cand
        found = shutil.which("java")
        if found:
            return Path(found)
        raise FileNotFoundError("JDK java executable required for dynamic --attach.")

    @staticmethod
    def _discover_logisim_pid() -> Optional[int]:
        if os.name == "nt":
            try:
                res = subprocess.run(
                    [
                        "powershell",
                        "-NoProfile",
                        "-Command",
                        "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*logisim*' -and $_.Name -match 'java' } | Select-Object -First 1 -ExpandProperty ProcessId",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=8,
                )
                txt = res.stdout.strip()
                if txt and re.fullmatch(r"\d+", txt):
                    return int(txt)
            except Exception:
                pass
            return None

        # POSIX (Linux / macOS) fallback via pgrep or ps
        try:
            res = subprocess.run(
                ["pgrep", "-f", "logisim.*\\.jar|com\\.cburch\\.logisim\\.Main"],
                capture_output=True,
                text=True,
                timeout=5,
            )
            for line in res.stdout.splitlines():
                line = line.strip()
                if re.fullmatch(r"\d+", line):
                    return int(line)
        except Exception:
            pass
        return None
