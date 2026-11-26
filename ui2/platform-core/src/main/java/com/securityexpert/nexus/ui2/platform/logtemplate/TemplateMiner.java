package com.securityexpert.nexus.ui2.platform.logtemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small Drain-style online template miner (He et al., ICWS 2017): a fixed-depth parse tree whose first layer is the
 * token count of a message, whose next {@code depth - 2} layers are the leading tokens, and whose leaves hold template
 * clusters. A message joins the most similar cluster of its leaf when the share of equal tokens reaches the threshold
 * (the cluster's differing positions become {@link #WILDCARD}); otherwise it opens a new cluster.
 *
 * <p>Bounds: a node keeps at most {@code maxChildren} children (the rest share one wildcard child), a message is cut
 * at {@code maxTokens} tokens, and the table holds at most {@code maxClusters} clusters; a message that would need a
 * cluster beyond that returns {@link #OVERFLOW} and is not remembered. Tokens that contain a digit route as wildcards
 * in the tree. Callers normalise a message first (the census collapses numbers, hex, addresses and masked tokens to
 * {@link #WILDCARD}); the miner never sees raw text of its own accord and keeps only the template tokens.</p>
 *
 * <p><b>Frozen mode</b> (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 4.1 step 5): {@link #frozen} rebuilds the
 * tree from a frozen catalog's templates without merging any two of them, and {@link #match} looks a message up without
 * ever creating or widening a cluster, so the same message under the same catalog always gets the same cluster. A frozen
 * miner refuses {@link #add}. The census and the catalog build use the online {@link #add}; the log detection engine's
 * online matching uses only {@link #match}.</p>
 *
 * <p>Not thread safe while mining; a frozen miner is read-only after {@link #frozen} returns and may be shared.</p>
 */
public final class TemplateMiner {
    public static final String WILDCARD = "<*>";
    /** Returned by {@link #add} when the cluster table is full and the message matched no existing cluster. */
    public static final int OVERFLOW = -1;
    /** Returned by {@link #add} for a message without a token. */
    public static final int EMPTY = -2;
    /** Returned by {@link #match} when no frozen cluster takes the message. */
    public static final int NO_MATCH = -3;

    /** Miner parameters. {@code depth} counts the length layer, the token layers and the leaf (at least 3). */
    public record Params(int depth, double similarity, int maxChildren, int maxClusters, int maxTokens) {
        public Params {
            if (depth < 3 || depth > 8) throw new IllegalArgumentException("depth must be 3..8");
            if (!(similarity > 0.0 && similarity <= 1.0)) throw new IllegalArgumentException("similarity must be in (0,1]");
            if (maxChildren < 2) throw new IllegalArgumentException("maxChildren must be at least 2");
            if (maxClusters < 1) throw new IllegalArgumentException("maxClusters must be at least 1");
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be at least 1");
        }

        public static Params defaults() {
            return new Params(4, 0.5, 100, 20_000, 64);
        }
    }

    /** One template: its tokens and how many messages joined it. */
    public static final class Cluster {
        private final int id;
        private final String[] tokens;
        private long size;

        private Cluster(int id, String[] tokens) {
            this.id = id;
            this.tokens = tokens;
        }

        public int id() {
            return id;
        }

        public long size() {
            return size;
        }

        public String text() {
            return String.join(" ", tokens);
        }

        /** A copy of the template's tokens. */
        public List<String> tokens() {
            return List.of(tokens);
        }
    }

    private static final class Node {
        final Map<String, Node> children = new HashMap<>();
        List<Cluster> clusters; // leaves only
    }

    private final Params params;
    private final Map<Integer, Node> byLength = new HashMap<>();
    private final List<Cluster> all = new ArrayList<>();
    private boolean frozen;

    public TemplateMiner(Params params) {
        this.params = params;
    }

    /**
     * A frozen miner over {@code templates} (each a template text, tokens separated by single spaces, {@link #WILDCARD}
     * for a parameter). Cluster {@code i} is {@code templates.get(i)}; no two templates are merged, whatever their
     * similarity. Templates longer than {@code maxTokens} are cut like messages.
     */
    public static TemplateMiner frozen(Params params, List<String> templates) {
        TemplateMiner m = new TemplateMiner(new Params(params.depth(), params.similarity(), params.maxChildren(),
                Math.max(params.maxClusters(), Math.max(1, templates.size())), params.maxTokens()));
        for (String template : templates) {
            String[] tokens = m.tokenize(template);
            if (tokens.length == 0) throw new IllegalArgumentException("empty template");
            Node leaf = m.leafFor(tokens);
            Cluster c = new Cluster(m.all.size(), tokens);
            m.all.add(c);
            leaf.clusters.add(c);
        }
        m.frozen = true;
        return m;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * The frozen cluster of a normalised message, or {@link #NO_MATCH} / {@link #EMPTY}. Never creates or widens a
     * cluster. Candidates are the clusters of every leaf the message can reach (at each token layer the token's own child
     * and the wildcard child). The first rule that has a candidate decides, ties broken by the lower cluster id:
     * <ol>
     *   <li>a cluster whose every non-wildcard token equals the message's token at that position, the one with the most
     *       non-wildcard tokens (the most specific);</li>
     *   <li>otherwise the Drain similarity of {@link #add} (share of equal tokens), the highest that reaches the
     *       threshold.</li>
     * </ol>
     */
    public int match(String message) {
        String[] tokens = tokenize(message);
        if (tokens.length == 0) return EMPTY;
        Node root = byLength.get(tokens.length);
        if (root == null) return NO_MATCH;
        List<Cluster> candidates = new ArrayList<>();
        collect(root, tokens, 0, Math.min(params.depth() - 2, tokens.length), candidates);
        Cluster exact = null;
        int exactConstants = -1;
        Cluster similar = null;
        double similarSim = -1;
        for (Cluster c : candidates) {
            int same = 0;
            int constants = 0;
            boolean all = true;
            for (int i = 0; i < tokens.length; i++) {
                if (WILDCARD.equals(c.tokens[i])) continue;
                constants++;
                if (c.tokens[i].equals(tokens[i])) same++;
                else all = false;
            }
            if (all && (constants > exactConstants || (constants == exactConstants && c.id < exact.id))) {
                exact = c;
                exactConstants = constants;
            }
            double sim = (double) same / tokens.length;
            if (sim > similarSim || (sim == similarSim && similar != null && c.id < similar.id)) {
                similar = c;
                similarSim = sim;
            }
        }
        if (exact != null) return exact.id;
        if (similar != null && similarSim >= params.similarity()) return similar.id;
        return NO_MATCH;
    }

    private void collect(Node node, String[] tokens, int layer, int layers, List<Cluster> out) {
        if (layer == layers) {
            if (node.clusters != null) out.addAll(node.clusters);
            return;
        }
        String key = routeKey(tokens[layer]);
        Node own = node.children.get(key);
        if (own != null) collect(own, tokens, layer + 1, layers, out);
        if (!WILDCARD.equals(key)) {
            Node wild = node.children.get(WILDCARD);
            if (wild != null) collect(wild, tokens, layer + 1, layers, out);
        }
    }

    /** The leaf a token sequence routes to, creating the path (bounded children: the rest share the wildcard child). */
    private Node leafFor(String[] tokens) {
        Node node = byLength.computeIfAbsent(tokens.length, k -> new Node());
        int tokenLayers = params.depth() - 2;
        for (int i = 0; i < tokenLayers && i < tokens.length; i++) {
            String key = routeKey(tokens[i]);
            Node child = node.children.get(key);
            if (child == null) {
                if (WILDCARD.equals(key) || node.children.size() < params.maxChildren() - 1) {
                    child = new Node();
                    node.children.put(key, child);
                } else {
                    child = node.children.computeIfAbsent(WILDCARD, k -> new Node());
                }
            }
            node = child;
        }
        if (node.clusters == null) node.clusters = new ArrayList<>(2);
        return node;
    }

    public int clusterCount() {
        return all.size();
    }

    public List<Cluster> clusters() {
        return java.util.Collections.unmodifiableList(all);
    }

    public Cluster cluster(int id) {
        return all.get(id);
    }

    /** Adds one normalised message; returns the id of its cluster, {@link #OVERFLOW} or {@link #EMPTY}. */
    public int add(String message) {
        if (frozen) throw new IllegalStateException("a frozen miner never creates or widens a cluster; use match");
        String[] tokens = tokenize(message);
        if (tokens.length == 0) return EMPTY;
        Node node = leafFor(tokens);
        Cluster best = null;
        double bestSim = -1;
        int bestParams = -1;
        for (Cluster c : node.clusters) {
            int same = 0;
            int wild = 0;
            for (int i = 0; i < tokens.length; i++) {
                if (WILDCARD.equals(c.tokens[i])) wild++;
                else if (c.tokens[i].equals(tokens[i])) same++;
            }
            double sim = (double) same / tokens.length;
            if (sim > bestSim || (sim == bestSim && wild > bestParams)) {
                best = c;
                bestSim = sim;
                bestParams = wild;
            }
        }
        if (best != null && bestSim >= params.similarity()) {
            for (int i = 0; i < tokens.length; i++) {
                if (!WILDCARD.equals(best.tokens[i]) && !best.tokens[i].equals(tokens[i])) best.tokens[i] = WILDCARD;
            }
            best.size++;
            return best.id;
        }
        if (all.size() >= params.maxClusters()) return OVERFLOW;
        Cluster created = new Cluster(all.size(), tokens);
        created.size = 1;
        all.add(created);
        node.clusters.add(created);
        return created.id;
    }

    private String[] tokenize(String message) {
        if (message == null) return new String[0];
        String trimmed = message.strip();
        if (trimmed.isEmpty()) return new String[0];
        String[] parts = trimmed.split("\\s+");
        if (parts.length > params.maxTokens()) return java.util.Arrays.copyOf(parts, params.maxTokens());
        return parts;
    }

    private static String routeKey(String token) {
        if (WILDCARD.equals(token)) return WILDCARD;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) return WILDCARD;
        }
        return token;
    }
}
