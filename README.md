# Logisim Secure MCP

An MCP (Model Context Protocol) server and local JVM bridge for Logisim 2.7.1. Built purely on the Python 3 and Java 8 standard libraries with no third party runtime dependencies.

It lets AI coding assistants build, wire, package, audit, test, synthesize, and inspect digital logic circuits directly inside a running Logisim GUI window.

Note: This project was developed with AI assistance.

## Overview

### MCP Server (`server/`)
- Implements the MCP `stdio` JSON-RPC protocol (`2025-03-26`, `2024-11-05`) over both newline delimited and `Content-Length` stdio framing.
- Exposes 43 structured tools alongside built in design notes (`logisim_guide`) and workflow prompt templates.
- Checks every outgoing tool call and nested `logisim_batch` operation against Draft-07 schemas in Python (`server/schema_check.py`) before forwarding requests to Java.

### Layout and Routing (`server/layout_helper.py`, `CircuitLayoutOps.java`)
- **Component and port lookup (`logisim_describe_component`):** Resolves symbolic port aliases (`Adder.cin`, `Register.clk`, `RAM.we`, subcircuit pin names), pin offsets, bit widths, and enum attributes.
- **Undoable batch builds (`logisim_batch`):** Combines component placement, wiring, tunnel fanouts, attribute edits, and subcircuit packaging into a single Logisim undo transaction with `ref` aliases, optional auto tidy (`tidy_after`), DRC (`audit_after`), and screenshots (`screenshot_after`).
- **Spacing and routing (`logisim_connect`, `logisim_arrange`, `logisim_fanout_tunnels`):**
  - Uses an orthogonal A* router (`route="auto"`) that avoids component bounding boxes and foreign pins to prevent accidental shorts.
  - Supports labeled `Tunnel` wiring (`mode="tunnel"`) with automatic bit width matching and outward label alignment.
  - Creates bit sliced `Splitter` hubs paired with staggered `Tunnel` labels in one call (`logisim_fanout_tunnels`).
  - Arranges existing blocks cleanly with `logisim_arrange` (`row`, `column`, `grid`, `align`, `distribute`, `tidy`).
- **Subcircuit IC packaging (`logisim_configure_appearance`):** Generates clean rectangular or rounded IC symbols with ordered side pins, pin labels, notch, and title.

### Verification, Synthesis, and Screenshots (`CircuitAuditor.java`, `SimAndSynthOps.java`)
- **Design rule checker (`logisim_audit_circuit`):** Audits a single circuit or recursively checks all subcircuits for `signal_error` (`E`), oscillations, unclocked sequential elements, floating required inputs, orphan tunnels (with typo suggestions), width mismatches, wires crossing foreign pins, overlapping components, and subcircuit pin mismatches.
- **Test bench and state inspection (`logisim_test_bench`, `logisim_inspect_state`):** Runs stepped test vectors (`set`, `set_mem`, `tick`, `half_ticks`, `expect`, `expect_mem`, `0b10x1` wildcards) and inspects nested subcircuit values.
- **Combinational analysis and synthesis (`logisim_truth_table`, `logisim_analyze_circuit`, `logisim_synthesize_circuit`):** Extracts truth tables and minimized Boolean SOP expressions from combinational circuits, or generates gate networks directly from Boolean formulas or truth tables.
- **Live screenshots (`logisim_screenshot`):** Renders the active `CircuitState` with wire colors and optional error callouts or port overlays, returned to MCP clients as PNG image content.

## Security Model

1. **Loopback only:** Both the Java bridge server and Python client bind and connect strictly on `127.0.0.1` using length prefixed JSON frames (`MAX_FRAME_BYTES = 32 MiB`).
2. **Runtime shared key:** A 256 bit hex secret is generated at runtime in `~/.logisim-mcp/bridge.key` (or `LOGISIM_MCP_DIR`) with owner only permissions (`chmod 0600` on POSIX, owner ACLs on Windows). No API keys, tokens, or secrets are stored in this repository.
3. **Mutual HMAC-SHA256 handshake:** Every TCP connection runs a bidirectional random nonce HMAC-SHA256 verification (`logisim-mcp:c2s:...` and `logisim-mcp:s2c:...`) before any command is accepted.
4. **Ephemeral discovery file:** `~/.logisim-mcp/bridge.json` stores only the loopback address, port, protocol version, version string, and PID.

## Repository Layout

```text
logisim-secure-mcp/
├── build.py                           # Cross platform JDK build script (JDK 8+)
├── build.ps1                          # Windows PowerShell build helper
├── start_logisim_with_mcp.bat         # Windows helper to launch Logisim with the bridge loaded
├── java/
│   ├── MANIFEST.MF                    # Premain, Agentmain, and Main manifest
│   └── src/logisimmcp/
│       ├── BridgeServer.java          # Localhost TCP server and request dispatcher
│       ├── CircuitAuditor.java        # Recursive DRC engine
│       ├── CircuitLayoutOps.java      # A* routing, splitters, fanouts, layout, appearance
│       ├── Commands.java              # Bridge command handlers and transactions
│       ├── ComponentMeta.java         # Logisim component, port, and attribute reflection
│       ├── Json.java                  # Standalone JSON parser and serializer
│       ├── Launcher.java              # Logisim startup and dynamic JVM attach entrypoint
│       ├── SimAndSynthOps.java        # Test bench, state inspection, synthesis, screenshots
│       └── WireProtocol.java          # Framed transport and mutual HMAC-SHA256 handshake
├── server/
│   ├── __init__.py
│   ├── bridge_client.py               # Thread safe bridge client and Logisim launcher
│   ├── guide.py                       # Built in MCP tool handbook
│   ├── layout_helper.py               # Client side spacing, routing, and batch helpers
│   ├── logisim_mcp_server.py          # MCP stdio JSON-RPC server entrypoint
│   ├── prompts.py                     # Workflow prompts
│   ├── schema_check.py                # Draft-07 JSON Schema validator
│   ├── tool_schemas.py                # Tool and batch schemas (43 tools)
│   └── wire.py                        # Wire framing and HMAC client handshake
└── tests/
    ├── test_protocol_and_schemas.py   # Offline unit tests (wire, HMAC, schemas, MCP stdio)
    └── test_logisim_e2e.py            # Live Logisim JVM integration tests
```

## Setup and Usage

### Requirements

- Logisim 2.7.1 (`logisim-win-2.7.1.exe` or `logisim-generic-2.7.1.jar`)
- Python 3.10+
- Java 8+ JDK (needed to compile `logisim-mcp-bridge.jar` and use `logisim_attach`)

### 1. Build the Java Bridge

Before running the MCP server for the first time, compile `logisim-mcp-bridge.jar` from source (works on Windows, macOS, and Linux with JDK 8 or newer):

```bash
python build.py
```

On Windows PowerShell, you can also run:

```powershell
$env:LOGISIM_EXE = "C:\path\to\logisim-win-2.7.1.exe"
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

### 2. Configure Your MCP Client

Add the server to your MCP client configuration (replace `<path-to-repo>` and `<path-to-logisim>` with your local paths):

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

### Optional Environment Variables

| Variable | Description |
| :--- | :--- |
| `LOGISIM_EXE` | Path to `logisim-win-2.7.1.exe` or `logisim-generic-2.7.1.jar` |
| `LOGISIM_JAVA_EXE` | Path to `javaw` or `java` used to start the Logisim GUI |
| `LOGISIM_BRIDGE_JAR` | Custom path to `logisim-mcp-bridge.jar` |
| `LOGISIM_MCP_DIR` | Override runtime directory for `bridge.key` and `bridge.json` (defaults to `~/.logisim-mcp`) |

### Running Tests

Offline protocol, HMAC handshake, schema validation, and MCP stdio framing tests:

```bash
python -m unittest discover -s tests -p "test_protocol*.py" -v
```

Live Logisim JVM integration tests (requires Logisim 2.7.1 installed):

```bash
python -m unittest discover -s tests -p "test_logisim_e2e.py" -v
```
