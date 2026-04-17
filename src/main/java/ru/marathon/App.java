package ru.marathon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8000), 0);
        server.createContext("/api/v1/robinson_cruise", App::handleCruise);
        server.createContext("/api/v1/star_visibility", App::handleVisibility);
        server.createContext("/api/v1/constellation_finder", App::handleConstellation);
        server.start();
    }

    private static void handleCruise(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        try {
            JsonNode root = readJson(ex);
            requireObjectWithFields(root, Set.of("mass_shuttle", "mass_fuel_unit", "power_per_unit", "oxygen_time", "total_fuel", "fuel_consumption"));
            double mShuttle = positiveDouble(root, "mass_shuttle");
            double mFuelUnit = positiveDouble(root, "mass_fuel_unit");
            double power = positiveDouble(root, "power_per_unit");
            int oxygen = positiveInt(root, "oxygen_time");
            int totalFuel = nonNegativeInt(root, "total_fuel");
            double fuelConsumption = positiveDouble(root, "fuel_consumption");

            double best = 0.0;
            for (int exchangeFuel = 0; exchangeFuel <= totalFuel; exchangeFuel++) {
                double keyLift = StarKey.engage(exchangeFuel);
                int available = totalFuel - exchangeFuel;
                int bestOrbitFuel = -1;
                for (int orbitFuel = available; orbitFuel >= 0; orbitFuel--) {
                    int launchFuel = available - orbitFuel;
                    double toLift = Math.max(0.0, mShuttle + orbitFuel * mFuelUnit - keyLift);
                    if (launchFuel + 1e-9 >= toLift * fuelConsumption) {
                        bestOrbitFuel = orbitFuel;
                        break;
                    }
                }
                if (bestOrbitFuel < 0) continue;
                best = Math.max(best, simulateFlight(mShuttle, mFuelUnit, power, oxygen, bestOrbitFuel));
            }

            ObjectNode out = MAPPER.createObjectNode();
            out.put("max_distance", Math.round(best * 10.0) / 10.0);
            writeJson(ex, 200, out);
        } catch (BadInput e) {
            writeIncorrect(ex);
        } catch (Exception e) {
            writeIncorrect(ex);
        }
    }

    private static double simulateFlight(double mShuttle, double mFuelUnit, double power, int oxygen, int orbitFuel) {
        int accelBudget = orbitFuel / 2;
        int accelSteps = Math.min(accelBudget, oxygen / 2);

        double v = 0.0;
        double d = 0.0;
        int fuel = orbitFuel;

        for (int i = 0; i < accelSteps; i++) {
            fuel -= 1;
            double mass = mShuttle + fuel * mFuelUnit;
            double a = power / mass;
            d += v + 0.5 * a;
            v += a;
        }

        BrakeResult br = simulateBrake(mShuttle, mFuelUnit, power, fuel, v);
        if (!br.canStop) return 0.0;
        int coast = oxygen - accelSteps - br.timeSeconds;
        if (coast < 0) return 0.0;

        return d + v * coast + br.distance;
    }

    private static BrakeResult simulateBrake(double mShuttle, double mFuelUnit, double power, int fuel, double speed) {
        double v = speed;
        double d = 0.0;
        int t = 0;
        int f = fuel;
        while (v > 1e-12 && f > 0) {
            f -= 1;
            t += 1;
            double mass = mShuttle + f * mFuelUnit;
            double a = power / mass;
            if (v <= a + 1e-12) {
                double tau = v / a;
                d += v * tau - 0.5 * a * tau * tau;
                v = 0.0;
                break;
            } else {
                d += v - 0.5 * a;
                v -= a;
            }
        }
        return new BrakeResult(v <= 1e-12, t, d);
    }

    private record BrakeResult(boolean canStop, int timeSeconds, double distance) {}

    private static void handleVisibility(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        try {
            JsonNode root = readJson(ex);
            requireObjectWithFields(root, Set.of("star_vector", "moons", "observation_date"));
            JsonNode vec = root.get("star_vector");
            requireObjectWithFields(vec, Set.of("x", "y"));
            double sx = finiteDouble(vec, "x");
            double sy = finiteDouble(vec, "y");
            if (Math.hypot(sx, sy) <= 1e-12) throw new BadInput();
            JsonNode moons = root.get("moons");
            if (!moons.isArray() || moons.size() < 2 || moons.size() > 100) throw new BadInput();
            LocalDate obsDate = LocalDate.parse(text(root, "observation_date"));
            Instant dayStart = obsDate.atStartOfDay(ZoneOffset.UTC).toInstant();

            List<Moon> list = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonNode m : moons) {
                requireObjectWithFields(m, Set.of("name", "orbit_radius", "moon_radius", "initial_observation_angle", "initial_observation_time"));
                String name = text(m, "name");
                if (!names.add(name)) throw new BadInput();
                double orbitR = positiveDouble(m, "orbit_radius");
                double moonR = positiveDouble(m, "moon_radius");
                if (moonR > orbitR) throw new BadInput();
                double angleDeg = finiteDouble(m, "initial_observation_angle");
                if (angleDeg < 0.0 || angleDeg >= 360.0) throw new BadInput();
                Instant t0 = Instant.parse(text(m, "initial_observation_time"));
                list.add(new Moon(name, orbitR, moonR, Math.toRadians(angleDeg), t0));
            }

            // Integrate local module behavior: pairwise calls validate hashes.
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    LightAnalyzer.engage(list.get(i).name, list.get(j).name);
                }
            }

            double starAngle = Math.atan2(sy, sx);
            boolean[] visible = new boolean[86400];
            Arrays.fill(visible, true);
            for (int s = 0; s < 86400; s++) {
                Instant now = dayStart.plusSeconds(s);
                for (Moon m : list) {
                    double p = m.periodSeconds();
                    if (p <= 0) throw new BadInput();
                    double dt = Duration.between(m.initialObservationTime, now).toMillis() / 1000.0;
                    double theta = normalize(m.initialAngle - 2.0 * Math.PI * dt / p);
                    double delta = shortest(theta - starAngle);
                    if (Math.cos(delta) > 0 && Math.abs(Math.sin(delta)) * m.orbitRadius <= m.moonRadius + 1e-12) {
                        visible[s] = false;
                        break;
                    }
                }
            }

            ArrayNode intervals = MAPPER.createArrayNode();
            int i = 0;
            while (i < 86400) {
                while (i < 86400 && !visible[i]) i++;
                if (i >= 86400) break;
                int start = i;
                while (i + 1 < 86400 && visible[i + 1]) i++;
                int end = i;
                ObjectNode inter = MAPPER.createObjectNode();
                inter.put("start", dayStart.plusSeconds(start).toString());
                inter.put("end", dayStart.plusSeconds(end).toString());
                intervals.add(inter);
                i++;
            }

            ObjectNode out = MAPPER.createObjectNode();
            out.set("visible_intervals", intervals);
            writeJson(ex, 200, out);
        } catch (BadInput | DateTimeParseException e) {
            writeIncorrect(ex);
        } catch (Exception e) {
            writeIncorrect(ex);
        }
    }

    private static double normalize(double x) {
        double y = x % (2.0 * Math.PI);
        if (y < 0) y += 2.0 * Math.PI;
        return y;
    }

    private static double shortest(double x) {
        double y = normalize(x);
        if (y > Math.PI) y -= 2.0 * Math.PI;
        return y;
    }

    private record Moon(String name, double orbitRadius, double moonRadius, double initialAngle, Instant initialObservationTime) {
        double periodSeconds() {
            String[] p = name.split("#");
            if (p.length < 3) throw new BadInput();
            return Double.parseDouble(p[1]);
        }
    }

    private static void handleConstellation(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        try {
            JsonNode root = readJson(ex);
            requireObjectWithFields(root, Set.of("star_hashes", "target_constellation"));
            JsonNode hashesNode = root.get("star_hashes");
            if (!hashesNode.isArray() || hashesNode.size() > 1000) throw new BadInput();
            List<String> stars = new ArrayList<>();
            Set<String> uniq = new HashSet<>();
            for (JsonNode n : hashesNode) {
                if (!n.isTextual()) throw new BadInput();
                String s = n.asText();
                if (!uniq.add(s)) throw new BadInput();
                stars.add(s);
            }

            JsonNode targetConst = root.get("target_constellation");
            requireObjectWithFields(targetConst, Set.of("edges"));
            JsonNode edgesNode = targetConst.get("edges");
            if (!edgesNode.isArray()) throw new BadInput();
            List<WEdge> targetEdges = new ArrayList<>();
            int maxV = -1;
            Set<Long> euniq = new HashSet<>();
            for (JsonNode e : edgesNode) {
                requireObjectWithFields(e, Set.of("from", "to", "distance"));
                int from = nonNegativeInt(e, "from");
                int to = nonNegativeInt(e, "to");
                if (from == to) throw new BadInput();
                double d = positiveDouble(e, "distance");
                maxV = Math.max(maxV, Math.max(from, to));
                int a = Math.min(from, to), b = Math.max(from, to);
                long key = (((long) a) << 32) ^ b;
                if (!euniq.add(key)) throw new BadInput();
                targetEdges.add(new WEdge(from, to, d));
            }
            int n = maxV + 1;
            if (n < 2 || n > 50 || targetEdges.size() != n - 1) throw new BadInput();
            if (!isTree(n, targetEdges)) throw new BadInput();

            TargetModel target = TargetModel.build(n, targetEdges);
            int m = stars.size();
            if (m == 0) {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("found", false);
                writeJson(ex, 200, out);
                return;
            }

            Double[][] dist = new Double[m][m];
            DSU dsu = new DSU(m);
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    Double d = GalacticIdentifier.engage(stars.get(i), stars.get(j));
                    dist[i][j] = d;
                    dist[j][i] = d;
                    if (d != null) dsu.union(i, j);
                }
            }

            Map<Integer, List<Integer>> comps = new HashMap<>();
            for (int i = 0; i < m; i++) comps.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);

            List<List<String>> solutions = new ArrayList<>();
            for (List<Integer> comp : comps.values()) {
                if (comp.size() != n) continue;
                Candidate cand = Candidate.fromComponent(comp, dist);
                if (cand == null) continue;
                int[] map = solveIsomorphism(target, cand);
                if (map != null) {
                    List<String> matched = new ArrayList<>();
                    for (int idx : map) matched.add(stars.get(comp.get(idx)));
                    solutions.add(matched);
                    if (solutions.size() > 1) break;
                }
            }

            if (solutions.size() == 1) {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("found", true);
                ArrayNode arr = MAPPER.createArrayNode();
                for (String s : solutions.get(0)) arr.add(s);
                out.set("matched_stars", arr);
                writeJson(ex, 200, out);
            } else {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("found", false);
                writeJson(ex, 200, out);
            }
        } catch (BadInput e) {
            writeIncorrect(ex);
        } catch (Exception e) {
            writeIncorrect(ex);
        }
    }

    private static int[] solveIsomorphism(TargetModel t, Candidate c) {
        int n = t.n;
        if (!Arrays.equals(t.degreesSorted, c.degreesSorted)) return null;
        int[] map = new int[n];
        Arrays.fill(map, -1);
        boolean[] used = new boolean[n];
        return dfsMap(0, map, used, t, c) ? map : null;
    }

    private static boolean dfsMap(int depth, int[] map, boolean[] used, TargetModel t, Candidate c) {
        int n = t.n;
        if (depth == n) return true;
        int v = -1;
        int best = -1;
        for (int i = 0; i < n; i++) if (map[i] == -1) {
            int score = 0;
            for (int nb : t.adj[i]) if (map[nb] != -1) score++;
            if (score > best) { best = score; v = i; }
        }

        List<Integer> options = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            if (!used[u] && t.degree[v] == c.degree[u]) options.add(u);
        }
        options.sort(Comparator.comparingInt(u -> c.signature[u]));

        for (int u : options) {
            if (!fits(v, u, map, t, c)) continue;
            map[v] = u;
            used[u] = true;
            if (dfsMap(depth + 1, map, used, t, c)) return true;
            used[u] = false;
            map[v] = -1;
        }
        return false;
    }

    private static boolean fits(int tv, int cv, int[] map, TargetModel t, Candidate c) {
        List<int[]> newPairs = new ArrayList<>();
        for (int tn : t.adj[tv]) {
            int cn = map[tn];
            if (cn == -1) continue;
            Integer ce = c.edgeIndex(cv, cn);
            if (ce == null) return false;
            int te = t.edgeIndex(tv, tn);
            newPairs.add(new int[]{te, ce});
        }
        for (int i = 0; i < t.edges.size(); i++) {
            int a = t.edges.get(i).u, b = t.edges.get(i).v;
            if (a == tv || b == tv) continue;
            if (map[a] != -1 && map[b] != -1) {
                Integer ce = c.edgeIndex(map[a], map[b]);
                if (ce == null) return false;
                newPairs.add(new int[]{i, ce});
            }
        }
        for (int i = 0; i < newPairs.size(); i++) {
            for (int j = i + 1; j < newPairs.size(); j++) {
                int te1 = newPairs.get(i)[0], te2 = newPairs.get(j)[0];
                int ce1 = newPairs.get(i)[1], ce2 = newPairs.get(j)[1];
                int ts = Double.compare(t.edges.get(te1).w, t.edges.get(te2).w);
                int cs = Double.compare(c.edges.get(ce1).w, c.edges.get(ce2).w);
                if (Integer.signum(ts) != Integer.signum(cs)) return false;
            }
        }
        return true;
    }

    private static class TargetModel {
        final int n;
        final List<EdgeI> edges;
        final List<Integer>[] adj;
        final int[] degree;
        final int[] signature;
        final int[] degreesSorted;
        final Map<Long, Integer> edgeMap;

        private TargetModel(int n, List<EdgeI> edges, List<Integer>[] adj, int[] degree, int[] signature, int[] degreesSorted, Map<Long, Integer> edgeMap) {
            this.n = n;
            this.edges = edges;
            this.adj = adj;
            this.degree = degree;
            this.signature = signature;
            this.degreesSorted = degreesSorted;
            this.edgeMap = edgeMap;
        }

        static TargetModel build(int n, List<WEdge> input) {
            List<EdgeI> edges = new ArrayList<>();
            @SuppressWarnings("unchecked") List<Integer>[] adj = new List[n];
            for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
            int[] degree = new int[n];
            Map<Long, Integer> edgeMap = new HashMap<>();
            for (WEdge e : input) {
                int id = edges.size();
                edges.add(new EdgeI(e.from, e.to, e.w));
                adj[e.from].add(e.to);
                adj[e.to].add(e.from);
                degree[e.from]++;
                degree[e.to]++;
                edgeMap.put(edgeKey(e.from, e.to), id);
            }
            int[] signature = buildSignatures(n, adj, edgeMap, edges);
            int[] ds = degree.clone();
            Arrays.sort(ds);
            return new TargetModel(n, edges, adj, degree, signature, ds, edgeMap);
        }

        int edgeIndex(int a, int b) {
            return edgeMap.get(edgeKey(a, b));
        }
    }

    private static class Candidate {
        final int n;
        final List<EdgeI> edges;
        final List<Integer>[] adj;
        final int[] degree;
        final int[] signature;
        final int[] degreesSorted;
        final Map<Long, Integer> edgeMap;

        private Candidate(int n, List<EdgeI> edges, List<Integer>[] adj, int[] degree, int[] signature, int[] degreesSorted, Map<Long, Integer> edgeMap) {
            this.n = n;
            this.edges = edges;
            this.adj = adj;
            this.degree = degree;
            this.signature = signature;
            this.degreesSorted = degreesSorted;
            this.edgeMap = edgeMap;
        }

        static Candidate fromComponent(List<Integer> comp, Double[][] dist) {
            int n = comp.size();
            List<int[]> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    Double d = dist[comp.get(i)][comp.get(j)];
                    if (d == null) return null;
                    all.add(new int[]{i, j, all.size()});
                }
            }
            List<WEdge> mst = new ArrayList<>();
            List<MstEdge> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    pairs.add(new MstEdge(i, j, dist[comp.get(i)][comp.get(j)]));
                }
            }
            pairs.sort(Comparator.comparingDouble(e -> e.w));
            DSU d = new DSU(n);
            for (MstEdge e : pairs) {
                if (d.union(e.u, e.v)) mst.add(new WEdge(e.u, e.v, e.w));
                if (mst.size() == n - 1) break;
            }
            if (mst.size() != n - 1) return null;

            List<EdgeI> edges = new ArrayList<>();
            @SuppressWarnings("unchecked") List<Integer>[] adj = new List[n];
            for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
            int[] degree = new int[n];
            Map<Long, Integer> edgeMap = new HashMap<>();
            for (WEdge e : mst) {
                int id = edges.size();
                edges.add(new EdgeI(e.from, e.to, e.w));
                adj[e.from].add(e.to);
                adj[e.to].add(e.from);
                degree[e.from]++;
                degree[e.to]++;
                edgeMap.put(edgeKey(e.from, e.to), id);
            }
            int[] signature = buildSignatures(n, adj, edgeMap, edges);
            int[] ds = degree.clone();
            Arrays.sort(ds);
            return new Candidate(n, edges, adj, degree, signature, ds, edgeMap);
        }

        Integer edgeIndex(int a, int b) {
            return edgeMap.get(edgeKey(a, b));
        }
    }

    private static int[] buildSignatures(int n, List<Integer>[] adj, Map<Long, Integer> edgeMap, List<EdgeI> edges) {
        int[] sig = new int[n];
        for (int i = 0; i < n; i++) {
            List<Integer> ranks = new ArrayList<>();
            for (int nb : adj[i]) ranks.add(rankOf(edges, edgeMap.get(edgeKey(i, nb))));
            Collections.sort(ranks);
            sig[i] = Objects.hash(adj[i].size(), ranks);
        }
        return sig;
    }

    private static int rankOf(List<EdgeI> edges, int edgeId) {
        double w = edges.get(edgeId).w;
        int r = 0;
        for (EdgeI e : edges) if (e.w < w) r++;
        return r;
    }

    private static long edgeKey(int a, int b) {
        int x = Math.min(a, b), y = Math.max(a, b);
        return (((long) x) << 32) ^ y;
    }

    private static class MstEdge {
        int u, v;
        double w;
        MstEdge(int u, int v, double w) { this.u = u; this.v = v; this.w = w; }
    }

    private record WEdge(int from, int to, double w) {}
    private record EdgeI(int u, int v, double w) {}

    private static boolean isTree(int n, List<WEdge> edges) {
        DSU d = new DSU(n);
        for (WEdge e : edges) {
            if (!d.union(e.from, e.to)) return false;
        }
        int root = d.find(0);
        for (int i = 1; i < n; i++) if (d.find(i) != root) return false;
        return true;
    }

    private static class DSU {
        int[] p, r;
        DSU(int n) { p = new int[n]; r = new int[n]; for (int i = 0; i < n; i++) p[i] = i; }
        int find(int x) { return p[x] == x ? x : (p[x] = find(p[x])); }
        boolean union(int a, int b) {
            int x = find(a), y = find(b);
            if (x == y) return false;
            if (r[x] < r[y]) { int t = x; x = y; y = t; }
            p[y] = x;
            if (r[x] == r[y]) r[x]++;
            return true;
        }
    }

    // Material modules behavior reimplemented in Java for integration.
    private static class StarKey {
        static double engage(long fuel) {
            long iitml = 70;
            double stage1 = Math.min(fuel, iitml) / 1.234;
            double stage2 = fuel > iitml ? 8.0 * Math.sqrt(fuel - iitml) : 0.0;
            return stage1 + stage2;
        }
    }

    private static class LightAnalyzer {
        static void engage(String moon1, String moon2) {
            String[] a = moon1.split("#");
            String[] b = moon2.split("#");
            if (a.length != 3 || b.length != 3) throw new BadInput();
            long p1 = parseLong(a[1]);
            long p2 = parseLong(b[1]);
            if (p1 == p2) throw new BadInput();
            double up = 2 * Math.PI - Math.toRadians(parseDouble(a[2])) - Math.toRadians(parseDouble(b[2]));
            if (!(up > 0)) throw new BadInput();
        }
    }

    private static class GalacticIdentifier {
        static Double engage(String star1, String star2) {
            String[] a = star1.split("#");
            String[] b = star2.split("#");
            if (a.length < 2 || b.length < 2) throw new BadInput();
            String head1 = a[0].replaceAll("[0-9]", "");
            String head2 = b[0].replaceAll("[0-9]", "");
            if (!head1.equals(head2)) return null;
            int len = Math.min(a.length, b.length);
            double sum = 0.0;
            for (int i = 1; i < len; i++) {
                double d = parseDouble(a[i]) - parseDouble(b[i]);
                sum += d * d;
            }
            return Math.sqrt(sum);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            throw new BadInput();
        }
    }

    private static double parseDouble(String s) {
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (Exception e) {
            throw new BadInput();
        }
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return MAPPER.readTree(is);
        }
    }

    private static void writeIncorrect(HttpExchange ex) throws IOException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "incorrect_input");
        writeJson(ex, 400, out);
    }

    private static void writeJson(HttpExchange ex, int code, JsonNode out) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(out);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void requireObjectWithFields(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject()) throw new BadInput();
        Set<String> actual = new HashSet<>();
        n.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new BadInput();
    }

    private static String text(JsonNode n, String field) {
        JsonNode x = n.get(field);
        if (x == null || !x.isTextual()) throw new BadInput();
        return x.asText();
    }

    private static int positiveInt(JsonNode n, String field) {
        int v = nonNegativeInt(n, field);
        if (v <= 0) throw new BadInput();
        return v;
    }

    private static int nonNegativeInt(JsonNode n, String field) {
        JsonNode x = n.get(field);
        if (x == null || !x.isIntegralNumber()) throw new BadInput();
        long v = x.asLong();
        if (v < 0 || v > Integer.MAX_VALUE) throw new BadInput();
        return (int) v;
    }

    private static double positiveDouble(JsonNode n, String field) {
        double v = finiteDouble(n, field);
        if (!(v > 0)) throw new BadInput();
        return v;
    }

    private static double finiteDouble(JsonNode n, String field) {
        JsonNode x = n.get(field);
        if (x == null || !x.isNumber()) throw new BadInput();
        double v = x.asDouble();
        if (!Double.isFinite(v)) throw new BadInput();
        return v;
    }

    private static class BadInput extends RuntimeException {}
}
