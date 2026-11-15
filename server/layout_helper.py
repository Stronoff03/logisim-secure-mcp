"""Pure-Python balanced layout, clearance routing, tunnel spacing, and circuit tidying engine."""

from __future__ import annotations

import heapq
import re
from typing import Any, Callable, Dict, Iterable, List, Optional, Set, Tuple

GRID = 10

# Moderate, comfortable visual breathing room (not excessive sprawl).
DEFAULT_GAP_X = 40
DEFAULT_GAP_Y = 30
DEFAULT_WIRE_CLEARANCE = 10
DEFAULT_TUNNEL_OFFSET = 40
DEFAULT_TUNNEL_PITCH = 30

_DIR_VECTORS: Dict[str, Tuple[int, int]] = {
    "east": (1, 0),
    "west": (-1, 0),
    "south": (0, 1),
    "north": (0, -1),
}
_OPP_DIR: Dict[str, str] = {
    "east": "west",
    "west": "east",
    "south": "north",
    "north": "south",
}
_SMALL_IO_TYPES = {
    "Pin",
    "Tunnel",
    "Probe",
    "Clock",
    "Constant",
    "Power",
    "Ground",
    "LED",
    "Button",
}


def snap(val: int | float) -> int:
    """Round any coordinate or dimension to Logisim's 10px grid."""
    return int(round(float(val) / GRID) * GRID)


def _comp_box(comp: Dict[str, Any], label_pad: bool = False) -> Tuple[int, int, int, int]:
    """Return component bounding box as (x0, y0, x1, y1)."""
    b = comp.get("bounds") or {}
    if all(k in b for k in ("x", "y", "width", "height")) and int(b["width"]) > 0 and int(b["height"]) > 0:
        x0 = int(b["x"])
        y0 = int(b["y"])
        x1 = x0 + int(b["width"])
        y1 = y0 + int(b["height"])
    else:
        cx = int(comp.get("x", 0))
        cy = int(comp.get("y", 0))
        x0, y0, x1, y1 = cx - 10, cy - 10, cx + 10, cy + 10

    if label_pad and comp.get("label") and comp.get("type") not in ("Tunnel", "Pin", "Probe"):
        attrs = comp.get("attrs") or {}
        loc = str(attrs.get("labelloc") or "north").lower()
        if loc == "north":
            y0 -= 10
        elif loc == "south":
            y1 += 10
    return x0, y0, x1, y1


def _boxes_close(
    a: Tuple[int, int, int, int],
    b: Tuple[int, int, int, int],
    gap_x: int,
    gap_y: int,
) -> bool:
    """Return True if two boxes are closer than (gap_x, gap_y) simultaneously."""
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    x_gap = max(bx0 - ax1, ax0 - bx1)
    y_gap = max(by0 - ay1, ay0 - by1)
    return x_gap < gap_x and y_gap < gap_y


def fetch_circuit_snapshot(
    client: Any,
    circuit: Optional[str] = None,
    include_ports: bool = True,
    include_wires: bool = True,
) -> Dict[str, Any]:
    """Fetch complete paginated component list + wires for a circuit."""
    base_params: Dict[str, Any] = {
        "offset": 0,
        "limit": 500,
        "include_ports": include_ports,
        "include_wires": include_wires,
    }
    if circuit:
        base_params["circuit"] = circuit

    first = client.call("get_circuit", base_params, timeout=60.0)
    comps = list(first.get("components") or [])
    total = int(first.get("total_components", len(comps)))
    offset = len(comps)

    while offset < total:
        page_params = dict(base_params)
        page_params["offset"] = offset
        page_params["include_wires"] = False
        nxt = client.call("get_circuit", page_params, timeout=60.0)
        batch = list(nxt.get("components") or [])
        if not batch:
            break
        comps.extend(batch)
        offset += len(batch)

    out = dict(first)
    out["components"] = comps
    return out


def detect_crowding(
    snapshot: Dict[str, Any],
    min_gap_x: int = 20,
    min_gap_y: int = 20,
    max_warnings: int = 12,
) -> List[Dict[str, Any]]:
    """Detect pairs of components or tunnels that sit too tightly together ('dip dibe')."""
    comps = list(snapshot.get("components") or [])
    boxes = [_comp_box(c, label_pad=True) for c in comps]
    warnings: List[Dict[str, Any]] = []

    for i in range(len(comps)):
        if len(warnings) >= max_warnings:
            break
        ci = comps[i]
        bi = boxes[i]
        wi, hi = bi[2] - bi[0], bi[3] - bi[1]
        ti = str(ci.get("type") or "")
        for j in range(i + 1, len(comps)):
            cj = comps[j]
            bj = boxes[j]
            wj, hj = bj[2] - bj[0], bj[3] - bj[1]
            tj = str(cj.get("type") or "")

            x_gap = max(bj[0] - bi[2], bi[0] - bj[2])
            y_gap = max(bj[1] - bi[3], bi[1] - bj[3])

            # Allow neatly stacked Pins/Tunnels if separated by >= 10px edge gap (~30px center pitch)
            both_small = (ti in _SMALL_IO_TYPES and tj in _SMALL_IO_TYPES)
            req_x = 10 if both_small else min_gap_x
            req_y = 10 if (both_small and max(hi, hj) <= 24) else min_gap_y

            # Avoid false warnings on Pin + directly wired adjacent probe/const intentionally aligned far on another axis
            if x_gap < req_x and y_gap < req_y:
                warnings.append(
                    {
                        "type": "crammed_components" if (x_gap >= 0 and y_gap >= 0) else "overlapping_bounds",
                        "a": ci.get("label") or ci.get("id"),
                        "b": cj.get("label") or cj.get("id"),
                        "x_gap": x_gap,
                        "y_gap": y_gap,
                        "suggestion": "Run `logisim_arrange(mode='tidy')` to add clean breathing room.",
                    }
                )
                if len(warnings) >= max_warnings:
                    break
    return warnings


def _resolve_component_in_snapshot(
    comps: List[Dict[str, Any]],
    query: str,
    batch_refs: Optional[Dict[str, str]] = None,
) -> Optional[Dict[str, Any]]:
    q = query.strip()
    if batch_refs and q in batch_refs:
        q = batch_refs[q]

    for c in comps:
        if c.get("id") == q:
            return c

    if q.startswith("@"):
        coord = q[1:].strip()
        m = re.match(r"^(-?\d+)\s*,\s*(-?\d+)$", coord)
        if m:
            tx, ty = snap(int(m.group(1))), snap(int(m.group(2)))
            for c in comps:
                if int(c.get("x", 0)) == tx and int(c.get("y", 0)) == ty:
                    return c

    exact_lbl = [c for c in comps if str(c.get("label") or "").strip().lower() == q.lower()]
    if len(exact_lbl) == 1:
        return exact_lbl[0]

    exact_id_ci = [c for c in comps if str(c.get("id") or "").lower() == q.lower()]
    if len(exact_id_ci) == 1:
        return exact_id_ci[0]
    return None


def _resolve_port_in_comp(
    comp: Dict[str, Any],
    port_spec: Any,
) -> Optional[Dict[str, Any]]:
    ports = list(comp.get("ports") or [])
    if not ports:
        return None
    if port_spec is None:
        return ports[0] if len(ports) == 1 else None
    if isinstance(port_spec, int) and not isinstance(port_spec, bool):
        for p in ports:
            if int(p.get("index", -1)) == port_spec:
                return p
        return ports[port_spec] if 0 <= port_spec < len(ports) else None

    qs = str(port_spec).strip()
    if qs.isdigit():
        idx = int(qs)
        if 0 <= idx < len(ports):
            return ports[idx]

    q_low = qs.lower()
    for p in ports:
        if str(p.get("name") or "").strip().lower() == q_low:
            return p
    return None


def _resolve_endpoint(
    snapshot: Dict[str, Any],
    params: Dict[str, Any],
    prefix: str,
    batch_refs: Optional[Dict[str, str]] = None,
) -> Optional[Tuple[int, int, int, Optional[Dict[str, Any]]]]:
    """Return (x, y, width, component_dict_or_none) or None if fallback to Java is safer."""
    comps = list(snapshot.get("components") or [])
    comp_ref = params.get(prefix)
    px_key = f"{prefix}_x"
    py_key = f"{prefix}_y"

    if isinstance(comp_ref, str) and comp_ref.strip():
        comp = _resolve_component_in_snapshot(comps, comp_ref, batch_refs)
        if comp is None:
            return None
        port = _resolve_port_in_comp(comp, params.get(f"{prefix}_port"))
        if port is None:
            return None
        return snap(int(port["x"])), snap(int(port["y"])), int(port.get("width", -1)), comp

    if isinstance(params.get(px_key), int) and isinstance(params.get(py_key), int):
        return snap(int(params[px_key])), snap(int(params[py_key])), -1, None
    return None


def _build_wire_index(
    wires: Iterable[Dict[str, Any]],
) -> Tuple[Set[Tuple[int, int]], Set[Tuple[Tuple[int, int], Tuple[int, int]]], Set[Tuple[int, int]]]:
    """Build grid edge set, endpoint set, and wire occupancy point set."""
    edges: Set[Tuple[Tuple[int, int], Tuple[int, int]]] = set()
    endpoints: Set[Tuple[int, int]] = set()
    wire_pts: Set[Tuple[int, int]] = set()

    for w in wires:
        x1, y1 = snap(int(w["x1"])), snap(int(w["y1"]))
        x2, y2 = snap(int(w["x2"])), snap(int(w["y2"]))
        if x1 == x2 and y1 == y2:
            continue
        endpoints.add((x1, y1))
        endpoints.add((x2, y2))
        if y1 == y2:
            lo, hi = min(x1, x2), max(x1, x2)
            for x in range(lo, hi, GRID):
                a, b = (x, y1), (x + GRID, y1)
                edges.add((a, b))
                edges.add((b, a))
                wire_pts.add(a)
                wire_pts.add(b)
        elif x1 == x2:
            lo, hi = min(y1, y2), max(y1, y2)
            for y in range(lo, hi, GRID):
                a, b = (x1, y), (x1, y + GRID)
                edges.add((a, b))
                edges.add((b, a))
                wire_pts.add(a)
                wire_pts.add(b)
    return wire_pts, edges, endpoints


def _merge_path_segments(path: List[Tuple[int, int]]) -> List[Dict[str, int]]:
    """Collapse collinear path points into clean orthogonal segment dicts."""
    if len(path) < 2:
        return []
    segs: List[Dict[str, int]] = []
    sx, sy = path[0]
    px, py = path[0]
    dx_prev = path[1][0] - px
    dy_prev = path[1][1] - py

    for idx in range(1, len(path)):
        cx, cy = path[idx]
        dx = cx - px
        dy = cy - py
        if (dx != 0 and dy_prev != 0) or (dy != 0 and dx_prev != 0) or (dx == 0 and dy == 0):
            if (sx, sy) != (px, py):
                segs.append({"x1": sx, "y1": sy, "x2": px, "y2": py})
            sx, sy = px, py
            dx_prev, dy_prev = dx, dy
        px, py = cx, cy

    if (sx, sy) != (px, py):
        segs.append({"x1": sx, "y1": sy, "x2": px, "y2": py})
    return segs


def find_spaced_wire_path(
    snapshot: Dict[str, Any],
    start: Tuple[int, int],
    goal: Tuple[int, int],
    allow_collision: bool = False,
    wire_clearance: int = DEFAULT_WIRE_CLEARANCE,
) -> Optional[List[Dict[str, int]]]:
    """Run clearance-aware bounded A* on the 10px grid to avoid chip-hugging & parallel crowding."""
    sx, sy = snap(start[0]), snap(start[1])
    tx, ty = snap(goal[0]), snap(goal[1])
    if (sx, sy) == (tx, ty):
        return []

    comps = list(snapshot.get("components") or [])
    wires = list(snapshot.get("wires") or [])

    min_x = min(sx, tx) - 160
    max_x = max(sx, tx) + 160
    min_y = min(sy, ty) - 160
    max_y = max(sy, ty) + 160

    # Cap search space to keep routing fast (< ~25ms)
    if ((max_x - min_x) // GRID) * ((max_y - min_y) // GRID) > 18000:
        return None

    foreign_ports: Set[Tuple[int, int]] = set()
    comp_boxes: List[Tuple[int, int, int, int]] = []
    for c in comps:
        bx0, by0, bx1, by1 = _comp_box(c, label_pad=True)
        if bx1 + wire_clearance >= min_x and bx0 - wire_clearance <= max_x and by1 + wire_clearance >= min_y and by0 - wire_clearance <= max_y:
            comp_boxes.append((bx0, by0, bx1, by1))
        for p in c.get("ports") or []:
            pt = (snap(int(p["x"])), snap(int(p["y"])))
            if pt != (sx, sy) and pt != (tx, ty):
                foreign_ports.add(pt)

    wire_pts, wire_edges, wire_endpoints = _build_wire_index(wires)

    # Identify source's existing connected trunk net so fanout wires can branch cleanly off it
    src_net_pts: Set[Tuple[int, int]] = {(sx, sy)}
    src_net_edges: Set[Tuple[Tuple[int, int], Tuple[int, int]]] = set()
    bfs_q: List[Tuple[int, int]] = [(sx, sy)]
    while bfs_q:
        u = bfs_q.pop(0)
        for dx, dy in ((GRID, 0), (-GRID, 0), (0, GRID), (0, -GRID)):
            v = (u[0] + dx, u[1] + dy)
            if (u, v) in wire_edges:
                src_net_edges.add((u, v))
                src_net_edges.add((v, u))
                if v not in src_net_pts and v not in foreign_ports:
                    src_net_pts.add(v)
                    bfs_q.append(v)

    blocked_endpoints = wire_endpoints - src_net_pts - {(sx, sy), (tx, ty)}

    clr = max(0, snap(wire_clearance))

    # Build sparse lookup sets for O(1) interior & halo cost inside search window
    interior_pts: Set[Tuple[int, int]] = set()
    halo_pts: Set[Tuple[int, int]] = set()
    for bx0, by0, bx1, by1 in comp_boxes:
        ix0, ix1 = max(min_x, snap(bx0 + GRID)), min(max_x, snap(bx1 - GRID))
        iy0, iy1 = max(min_y, snap(by0 + GRID)), min(max_y, snap(by1 - GRID))
        for x in range(ix0, ix1 + 1, GRID):
            for y in range(iy0, iy1 + 1, GRID):
                interior_pts.add((x, y))
        if clr > 0:
            hx0, hx1 = max(min_x, snap(bx0 - clr)), min(max_x, snap(bx1 + clr))
            hy0, hy1 = max(min_y, snap(by0 - clr)), min(max_y, snap(by1 + clr))
            for x in range(hx0, hx1 + 1, GRID):
                for y in range(hy0, hy1 + 1, GRID):
                    if (x, y) not in interior_pts:
                        halo_pts.add((x, y))

    # Do not penalize halo at the exact starting/ending port cell so normal port egress stays clean
    halo_pts.discard((sx, sy))
    halo_pts.discard((tx, ty))
    interior_pts.discard((sx, sy))
    interior_pts.discard((tx, ty))

    # State: (f, g, x, y, prev_dir_idx); dir_idx 0..3 = E,W,S,N, -1 = start
    dirs = [(GRID, 0), (-GRID, 0), (0, GRID), (0, -GRID)]
    start_state = (sx, sy, -1)
    best_g: Dict[Tuple[int, int, int], int] = {start_state: 0}
    parent: Dict[Tuple[int, int, int], Optional[Tuple[int, int, int]]] = {start_state: None}
    pq: List[Tuple[int, int, int, int, int]] = [
        (abs(tx - sx) + abs(ty - sy), 0, sx, sy, -1)
    ]

    goal_state: Optional[Tuple[int, int, int]] = None
    expansions = 0
    max_expansions = 35000

    while pq and expansions < max_expansions:
        _f, g, x, y, pdir = heapq.heappop(pq)
        cur_state = (x, y, pdir)
        if g > best_g.get(cur_state, 10**12):
            continue
        if (x, y) == (tx, ty):
            goal_state = cur_state
            break
        expansions += 1

        on_unrelated_wire = (
            (x, y) in wire_pts
            and (x, y) not in src_net_pts
            and (x, y) != (sx, sy)
            and (x, y) != (tx, ty)
        )

        for d_idx, (dx, dy) in enumerate(dirs):
            # Never turn a corner on top of an unrelated wire segment (prevents accidental T-junction dots)
            if pdir != -1 and d_idx != pdir and on_unrelated_wire:
                continue
            # Never immediately 180-reverse
            if pdir != -1 and d_idx == (pdir ^ 1):
                continue

            nx, ny = x + dx, y + dy
            if nx < min_x or nx > max_x or ny < min_y or ny > max_y:
                continue

            on_own_trunk = ((x, y), (nx, ny)) in src_net_edges
            if not allow_collision:
                if (nx, ny) in foreign_ports:
                    continue
                if ((x, y), (nx, ny)) in wire_edges and not on_own_trunk:
                    continue
                if (nx, ny) in blocked_endpoints:
                    continue

            step_cost = 2 if on_own_trunk else GRID
            if pdir != -1 and d_idx != pdir and not on_own_trunk:
                step_cost += 22  # Prefer minimal clean bends
            if not on_own_trunk:
                if (nx, ny) in interior_pts:
                    step_cost += 220  # Strongly avoid routing across chip interiors
                elif (nx, ny) in halo_pts:
                    step_cost += 45  # Prefer >= 20px breathing lane outside component walls

            if (nx, ny) in wire_pts and (nx, ny) not in src_net_pts and (nx, ny) != (tx, ty):
                step_cost += 16  # Prefer fewer orthogonal crossings

            # Parallel-wire spacing halo: gently penalize running right beside an existing parallel wire at 10px
            if clr > 0 and not on_own_trunk:
                if dx != 0:  # Moving horizontally: check wires at y +/- GRID
                    if (((x, y - GRID), (nx, ny - GRID)) in wire_edges) or (((x, y + GRID), (nx, ny + GRID)) in wire_edges):
                        step_cost += 25
                else:  # Moving vertically: check wires at x +/- GRID
                    if (((x - GRID, y), (nx - GRID, ny)) in wire_edges) or (((x + GRID, y), (nx + GRID, ny)) in wire_edges):
                        step_cost += 25

            ng = g + step_cost
            nstate = (nx, ny, d_idx)
            if ng < best_g.get(nstate, 10**12):
                best_g[nstate] = ng
                parent[nstate] = cur_state
                h = abs(tx - nx) + abs(ty - ny)
                heapq.heappush(pq, (ng + h, ng, nx, ny, d_idx))

    if goal_state is None:
        return None

    pts: List[Tuple[int, int]] = []
    cur: Optional[Tuple[int, int, int]] = goal_state
    while cur is not None:
        pts.append((cur[0], cur[1]))
        cur = parent.get(cur)
    pts.reverse()
    start_idx = 0
    while start_idx + 1 < len(pts) and (pts[start_idx], pts[start_idx + 1]) in src_net_edges:
        start_idx += 1
    return _merge_path_segments(pts[start_idx:])


def route_wire_spaced(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Optional[Dict[str, Any]]:
    """Route `connect(mode='wire', route='auto')` using clearance-aware A*, or return None for Java fallback."""
    mode = str(params.get("mode") or "wire").strip().lower()
    route = str(params.get("route") or "auto").strip().lower()
    if mode != "wire" or route != "auto" or bool(params.get("tight", False)):
        return None

    circuit = params.get("circuit")
    snapshot = fetch_circuit_snapshot(client, circuit, include_ports=True, include_wires=True)
    ep1 = _resolve_endpoint(snapshot, params, "from", batch_refs)
    ep2 = _resolve_endpoint(snapshot, params, "to", batch_refs)
    if ep1 is None or ep2 is None:
        return None

    sx, sy, w1, _ = ep1
    tx, ty, w2, _ = ep2
    if w1 > 0 and w2 > 0 and w1 != w2:
        # Defer to Java bridge so exact width-mismatch error/semantics stay identical
        return None

    allow_collision = bool(params.get("allow_collision", False))
    clearance = int(params.get("wire_clearance", DEFAULT_WIRE_CLEARANCE))
    segs = find_spaced_wire_path(snapshot, (sx, sy), (tx, ty), allow_collision=allow_collision, wire_clearance=clearance)
    if segs is None:
        return None

    if segs:
        wire_params: Dict[str, Any] = {"segments": segs}
        if circuit:
            wire_params["circuit"] = circuit
        client.call("add_wires", wire_params, timeout=60.0)

    return {
        "from": {"x": sx, "y": sy},
        "to": {"x": tx, "y": ty},
        "mode": "wire",
        "route": "auto_spaced",
        "segments_added": len(segs),
        "segments": segs,
    }


def _sanitize_net_name(raw: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]", "_", raw.strip())
    return cleaned or "net"


def _unique_tunnel_name(snapshot: Dict[str, Any], src: Optional[Dict[str, Any]], dst: Optional[Dict[str, Any]]) -> str:
    existing = {
        str(c.get("label") or "").strip()
        for c in snapshot.get("components") or []
        if c.get("type") == "Tunnel" and c.get("label")
    }
    base = "net"
    if src and src.get("label"):
        base = _sanitize_net_name(str(src["label"]))
    elif dst and dst.get("label"):
        base = _sanitize_net_name(str(dst["label"]))
    if base not in existing:
        return base
    for i in range(1, 1000):
        cand = f"{base}_{i}"
        if cand not in existing:
            return cand
    return f"{base}_t"


def _has_connected_tunnel(
    snapshot: Dict[str, Any],
    port_pt: Tuple[int, int],
    label: str,
) -> bool:
    comps = list(snapshot.get("components") or [])
    adj: Dict[Tuple[int, int], List[Tuple[int, int]]] = {}
    for w in snapshot.get("wires") or []:
        a = (snap(int(w["x1"])), snap(int(w["y1"])))
        b = (snap(int(w["x2"])), snap(int(w["y2"])))
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)

    visited = {port_pt}
    q = [port_pt]
    while q:
        cur = q.pop(0)
        for c in comps:
            if c.get("type") == "Tunnel" and str(c.get("label") or "") == label:
                if (snap(int(c.get("x", 0))), snap(int(c.get("y", 0)))) == cur:
                    return True
        for nxt in adj.get(cur, []):
            if nxt not in visited:
                visited.add(nxt)
                q.append(nxt)
    return False


def _ordered_outward_dirs(port_pt: Tuple[int, int], comp: Optional[Dict[str, Any]]) -> List[str]:
    if comp is None:
        return ["east", "west", "south", "north"]
    bx0, by0, bx1, by1 = _comp_box(comp)
    cx = (bx0 + bx1) / 2.0
    cy = (by0 + by1) / 2.0
    dx = port_pt[0] - cx
    dy = port_pt[1] - cy
    if abs(dx) >= abs(dy):
        return [
            "east" if dx >= 0 else "west",
            "south" if dy >= 0 else "north",
            "north" if dy >= 0 else "south",
            "west" if dx >= 0 else "east",
        ]
    return [
        "south" if dy >= 0 else "north",
        "east" if dx >= 0 else "west",
        "west" if dx >= 0 else "east",
        "north" if dy >= 0 else "south",
    ]


def _estimate_tunnel_box(pt: Tuple[int, int], tunnel_facing: str, label: str) -> Tuple[int, int, int, int]:
    tx, ty = pt
    tw = max(30, len(label) * 8 + 16)
    if tunnel_facing == "west":  # Extends east from tip
        return (tx, ty - 9, tx + tw, ty + 9)
    if tunnel_facing == "east":  # Extends west from tip
        return (tx - tw, ty - 9, tx, ty + 9)
    if tunnel_facing == "north":  # Extends south from tip
        return (tx - 9, ty, tx + 9, ty + tw)
    return (tx - 9, ty - tw, tx + 9, ty)  # facing south -> extends north


def _segments_intersect_port_or_wire(
    segs: List[Dict[str, int]],
    foreign_ports: Set[Tuple[int, int]],
    wire_pts: Set[Tuple[int, int]],
) -> bool:
    for s in segs:
        x1, y1, x2, y2 = s["x1"], s["y1"], s["x2"], s["y2"]
        if x1 == x2:
            lo, hi = min(y1, y2), max(y1, y2)
            for y in range(lo, hi + 1, GRID):
                pt = (x1, y)
                if pt in foreign_ports:
                    return True
                if pt != (x1, y1) and pt in wire_pts:
                    return True
        else:
            lo, hi = min(x1, x2), max(x1, x2)
            for x in range(lo, hi + 1, GRID):
                pt = (x, y1)
                if pt in foreign_ports:
                    return True
                if pt != (x1, y1) and pt in wire_pts:
                    return True
    return False


def connect_tunnel_spaced(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Optional[Dict[str, Any]]:
    """Place collision-free, comfortably spaced Tunnel pairs for `connect(mode='tunnel')`."""
    mode = str(params.get("mode") or "wire").strip().lower()
    if mode != "tunnel" or bool(params.get("tight", False)):
        return None

    circuit = params.get("circuit")
    snapshot = fetch_circuit_snapshot(client, circuit, include_ports=True, include_wires=True)
    ep1 = _resolve_endpoint(snapshot, params, "from", batch_refs)
    ep2 = _resolve_endpoint(snapshot, params, "to", batch_refs)
    if ep1 is None or ep2 is None:
        return None

    sx, sy, w1, c1 = ep1
    tx, ty, w2, c2 = ep2
    if w1 > 0 and w2 > 0 and w1 != w2:
        return None
    width = w1 if w1 > 0 else (w2 if w2 > 0 else 1)

    t_name = str(params.get("tunnel_name") or "").strip() or _unique_tunnel_name(snapshot, c1, c2)

    comps = list(snapshot.get("components") or [])
    existing_boxes: List[Tuple[int, int, int, int]] = [_comp_box(c, label_pad=True) for c in comps]
    foreign_ports: Set[Tuple[int, int]] = {
        (snap(int(p["x"])), snap(int(p["y"])))
        for c in comps
        for p in (c.get("ports") or [])
    }
    wire_pts, _, _ = _build_wire_index(snapshot.get("wires") or [])

    added_comps: List[str] = []
    added_wires: List[Dict[str, int]] = []

    def place_one(port_pt: Tuple[int, int], owner: Optional[Dict[str, Any]]) -> None:
        if _has_connected_tunnel(snapshot, port_pt, t_name):
            return
        dirs = _ordered_outward_dirs(port_pt, owner)
        local_foreign = foreign_ports - {port_pt}

        best_choice: Optional[Tuple[int, Tuple[int, int], str, List[Dict[str, int]], Tuple[int, int, int, int]]] = None
        for d_rank, d in enumerate(dirs):
            vx, vy = _DIR_VECTORS[d]
            px_vec, py_vec = (-vy, vx)  # perpendicular axis
            t_facing = _OPP_DIR[d]
            for dist in (30, 40, 20, 50):
                for jog in (0, -30, 30, -20, 20, -60, 60):
                    cand = (
                        snap(port_pt[0] + vx * dist + px_vec * jog),
                        snap(port_pt[1] + vy * dist + py_vec * jog),
                    )
                    if cand in foreign_ports:
                        continue
                    t_box = _estimate_tunnel_box(cand, t_facing, t_name)
                    if any(_boxes_close(t_box, eb, gap_x=10, gap_y=10) for eb in existing_boxes):
                        continue

                    if jog == 0:
                        segs = [{"x1": port_pt[0], "y1": port_pt[1], "x2": cand[0], "y2": cand[1]}]
                    else:
                        elbow1 = (snap(port_pt[0] + vx * 20), snap(port_pt[1] + vy * 20))
                        elbow2 = (
                            snap(elbow1[0] + px_vec * jog),
                            snap(elbow1[1] + py_vec * jog),
                        )
                        segs = _merge_path_segments([port_pt, elbow1, elbow2, cand])

                    if _segments_intersect_port_or_wire(segs, local_foreign, wire_pts):
                        continue

                    score = d_rank * 40 + abs(dist - 30) + abs(jog) * 2
                    if best_choice is None or score < best_choice[0]:
                        best_choice = (score, cand, t_facing, segs, t_box)
                if best_choice is not None and d_rank == 0 and best_choice[0] <= 10:
                    break
            if best_choice is not None and best_choice[0] <= 20:
                break

        if best_choice is None:
            # Ultimate clean outward fallback at 30px
            d = dirs[0]
            vx, vy = _DIR_VECTORS[d]
            cand = (snap(port_pt[0] + vx * 30), snap(port_pt[1] + vy * 30))
            t_facing = _OPP_DIR[d]
            segs = [{"x1": port_pt[0], "y1": port_pt[1], "x2": cand[0], "y2": cand[1]}]
            t_box = _estimate_tunnel_box(cand, t_facing, t_name)
        else:
            _, cand, t_facing, segs, t_box = best_choice

        add_params: Dict[str, Any] = {
            "type": "Tunnel",
            "lib": "Wiring",
            "x": cand[0],
            "y": cand[1],
            "attrs": {"facing": t_facing, "width": width, "label": t_name},
        }
        if circuit:
            add_params["circuit"] = circuit
        t_res = client.call("add_component", add_params, timeout=60.0)
        added_comps.append(str(t_res.get("id") or f"Tunnel@{cand[0]},{cand[1]}"))
        existing_boxes.append(t_box)
        foreign_ports.add(cand)

        if segs:
            w_params: Dict[str, Any] = {"segments": segs}
            if circuit:
                w_params["circuit"] = circuit
            client.call("add_wires", w_params, timeout=60.0)
            added_wires.extend(segs)

    place_one((sx, sy), c1)
    place_one((tx, ty), c2)

    return {
        "from": {"x": sx, "y": sy},
        "to": {"x": tx, "y": ty},
        "mode": "tunnel",
        "tunnel_name": t_name,
        "width": width,
        "components_added": added_comps,
        "segments_added": len(added_wires),
        "segments": added_wires,
    }


def _collect_local_wire_tree(
    wires: List[Dict[str, Any]],
    start_pt: Tuple[int, int],
    end_pt: Tuple[int, int],
    forbidden_pts: Set[Tuple[int, int]],
) -> List[Dict[str, Any]]:
    """Trace exact wire segments belonging to one splitter-branch-to-tunnel path."""
    if start_pt == end_pt:
        return []
    adj: Dict[Tuple[int, int], List[Tuple[Tuple[int, int], Dict[str, Any]]]] = {}
    for w in wires:
        a = (snap(int(w["x1"])), snap(int(w["y1"])))
        b = (snap(int(w["x2"])), snap(int(w["y2"])))
        adj.setdefault(a, []).append((b, w))
        adj.setdefault(b, []).append((a, w))

    parent: Dict[Tuple[int, int], Tuple[Tuple[int, int], Dict[str, Any]]] = {}
    visited = {start_pt}
    q = [start_pt]
    found = False
    while q and not found:
        cur = q.pop(0)
        for nxt, seg in adj.get(cur, []):
            if nxt in visited or nxt in forbidden_pts:
                continue
            visited.add(nxt)
            parent[nxt] = (cur, seg)
            if nxt == end_pt:
                found = True
                break
            q.append(nxt)

    if not found:
        return []
    collected: List[Dict[str, Any]] = []
    cur = end_pt
    while cur != start_pt:
        prev, seg = parent[cur]
        collected.append({"x1": int(seg["x1"]), "y1": int(seg["y1"]), "x2": int(seg["x2"]), "y2": int(seg["y2"])})
        cur = prev
    return collected


def fanout_tunnels_spaced(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """Run `fanout_tunnels` with comfortable default tunnel_offset=40 and pitch=30px."""
    bridge_params = dict(params)
    pitch = snap(max(20, min(100, int(bridge_params.pop("pitch", DEFAULT_TUNNEL_PITCH)))))
    if "tunnel_offset" not in bridge_params:
        bridge_params["tunnel_offset"] = DEFAULT_TUNNEL_OFFSET

    # Resolve optional `from` batch alias before forwarding
    if batch_refs and isinstance(bridge_params.get("from"), str):
        f = bridge_params["from"].strip()
        if f in batch_refs:
            bridge_params["from"] = batch_refs[f]

    res = client.call("fanout_tunnels", bridge_params, timeout=60.0)
    if batch_refs is not None and isinstance(bridge_params.get("ref"), str) and bridge_params["ref"].strip():
        if res.get("splitter_id"):
            batch_refs[bridge_params["ref"].strip()] = str(res["splitter_id"])

    tunnels = list(res.get("tunnels") or [])
    if pitch <= 20 or len(tunnels) <= 1:
        res["pitch"] = pitch
        return res

    circuit = bridge_params.get("circuit")
    splitter_id = str(res.get("splitter_id") or "")
    snapshot = fetch_circuit_snapshot(client, circuit, include_ports=True, include_wires=True)
    comps = list(snapshot.get("components") or [])
    wires = list(snapshot.get("wires") or [])

    splitter = next((c for c in comps if c.get("id") == splitter_id), None)
    if splitter is None:
        return res

    s_facing = str((splitter.get("attrs") or {}).get("facing") or "east").lower()
    if s_facing not in _DIR_VECTORS:
        s_facing = "east"
    horiz = s_facing in ("east", "west")
    vx, vy = _DIR_VECTORS[s_facing]

    s_ports = list(splitter.get("ports") or [])
    if len(s_ports) < len(tunnels) + 1:
        return res

    branch_pts = [(snap(int(p["x"])), snap(int(p["y"]))) for p in s_ports[1 : 1 + len(tunnels)]]
    branch_perp = [pt[1] if horiz else pt[0] for pt in branch_pts]

    center_branch = (branch_perp[0] + branch_perp[-1]) / 2.0
    start_perp = snap(center_branch - ((len(tunnels) - 1) * pitch) / 2.0)
    target_perp = [start_perp + i * pitch for i in range(len(tunnels))]

    up_count = sum(1 for i, tp in enumerate(target_perp) if tp < branch_perp[i])
    down_count = sum(1 for i, tp in enumerate(target_perp) if tp > branch_perp[i])
    min_req_offset = (max(up_count, down_count) + 2) * GRID
    req_offset = max(int(bridge_params.get("tunnel_offset", DEFAULT_TUNNEL_OFFSET)), min_req_offset)
    eff_offset = snap(req_offset)

    # Bump offset outward slightly if any spaced tunnel would crowd an unrelated existing component
    tunnel_ids = {str(t["id"]) for t in tunnels}
    other_boxes = [_comp_box(c, label_pad=True) for c in comps if c.get("id") not in tunnel_ids and c.get("id") != splitter_id]
    t_facing = _OPP_DIR[s_facing]
    for extra in (0, 20, 40, 60):
        cand_offset = eff_offset + extra
        clash = False
        for idx, tm in enumerate(tunnels):
            bp = branch_pts[idx]
            cand_pt = (
                (bp[0] + vx * cand_offset, target_perp[idx])
                if horiz
                else (target_perp[idx], bp[1] + vy * cand_offset)
            )
            t_box = _estimate_tunnel_box(cand_pt, t_facing, str(tm.get("name") or ""))
            if any(_boxes_close(t_box, ob, 10, 10) for ob in other_boxes):
                clash = True
                break
        if not clash:
            eff_offset = cand_offset
            break

    # Remove old narrow-pitch fanout wires cleanly
    old_pts = [(snap(int(t["x"])), snap(int(t["y"]))) for t in tunnels]
    splitter_com = (snap(int(s_ports[0]["x"])), snap(int(s_ports[0]["y"])))
    wires_to_delete: List[Dict[str, int]] = []
    seen_seg_keys: Set[Tuple[int, int, int, int]] = set()
    for idx in range(len(tunnels)):
        segs = _collect_local_wire_tree(wires, branch_pts[idx], old_pts[idx], {splitter_com})
        for s in segs:
            key = (s["x1"], s["y1"], s["x2"], s["y2"])
            if key not in seen_seg_keys:
                seen_seg_keys.add(key)
                wires_to_delete.append(s)

    if wires_to_delete:
        del_params: Dict[str, Any] = {"wires": wires_to_delete}
        if circuit:
            del_params["circuit"] = circuit
        client.call("delete", del_params, timeout=60.0)

    # Move tunnels and add clean staggered non-crossing elbow wires
    new_wires: List[Dict[str, int]] = []
    up_seen = 0
    down_rem = down_count
    for idx, tm in enumerate(tunnels):
        bp = branch_pts[idx]
        dst = (
            (snap(bp[0] + vx * eff_offset), snap(target_perp[idx]))
            if horiz
            else (snap(target_perp[idx]), snap(bp[1] + vy * eff_offset))
        )
        old_id = str(tm["id"])
        if (snap(int(tm["x"])), snap(int(tm["y"]))) != dst:
            mv_params: Dict[str, Any] = {"id": old_id, "x": dst[0], "y": dst[1]}
            if circuit:
                mv_params["circuit"] = circuit
            mv_res = client.call("move_component", mv_params, timeout=60.0)
            tm["id"] = str(mv_res.get("new_id") or old_id)
            tm["x"] = dst[0]
            tm["y"] = dst[1]

        if target_perp[idx] == branch_perp[idx]:
            new_wires.append({"x1": bp[0], "y1": bp[1], "x2": dst[0], "y2": dst[1]})
        else:
            if target_perp[idx] < branch_perp[idx]:
                up_seen += 1
                jog_steps = up_seen
            else:
                jog_steps = down_rem
                down_rem -= 1
            elbow1 = (snap(bp[0] + vx * jog_steps * GRID), snap(bp[1] + vy * jog_steps * GRID))
            elbow2 = (elbow1[0], dst[1]) if horiz else (dst[0], elbow1[1])
            new_wires.extend(_merge_path_segments([bp, elbow1, elbow2, dst]))

    if new_wires:
        w_params: Dict[str, Any] = {"segments": new_wires}
        if circuit:
            w_params["circuit"] = circuit
        client.call("add_wires", w_params, timeout=60.0)

    res["pitch"] = pitch
    res["tunnel_offset"] = eff_offset
    res["tunnels"] = tunnels
    return res


def _cluster_wire_nets(
    wires: List[Dict[str, Any]],
    comps: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Group wires into electrical nets via Union-Find and attach connected component ports."""
    parent_uf: Dict[Tuple[int, int], Tuple[int, int]] = {}

    def find(pt: Tuple[int, int]) -> Tuple[int, int]:
        while parent_uf.get(pt, pt) != pt:
            parent_uf[pt] = parent_uf.get(parent_uf[pt], parent_uf[pt])
            pt = parent_uf[pt]
        return pt

    def union(a: Tuple[int, int], b: Tuple[int, int]) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent_uf[rb] = ra

    norm_wires: List[Tuple[Tuple[int, int], Tuple[int, int], Dict[str, int]]] = []
    for w in wires:
        a = (snap(int(w["x1"])), snap(int(w["y1"])))
        b = (snap(int(w["x2"])), snap(int(w["y2"])))
        parent_uf.setdefault(a, a)
        parent_uf.setdefault(b, b)
        union(a, b)
        norm_wires.append((a, b, {"x1": int(w["x1"]), "y1": int(w["y1"]), "x2": int(w["x2"]), "y2": int(w["y2"])}))

    # Split collinear wires along T-junctions if a vertex touches an interior point of a segment
    all_pts = list(parent_uf.keys())
    for a, b, _ in norm_wires:
        if a[1] == b[1]:
            lo, hi = min(a[0], b[0]), max(a[0], b[0])
            for pt in all_pts:
                if pt[1] == a[1] and lo < pt[0] < hi:
                    union(a, pt)
        elif a[0] == b[0]:
            lo, hi = min(a[1], b[1]), max(a[1], b[1])
            for pt in all_pts:
                if pt[0] == a[0] and lo < pt[1] < hi:
                    union(a, pt)

    # Also record ports that sit on any wire vertex
    port_at: Dict[Tuple[int, int], List[Tuple[int, int, str]]] = {}
    for c_idx, c in enumerate(comps):
        for p in c.get("ports") or []:
            pt = (snap(int(p["x"])), snap(int(p["y"])))
            port_at.setdefault(pt, []).append((c_idx, int(p.get("index", 0)), str(p.get("dir") or "in")))

    nets: Dict[Tuple[int, int], Dict[str, Any]] = {}
    for a, b, raw in norm_wires:
        root = find(a)
        net = nets.setdefault(root, {"wires": [], "ports": set()})
        net["wires"].append(raw)

    for pt in list(parent_uf.keys()):
        root = find(pt)
        if root in nets and pt in port_at:
            for item in port_at[pt]:
                nets[root]["ports"].add(item)

    return list(nets.values())


def tidy_circuit(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """De-crowd components gently while preserving alignment and re-routing attached wires cleanly."""
    circuit = params.get("circuit")
    gap_x = max(20, snap(int(params.get("gap_x", DEFAULT_GAP_X))))
    gap_y = max(20, snap(int(params.get("gap_y", DEFAULT_GAP_Y))))
    reroute_wires = bool(params.get("reroute_wires", True))

    snapshot = fetch_circuit_snapshot(client, circuit, include_ports=True, include_wires=True)
    comps = list(snapshot.get("components") or [])
    wires = list(snapshot.get("wires") or [])
    if len(comps) <= 1:
        return {"mode": "tidy", "moved_count": 0, "rerouted_nets": 0, "moved": []}

    # Determine movable subset
    target_indices: Set[int] = set(range(len(comps)))
    req_comps = params.get("components")
    if isinstance(req_comps, list) and req_comps:
        target_indices = set()
        for q in req_comps:
            found = _resolve_component_in_snapshot(comps, str(q), batch_refs)
            if found is not None:
                for idx, c in enumerate(comps):
                    if c.get("id") == found.get("id"):
                        target_indices.add(idx)

    cur_pos = [(snap(int(c["x"])), snap(int(c["y"]))) for c in comps]
    orig_pos = list(cur_pos)
    boxes = [_comp_box(c, label_pad=True) for c in comps]

    def shifted_box(idx: int) -> Tuple[int, int, int, int]:
        dx = cur_pos[idx][0] - orig_pos[idx][0]
        dy = cur_pos[idx][1] - orig_pos[idx][1]
        b = boxes[idx]
        return (b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy)

    # Order left-to-right, top-to-bottom so dataflow naturally pushes downstream blocks rightward/downward
    order = sorted(range(len(comps)), key=lambda i: (orig_pos[i][0], orig_pos[i][1]))

    for _sweep in range(24):
        moved_any = False
        for pos_a in range(len(order)):
            i = order[pos_a]
            ci = comps[i]
            ti = str(ci.get("type") or "")
            bi = shifted_box(i)
            hi = bi[3] - bi[1]
            for pos_b in range(pos_a + 1, len(order)):
                j = order[pos_b]
                if i not in target_indices and j not in target_indices:
                    continue
                cj = comps[j]
                tj = str(cj.get("type") or "")
                bj = shifted_box(j)
                hj = bj[3] - bj[1]

                both_small = (ti in _SMALL_IO_TYPES and tj in _SMALL_IO_TYPES)
                req_gx = 20 if both_small else gap_x
                req_gy = max(12, gap_y - 10) if (both_small and max(hi, hj) <= 24) else gap_y

                x_gap = max(bj[0] - bi[2], bi[0] - bj[2])
                y_gap = max(bj[1] - bi[3], bi[1] - bj[3])
                if x_gap >= req_gx or y_gap >= req_gy:
                    continue

                need_x = snap(req_gx - x_gap + (GRID - 1)) if x_gap < req_gx else 0
                if need_x == 0 and x_gap < req_gx:
                    need_x = GRID
                need_y = snap(req_gy - y_gap + (GRID - 1)) if y_gap < req_gy else 0
                if need_y == 0 and y_gap < req_gy:
                    need_y = GRID

                # Choose smaller axis push (keeps rows/columns aligned cleanly)
                push_idx = j if j in target_indices else i
                sign = 1 if push_idx == j else -1

                if need_x <= need_y:
                    # Push horizontally away from the earlier block
                    step = need_x * sign
                    if step == 0:
                        step = GRID * sign
                    cur_pos[push_idx] = (cur_pos[push_idx][0] + step, cur_pos[push_idx][1])
                else:
                    dir_y = 1 if (bj[1] + bj[3]) >= (bi[1] + bi[3]) else -1
                    step = need_y * dir_y * sign
                    if step == 0:
                        step = GRID * dir_y * sign
                    cur_pos[push_idx] = (cur_pos[push_idx][0], cur_pos[push_idx][1] + step)

                bi = shifted_box(i)
                moved_any = True
        if not moved_any:
            break

    # Keep circuit shifted strictly >= 20px positive coordinates if anything pushed negative
    min_bx = min(shifted_box(i)[0] for i in range(len(comps)))
    min_by = min(shifted_box(i)[1] for i in range(len(comps)))
    shift_x = snap(40 - min_bx) if min_bx < 20 else 0
    shift_y = snap(40 - min_by) if min_by < 20 else 0
    if shift_x or shift_y:
        cur_pos = [(p[0] + shift_x, p[1] + shift_y) for p in cur_pos]

    moved_indices = {i for i in range(len(comps)) if cur_pos[i] != orig_pos[i]}
    if not moved_indices:
        return {"mode": "tidy", "moved_count": 0, "rerouted_nets": 0, "moved": []}

    # Snapshot electrical nets BEFORE moving components
    nets = _cluster_wire_nets(wires, comps) if reroute_wires else []
    affected_nets = [
        net for net in nets
        if any(c_idx in moved_indices for (c_idx, _p_idx, _d) in net["ports"])
    ]

    # Delete wires on affected nets first so dangling stubs don't linger
    wires_to_del: List[Dict[str, int]] = []
    for net in affected_nets:
        wires_to_del.extend(net["wires"])
    if wires_to_del:
        del_p: Dict[str, Any] = {"wires": wires_to_del}
        if circuit:
            del_p["circuit"] = circuit
        client.call("delete", del_p, timeout=60.0)

    # Apply component moves & track old_id -> new_id
    id_map: Dict[int, str] = {}
    moved_records: List[Dict[str, Any]] = []
    for idx in sorted(moved_indices):
        old_id = str(comps[idx]["id"])
        nx, ny = cur_pos[idx]
        mv_p: Dict[str, Any] = {"id": old_id, "x": nx, "y": ny}
        if circuit:
            mv_p["circuit"] = circuit
        mv_res = client.call("move_component", mv_p, timeout=60.0)
        new_id = str(mv_res.get("new_id") or old_id)
        id_map[idx] = new_id
        if batch_refs:
            for r_k, r_v in list(batch_refs.items()):
                if r_v == old_id:
                    batch_refs[r_k] = new_id
        moved_records.append({"old_id": old_id, "new_id": new_id, "x": nx, "y": ny})

    rerouted = 0
    if reroute_wires and affected_nets:
        new_snap = fetch_circuit_snapshot(client, circuit, include_ports=True, include_wires=True)
        new_comps_by_id = {c["id"]: c for c in new_snap.get("components") or []}

        for net in affected_nets:
            resolved_pts: List[Tuple[int, int, str]] = []
            for c_idx, p_idx, p_dir in sorted(net["ports"]):
                cid = id_map.get(c_idx, str(comps[c_idx]["id"]))
                nc = new_comps_by_id.get(cid)
                if nc is None:
                    continue
                port = _resolve_port_in_comp(nc, p_idx)
                if port is not None:
                    resolved_pts.append((snap(int(port["x"])), snap(int(port["y"])), p_dir))

            if len(resolved_pts) >= 2:
                # Connect net via minimum-length star/MST using clearance-aware A*
                connected = [resolved_pts[0][:2]]
                remaining = [pt[:2] for pt in resolved_pts[1:]]
                while remaining:
                    best_pair: Optional[Tuple[int, Tuple[int, int], Tuple[int, int]]] = None
                    for u in connected:
                        for v in remaining:
                            d = abs(u[0] - v[0]) + abs(u[1] - v[1])
                            if best_pair is None or d < best_pair[0]:
                                best_pair = (d, u, v)
                    assert best_pair is not None
                    _, u, v = best_pair
                    remaining.remove(v)
                    connected.append(v)

                    segs = find_spaced_wire_path(new_snap, u, v, allow_collision=False, wire_clearance=DEFAULT_WIRE_CLEARANCE)
                    if segs is None:
                        c_p: Dict[str, Any] = {
                            "from_x": u[0],
                            "from_y": u[1],
                            "to_x": v[0],
                            "to_y": v[1],
                            "mode": "wire",
                            "route": "auto",
                        }
                        if circuit:
                            c_p["circuit"] = circuit
                        client.call("connect", c_p, timeout=60.0)
                    elif segs:
                        w_p: Dict[str, Any] = {"segments": segs}
                        if circuit:
                            w_p["circuit"] = circuit
                        client.call("add_wires", w_p, timeout=60.0)
                        new_snap["wires"].extend(segs)
                rerouted += 1
            elif len(net["ports"]) == 1:
                # Single-ended stub attached to a moved component: translate wires rigidly
                only_c_idx = next(iter(net["ports"]))[0]
                dx = cur_pos[only_c_idx][0] - orig_pos[only_c_idx][0]
                dy = cur_pos[only_c_idx][1] - orig_pos[only_c_idx][1]
                shifted_segs = [
                    {
                        "x1": w["x1"] + dx,
                        "y1": w["y1"] + dy,
                        "x2": w["x2"] + dx,
                        "y2": w["y2"] + dy,
                    }
                    for w in net["wires"]
                ]
                if shifted_segs:
                    w_p = {"segments": shifted_segs}
                    if circuit:
                        w_p["circuit"] = circuit
                    client.call("add_wires", w_p, timeout=60.0)

    return {
        "mode": "tidy",
        "gap_x": gap_x,
        "gap_y": gap_y,
        "moved_count": len(moved_records),
        "rerouted_nets": rerouted,
        "moved": moved_records,
    }


def _find_free_slot(
    existing_boxes: List[Tuple[int, int, int, int]],
    orig_x: int,
    orig_y: int,
    box_at_origin: Tuple[int, int, int, int],
    gap_x: int,
    gap_y: int,
) -> Tuple[int, int]:
    """Find nearest snapped (x, y) near (orig_x, orig_y) clear of existing_boxes by (gap_x, gap_y)."""
    ox0, oy0, ox1, oy1 = box_at_origin
    if not any(_boxes_close((ox0, oy0, ox1, oy1), eb, gap_x, gap_y) for eb in existing_boxes):
        return orig_x, orig_y

    for radius in range(GRID, 360, GRID):
        for dy in (0, radius, -radius, radius // 2, -radius // 2):
            for dx in (0, radius, radius // 2, -radius):
                if dx == 0 and dy == 0:
                    continue
                nx, ny = snap(orig_x + dx), snap(orig_y + dy)
                if nx < 20 or ny < 20:
                    continue
                cand_box = (ox0 + (nx - orig_x), oy0 + (ny - orig_y), ox1 + (nx - orig_x), oy1 + (ny - orig_y))
                if not any(_boxes_close(cand_box, eb, gap_x, gap_y) for eb in existing_boxes):
                    return nx, ny
    return orig_x, orig_y


def add_component_spaced(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """Place component; optionally nudge via `auto_space=true`, and report crowding warnings if needed."""
    call_params = dict(params)
    auto_space = bool(call_params.pop("auto_space", False))
    circuit = call_params.get("circuit")

    if auto_space:
        snapshot = fetch_circuit_snapshot(client, circuit, include_ports=False, include_wires=False)
        existing = [_comp_box(c, label_pad=True) for c in snapshot.get("components") or []]
        desc = client.call(
            "describe_component",
            {k: v for k, v in call_params.items() if k in ("type", "lib", "attrs")},
            timeout=30.0,
        )
        ob = desc.get("offset_bounds") or {}
        ox = int(ob.get("x", -10))
        oy = int(ob.get("y", -10))
        ow = max(20, int(ob.get("width", 20)))
        oh = max(20, int(ob.get("height", 20)))
        px, py = snap(int(call_params["x"])), snap(int(call_params["y"]))
        nx, ny = _find_free_slot(existing, px, py, (px + ox, py + oy, px + ox + ow, py + oy + oh), DEFAULT_GAP_X, DEFAULT_GAP_Y)
        call_params["x"] = nx
        call_params["y"] = ny

    res = client.call("add_component", call_params, timeout=60.0)
    if batch_refs is not None and isinstance(call_params.get("ref"), str) and call_params["ref"].strip():
        batch_refs[call_params["ref"].strip()] = str(res["id"])
    return res


def add_components_spaced(
    client: Any,
    params: Dict[str, Any],
    batch_refs: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """Place multiple components; supports `auto_space=true` and emits concise `layout_warnings`."""
    call_params = dict(params)
    auto_space = bool(call_params.pop("auto_space", False))
    circuit = call_params.get("circuit")
    items = [dict(it) for it in (call_params.get("items") or [])]

    if auto_space and items:
        snapshot = fetch_circuit_snapshot(client, circuit, include_ports=False, include_wires=False)
        occupied = [_comp_box(c, label_pad=True) for c in snapshot.get("components") or []]
        desc_cache: Dict[str, Tuple[int, int, int, int]] = {}
        for it in items:
            cache_key = f"{it.get('lib') or ''}:{it.get('type')}:{sorted((it.get('attrs') or {}).items())}"
            if cache_key not in desc_cache:
                try:
                    desc = client.call(
                        "describe_component",
                        {k: v for k, v in it.items() if k in ("type", "lib", "attrs")},
                        timeout=30.0,
                    )
                    ob = desc.get("offset_bounds") or {}
                    desc_cache[cache_key] = (
                        int(ob.get("x", -10)),
                        int(ob.get("y", -10)),
                        max(20, int(ob.get("width", 20))),
                        max(20, int(ob.get("height", 20))),
                    )
                except Exception:
                    desc_cache[cache_key] = (-10, -10, 20, 20)
            ox, oy, ow, oh = desc_cache[cache_key]
            px, py = snap(int(it["x"])), snap(int(it["y"]))
            cand_box = (px + ox, py + oy, px + ox + ow, py + oy + oh)
            nx, ny = _find_free_slot(occupied, px, py, cand_box, DEFAULT_GAP_X, DEFAULT_GAP_Y)
            it["x"] = nx
            it["y"] = ny
            occupied.append((nx + ox, ny + oy, nx + ox + ow, ny + oy + oh))
        call_params["items"] = items

    res = client.call("add_components", call_params, timeout=60.0)
    if batch_refs is not None:
        for created in res.get("components") or []:
            r = created.get("ref")
            if isinstance(r, str) and r.strip() and created.get("id"):
                batch_refs[r.strip()] = str(created["id"])

    # Emit non-blocking layout advisory if placed circuit is cramped
    try:
        snap_after = fetch_circuit_snapshot(client, circuit, include_ports=False, include_wires=False)
        warnings = detect_crowding(snap_after)
        if warnings:
            res["layout_warnings"] = warnings
    except Exception:
        pass
    return res


def _resolve_batch_refs(step: Dict[str, Any], batch_refs: Dict[str, str]) -> Dict[str, Any]:
    out = dict(step)
    for key in ("from", "to", "component", "id"):
        v = out.get(key)
        if isinstance(v, str) and v.strip() in batch_refs:
            out[key] = batch_refs[v.strip()]
    for list_key in ("ids", "components"):
        lst = out.get(list_key)
        if isinstance(lst, list):
            out[list_key] = [
                batch_refs[x.strip()] if isinstance(x, str) and x.strip() in batch_refs else x
                for x in lst
            ]
    return out


def execute_batch_spaced(
    client: Any,
    args: Dict[str, Any],
    single_dispatcher: Callable[[str, Dict[str, Any], Optional[Dict[str, str]]], Any],
) -> Dict[str, Any]:
    """Execute `logisim_batch` steps in Python so spaced routing, fanout & tidy work seamlessly inside batches."""
    ops = list(args.get("operations") or [])
    stop_on_error = args.get("stop_on_error", True) is not False
    default_circuit = args.get("circuit")
    tidy_after = bool(args.get("tidy_after", False))

    batch_refs: Dict[str, str] = {}
    step_results: List[Dict[str, Any]] = []
    succeeded = 0
    failed = 0

    for idx, raw_op in enumerate(ops):
        op_name = str(raw_op.get("op") or "")
        if op_name == "batch":
            raise ValueError(f"Nested 'batch' operations are not permitted (op index {idx})")

        step = {k: v for k, v in raw_op.items() if k != "op"}
        if default_circuit and "circuit" not in step:
            step["circuit"] = default_circuit
        step = _resolve_batch_refs(step, batch_refs)

        entry: Dict[str, Any] = {"index": idx, "op": op_name}
        try:
            op_res = single_dispatcher(op_name, step, batch_refs)
            entry["ok"] = True
            entry["result"] = op_res
            succeeded += 1
        except Exception as exc:
            failed += 1
            entry["ok"] = False
            entry["error"] = str(exc) or type(exc).__name__
            step_results.append(entry)
            if stop_on_error:
                break
            continue
        step_results.append(entry)

    out: Dict[str, Any] = {
        "ok": failed == 0,
        "total_ops": len(ops),
        "executed": len(step_results),
        "succeeded": succeeded,
        "failed": failed,
        "results": step_results,
    }

    if tidy_after and failed == 0:
        tidy_p: Dict[str, Any] = {"mode": "tidy"}
        if default_circuit:
            tidy_p["circuit"] = default_circuit
        out["tidy"] = tidy_circuit(client, tidy_p, batch_refs)

    if args.get("audit_after"):
        audit_p: Dict[str, Any] = {}
        if default_circuit:
            audit_p["circuit"] = default_circuit
        out["audit"] = client.call("audit_circuit", audit_p, timeout=60.0)

    if args.get("screenshot_after"):
        shot_p: Dict[str, Any] = {"annotate": True}
        if default_circuit:
            shot_p["circuit"] = default_circuit
        out["screenshot"] = client.call("screenshot", shot_p, timeout=60.0)

    if failed == 0 and not tidy_after:
        try:
            snap_after = fetch_circuit_snapshot(client, default_circuit, include_ports=False, include_wires=False)
            warnings = detect_crowding(snap_after)
            if warnings:
                out["layout_warnings"] = warnings
        except Exception:
            pass

    return out


def configure_appearance_spaced(client: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    """Configure clean subcircuit IC box appearance with comfortable pin spacing by default."""
    call_params = dict(params)
    if "pin_spacing" not in call_params:
        explicit_sides = any(k in call_params for k in ("west", "east", "north", "south"))
        if explicit_sides:
            max_side = max(
                len(call_params.get("west") or []),
                len(call_params.get("east") or []),
                len(call_params.get("north") or []),
                len(call_params.get("south") or []),
            )
            call_params["pin_spacing"] = 30 if max_side <= 6 else 20
        else:
            try:
                snap_c = fetch_circuit_snapshot(
                    client, call_params.get("circuit"), include_ports=False, include_wires=False
                )
                pin_count = sum(1 for c in snap_c.get("components") or [] if c.get("type") == "Pin")
                call_params["pin_spacing"] = 30 if pin_count <= 10 else 20
            except Exception:
                call_params["pin_spacing"] = 20
    return client.call("configure_appearance", call_params, timeout=60.0)


def synthesize_circuit_spaced(client: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    """Synthesize combinational logic and apply gentle spacing tidy pass unless `auto_tidy=false`."""
    call_params = dict(params)
    auto_tidy = call_params.pop("auto_tidy", True)
    res = client.call("synthesize_circuit", call_params, timeout=120.0)
    if auto_tidy:
        try:
            tidy_res = tidy_circuit(
                client,
                {
                    "circuit": call_params.get("circuit"),
                    "gap_x": DEFAULT_GAP_X,
                    "gap_y": DEFAULT_GAP_Y,
                    "reroute_wires": True,
                },
            )
            res["tidy"] = tidy_res
        except Exception:
            pass
    return res

