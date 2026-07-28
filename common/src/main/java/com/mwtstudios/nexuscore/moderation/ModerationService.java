package com.mwtstudios.nexuscore.moderation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.mwtstudios.nexuscore.storage.JsonStore;

/**
 * Native punishments: bans, temporary bans, mutes, warnings, and their history.
 *
 * <p>NexusCore keeps its own punishment records rather than driving vanilla's ban list. That
 * is a deliberate choice: vanilla bans carry no duration, no actor UUID, no warning history,
 * and no audit linkage, and NexusCore needs all four. The trade-off is that a NexusCore ban
 * is enforced by NexusCore — remove the mod and the bans stop applying — which is stated
 * plainly in the admin documentation rather than left for an operator to discover.</p>
 *
 * <p>Punishments are <b>never deleted</b>. Unbanning marks the record inactive and stamps who
 * lifted it and when. History that can be erased is not history, and an operator reviewing a
 * repeat offender needs to see that the previous ban was lifted, not find no trace of it.</p>
 *
 * <p>Expiry is evaluated on read rather than by a sweep, so a temporary ban is over the
 * instant its expiry passes, with no dependence on a scheduler having run.</p>
 */
public final class ModerationService {

    /** File name under the NexusCore data directory. */
    public static final String FILE = "punishments.json";

    /** Current schema version of the punishment document. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final JsonStore store;
    private final LongSupplier clock;
    private final Document document;

    /**
     * Active records grouped by {@code TYPE|targetUuid}.
     *
     * <p>{@code activeRecord()} runs on the chat path and the login path, and it used to scan every
     * punishment ever written to find the handful belonging to one player — so the cost of sending a
     * chat message grew with the total history of the server, which is the one number that only ever
     * goes up. Records are never deleted here (history that can be erased is not history), so the
     * scan could not be bounded by pruning; an index over the active subset is what bounds it.</p>
     *
     * <p>Holds the same {@link Record} objects as {@code document.records}, never copies, so a flag
     * flipped through one is visible through the other. Entries leave when a record stops being
     * active. This is a lookup structure rebuilt from the document, never persisted — the document
     * remains the only source of truth.</p>
     */
    private final Map<String, List<Record>> activeBySubject = new HashMap<>();

    /**
     * @param store the data store
     * @param clock supplies the current time in milliseconds
     */
    public ModerationService(JsonStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        this.document = store.read(FILE, Document.class, Document::new);
        if (document.records == null) {
            document.records = new ArrayList<>();
        }
        rebuildActiveIndex();
    }

    /** The kinds of punishment NexusCore records. */
    public enum Type {
        /** Removed from the server once. Never active afterwards. */
        KICK,
        /** Blocked from joining. */
        BAN,
        /** Blocked from chatting. */
        MUTE,
        /** Recorded against the player, with no immediate effect. */
        WARNING
    }

    // ---- issuing -----------------------------------------------------------------------

    /**
     * Records a punishment.
     *
     * @param type what kind
     * @param target who it applies to
     * @param targetName their name at the time, for readable history
     * @param actor who issued it, or null for the console
     * @param actorName the issuer's display name
     * @param reason the operator-supplied reason
     * @param expiresAtEpochMillis when it lapses, or {@link Long#MAX_VALUE} for permanent
     * @return the stored record
     */
    public Record issue(Type type, UUID target, String targetName, UUID actor, String actorName,
            String reason, long expiresAtEpochMillis) {
        Record record = new Record();
        record.id = UUID.randomUUID().toString();
        record.type = type.name();
        record.targetUuid = target.toString();
        record.targetName = targetName;
        record.actorUuid = actor == null ? null : actor.toString();
        record.actorName = actorName;
        record.reason = reason;
        record.issuedAtEpochMillis = clock.getAsLong();
        record.expiresAtEpochMillis = expiresAtEpochMillis;
        record.active = type != Type.KICK && type != Type.WARNING;

        if (record.active) {
            // At most one punishment of a kind is in force at a time. Without this a second ban
            // left the first active too: /unban lifted one, reported success, and the player
            // stayed banned. The superseded record is kept — punishments are never deleted —
            // and stamped so the history shows what replaced it.
            for (Record existing : document.records) {
                if (existing.active && existing != record
                        && record.type.equals(existing.type)
                        && record.targetUuid.equals(existing.targetUuid)) {
                    existing.active = false;
                    dropFromActiveIndex(existing);
                    existing.liftedByUuid = record.actorUuid;
                    existing.liftedByName = actorName;
                    existing.liftedAtEpochMillis = record.issuedAtEpochMillis;
                    existing.supersededByRecordId = record.id;
                }
            }
        }

        document.records.add(record);
        if (record.active) {
            addToActiveIndex(record);
        }
        save();
        return record;
    }

    /**
     * Lifts the active punishment of a kind, keeping the record for history.
     *
     * @param type which kind to lift
     * @param target whose punishment
     * @param actor who lifted it, or null for the console
     * @param actorName the lifter's display name
     * @return the lifted record, if one was active
     */
    public Optional<Record> lift(Type type, UUID target, UUID actor, String actorName) {
        Optional<Record> active = activeRecord(type, target);
        if (active.isEmpty()) {
            return active;
        }
        // reconcile() has already left exactly one record of this kind in force, so retiring it
        // genuinely unbans. The original defect was lifting one of several and reporting success.
        Record inForce = active.get();
        inForce.active = false;
        inForce.liftedByUuid = actor == null ? null : actor.toString();
        inForce.liftedByName = actorName;
        inForce.liftedAtEpochMillis = clock.getAsLong();
        dropFromActiveIndex(inForce);
        save();
        return active;
    }

    // ---- queries -----------------------------------------------------------------------

    /**
     * Finds the punishment currently in force.
     *
     * <p>Expiry is checked here, so a lapsed temporary ban is reported as absent even though
     * its record still exists. When a record is found to have lapsed it is deactivated and
     * persisted, so the file converges without needing a sweep task.</p>
     *
     * @param type which kind
     * @param target whose
     * @return the active record, if any
     */
    public Optional<Record> activeRecord(Type type, UUID target) {
        return reconcile(type, target);
    }

    /**
     * Brings a player's records of one kind into a single consistent state and returns whatever
     * is left in force.
     *
     * <p>Does three things, in order: retires anything whose expiry has passed, picks the
     * strictest of whatever remains, and retires the rest. It is named and called explicitly
     * rather than left as a side effect of a query, because {@link #lift} depends on it having
     * run — a query that quietly mutates is the kind of thing a later refactor removes without
     * noticing what depended on it.</p>
     *
     * <p>Deliberate re-bans never reach the strictest rule: {@link #issue} already retires the
     * previous record, so an operator typing a new {@code /ban} gets the new terms whether they
     * are shorter or longer. What arrives here with several rows in force is data written before
     * {@code issue()} superseded, where there is no ordering intent to honour and the safe
     * reading is the strictest — permanent over any expiry, otherwise the later expiry.</p>
     *
     * @param type which kind
     * @param target whose records
     * @return the single record left in force, if any
     */
    private Optional<Record> reconcile(Type type, UUID target) {
        String id = target.toString();
        String kind = type.name();
        long now = clock.getAsLong();
        boolean changed = false;
        Record strictest = null;
        List<Record> inForce = new ArrayList<>();

        // The index, not the whole document: this runs on every chat message and every login.
        for (Record record : new ArrayList<>(activeBySubject.getOrDefault(subjectKey(kind, id), List.of()))) {
            if (!record.active) {
                continue;
            }
            if (record.expiresAtEpochMillis != Long.MAX_VALUE && record.expiresAtEpochMillis <= now) {
                record.active = false;
                record.liftedByName = "expiry";
                record.liftedAtEpochMillis = now;
                dropFromActiveIndex(record);
                changed = true;
                continue;
            }
            if (strictest == null || isStricter(record, strictest)) {
                strictest = record;
            }
            inForce.add(record);
        }

        for (Record loser : inForce) {
            if (loser != strictest) {
                loser.active = false;
                loser.liftedByName = "superseded";
                loser.liftedAtEpochMillis = now;
                loser.supersededByRecordId = strictest == null ? null : strictest.id;
                dropFromActiveIndex(loser);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return Optional.ofNullable(strictest);
    }

    /**
     * @param candidate a punishment in force
     * @param incumbent the strictest found so far
     * @return true when {@code candidate} keeps the player punished for longer
     */
    private static boolean isStricter(Record candidate, Record incumbent) {
        if (candidate.expiresAtEpochMillis == Long.MAX_VALUE) {
            return incumbent.expiresAtEpochMillis != Long.MAX_VALUE;
        }
        if (incumbent.expiresAtEpochMillis == Long.MAX_VALUE) {
            return false;
        }
        return candidate.expiresAtEpochMillis > incumbent.expiresAtEpochMillis;
    }

    /**
     * @param target the player
     * @return their active ban, if they have one
     */
    public Optional<Record> activeBan(UUID target) {
        return activeRecord(Type.BAN, target);
    }

    /**
     * @param target the player
     * @return their active mute, if they have one
     */
    public Optional<Record> activeMute(UUID target) {
        return activeRecord(Type.MUTE, target);
    }

    /**
     * @param target the player
     * @return every record for them, newest first
     */
    public List<Record> history(UUID target) {
        String id = target.toString();
        List<Record> found = new ArrayList<>();
        for (Record record : document.records) {
            if (id.equals(record.targetUuid)) {
                found.add(record);
            }
        }
        found.sort((a, b) -> Long.compare(b.issuedAtEpochMillis, a.issuedAtEpochMillis));
        return found;
    }

    /**
     * @param target the player
     * @return only their warnings, newest first
     */
    public List<Record> warnings(UUID target) {
        return history(target).stream().filter(record -> Type.WARNING.name().equals(record.type)).toList();
    }

    /** @return every currently active ban */
    public List<Record> activeBans() {
        List<Record> found = new ArrayList<>();
        // Walks the index rather than every punishment ever written, and one subject key IS one
        // player, so the deduplication the file order used to require is now structural.
        // reconcile() mutates the index as it retires records, so iterate a snapshot of the keys.
        String prefix = Type.BAN.name() + '|';
        List<String> subjects = new ArrayList<>();
        for (String key : activeBySubject.keySet()) {
            if (key.startsWith(prefix)) {
                subjects.add(key);
            }
        }
        Set<UUID> counted = new HashSet<>();
        for (String key : subjects) {
            UUID target = parseUuid(key.substring(prefix.length()));
            if (target != null && counted.add(target)) {
                activeRecord(Type.BAN, target).ifPresent(found::add);
            }
        }
        return found;
    }

    /** @return how many records exist in total, active or not */
    public int totalRecords() {
        return document.records.size();
    }

    /**
     * How many records the active index holds.
     *
     * <p>Package-private and test-only: the index's whole purpose is to stay proportional to the
     * ACTIVE punishments rather than to every punishment ever written, and that property is
     * invisible through the public API.</p>
     *
     * @return the total number of indexed records across every subject
     */
    int activeIndexSize() {
        int total = 0;
        for (List<Record> bucket : activeBySubject.values()) {
            total += bucket.size();
        }
        return total;
    }

    /** Key for the active index. */
    private static String subjectKey(String type, String targetUuid) {
        return type + '|' + targetUuid;
    }

    /** Rebuilds the active index from the document. The document stays the only source of truth. */
    private void rebuildActiveIndex() {
        activeBySubject.clear();
        for (Record record : document.records) {
            if (record.active && record.type != null && record.targetUuid != null) {
                addToActiveIndex(record);
            }
        }
    }

    private void addToActiveIndex(Record record) {
        activeBySubject.computeIfAbsent(subjectKey(record.type, record.targetUuid), key -> new ArrayList<>())
                .add(record);
    }

    /**
     * Removes a record that has stopped being active.
     *
     * <p><b>This is a space guarantee, not a correctness one</b>, and the distinction is worth
     * stating because the obvious assumption is the other way round. {@link #reconcile} re-checks
     * {@code active} on every entry it reads, so a retired record left in the index is skipped and
     * cannot resurrect a lifted ban. What a missing drop does is let the index accumulate every
     * record ever written — at which point it is the full scan it was built to replace, wearing a
     * different name and costing memory as well.</p>
     *
     * <p>Verified that way round: removing any single drop call fails no behavioural test, which is
     * why {@code activeIndexSize()} exists and is asserted directly.</p>
     */
    private void dropFromActiveIndex(Record record) {
        List<Record> bucket = activeBySubject.get(subjectKey(record.type, record.targetUuid));
        if (bucket == null) {
            return;
        }
        bucket.removeIf(candidate -> candidate == record);
        if (bucket.isEmpty()) {
            activeBySubject.remove(subjectKey(record.type, record.targetUuid));
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private void save() {
        document.schemaVersion = CURRENT_SCHEMA_VERSION;
        store.write(FILE, document);
    }

    /**
     * Truncates and normalises an operator-supplied reason.
     *
     * @param reason the raw text, possibly null
     * @param maxLength the configured maximum
     * @return a bounded single-line reason, never null
     */
    public static String sanitiseReason(String reason, int maxLength) {
        if (reason == null || reason.isBlank()) {
            return "No reason given";
        }
        String cleaned = reason.replaceAll("[\\r\\n\\t]", " ").trim().replaceAll(" {2,}", " ");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    /** Persisted shape of {@value #FILE}. */
    static final class Document {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        List<Record> records = new ArrayList<>();
    }

    /**
     * One punishment record.
     *
     * <p>A mutable class rather than a record because Gson populates it field by field and
     * because {@code active} genuinely changes over the object's life.</p>
     */
    public static final class Record {
        String id;
        String type;
        String targetUuid;
        String targetName;
        String actorUuid;
        String actorName;
        String reason;
        long issuedAtEpochMillis;
        long expiresAtEpochMillis = Long.MAX_VALUE;
        boolean active;
        String liftedByUuid;
        String liftedByName;
        long liftedAtEpochMillis;
        String supersededByRecordId;

        /** @return the record's stable id */
        public String id() {
            return id;
        }

        /** @return the punishment kind */
        public Type type() {
            return Type.valueOf(type.toUpperCase(Locale.ROOT));
        }

        /** @return the punished player's name at the time it was issued */
        public String targetName() {
            return targetName;
        }

        /** @return who issued it */
        public String actorName() {
            return actorName;
        }

        /** @return the recorded reason */
        public String reason() {
            return reason;
        }

        /** @return when it was issued */
        public long issuedAt() {
            return issuedAtEpochMillis;
        }

        /** @return when it lapses, or {@link Long#MAX_VALUE} */
        public long expiresAt() {
            return expiresAtEpochMillis;
        }

        /** @return true when it is still in force */
        public boolean active() {
            return active;
        }

        /** @return true when it never expires */
        public boolean permanent() {
            return expiresAtEpochMillis == Long.MAX_VALUE;
        }

        /** @return who lifted it, or null */
        public String liftedByName() {
            return liftedByName;
        }
    }
}
