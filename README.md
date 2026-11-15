# Logisim Secure MCP

A production-oriented Model Context Protocol (MCP) server and in-process JVM bridge for **Logisim 2.7.1**, engineered with **zero third-party Python or Java runtime dependencies** (pure Python 3 stdlib + pure Java 8 stdlib).

It enables AI coding agents to design, route, package, audit, test, synthesize, and visually inspect digital logic circuits directly inside a live Logisim GUI session.

> **Disclosure:** This project was designed and built with AI assistance.

---

## Features & Architecture

### 1. Zero-Dependency MCP Server (`server/`)
- Implements the MCP `stdio` JSON-RPC protocol (`2025-03-26` / `2024-11-05`) over both newline-delimited and `Content-Length` stdio transports.
- Exposes **43 structured MCP tools**, plus built-in design handbook resources (`logisim_guide`) and workflow prompt templates.
- Validates every outgoing tool call and nested `logisim_batch` operation against explicit Draft-07 JSON schemas in Python (`server/schema_check.py`) before hitting the JVM bridge.

### 2. Layout & Routing Engine (`server/layout_helper.py` & `CircuitLayoutOps.java`)
- **Semantic Component & Port Metadata (`logisim_describe_component`):** Resolves symbolic port aliases (`Adder.cin`, `Register.clk`/`D`/`en`, `RAM.we`/`oe`/`clr`, custom subcircuit pin labels), exact port offsets, bit widths, and attribute option lists (`JComboBox` enums).
- **Atomic Undoable Transactions (`logisim_batch`):** Groups component placement, semantic wiring, tunnel fanouts, attribute updates, and custom subcircuit packaging into a single undoable Logisim project transaction with `ref` aliases, optional post-build auto-tidy (`tidy_after`), DRC (`audit_after`), and live screenshot (`screenshot_after`).
- **Balanced Readable Spacing & A\* Routing (`logisim_connect`, `logisim_arrange`, `logisim_fanout_tunnels`):**
  - Bounds-aware `A*` orthogonal router (`route="auto"`) keeps clear of component bodies and foreign pins to prevent accidental shorts or wire-over-port crossings.
  - Auto-oriented labeled `Tunnel` placement (`mode="tunnel"`) matches bit widths automatically and points labels outward so text never covers chips.
  - `logisim_fanout_tunnels` constructs bit-sliced `Splitter` hubs paired with staggered, readable `Tunnel` arrays in a single call.
  - `logisim_arrange` (`row`, `column`, `grid`, `align`, `distribute`, `tidy`) normalizes spacing and preserves clean breathing room (`gap_x` / `gap_y`) around components.
- **Custom Subcircuit IC Packaging (`logisim_configure_appearance`):** Generates clean, grid-aligned IC symbols (`rect`, `roundrect`, `rounded`) with `west`/`east`/`north`/`south` pin distribution, pin labels, notch, and titles.

### 3. Verification, Synthesis & Live Visual Feedback (`CircuitAuditor.java`, `SimAndSynthOps.java`)
- **Static & Live Design Rule Check (`logisim_audit_circuit`):** Audits single circuits or recursively across all subcircuits for `signal_error` (`E`), `oscillation_detected`, `unclocked_sequential`, `floating_required_input`, `tunnel_orphan` (with fuzzy typo suggestions), `tunnel_width_mismatch`, `wire_passes_through_port`, `component_overlap`, and `subcircuit_pin_mismatch`.
- **Cycle-Accurate Test Bench (`logisim_test_bench` & `logisim_inspect_state`):** Runs multi-step/multi-cycle vector tables supporting `set`, `set_mem`, `tick` (clock cycles), `half_ticks`, `expect`, `expect_mem`, hierarchical `Subcircuit.Signal` inspection, `0b10x1` wildcard masks, and step traces.
- **Combinational Analysis & Synthesis (`logisim_truth_table`, `logisim_analyze_circuit`, `logisim_synthesize_circuit`):** Extracts truth tables and minimized SOP Boolean expressions from existing combinational circuits, or synthesizes clean multi-output gate networks from Boolean formulas or truth tables.
- **Live Annotated Screenshots (`logisim_screenshot`):** Renders live simulation colors from Logisim's active `CircuitState` with optional DRC error callouts and port overlays, returned directly as MCP `ImageContent`.

---

## Security Model

1. **Loopback-Only Transport:** Both the Java bridge server and Python client strictly bind and connect only to `127.0.0.1` using length-prefixed JSON framing (`MAX_FRAME_BYTES = 32 MiB`).
2. **External Shared Secret (Out of Repo):** A 256-bit shared hex secret is generated at runtime in `~/.logisim-mcp/bridge.key` (configurable via `LOGISIM_MCP_DIR`) with owner-restricted permissions (`chmod 0600` on POSIX / owner ACLs on Windows). No API keys, tokens, or secrets are stored in this repository.
3. **Mutual HMAC-SHA256 Authentication:** Every local TCP connection performs a two-way random-nonce HMAC-SHA256 challenge-response handshake (`logisim-mcp:c2s:...` and `logisim-mcp:s2c:...`) before accepting any command.
4. **Ephemeral Discovery Metadata:** `~/.logisim-mcp/bridge.json` records only loopback host, port, protocol version, version string, and PID—never secret material.

---

## Project Structure

```text
logisim-secure-mcp/
├── build.ps1                          # Windows JDK build script for the Java 8 bridge JAR
├── start_logisim_with_mcp.bat         # Convenience launcher for Logisim with bridge loaded
├── java/
│   ├── MANIFEST.MF                    # Premain/Agent/Main manifest
│   └── src/logisimmcp/
│       ├── BridgeServer.java          # Localhost TCP server & request dispatcher
│       ├── CircuitAuditor.java        # Recursive DRC engine
│       ├── CircuitLayoutOps.java      # A* routing, splitters, fanouts, arrangement, appearance
│       ├── Commands.java              # Bridge command handlers & transactions
│       ├── ComponentMeta.java         # Logisim component/port/attribute reflection
│       ├── Json.java                  # Zero-dependency JSON parser/serializer
│       ├── Launcher.java              # Logisim startup & dynamic JVM attach entrypoint
│       ├── SimAndSynthOps.java        # Test bench, state inspection, synthesis, screenshots
│       └── WireProtocol.java          # Framed transport & mutual HMAC-SHA256 handshake
├── server/
│   ├── __init__.py
│   ├── bridge_client.py               # Thread-safe bridge client & Logisim process launcher
│   ├── guide.py                       # Built-in MCP tool handbook & best practices
│   ├── layout_helper.py               # Client-side spacing/routing & batch helpers
│   ├── logisim_mcp_server.py          # MCP stdio JSON-RPC server entrypoint
│   ├── prompts.py                     # Reusable workflow prompts
│   ├── schema_check.py                # Draft-07 JSON Schema validator
│   ├── tool_schemas.py                # Tool & batch operation definitions (43 tools)
│   └── wire.py                        # Framed wire protocol & HMAC client handshake
└── tests/
    ├── test_protocol_and_schemas.py   # Offline unit tests (wire, HMAC, schemas, MCP stdio)
    └── test_logisim_e2e.py            # Live Logisim JVM end-to-end integration tests
```

---

## Prerequisites & Setup

- **Logisim 2.7.1** (`logisim-win-2.7.1.exe` or `logisim-generic-2.7.1.jar`)
- **Python 3.10+**
- **Java 8+ JDK** (targets `--release 8` to compile `logisim-mcp-bridge.jar` and support `logisim_attach`)

### Environment Variables (Optional)

| Variable | Purpose |
| :--- | :--- |
| `LOGISIM_EXE` | Path to `logisim-win-2.7.1.exe` or `logisim-generic-2.7.1.jar` |
| `LOGISIM_JAVA_EXE` | Path to `javaw` / `java` used to start Logisim GUI |
| `LOGISIM_BRIDGE_JAR` | Custom path to `logisim-mcp-bridge.jar` |
| `LOGISIM_MCP_DIR` | Override runtime directory for `bridge.key` and `bridge.json` (defaults to `~/.logisim-mcp`) |

### MCP Client Configuration

Add the following server entry to your MCP client configuration (replacing `<path-to-repo>` and `<path-to-logisim>` with your local paths):

```json
{
  "mcpServers": {
    "logisim": {
      "command": "python",
      "args": [
        "<path-to-repo>/server/logisim_mcp_server.py"
      ],
      "env": {
        "LOGISIM_EXE": "<path-to-logisim>/logisim-win-2.7.1.exe"
      }
    }
  }
}
```

### Building the Java Bridge

Before first use, compile `logisim-mcp-bridge.jar` in the repository root (targeting `--release 8` on Windows):

```powershell
$env:LOGISIM_EXE = "C:\path\to\logisim-win-2.7.1.exe"
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

### Running Tests

Run the offline protocol, HMAC authentication, JSON schema, and MCP server unit tests:

```bash
python -m unittest discover -s tests -p "test_protocol*.py" -v
```

Run the full live Logisim end-to-end test suite (requires `LOGISIM_EXE` / Logisim 2.7.1 installed):

```bash
python -m unittest discover -s tests -p "test_logisim_e2e.py" -v
```
