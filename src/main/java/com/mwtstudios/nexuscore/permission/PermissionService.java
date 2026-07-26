package com.mwtstudios.nexuscore.permission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.storage.JsonStore;

/**
 * The native permission engine (§9), implementing the precedence rules of ADR-0004.
 *
 * <p>There is exactly one evaluator. Commands, the admin GUI, and every future API path call
 * {@link #evaluate}, which is what makes "the GUI and command paths use the same evaluator"
 * true by construction rather than by convention.</p>
 *
 * <p>Precedence, highest first: specificity score · direct subject node beats group node ·
 * explicit DENY beats ALLOW · higher group weight · pattern then source lexicographically.
 * The last step exists purely to make the order <em>total</em>, so repeated evaluation of an
 * unchanged data set always produces the same answer.</p>
 */
public final class PermissionService {

    /** File name under the NexusCore data directory. */
    public static final String FILE = "permissions.json";

    /** Current schema version of the permission document. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Granted to operators when {@code operatorBootstrap} is enabled. */
    public static final String BOOTSTRAP_NODE = "nexuscore.bootstrap";

    private final JsonStore store;
    private final Document document;

    /** Bounded LRU of evaluated decisions, cleared whenever the data changes. */
    private final Map<String, PermissionDecision> cache;
    private int cacheHits;
    private int cacheMisses;

    /**
     * @param store the data store holding {@value #FILE}
     * @param cacheSize maximum cached decisions; bounded per §16
     */
    public PermissionService(JsonStore store, int cacheSize) {
        this.store = store;
        this.document = store.read(FILE, Document.class, PermissionService::defaults);
        normalise();
        int bound = Math.max(64, cacheSize);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PermissionDecision> eldest) {
                return size() > bound;
            }
        };
        save();
    }

    // ---- evaluation --------------------------------------------------------------------

    /**
     * Evaluates a node for a subject.
     *
     * @param subject the player's UUID
     * @param node the permission node being requested
     * @return the decision, with an explanation attached
     */
    public synchronized PermissionDecision evaluate(UUID subject, String node) {
        String key = subject + "|" + node.toLowerCase(Locale.ROOT);
        PermissionDecision cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        cacheMisses++;
        PermissionDecision decision = evaluateUncached(subject, node);
        cache.put(key, decision);
        return decision;
    }

    /**
     * Convenience for call sites that only need a yes or no.
     *
     * @param subject the player's UUID
     * @param node the permission node
     * @return true only when the decision is ALLOW
     */
    public boolean allows(UUID subject, String node) {
        return evaluate(subject, node).allowed();
    }

    private PermissionDecision evaluateUncached(UUID subject, String node) {
        String request = node.toLowerCase(Locale.ROOT);
        List<Candidate> candidates = new ArrayList<>();

        Subject record = document.subjects.get(subject.toString());

        if (record != null && record.nodes != null) {
            collect(candidates, record.nodes, true, "direct", 0);
        }

        for (String groupName : resolveGroupChain(record)) {
            Group group = document.groups.get(groupName);
            if (group != null && group.nodes != null) {
                collect(candidates, group.nodes, false, "group:" + groupName, group.weight);
            }
        }

        Candidate best = candidates.stream()
                .filter(candidate -> candidate.node().matches(request))
                .min(PRECEDENCE)
                .orElse(null);

        if (best == null) {
            return PermissionDecision.undefined(request);
        }
        PermissionDecision.Result result = best.allow()
                ? PermissionDecision.Result.ALLOW
                : PermissionDecision.Result.DENY;

        String explanation = (best.allow() ? "granted" : "denied")
                + " by pattern '" + best.node().pattern() + "' (specificity " + best.node().specificity() + ")"
                + " from " + best.source()
                + (best.direct()
                        ? " — this is a direct grant on the player, which outranks any group rule at the same "
                                + "specificity; removing it requires removing the direct node, not adding a group deny"
                        : " with weight " + best.weight());

        return new PermissionDecision(request, result, best.node().pattern(), best.source(), explanation);
    }

    /**
     * ADR-0004 precedence. Lower compares first, so {@code min()} yields the winner.
     */
    private static final Comparator<Candidate> PRECEDENCE = Comparator
            .comparingInt((Candidate c) -> -c.node().specificity())
            .thenComparing(c -> c.direct() ? 0 : 1)
            .thenComparing(c -> c.allow() ? 1 : 0)
            .thenComparing(c -> -c.weight())
            .thenComparing(c -> c.node().pattern())
            .thenComparing(Candidate::source);

    private static void collect(List<Candidate> into, Map<String, String> nodes, boolean direct, String source, int weight) {
        for (Map.Entry<String, String> entry : nodes.entrySet()) {
            if (!PermissionNode.isValid(entry.getKey())) {
                continue;
            }
            boolean allow = !"DENY".equalsIgnoreCase(entry.getValue());
            into.add(new Candidate(PermissionNode.of(entry.getKey()), allow, direct, source, weight));
        }
    }

    // ---- groups ------------------------------------------------------------------------

    /**
     * Resolves a subject's group chain in stable order, following inheritance.
     *
     * @param record the subject, or null for a player with no record
     * @return group names, most specific membership first
     */
    private List<String> resolveGroupChain(Subject record) {
        Set<String> ordered = new LinkedHashSet<>();
        List<String> roots = new ArrayList<>();
        if (record != null && record.groups != null) {
            roots.addAll(record.groups);
        }
        if (roots.isEmpty()) {
            roots.add(document.defaultGroup);
        }
        for (String root : roots) {
            walkInheritance(root, ordered, new LinkedHashSet<>());
        }
        return new ArrayList<>(ordered);
    }

    private void walkInheritance(String groupName, Set<String> ordered, Set<String> visiting) {
        if (groupName == null || ordered.contains(groupName) || !visiting.add(groupName)) {
            // Already resolved, or a cycle. Cycles are rejected at write time; tolerating one
            // here rather than recursing forever keeps a hand-edited file from hanging the server.
            return;
        }
        Group group = document.groups.get(groupName);
        if (group == null) {
            return;
        }
        ordered.add(groupName);
        if (group.inherits != null) {
            for (String parent : group.inherits) {
                walkInheritance(parent, ordered, visiting);
            }
        }
    }

    /**
     * Detects whether adding {@code parent} to {@code child} would create an inheritance cycle.
     *
     * @param child the group that would inherit
     * @param parent the group it would inherit from
     * @return true when the edge would close a loop
     */
    public synchronized boolean wouldCycle(String child, String parent) {
        if (child.equals(parent)) {
            return true;
        }
        Set<String> seen = new LinkedHashSet<>();
        return reaches(parent, child, seen);
    }

    private boolean reaches(String from, String target, Set<String> seen) {
        if (from.equals(target)) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        Group group = document.groups.get(from);
        if (group == null || group.inherits == null) {
            return false;
        }
        for (String parent : group.inherits) {
            if (reaches(parent, target, seen)) {
                return true;
            }
        }
        return false;
    }

    // ---- mutation ----------------------------------------------------------------------

    /**
     * Grants or denies a node directly on a player.
     *
     * @param subject the player
     * @param node the pattern
     * @param allow true to grant, false to explicitly deny
     * @throws IllegalArgumentException if the pattern is malformed
     */
    public synchronized void setSubjectNode(UUID subject, String node, boolean allow) {
        PermissionNode parsed = PermissionNode.of(node);
        Subject record = document.subjects.computeIfAbsent(subject.toString(), key -> new Subject());
        record.nodes.put(parsed.pattern(), allow ? "ALLOW" : "DENY");
        invalidateAndSave();
    }

    /**
     * Removes a direct node from a player.
     *
     * @param subject the player
     * @param node the pattern
     * @return true when something was removed
     */
    public synchronized boolean removeSubjectNode(UUID subject, String node) {
        Subject record = document.subjects.get(subject.toString());
        if (record == null || record.nodes.remove(node.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        invalidateAndSave();
        return true;
    }

    /**
     * Adds a player to a group.
     *
     * @param subject the player
     * @param groupName the group
     * @return true when the group exists and the player was added
     */
    public synchronized boolean addToGroup(UUID subject, String groupName) {
        if (!document.groups.containsKey(groupName)) {
            return false;
        }
        Subject record = document.subjects.computeIfAbsent(subject.toString(), key -> new Subject());
        if (record.groups.contains(groupName)) {
            return false;
        }
        record.groups.add(groupName);
        invalidateAndSave();
        return true;
    }

    /**
     * Removes a player from a group.
     *
     * @param subject the player
     * @param groupName the group
     * @return true when the player was in it
     */
    public synchronized boolean removeFromGroup(UUID subject, String groupName) {
        Subject record = document.subjects.get(subject.toString());
        if (record == null || !record.groups.remove(groupName)) {
            return false;
        }
        invalidateAndSave();
        return true;
    }

    /**
     * Creates a group.
     *
     * @param groupName the new group's name
     * @param weight tie-break weight; higher wins at equal specificity
     * @return true when it did not already exist
     */
    public synchronized boolean createGroup(String groupName, int weight) {
        if (document.groups.containsKey(groupName)) {
            return false;
        }
        Group group = new Group();
        group.weight = weight;
        document.groups.put(groupName, group);
        invalidateAndSave();
        return true;
    }

    /**
     * Sets a node on a group.
     *
     * @param groupName the group
     * @param node the pattern
     * @param allow true to grant, false to deny
     * @return true when the group exists
     */
    public synchronized boolean setGroupNode(String groupName, String node, boolean allow) {
        Group group = document.groups.get(groupName);
        if (group == null) {
            return false;
        }
        group.nodes.put(PermissionNode.of(node).pattern(), allow ? "ALLOW" : "DENY");
        invalidateAndSave();
        return true;
    }

    /**
     * Makes one group inherit another, refusing to create a cycle.
     *
     * @param child the inheriting group
     * @param parent the inherited group
     * @return an outcome describing what happened
     */
    public synchronized InheritResult addInheritance(String child, String parent) {
        if (!document.groups.containsKey(child) || !document.groups.containsKey(parent)) {
            return InheritResult.UNKNOWN_GROUP;
        }
        if (wouldCycle(child, parent)) {
            return InheritResult.WOULD_CYCLE;
        }
        Group group = document.groups.get(child);
        if (!group.inherits.contains(parent)) {
            group.inherits.add(parent);
            invalidateAndSave();
        }
        return InheritResult.OK;
    }

    /** Outcome of an inheritance edit. */
    public enum InheritResult {
        /** The edge was added. */
        OK,
        /** One of the groups does not exist. */
        UNKNOWN_GROUP,
        /** The edge would close an inheritance loop and was refused. */
        WOULD_CYCLE
    }

    // ---- queries -----------------------------------------------------------------------

    /** @return every group name, sorted */
    public synchronized Set<String> groupNames() {
        return new TreeSet<>(document.groups.keySet());
    }

    /** @return the configured default group */
    public synchronized String defaultGroup() {
        return document.defaultGroup;
    }

    /**
     * @param subject the player
     * @return the group names that apply to them, including inherited ones
     */
    public synchronized List<String> groupsOf(UUID subject) {
        return resolveGroupChain(document.subjects.get(subject.toString()));
    }

    /**
     * @param subject the player
     * @return their direct nodes, or an empty map
     */
    public synchronized Map<String, String> directNodesOf(UUID subject) {
        Subject record = document.subjects.get(subject.toString());
        return record == null ? Map.of() : new LinkedHashMap<>(record.nodes);
    }

    /**
     * @param groupName the group
     * @return its weight, if it exists
     */
    public synchronized Optional<Integer> weightOf(String groupName) {
        Group group = document.groups.get(groupName);
        return Optional.ofNullable(group == null ? null : group.weight);
    }

    /** @return a one-line cache statistic for diagnostics */
    public synchronized String cacheStats() {
        return "size=" + cache.size() + " hits=" + cacheHits + " misses=" + cacheMisses;
    }

    /** Applies a settings change that affects this service. */
    public synchronized void applySettings(NexusSettings settings) {
        if (!settings.defaultGroup.equals(document.defaultGroup) && document.groups.containsKey(settings.defaultGroup)) {
            document.defaultGroup = settings.defaultGroup;
            invalidateAndSave();
        }
    }

    /** Clears the decision cache. Called whenever anything that feeds a decision changes. */
    public synchronized void invalidate() {
        cache.clear();
    }

    private void invalidateAndSave() {
        cache.clear();
        save();
    }

    private void save() {
        document.schemaVersion = CURRENT_SCHEMA_VERSION;
        store.write(FILE, document);
    }

    private void normalise() {
        if (document.groups == null) {
            document.groups = new LinkedHashMap<>();
        }
        if (document.subjects == null) {
            document.subjects = new LinkedHashMap<>();
        }
        if (document.defaultGroup == null || !document.groups.containsKey(document.defaultGroup)) {
            document.defaultGroup = document.groups.containsKey("default") ? "default"
                    : document.groups.keySet().stream().findFirst().orElse("default");
        }
        document.groups.values().forEach(group -> {
            if (group.nodes == null) {
                group.nodes = new LinkedHashMap<>();
            }
            if (group.inherits == null) {
                group.inherits = new ArrayList<>();
            }
        });
        document.subjects.values().forEach(subject -> {
            if (subject.nodes == null) {
                subject.nodes = new LinkedHashMap<>();
            }
            if (subject.groups == null) {
                subject.groups = new ArrayList<>();
            }
        });
    }

    /**
     * The shipped starting policy: ordinary players get the self-service commands, moderators
     * get the moderation and player tools plus the admin panel, admins get everything.
     *
     * <p>Note that {@code admin} is granted {@code nexuscore.*} but moderators are not, and
     * that no group is granted the high-risk nodes by default (Appendix B).</p>
     */
    private static Document defaults() {
        Document document = new Document();

        Group def = new Group();
        def.weight = 0;
        for (String node : List.of(
                "nexuscore.command.core.version",
                "nexuscore.command.core.help",
                "nexuscore.command.teleport.home",
                "nexuscore.command.teleport.sethome",
                "nexuscore.command.teleport.delhome",
                "nexuscore.command.teleport.homes",
                "nexuscore.command.teleport.warp",
                "nexuscore.command.teleport.warps",
                "nexuscore.command.teleport.spawn",
                "nexuscore.command.teleport.back",
                "nexuscore.command.teleport.request",
                "nexuscore.command.teleport.accept",
                "nexuscore.command.teleport.deny")) {
            def.nodes.put(node, "ALLOW");
        }
        document.groups.put("default", def);

        Group moderator = new Group();
        moderator.weight = 50;
        moderator.inherits.add("default");
        moderator.prefix = "&7[Mod] ";
        for (String node : List.of(
                "nexuscore.command.moderation.*",
                "nexuscore.command.player.*",
                "nexuscore.command.teleport.tp",
                "nexuscore.command.teleport.setwarp",
                "nexuscore.gui.admin.open",
                "nexuscore.gui.admin.players")) {
            moderator.nodes.put(node, "ALLOW");
        }
        document.groups.put("moderator", moderator);

        Group admin = new Group();
        admin.weight = 100;
        admin.inherits.add("moderator");
        admin.prefix = "&c[Admin] ";
        admin.nodes.put("nexuscore.*", "ALLOW");
        document.groups.put("admin", admin);

        document.defaultGroup = "default";
        return document;
    }

    // ---- persisted shapes --------------------------------------------------------------

    /** One resolved candidate rule. */
    private record Candidate(PermissionNode node, boolean allow, boolean direct, String source, int weight) {
    }

    /** Persisted shape of {@value #FILE}. */
    static final class Document {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        String defaultGroup = "default";
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, Subject> subjects = new LinkedHashMap<>();
    }

    /** Persisted shape of a group. */
    static final class Group {
        int weight;
        List<String> inherits = new ArrayList<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        String prefix = "";
        String suffix = "";
    }

    /** Persisted shape of a player's direct permission record. */
    static final class Subject {
        List<String> groups = new ArrayList<>();
        Map<String, String> nodes = new LinkedHashMap<>();
    }
}
