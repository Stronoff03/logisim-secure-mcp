package logisimmcp;

import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.Curve;
import com.cburch.draw.shapes.DrawAttr;
import com.cburch.draw.shapes.Rectangle;
import com.cburch.draw.shapes.RoundRectangle;
import com.cburch.draw.shapes.Text;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.circuit.appear.CircuitAppearance;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.util.StringUtil;

import java.awt.Color;
import java.awt.Font;
import java.util.*;

/**
 * Obstacle-avoiding wire routing, paired tunnel connections, bus fanout-to-tunnels,
 * component arrangement, and custom subcircuit IC appearance builder.
 */
public final class CircuitLayoutOps {
    private CircuitLayoutOps() {}

    public static final class ConnectResult {
        public final List<Wire> wiresAdded = new ArrayList<Wire>();
        public final List<Component> componentsAdded = new ArrayList<Component>();
        public final List<String> warnings = new ArrayList<String>();
        public final List<Location> pathPoints = new ArrayList<Location>();
        public String routeUsed = "";
        public String tunnelLabel = null;
    }

    public static ConnectResult connectSmart(
            Project proj,
            Circuit circ,
            CircuitMutation mut,
            Canvas canvas,
            Location p1,
            Location p2,
            int p1Width,
            int p2Width,
            Component srcComp,
            Component dstComp,
            String mode,
            String route,
            String tunnelName,
            List<Location> explicitWaypoints,
            boolean allowCollision) {

        ConnectResult res = new ConnectResult();
        if (p1Width > 0 && p2Width > 0 && p1Width != p2Width) {
            throw new IllegalArgumentException(
                    "Bit width mismatch: source port is " + p1Width + "-bit, target port is " + p2Width + "-bit. "
                    + "Insert a Splitter or Bit Extender, or update component width attributes first.");
        }

        if ("tunnel".equalsIgnoreCase(mode)) {
            int width = p1Width > 0 ? p1Width : (p2Width > 0 ? p2Width : 1);
            String label = (tunnelName != null && !tunnelName.trim().isEmpty())
                    ? tunnelName.trim()
                    : generateTunnelName(circ, srcComp, dstComp);
            res.tunnelLabel = label;
            placeEndpointTunnel(proj, circ, mut, p1, srcComp, width, label, res);
            if (!p1.equals(p2)) {
                placeEndpointTunnel(proj, circ, mut, p2, dstComp, width, label, res);
            }
            res.routeUsed = "tunnel";
            return res;
        }

        if (p1.equals(p2)) {
            res.routeUsed = "colocated";
            res.pathPoints.add(p1);
            return res;
        }

        String rMode = (route == null || route.trim().isEmpty()) ? "auto" : route.trim().toLowerCase();
        List<Location> pts = null;

        if ("waypoints".equals(rMode) && explicitWaypoints != null && !explicitWaypoints.isEmpty()) {
            pts = new ArrayList<Location>();
            pts.add(p1);
            for (Location wp : explicitWaypoints) {
                pts.add(Location.create(ComponentMeta.snap(wp.getX()), ComponentMeta.snap(wp.getY())));
            }
            pts.add(p2);
            res.routeUsed = "waypoints";
        } else if ("direct".equals(rMode)) {
            pts = Arrays.asList(p1, p2);
            res.routeUsed = "direct";
        } else if ("hv".equals(rMode)) {
            pts = buildHV(p1, p2);
            res.routeUsed = "hv";
        } else if ("vh".equals(rMode)) {
            pts = buildVH(p1, p2);
            res.routeUsed = "vh";
        } else {
            pts = findCleanOrthogonalPath(circ, mut, p1, p2, allowCollision, res);
            res.routeUsed = "auto";
        }

        // Convert polyline points to strictly horizontal/vertical Logisim Wire segments
        List<Location> orthogonalized = orthogonalize(pts);
        orthogonalized = simplifyCollinear(orthogonalized);
        res.pathPoints.addAll(orthogonalized);

        for (int i = 0; i < orthogonalized.size() - 1; i++) {
            Location a = orthogonalized.get(i);
            Location b = orthogonalized.get(i + 1);
            if (a.equals(b)) continue;
            if (a.getX() != b.getX() && a.getY() != b.getY()) {
                Location corner = Location.create(b.getX(), a.getY());
                Wire w1 = Wire.create(a, corner);
                Wire w2 = Wire.create(corner, b);
                mut.add(w1);
                mut.add(w2);
                res.wiresAdded.add(w1);
                res.wiresAdded.add(w2);
            } else {
                Wire w = Wire.create(a, b);
                mut.add(w);
                res.wiresAdded.add(w);
            }
        }
        return res;
    }

    private static boolean hasConnectedTunnel(Circuit circ, Location portLoc, String label) {
        Set<Location> visited = new HashSet<Location>();
        ArrayDeque<Location> q = new ArrayDeque<Location>();
        visited.add(portLoc);
        q.add(portLoc);
        while (!q.isEmpty()) {
            Location cur = q.poll();
            for (Component c : circ.getNonWires()) {
                if ("Tunnel".equals(c.getFactory().getName()) && c.endsAt(cur)) {
                    if (label.equals(ComponentMeta.getComponentLabel(c))) {
                        return true;
                    }
                }
            }
            for (Wire w : circ.getWires()) {
                if (w.contains(cur)) {
                    Location e0 = w.getEnd0();
                    Location e1 = w.getEnd1();
                    if (visited.add(e0)) q.add(e0);
                    if (visited.add(e1)) q.add(e1);
                }
            }
        }
        return false;
    }

    private static void placeEndpointTunnel(
            Project proj, Circuit circ, CircuitMutation mut,
            Location portLoc, Component comp, int width, String label, ConnectResult res) {
        if (hasConnectedTunnel(circ, portLoc, label)) {
            return;
        }
        AddTool tunnelTool = ComponentMeta.findTool(proj, "Tunnel", "Wiring");
        ComponentFactory factory = tunnelTool.getFactory();

        // Pick outward step direction away from component center
        Direction[] prefs = orderedOutwardDirections(portLoc, comp);
        Location wireEnd = portLoc;
        Direction tunnelFacing = prefs[0].reverse();

        for (Direction d : prefs) {
            for (int dist : new int[] { 20, 10, 30 }) {
                Location cand = stepDir(portLoc, d, dist);
                if (!hasAnyPortOrCompAt(circ, cand) && !crossesAnyPortOnSegment(circ, portLoc, cand)) {
                    wireEnd = cand;
                    tunnelFacing = d.reverse();
                    break;
                }
            }
            if (!wireEnd.equals(portLoc)) break;
        }

        if (!wireEnd.equals(portLoc)) {
            Wire stub = Wire.create(portLoc, wireEnd);
            mut.add(stub);
            res.wiresAdded.add(stub);
        }

        AttributeSet attrs = factory.createAttributeSet();
        attrs.setValue(StdAttr.FACING, tunnelFacing);
        attrs.setValue(StdAttr.WIDTH, BitWidth.create(width));
        attrs.setValue(StdAttr.LABEL, label);
        Component tunnelComp = factory.createComponent(wireEnd, attrs);
        mut.add(tunnelComp);
        res.componentsAdded.add(tunnelComp);
    }

    private static Direction[] orderedOutwardDirections(Location portLoc, Component comp) {
        if (comp == null) {
            return new Direction[] { Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH };
        }
        Bounds b = comp.getBounds();
        int cx = b.getX() + b.getWidth() / 2;
        int cy = b.getY() + b.getHeight() / 2;
        int dx = portLoc.getX() - cx;
        int dy = portLoc.getY() - cy;

        List<Direction> dirs = new ArrayList<Direction>(4);
        if (Math.abs(dx) >= Math.abs(dy)) {
            dirs.add(dx >= 0 ? Direction.EAST : Direction.WEST);
            dirs.add(dy >= 0 ? Direction.SOUTH : Direction.NORTH);
            dirs.add(dy >= 0 ? Direction.NORTH : Direction.SOUTH);
            dirs.add(dx >= 0 ? Direction.WEST : Direction.EAST);
        } else {
            dirs.add(dy >= 0 ? Direction.SOUTH : Direction.NORTH);
            dirs.add(dx >= 0 ? Direction.EAST : Direction.WEST);
            dirs.add(dx >= 0 ? Direction.WEST : Direction.EAST);
            dirs.add(dy >= 0 ? Direction.NORTH : Direction.SOUTH);
        }
        return dirs.toArray(new Direction[4]);
    }

    private static Location stepDir(Location loc, Direction dir, int dist) {
        if (dir == Direction.EAST) return Location.create(loc.getX() + dist, loc.getY());
        if (dir == Direction.WEST) return Location.create(loc.getX() - dist, loc.getY());
        if (dir == Direction.SOUTH) return Location.create(loc.getX(), loc.getY() + dist);
        return Location.create(loc.getX(), loc.getY() - dist);
    }

    private static boolean hasAnyPortOrCompAt(Circuit circ, Location pt) {
        for (Component c : circ.getNonWires()) {
            if (c.endsAt(pt) || c.getLocation().equals(pt)) return true;
        }
        return false;
    }

    private static boolean crossesAnyPortOnSegment(Circuit circ, Location a, Location b) {
        for (Component c : circ.getNonWires()) {
            for (EndData end : c.getEnds()) {
                Location p = end.getLocation();
                if (!p.equals(a) && !p.equals(b) && pointOnSegment(p, a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String generateTunnelName(Circuit circ, Component src, Component dst) {
        Set<String> existing = new HashSet<String>();
        for (Component c : circ.getNonWires()) {
            if ("Tunnel".equals(c.getFactory().getName())) {
                String lbl = ComponentMeta.getComponentLabel(c);
                if (!lbl.isEmpty()) existing.add(lbl);
            }
        }
        String base = "net";
        if (src != null && !ComponentMeta.getComponentLabel(src).isEmpty()) {
            base = sanitizeNetName(ComponentMeta.getComponentLabel(src));
        } else if (dst != null && !ComponentMeta.getComponentLabel(dst).isEmpty()) {
            base = sanitizeNetName(ComponentMeta.getComponentLabel(dst));
        }
        if (!existing.contains(base)) return base;
        for (int i = 1; i < 1000; i++) {
            String cand = base + "_" + i;
            if (!existing.contains(cand)) return cand;
        }
        return "net_" + System.nanoTime();
    }

    private static String sanitizeNetName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
            else if (c == ' ' || c == '-') sb.append('_');
        }
        return sb.length() > 0 ? sb.toString() : "net";
    }

    private static List<Location> buildHV(Location p1, Location p2) {
        if (p1.getX() == p2.getX() || p1.getY() == p2.getY()) {
            return Arrays.asList(p1, p2);
        }
        return Arrays.asList(p1, Location.create(p2.getX(), p1.getY()), p2);
    }

    private static List<Location> buildVH(Location p1, Location p2) {
        if (p1.getX() == p2.getX() || p1.getY() == p2.getY()) {
            return Arrays.asList(p1, p2);
        }
        return Arrays.asList(p1, Location.create(p1.getX(), p2.getY()), p2);
    }

    private static List<Location> orthogonalize(List<Location> pts) {
        List<Location> out = new ArrayList<Location>();
        for (Location p : pts) {
            if (out.isEmpty()) {
                out.add(p);
                continue;
            }
            Location prev = out.get(out.size() - 1);
            if (prev.equals(p)) continue;
            if (prev.getX() != p.getX() && prev.getY() != p.getY()) {
                out.add(Location.create(p.getX(), prev.getY()));
            }
            out.add(p);
        }
        return out;
    }

    private static List<Location> simplifyCollinear(List<Location> pts) {
        if (pts.size() <= 2) return pts;
        List<Location> out = new ArrayList<Location>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            Location a = out.get(out.size() - 1);
            Location b = pts.get(i);
            Location c = pts.get(i + 1);
            boolean sameX = a.getX() == b.getX() && b.getX() == c.getX();
            boolean sameY = a.getY() == b.getY() && b.getY() == c.getY();
            if (!sameX && !sameY) {
                out.add(b);
            }
        }
        Location last = pts.get(pts.size() - 1);
        if (!out.get(out.size() - 1).equals(last)) out.add(last);
        return out;
    }

    private static final class ObstacleMap {
        final Set<Location> blockedPorts = new HashSet<Location>();
        final Set<Location> wireEndpoints = new HashSet<Location>();
        final List<Wire> existingWires = new ArrayList<Wire>();
        final List<Bounds> componentBoxes = new ArrayList<Bounds>();

        ObstacleMap(Circuit circ, Location allow1, Location allow2) {
            for (Component c : circ.getNonWires()) {
                componentBoxes.add(c.getBounds());
                for (EndData ed : c.getEnds()) {
                    Location p = ed.getLocation();
                    if (!p.equals(allow1) && !p.equals(allow2)) {
                        blockedPorts.add(p);
                    }
                }
            }
            for (Wire w : circ.getWires()) {
                existingWires.add(w);
                if (!w.getEnd0().equals(allow1) && !w.getEnd0().equals(allow2)) wireEndpoints.add(w.getEnd0());
                if (!w.getEnd1().equals(allow1) && !w.getEnd1().equals(allow2)) wireEndpoints.add(w.getEnd1());
            }
        }

        boolean hasHardSegmentCollision(Location a, Location b) {
            for (Location p : blockedPorts) {
                if (pointOnSegment(p, a, b)) return true;
            }
            boolean horiz = a.getY() == b.getY();
            for (Wire w : existingWires) {
                if (horiz && !w.isVertical() && w.getEnd0().getY() == a.getY()) {
                    if (segmentsOverlap1D(a.getX(), b.getX(), w.getEnd0().getX(), w.getEnd1().getX())) {
                        return true;
                    }
                } else if (!horiz && w.isVertical() && w.getEnd0().getX() == a.getX()) {
                    if (segmentsOverlap1D(a.getY(), b.getY(), w.getEnd0().getY(), w.getEnd1().getY())) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean isCornerUnsafe(Location corner, Location p1, Location p2) {
            if (corner.equals(p1) || corner.equals(p2)) return false;
            if (blockedPorts.contains(corner) || wireEndpoints.contains(corner)) return true;
            // Disallow placing a 90-degree wire endpoint on an interior point of an existing wire (unintended tee)
            for (Wire w : existingWires) {
                if (pointOnSegment(corner, w.getEnd0(), w.getEnd1())) {
                    return true;
                }
            }
            return false;
        }

        int segmentBodyPenalty(Location a, Location b) {
            int pen = 0;
            int minX = Math.min(a.getX(), b.getX());
            int maxX = Math.max(a.getX(), b.getX());
            int minY = Math.min(a.getY(), b.getY());
            int maxY = Math.max(a.getY(), b.getY());
            for (Bounds box : componentBoxes) {
                if (box.getWidth() <= 4 || box.getHeight() <= 4) continue;
                int bx0 = box.getX() + 5;
                int by0 = box.getY() + 5;
                int bx1 = box.getX() + box.getWidth() - 5;
                int by1 = box.getY() + box.getHeight() - 5;
                if (bx1 >= bx0 && by1 >= by0) {
                    if (maxX >= bx0 && minX <= bx1 && maxY >= by0 && minY <= by1) {
                        pen += 50;
                    }
                }
            }
            return pen;
        }
    }

    private static final class GridNode implements Comparable<GridNode> {
        final int gx, gy;
        final int dir; // -1 none, 0 horiz, 1 vert
        final int gCost, fCost;
        final GridNode parent;

        GridNode(int gx, int gy, int dir, int gCost, int fCost, GridNode parent) {
            this.gx = gx;
            this.gy = gy;
            this.dir = dir;
            this.gCost = gCost;
            this.fCost = fCost;
            this.parent = parent;
        }

        @Override
        public int compareTo(GridNode o) {
            return Integer.compare(this.fCost, o.fCost);
        }
    }

    private static List<Location> findCleanOrthogonalPath(
            Circuit circ, CircuitMutation mut, Location p1, Location p2,
            boolean allowCollision, ConnectResult res) {
        ObstacleMap obs = new ObstacleMap(circ, p1, p2);

        // 1. Try direct straight / HV / VH candidates first if collision-free & low penalty
        List<List<Location>> quick = new ArrayList<List<Location>>();
        if (p1.getX() == p2.getX() || p1.getY() == p2.getY()) {
            quick.add(Arrays.asList(p1, p2));
        } else {
            quick.add(buildHV(p1, p2));
            quick.add(buildVH(p1, p2));
        }
        List<Location> bestQuick = null;
        int bestQuickPen = Integer.MAX_VALUE;
        for (List<Location> cand : quick) {
            if (isCandidateClean(cand, obs, p1, p2)) {
                int pen = pathPenalty(cand, obs);
                if (pen < bestQuickPen) {
                    bestQuickPen = pen;
                    bestQuick = cand;
                }
            }
        }
        if (bestQuick != null && bestQuickPen == 0) {
            return bestQuick;
        }

        // 2. Bounded A* on 10px grid around p1..p2 (margin 160px = 16 grid cells)
        int minX = (Math.min(p1.getX(), p2.getX()) - 160) / 10;
        int maxX = (Math.max(p1.getX(), p2.getX()) + 160) / 10;
        int minY = (Math.min(p1.getY(), p2.getY()) - 160) / 10;
        int maxY = (Math.max(p1.getY(), p2.getY()) + 160) / 10;

        int sx = p1.getX() / 10, sy = p1.getY() / 10;
        int tx = p2.getX() / 10, ty = p2.getY() / 10;
        // Handle off-grid endpoints gracefully by using exact coords if not multiples of 10
        if (p1.getX() % 10 != 0 || p1.getY() % 10 != 0 || p2.getX() % 10 != 0 || p2.getY() % 10 != 0) {
            if (bestQuick != null) return bestQuick;
            return buildHV(p1, p2);
        }

        PriorityQueue<GridNode> pq = new PriorityQueue<GridNode>();
        Map<Long, Integer> bestCost = new HashMap<Long, Integer>();
        pq.add(new GridNode(sx, sy, -1, 0, Math.abs(tx - sx) + Math.abs(ty - sy), null));

        final int[] DX = { 1, -1, 0, 0 };
        final int[] DY = { 0, 0, 1, -1 };
        int expansions = 0;

        while (!pq.isEmpty() && expansions++ < 25000) {
            GridNode cur = pq.poll();
            if (cur.gx == tx && cur.gy == ty) {
                List<Location> rawPath = new ArrayList<Location>();
                for (GridNode n = cur; n != null; n = n.parent) {
                    rawPath.add(Location.create(n.gx * 10, n.gy * 10));
                }
                Collections.reverse(rawPath);
                List<Location> simp = simplifyCollinear(rawPath);
                if (bestQuick != null && bestQuickPen <= pathPenalty(simp, obs)) {
                    return bestQuick;
                }
                return simp;
            }
            long stateKey = encodeState(cur.gx, cur.gy, Math.max(0, cur.dir));
            Integer prevBest = bestCost.get(Long.valueOf(stateKey));
            if (prevBest != null && prevBest.intValue() < cur.gCost) continue;

            Location curLoc = Location.create(cur.gx * 10, cur.gy * 10);
            for (int d = 0; d < 4; d++) {
                int nx = cur.gx + DX[d];
                int ny = cur.gy + DY[d];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue;
                int ndir = (d < 2) ? 0 : 1;
                Location nextLoc = Location.create(nx * 10, ny * 10);

                // Never step onto an unrelated component port
                if (!(nx == tx && ny == ty) && obs.blockedPorts.contains(nextLoc)) continue;
                // Avoid colinear overlap with existing wires
                if (!allowCollision && obs.hasHardSegmentCollision(curLoc, nextLoc)) continue;
                // If bending here (cur.dir != -1 && cur.dir != ndir), don't bend on existing wire!
                if (!allowCollision && cur.dir != -1 && cur.dir != ndir && obs.isCornerUnsafe(curLoc, p1, p2)) {
                    continue;
                }

                int stepCost = 10;
                if (cur.dir != -1 && cur.dir != ndir) stepCost += 25;
                if (!(nx == tx && ny == ty) && !(cur.gx == sx && cur.gy == sy)) {
                    stepCost += obs.segmentBodyPenalty(curLoc, nextLoc);
                }

                int ng = cur.gCost + stepCost;
                long nkey = encodeState(nx, ny, ndir);
                Integer old = bestCost.get(Long.valueOf(nkey));
                if (old == null || ng < old.intValue()) {
                    bestCost.put(Long.valueOf(nkey), Integer.valueOf(ng));
                    int h = (Math.abs(tx - nx) + Math.abs(ty - ny)) * 10;
                    pq.add(new GridNode(nx, ny, ndir, ng, ng + h, cur));
                }
            }
        }

        if (bestQuick != null) return bestQuick;
        List<Location> fallback = buildHV(p1, p2);
        res.warnings.add("Could not find completely obstacle-free route between " + p1 + " and " + p2
                + "; used default HV route. Consider mode='tunnel' for cleaner long-distance routing.");
        return fallback;
    }

    private static long encodeState(int gx, int gy, int dir) {
        return (((long) (gx + 50000)) << 22) | (((long) (gy + 50000)) << 2) | (long) (dir & 3);
    }

    private static boolean isCandidateClean(List<Location> pts, ObstacleMap obs, Location p1, Location p2) {
        for (int i = 0; i < pts.size() - 1; i++) {
            Location a = pts.get(i);
            Location b = pts.get(i + 1);
            if (obs.hasHardSegmentCollision(a, b)) return false;
            if (i > 0 && obs.isCornerUnsafe(a, p1, p2)) return false;
        }
        return true;
    }

    private static int pathPenalty(List<Location> pts, ObstacleMap obs) {
        int pen = Math.max(0, pts.size() - 2) * 15;
        for (int i = 0; i < pts.size() - 1; i++) {
            pen += obs.segmentBodyPenalty(pts.get(i), pts.get(i + 1));
        }
        return pen;
    }

    public static boolean pointOnSegment(Location p, Location a, Location b) {
        if (a.getX() == b.getX() && p.getX() == a.getX()) {
            int minY = Math.min(a.getY(), b.getY());
            int maxY = Math.max(a.getY(), b.getY());
            return p.getY() >= minY && p.getY() <= maxY;
        }
        if (a.getY() == b.getY() && p.getY() == a.getY()) {
            int minX = Math.min(a.getX(), b.getX());
            int maxX = Math.max(a.getX(), b.getX());
            return p.getX() >= minX && p.getX() <= maxX;
        }
        return false;
    }

    private static boolean segmentsOverlap1D(int a0, int a1, int b0, int b1) {
        int amin = Math.min(a0, a1), amax = Math.max(a0, a1);
        int bmin = Math.min(b0, b1), bmax = Math.max(b0, b1);
        return Math.min(amax, bmax) > Math.max(amin, bmin);
    }

    // =========================================================================
    // Wide bus Splitter + Paired Tunnels in 1 call
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fanoutTunnels(
            Project proj, Circuit circ, Canvas canvas,
            Map<String, Object> params, Map<String, Component> batchRefs) {

        String fromSpec = params.get("from") instanceof String ? (String) params.get("from") : null;
        Object fromPortSpec = params.get("from_port");
        Component srcComp = null;
        ComponentMeta.ResolvedPort srcPort = null;
        if (fromSpec != null && !fromSpec.trim().isEmpty()) {
            srcComp = ComponentMeta.resolveComponent(circ, fromSpec, batchRefs);
            srcPort = ComponentMeta.resolvePort(srcComp, fromPortSpec, circ, canvas);
        }

        int incomingWidth;
        if (params.get("width") instanceof Number) {
            incomingWidth = ((Number) params.get("width")).intValue();
        } else if (srcPort != null) {
            incomingWidth = srcPort.endData.getWidth().getWidth();
        } else {
            throw new IllegalArgumentException("Either 'from' or explicit 'width' is required");
        }
        if (incomingWidth < 2 || incomingWidth > 32) {
            throw new IllegalArgumentException("Splitter incoming bit width must be between 2 and 32 (got " + incomingWidth + ")");
        }
        if (srcPort != null && srcPort.endData.getWidth().getWidth() != incomingWidth) {
            throw new IllegalArgumentException("Source port width (" + srcPort.endData.getWidth().getWidth()
                    + ") does not match requested fanout width (" + incomingWidth + ")");
        }

        Object slicesObj = params.get("slices");
        if (!(slicesObj instanceof List<?>) || ((List<?>) slicesObj).isEmpty()) {
            throw new IllegalArgumentException("'slices' must be a non-empty array of {name, bits}");
        }
        List<?> rawSlices = (List<?>) slicesObj;
        int fanout = rawSlices.size();
        if (fanout < 1 || fanout > 32) {
            throw new IllegalArgumentException("Fanout slice count must be 1..32");
        }

        // Validate every slice's bit ranges first
        int[] bitAssignedTo = new int[incomingWidth];
        Arrays.fill(bitAssignedTo, -1);
        List<String> sliceNames = new ArrayList<String>(fanout);
        List<Integer> sliceWidths = new ArrayList<Integer>(fanout);

        for (int sIdx = 0; sIdx < fanout; sIdx++) {
            Object item = rawSlices.get(sIdx);
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Slice at index " + sIdx + " must be an object {name, bits}");
            }
            Map<String, Object> sm = (Map<String, Object>) item;
            String name = sm.get("name") instanceof String ? ((String) sm.get("name")).trim() : "";
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Slice " + sIdx + " missing non-empty 'name'");
            }
            List<Integer> bits = parseBitSpec(sm.get("bits"), incomingWidth);
            if (bits.isEmpty()) {
                throw new IllegalArgumentException("Slice '" + name + "' specifies no bits");
            }
            for (int b : bits) {
                if (bitAssignedTo[b] >= 0) {
                    throw new IllegalArgumentException("Bit " + b + " claimed by both '"
                            + sliceNames.get(bitAssignedTo[b]) + "' and '" + name + "'");
                }
                bitAssignedTo[b] = sIdx;
            }
            sliceNames.add(name);
            sliceWidths.add(Integer.valueOf(bits.size()));
        }

        String facingStr = params.get("facing") instanceof String ? (String) params.get("facing") : "east";
        int sx;
        int sy;
        if (params.get("splitter_x") instanceof Number && params.get("splitter_y") instanceof Number) {
            sx = ComponentMeta.snap(((Number) params.get("splitter_x")).intValue());
            sy = ComponentMeta.snap(((Number) params.get("splitter_y")).intValue());
        } else if (srcPort != null) {
            sx = ComponentMeta.snap(srcPort.location.getX() + 40);
            sy = ComponentMeta.snap(srcPort.location.getY());
        } else {
            throw new IllegalArgumentException("Provide splitter_x/splitter_y or a 'from' component");
        }

        int tunnelOffset = (params.get("tunnel_offset") instanceof Number)
                ? Math.max(10, ComponentMeta.snap(((Number) params.get("tunnel_offset")).intValue()))
                : 30;

        CircuitMutation mut = new CircuitMutation(circ);
        AddTool splitterTool = ComponentMeta.findTool(proj, "Splitter", "Wiring");
        ComponentFactory splitterFactory = splitterTool.getFactory();
        AttributeSet sAttrs = splitterFactory.createAttributeSet();

        Map<String, Object> splitterAttrMap = new LinkedHashMap<String, Object>();
        splitterAttrMap.put("facing", facingStr);
        splitterAttrMap.put("fanout", Integer.valueOf(fanout));
        splitterAttrMap.put("incoming", Integer.valueOf(incomingWidth));
        splitterAttrMap.put("appear", params.get("appear") instanceof String ? (String) params.get("appear") : "center");
        for (int b = 0; b < incomingWidth; b++) {
            splitterAttrMap.put("bit" + b, bitAssignedTo[b] >= 0 ? String.valueOf(bitAssignedTo[b]) : "none");
        }
        ComponentMeta.applyAttributes(sAttrs, splitterAttrMap);

        Location sLoc = Location.create(sx, sy);
        Component splitter = splitterFactory.createComponent(sLoc, sAttrs);
        mut.add(splitter);

        List<EndData> sEnds = splitter.getEnds();
        List<Map<String, Object>> createdTunnels = new ArrayList<Map<String, Object>>();
        int wiresAdded = 0;

        if (srcPort != null && !sEnds.isEmpty()) {
            ConnectResult wireRes = connectSmart(
                    proj, circ, mut, canvas,
                    srcPort.location, sEnds.get(0).getLocation(),
                    srcPort.endData.getWidth().getWidth(), sEnds.get(0).getWidth().getWidth(),
                    srcComp, splitter, "wire", "auto", null, null, false);
            wiresAdded += wireRes.wiresAdded.size();
        }

        AddTool tunnelTool = ComponentMeta.findTool(proj, "Tunnel", "Wiring");
        ComponentFactory tunnelFactory = tunnelTool.getFactory();
        Direction sFacing = sAttrs.getValue(StdAttr.FACING);
        Direction tFacing = sFacing.reverse(); // Tunnel tip points back toward splitter branch

        // Fan out tunnel endpoints cleanly by 20px pitch perpendicular to splitter facing so Tunnel boxes never overlap
        boolean horiz = (sFacing == Direction.EAST || sFacing == Direction.WEST);
        int sliceCount = Math.min(fanout, Math.max(0, sEnds.size() - 1));
        int[] branchPerp = new int[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            Location bPt = sEnds.get(i + 1).getLocation();
            branchPerp[i] = horiz ? bPt.getY() : bPt.getX();
        }
        int baseTarget = sliceCount > 1
                ? ComponentMeta.snap(branchPerp[0] - ((sliceCount - 1) * 5))
                : (sliceCount == 1 ? branchPerp[0] : sy);

        int[] targetPerp = new int[sliceCount];
        int maxJogSteps = 0;
        int upCount = 0;
        int downCount = 0;
        for (int i = 0; i < sliceCount; i++) {
            targetPerp[i] = baseTarget + i * 20;
            if (targetPerp[i] < branchPerp[i]) upCount++;
            else if (targetPerp[i] > branchPerp[i]) downCount++;
        }
        maxJogSteps = Math.max(upCount, downCount);
        int effectiveOffset = Math.max(tunnelOffset, (maxJogSteps + 2) * 10);

        int upSeen = 0;
        int downRemaining = downCount;
        for (int sIdx = 0; sIdx < sliceCount; sIdx++) {
            Location branchPt = sEnds.get(sIdx + 1).getLocation();
            Location endPt = stepDir(branchPt, sFacing, effectiveOffset);
            Location tunnelPt = horiz
                    ? Location.create(endPt.getX(), targetPerp[sIdx])
                    : Location.create(targetPerp[sIdx], endPt.getY());

            if (targetPerp[sIdx] == branchPerp[sIdx]) {
                Wire seg = Wire.create(branchPt, tunnelPt);
                mut.add(seg);
                wiresAdded++;
            } else {
                int jogSteps;
                if (targetPerp[sIdx] < branchPerp[sIdx]) {
                    upSeen++;
                    jogSteps = upSeen;
                } else {
                    jogSteps = downRemaining;
                    downRemaining--;
                }
                Location elbow1 = stepDir(branchPt, sFacing, jogSteps * 10);
                Location elbow2 = horiz
                        ? Location.create(elbow1.getX(), tunnelPt.getY())
                        : Location.create(tunnelPt.getX(), elbow1.getY());
                Wire w1 = Wire.create(branchPt, elbow1);
                Wire w2 = Wire.create(elbow1, elbow2);
                Wire w3 = Wire.create(elbow2, tunnelPt);
                mut.add(w1);
                mut.add(w2);
                mut.add(w3);
                wiresAdded += 3;
            }

            AttributeSet tAttrs = tunnelFactory.createAttributeSet();
            tAttrs.setValue(StdAttr.FACING, tFacing);
            tAttrs.setValue(StdAttr.WIDTH, BitWidth.create(sliceWidths.get(sIdx).intValue()));
            tAttrs.setValue(StdAttr.LABEL, sliceNames.get(sIdx));
            Component tComp = tunnelFactory.createComponent(tunnelPt, tAttrs);
            mut.add(tComp);

            Map<String, Object> tm = new LinkedHashMap<String, Object>();
            tm.put("name", sliceNames.get(sIdx));
            tm.put("width", sliceWidths.get(sIdx));
            tm.put("id", ComponentMeta.componentId(tComp));
            tm.put("x", Integer.valueOf(tunnelPt.getX()));
            tm.put("y", Integer.valueOf(tunnelPt.getY()));
            createdTunnels.add(tm);
        }

        proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Fanout Tunnels")));

        String ref = params.get("ref") instanceof String ? ((String) params.get("ref")).trim() : "";
        if (!ref.isEmpty() && batchRefs != null) {
            batchRefs.put(ref, splitter);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("splitter_id", ComponentMeta.componentId(splitter));
        out.put("incoming_width", Integer.valueOf(incomingWidth));
        out.put("fanout", Integer.valueOf(fanout));
        out.put("tunnels", createdTunnels);
        out.put("wires_added", Integer.valueOf(wiresAdded));
        return out;
    }

    private static List<Integer> parseBitSpec(Object spec, int maxWidth) {
        List<Integer> bits = new ArrayList<Integer>();
        if (spec instanceof Number) {
            int b = ((Number) spec).intValue();
            checkBitRange(b, maxWidth);
            bits.add(Integer.valueOf(b));
            return bits;
        }
        if (spec instanceof List<?>) {
            for (Object o : (List<?>) spec) {
                bits.addAll(parseBitSpec(o, maxWidth));
            }
            return bits;
        }
        String s = String.valueOf(spec).trim();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.contains("-")) {
                String[] bounds = part.split("-", 2);
                int a = Integer.parseInt(bounds[0].trim());
                int b = Integer.parseInt(bounds[1].trim());
                int lo = Math.min(a, b), hi = Math.max(a, b);
                for (int i = lo; i <= hi; i++) {
                    checkBitRange(i, maxWidth);
                    bits.add(Integer.valueOf(i));
                }
            } else {
                int v = Integer.parseInt(part);
                checkBitRange(v, maxWidth);
                bits.add(Integer.valueOf(v));
            }
        }
        return bits;
    }

    private static void checkBitRange(int b, int maxWidth) {
        if (b < 0 || b >= maxWidth) {
            throw new IllegalArgumentException("Bit index " + b + " out of range 0.." + (maxWidth - 1));
        }
    }

    // =========================================================================
    // Component Arrangement
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> arrangeComponents(
            Project proj, Circuit circ, Map<String, Object> params) {
        Object compsObj = params.get("components");
        if (!(compsObj instanceof List<?>) || ((List<?>) compsObj).isEmpty()) {
            throw new IllegalArgumentException("'components' array must be non-empty");
        }
        List<Component> targets = new ArrayList<Component>();
        for (Object item : (List<?>) compsObj) {
            targets.add(ComponentMeta.resolveComponent(circ, String.valueOf(item), null));
        }
        String mode = params.get("mode") instanceof String ? ((String) params.get("mode")).trim().toLowerCase() : "align";
        String align = params.get("align") instanceof String ? ((String) params.get("align")).trim().toLowerCase() : "left";
        String axis = params.get("axis") instanceof String ? ((String) params.get("axis")).trim().toLowerCase() : "vertical";
        int spacing = params.get("spacing") instanceof Number ? ((Number) params.get("spacing")).intValue() : 40;
        int columns = params.get("columns") instanceof Number ? Math.max(1, ((Number) params.get("columns")).intValue()) : 4;
        int gapX = params.get("gap_x") instanceof Number ? ((Number) params.get("gap_x")).intValue() : 120;
        int gapY = params.get("gap_y") instanceof Number ? ((Number) params.get("gap_y")).intValue() : 80;

        Map<Component, Location> newLocs = new LinkedHashMap<Component, Location>();
        if ("snap".equals(mode)) {
            for (Component c : targets) {
                Location l = c.getLocation();
                newLocs.put(c, Location.create(ComponentMeta.snap(l.getX()), ComponentMeta.snap(l.getY())));
            }
        } else if ("grid".equals(mode)) {
            int ox = params.get("origin_x") instanceof Number
                    ? ComponentMeta.snap(((Number) params.get("origin_x")).intValue())
                    : ComponentMeta.snap(targets.get(0).getLocation().getX());
            int oy = params.get("origin_y") instanceof Number
                    ? ComponentMeta.snap(((Number) params.get("origin_y")).intValue())
                    : ComponentMeta.snap(targets.get(0).getLocation().getY());
            for (int i = 0; i < targets.size(); i++) {
                int r = i / columns;
                int col = i % columns;
                newLocs.put(targets.get(i), Location.create(ComponentMeta.snap(ox + col * gapX), ComponentMeta.snap(oy + r * gapY)));
            }
        } else if ("distribute".equals(mode)) {
            List<Component> sorted = new ArrayList<Component>(targets);
            final boolean horiz = "horizontal".equals(axis) || "x".equals(axis);
            Collections.sort(sorted, new Comparator<Component>() {
                @Override
                public int compare(Component a, Component b) {
                    return horiz
                            ? Integer.compare(a.getLocation().getX(), b.getLocation().getX())
                            : Integer.compare(a.getLocation().getY(), b.getLocation().getY());
                }
            });
            int cur = horiz ? sorted.get(0).getLocation().getX() : sorted.get(0).getLocation().getY();
            for (int i = 0; i < sorted.size(); i++) {
                Component c = sorted.get(i);
                Location l = c.getLocation();
                Bounds b = c.getBounds();
                if (horiz) {
                    newLocs.put(c, Location.create(ComponentMeta.snap(cur), ComponentMeta.snap(l.getY())));
                    cur += Math.max(20, b.getWidth()) + spacing;
                } else {
                    newLocs.put(c, Location.create(ComponentMeta.snap(l.getX()), ComponentMeta.snap(cur)));
                    cur += Math.max(20, b.getHeight()) + spacing;
                }
            }
        } else {
            // align mode
            int refVal = targets.get(0).getLocation().getX();
            if ("left".equals(align)) {
                refVal = Integer.MAX_VALUE;
                for (Component c : targets) refVal = Math.min(refVal, c.getLocation().getX());
            } else if ("right".equals(align)) {
                refVal = Integer.MIN_VALUE;
                for (Component c : targets) refVal = Math.max(refVal, c.getLocation().getX());
            } else if ("top".equals(align)) {
                refVal = Integer.MAX_VALUE;
                for (Component c : targets) refVal = Math.min(refVal, c.getLocation().getY());
            } else if ("bottom".equals(align)) {
                refVal = Integer.MIN_VALUE;
                for (Component c : targets) refVal = Math.max(refVal, c.getLocation().getY());
            } else if ("center_x".equals(align)) {
                long sum = 0;
                for (Component c : targets) sum += c.getLocation().getX();
                refVal = (int) (sum / targets.size());
            } else if ("center_y".equals(align)) {
                long sum = 0;
                for (Component c : targets) sum += c.getLocation().getY();
                refVal = (int) (sum / targets.size());
            } else {
                throw new IllegalArgumentException("Invalid align '" + align + "'. Use left/right/top/bottom/center_x/center_y");
            }
            refVal = ComponentMeta.snap(refVal);
            boolean xAlign = "left".equals(align) || "right".equals(align) || "center_x".equals(align);
            for (Component c : targets) {
                Location l = c.getLocation();
                newLocs.put(c, xAlign
                        ? Location.create(refVal, ComponentMeta.snap(l.getY()))
                        : Location.create(ComponentMeta.snap(l.getX()), refVal));
            }
        }

        CircuitMutation mut = new CircuitMutation(circ);
        List<Map<String, Object>> moved = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Component, Location> e : newLocs.entrySet()) {
            Component oldC = e.getKey();
            Location dst = e.getValue();
            if (oldC.getLocation().equals(dst)) continue;
            AttributeSet copyAttrs = (AttributeSet) oldC.getAttributeSet().clone();
            Component newC = oldC.getFactory().createComponent(dst, copyAttrs);
            mut.replace(oldC, newC);

            Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("old_id", ComponentMeta.componentId(oldC));
            rec.put("new_id", ComponentMeta.componentId(newC));
            rec.put("x", Integer.valueOf(dst.getX()));
            rec.put("y", Integer.valueOf(dst.getY()));
            moved.add(rec);
        }

        if (!moved.isEmpty()) {
            proj.doAction(mut.toAction(StringUtil.constantGetter("MCP Arrange Components")));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", mode);
        out.put("moved_count", Integer.valueOf(moved.size()));
        out.put("moved", moved);
        return out;
    }

    // =========================================================================
    // Custom Subcircuit IC Appearance Configuration
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static Map<String, Object> configureAppearance(Project proj, Circuit circ, Map<String, Object> params) {
        CircuitAppearance app = circ.getAppearance();
        Collection<Instance> pins = app.getCircuitPins().getPins();
        if (pins.isEmpty()) {
            throw new IllegalArgumentException("Circuit '" + circ.getName() + "' has no Input/Output Pins to expose on an IC symbol");
        }

        Map<String, Instance> byLabelOrId = new LinkedHashMap<String, Instance>();
        List<Instance> allPins = new ArrayList<Instance>(pins);
        for (Instance pin : allPins) {
            String lbl = pin.getAttributeValue(StdAttr.LABEL);
            if (lbl != null && !lbl.trim().isEmpty()) {
                byLabelOrId.put(lbl.trim().toLowerCase(), pin);
            }
            byLabelOrId.put("pin@" + pin.getLocation().getX() + "," + pin.getLocation().getY(), pin);
        }

        List<Instance> westPins = new ArrayList<Instance>();
        List<Instance> eastPins = new ArrayList<Instance>();
        List<Instance> northPins = new ArrayList<Instance>();
        List<Instance> southPins = new ArrayList<Instance>();
        Set<Instance> assigned = new HashSet<Instance>();

        assignEdgeList(params.get("west"), byLabelOrId, westPins, assigned, "west");
        assignEdgeList(params.get("east"), byLabelOrId, eastPins, assigned, "east");
        assignEdgeList(params.get("north"), byLabelOrId, northPins, assigned, "north");
        assignEdgeList(params.get("south"), byLabelOrId, southPins, assigned, "south");

        // Auto-place remaining pins: inputs -> west, outputs -> east (sorted by Y)
        List<Instance> remaining = new ArrayList<Instance>();
        for (Instance pin : allPins) {
            if (!assigned.contains(pin)) remaining.add(pin);
        }
        Collections.sort(remaining, new Comparator<Instance>() {
            @Override
            public int compare(Instance a, Instance b) {
                int c = Integer.compare(a.getLocation().getY(), b.getLocation().getY());
                return c != 0 ? c : Integer.compare(a.getLocation().getX(), b.getLocation().getX());
            }
        });
        for (Instance pin : remaining) {
            if (Pin.FACTORY.isInputPin(pin)) westPins.add(pin);
            else eastPins.add(pin);
        }

        int pinSpacing = params.get("pin_spacing") instanceof Number
                ? Math.max(10, ComponentMeta.snap(((Number) params.get("pin_spacing")).intValue()))
                : 20;
        boolean showPinLabels = !(Boolean.FALSE.equals(params.get("show_pin_labels")));

        int maxSideV = Math.max(westPins.size(), eastPins.size());
        int maxSideH = Math.max(northPins.size(), southPins.size());

        int maxWestLabelLen = maxLabelLen(westPins);
        int maxEastLabelLen = maxLabelLen(eastPins);
        String title = params.get("title") instanceof String && !((String) params.get("title")).trim().isEmpty()
                ? ((String) params.get("title")).trim()
                : circ.getName();

        int autoWidth = Math.max(80, (maxWestLabelLen + maxEastLabelLen) * 7 + title.length() * 8 + 40);
        autoWidth = Math.max(autoWidth, (maxSideH + 1) * pinSpacing + 20);
        autoWidth = ((autoWidth + 9) / 10) * 10;

        int autoHeight = Math.max(40, (Math.max(1, maxSideV) + 1) * pinSpacing);
        if (!northPins.isEmpty() || !southPins.isEmpty()) autoHeight = Math.max(autoHeight, 60);
        autoHeight = ((autoHeight + 9) / 10) * 10;

        int width = params.get("width") instanceof Number
                ? Math.max(30, ComponentMeta.snap(((Number) params.get("width")).intValue()))
                : autoWidth;
        int height = params.get("height") instanceof Number
                ? Math.max(30, ComponentMeta.snap(((Number) params.get("height")).intValue()))
                : autoHeight;

        int rx = 50;
        int ry = 50;
        List<CanvasObject> shapes = new ArrayList<CanvasObject>();

        String shapeType = params.get("shape") instanceof String ? ((String) params.get("shape")).trim().toLowerCase() : "rect";
        if ("roundrect".equals(shapeType) || "rounded".equals(shapeType)) {
            RoundRectangle rr = new RoundRectangle(rx, ry, width, height);
            rr.setValue(DrawAttr.PAINT_TYPE, DrawAttr.PAINT_STROKE_FILL);
            rr.setValue(DrawAttr.STROKE_WIDTH, Integer.valueOf(2));
            rr.setValue(DrawAttr.STROKE_COLOR, Color.BLACK);
            rr.setValue(DrawAttr.FILL_COLOR, new Color(248, 250, 252));
            rr.setValue(DrawAttr.CORNER_RADIUS, Integer.valueOf(8));
            shapes.add(rr);
        } else {
            Rectangle rect = new Rectangle(rx, ry, width, height);
            rect.setValue(DrawAttr.PAINT_TYPE, DrawAttr.PAINT_STROKE_FILL);
            rect.setValue(DrawAttr.STROKE_WIDTH, Integer.valueOf(2));
            rect.setValue(DrawAttr.STROKE_COLOR, Color.BLACK);
            rect.setValue(DrawAttr.FILL_COLOR, new Color(248, 250, 252));
            shapes.add(rect);
        }

        // Classic IC top notch
        Curve notch = new Curve(
                Location.create(rx + width / 2 - 6, ry + 1),
                Location.create(rx + width / 2 + 6, ry + 1),
                Location.create(rx + width / 2, ry + 11));
        notch.setValue(DrawAttr.STROKE_WIDTH, Integer.valueOf(2));
        notch.setValue(DrawAttr.STROKE_COLOR, Color.GRAY);
        shapes.add(notch);

        // Center IC title
        Text titleShape = new Text(rx + width / 2, ry + height / 2 + 4, title);
        titleShape.setValue(DrawAttr.ALIGNMENT, DrawAttr.ALIGN_CENTER);
        titleShape.setValue(DrawAttr.FONT, new Font("SansSerif", Font.BOLD, 12));
        titleShape.setValue(DrawAttr.FILL_COLOR, new Color(30, 41, 59));
        shapes.add(titleShape);

        Font pinFont = new Font("SansSerif", Font.PLAIN, 10);
        List<Map<String, Object>> placedPorts = new ArrayList<Map<String, Object>>();

        // Place West pins
        int westStartY = ry + ComponentMeta.snap(Math.max(pinSpacing / 2, (height - Math.max(0, westPins.size() - 1) * pinSpacing) / 2));
        for (int i = 0; i < westPins.size(); i++) {
            int py = westStartY + i * pinSpacing;
            Instance pin = westPins.get(i);
            shapes.add(new AppearancePort(Location.create(rx, py), pin));
            String lbl = pinLabelOrFallback(pin, "in" + i);
            if (showPinLabels) {
                Text t = new Text(rx + 6, py + 4, lbl);
                t.setValue(DrawAttr.ALIGNMENT, DrawAttr.ALIGN_LEFT);
                t.setValue(DrawAttr.FONT, pinFont);
                shapes.add(t);
            }
            placedPorts.add(pinSummary(lbl, "west", 0, py - ry));
        }

        // Place East pins
        int eastStartY = ry + ComponentMeta.snap(Math.max(pinSpacing / 2, (height - Math.max(0, eastPins.size() - 1) * pinSpacing) / 2));
        for (int i = 0; i < eastPins.size(); i++) {
            int py = eastStartY + i * pinSpacing;
            Instance pin = eastPins.get(i);
            shapes.add(new AppearancePort(Location.create(rx + width, py), pin));
            String lbl = pinLabelOrFallback(pin, "out" + i);
            if (showPinLabels) {
                Text t = new Text(rx + width - 6, py + 4, lbl);
                t.setValue(DrawAttr.ALIGNMENT, DrawAttr.ALIGN_RIGHT);
                t.setValue(DrawAttr.FONT, pinFont);
                shapes.add(t);
            }
            placedPorts.add(pinSummary(lbl, "east", width, py - ry));
        }

        // Place North pins
        int northStartX = rx + ComponentMeta.snap(Math.max(pinSpacing, (width - Math.max(0, northPins.size() - 1) * pinSpacing) / 2));
        for (int i = 0; i < northPins.size(); i++) {
            int px = northStartX + i * pinSpacing;
            Instance pin = northPins.get(i);
            shapes.add(new AppearancePort(Location.create(px, ry), pin));
            String lbl = pinLabelOrFallback(pin, "n" + i);
            if (showPinLabels) {
                Text t = new Text(px, ry + 14, lbl);
                t.setValue(DrawAttr.ALIGNMENT, DrawAttr.ALIGN_CENTER);
                t.setValue(DrawAttr.FONT, pinFont);
                shapes.add(t);
            }
            placedPorts.add(pinSummary(lbl, "north", px - rx, 0));
        }

        // Place South pins
        int southStartX = rx + ComponentMeta.snap(Math.max(pinSpacing, (width - Math.max(0, southPins.size() - 1) * pinSpacing) / 2));
        for (int i = 0; i < southPins.size(); i++) {
            int px = southStartX + i * pinSpacing;
            Instance pin = southPins.get(i);
            shapes.add(new AppearancePort(Location.create(px, ry + height), pin));
            String lbl = pinLabelOrFallback(pin, "s" + i);
            if (showPinLabels) {
                Text t = new Text(px, ry + height - 6, lbl);
                t.setValue(DrawAttr.ALIGNMENT, DrawAttr.ALIGN_CENTER);
                t.setValue(DrawAttr.FONT, pinFont);
                shapes.add(t);
            }
            placedPorts.add(pinSummary(lbl, "south", px - rx, height));
        }

        // Anchor aligned on grid
        Location anchorLoc;
        if (!eastPins.isEmpty()) {
            anchorLoc = Location.create(rx + width, eastStartY);
        } else if (!westPins.isEmpty()) {
            anchorLoc = Location.create(rx, westStartY);
        } else {
            anchorLoc = Location.create(rx, ry);
        }
        shapes.add(new AppearanceAnchor(anchorLoc));

        app.setDefaultAppearance(false);
        app.setObjectsForce(shapes);
        proj.getLogisimFile().setDirty(true);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("circuit", circ.getName());
        out.put("width", Integer.valueOf(width));
        out.put("height", Integer.valueOf(height));
        out.put("ports", placedPorts);
        return out;
    }

    private static void assignEdgeList(
            Object rawList, Map<String, Instance> byLabelOrId,
            List<Instance> dest, Set<Instance> assigned, String edgeName) {
        if (!(rawList instanceof List<?>)) return;
        for (Object o : (List<?>) rawList) {
            String key = String.valueOf(o).trim().toLowerCase();
            Instance pin = byLabelOrId.get(key);
            if (pin == null) {
                throw new IllegalArgumentException(
                        "Pin '" + o + "' for " + edgeName + " edge not found in subcircuit. Known pins: "
                        + byLabelOrId.keySet());
            }
            if (assigned.add(pin)) dest.add(pin);
        }
    }

    private static int maxLabelLen(List<Instance> pins) {
        int m = 0;
        for (Instance p : pins) {
            String l = p.getAttributeValue(StdAttr.LABEL);
            if (l != null) m = Math.max(m, l.length());
        }
        return m;
    }

    private static String pinLabelOrFallback(Instance pin, String fb) {
        String l = pin.getAttributeValue(StdAttr.LABEL);
        return (l != null && !l.trim().isEmpty()) ? l.trim() : fb;
    }

    private static Map<String, Object> pinSummary(String label, String edge, int rx, int ry) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("edge", edge);
        m.put("offset_x", Integer.valueOf(rx));
        m.put("offset_y", Integer.valueOf(ry));
        return m;
    }
}
