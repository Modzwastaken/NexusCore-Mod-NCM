package com.mwtstudios.nexuscore.moderation;

import java.util.ArrayList;
import java.util.List;
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

        document.records.add(record);
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
        active.ifPresent(record -> {
            record.active = false;
            record.liftedByUuid = actor == null ? null : actor.toString();
            record.liftedByName = actorName;
            record.liftedAtEpochMillis = clock.getAsLong();
            save();
        });
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
        String id = target.toString();
        String kind = type.name();
        long now = clock.getAsLong();
        boolean expiredSomething = false;
        Record found = null;

        for (Record record : document.records) {
            if (!record.active || !kind.equals(record.type) || !id.equals(record.targetUuid)) {
                continue;
            }
            if (record.expiresAtEpochMillis != Long.MAX_VALUE && record.expiresAtEpochMillis <= now) {
                record.active = false;
                record.liftedByName = "expiry";
                record.liftedAtEpochMillis = now;
                expiredSomething = true;
                continue;
            }
            found = record;
        }
        if (expiredSomething) {
            save();
        }
        return Optional.ofNullable(found);
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
        for (Record record : new ArrayList<>(document.records)) {
            if (record.active && Type.BAN.name().equals(record.type)) {
                UUID target = parseUuid(record.targetUuid);
                if (target != null) {
                    activeRecord(Type.BAN, target).ifPresent(found::add);
                }
            }
        }
        return found;
    }

    /** @return how many records exist in total, active or not */
    public int totalRecords() {
        return document.records.size();
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
