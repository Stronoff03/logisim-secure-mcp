#!/usr/bin/env python3
"""Cross-platform build script for logisim-mcp-bridge.jar (pure Python stdlib)."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC_DIR = ROOT / "java" / "src" / "logisimmcp"
BUILD_DIR = ROOT / "java" / "build"
MANIFEST = ROOT / "java" / "MANIFEST.MF"
OUT_JAR = ROOT / "logisim-mcp-bridge.jar"

LOGISIM_NAMES = (
    "logisim-win-2.7.1.exe",
    "logisim-generic-2.7.1.jar",
    "logisim.exe",
    "logisim.jar",
)


def find_jdk_tool(name: str) -> Path:
    exe_names = (f"{name}.exe", name) if os.name == "nt" else (name,)

    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        for exe in exe_names:
            cand = Path(java_home) / "bin" / exe
            if cand.is_file():
                return cand

    found = shutil.which(name)
    if found:
        return Path(found)

    search_roots = []
    if os.name == "nt":
        search_roots.extend([Path(r"C:\Program Files\Java"), Path(r"C:\Program Files\Eclipse Adoptium")])
    else:
        search_roots.extend([Path("/Library/Java/JavaVirtualMachines"), Path("/usr/lib/jvm")])

    for base in search_roots:
        if not base.is_dir():
            continue
        for child in sorted(base.iterdir(), reverse=True):
            for bin_dir in (child / "bin", child / "Contents" / "Home" / "bin"):
                for exe in exe_names:
                    cand = bin_dir / exe
                    if cand.is_file():
                        return cand

    raise FileNotFoundError(f"JDK tool '{name}' not found. Install a JDK and ensure PATH or JAVA_HOME is set.")


def find_logisim_binary() -> Path:
    env = os.environ.get("LOGISIM_EXE", "").strip()
    if env:
        p = Path(env)
        if p.is_file():
            return p
        raise FileNotFoundError(f"LOGISIM_EXE points to missing file: {p}")

    search_dirs = [
        ROOT,
        Path.home() / "Desktop" / "All",
        Path.home() / "Desktop",
        Path.home() / "Downloads",
        Path("/usr/share/java"),
        Path(r"C:\Program Files\Logisim"),
        Path(r"C:\Program Files (x86)\Logisim"),
    ]
    for base in search_dirs:
        for name in LOGISIM_NAMES:
            cand = base / name
            if cand.is_file():
                return cand

    for cmd in ("logisim", "logisim-generic"):
        found = shutil.which(cmd)
        if found:
            return Path(found)

    raise FileNotFoundError(
        "Logisim 2.7.1 executable or JAR not found. "
        "Set LOGISIM_EXE or place logisim-win-2.7.1.exe / logisim-generic-2.7.1.jar in the repo root."
    )


def detect_java_major(javac: Path) -> int:
    proc = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=False)
    out = (proc.stdout + "\n" + proc.stderr).strip()
    m = re.search(r"(\d+)(?:\.(\d+))?", out)
    if not m:
        return 9
    major = int(m.group(1))
    if major == 1 and m.group(2):
        return int(m.group(2))
    return major


def main() -> int:
    javac = find_jdk_tool("javac")
    jar = find_jdk_tool("jar")
    logisim = find_logisim_binary()

    sources = sorted(str(p) for p in SRC_DIR.glob("*.java"))
    if not sources:
        raise FileNotFoundError(f"No Java sources found in {SRC_DIR}")

    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    major = detect_java_major(javac)
    if major >= 9:
        target_flags = ["-Xlint:-options", "--release", "8"]
    else:
        target_flags = ["-source", "1.8", "-target", "1.8"]

    print(f"Compiling {len(sources)} Java files using JDK {major}...")
    compile_cmd = [
        str(javac),
        *target_flags,
        "-encoding",
        "UTF-8",
        "-cp",
        str(logisim),
        "-d",
        str(BUILD_DIR),
        *sources,
    ]
    res = subprocess.run(compile_cmd)
    if res.returncode != 0:
        return res.returncode

    print(f"Packaging {OUT_JAR.name}...")
    jar_cmd = [str(jar), "cfm", str(OUT_JAR), str(MANIFEST), "-C", str(BUILD_DIR), "."]
    res = subprocess.run(jar_cmd)
    if res.returncode != 0:
        return res.returncode

    print(f"Build succeeded: {OUT_JAR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
