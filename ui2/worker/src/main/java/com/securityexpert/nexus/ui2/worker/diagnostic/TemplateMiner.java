package com.securityexpert.nexus.ui2.worker.diagnostic;

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
 * <p>Not thread safe.</p>
 */
public final class TemplateMiner {
    public static final String WILDCARD = "<*>";
    /** Returned by {@link #add} when the cluster table is full and the message matched no existing cluster. */
    public static final int OVERFLOW = -1;
    /** Returned by {@link #add} for a message without a token. */
    public static final int EMPTY = -2;

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
    }

    private static final class Node {
        final Map<String, Node> children = new HashMap<>();
        List<Cluster> clusters; // leaves only
    }

    private final Params params;
    private final Map<Integer, Node> byLength = new HashMap<>();
    private final List<Cluster> all = new ArrayList<>();

    public TemplateMiner(Params params) {
        this.params = params;
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
        String[] tokens = tokenize(message);
        if (tokens.length == 0) return EMPTY;
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
