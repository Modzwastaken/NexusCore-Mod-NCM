package com.mwtstudios.nexuscore.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.storage.StorageException;

import org.slf4j.Logger;

/**
 * Append-only, hash-chained audit log (§15.2).
 *
 * <p>Every privileged action writes one record. Each record carries the SHA-256 of the
 * previous record, so removing or editing an entry breaks the chain from that point onward
 * and {@link #verify()} says exactly where. That does not make tampering impossible — anyone
 * with the file can rewrite the whole chain — but it makes <em>quiet</em> tampering
 * impossible, which is the property an operator actually needs.</p>
 *
 * <p>Sensitive parameters are redacted <b>at write time, not read time</b>, as §15.2 requires.
 * A value that never enters the file cannot leak from a support bundle later.</p>
 */
public final class AuditService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** File name under the NexusCore data directory. One JSON object per line. */
    public static final String FILE = "audit.log";

    /** Hash recorded for the very first entry, which has no predecessor. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /** Parameter names whose values never reach the log. */
    private static final List<String> REDACTED_KEYS = List.of("ip", "address", "password", "token", "secret");

    private static final String REDACTED = "[redacted]";

    private final JsonStore store;
    private final String modVersion;
    private final AtomicLong sequence = new AtomicLong();
    private volatile String lastHash = GENESIS_HASH;
    private volatile boolean enabled = true;

    /**
     * @param store the data store the log lives in
     * @param modVersion recorded on every entry so a log spanning upgrades stays interpretable
     */
    public AuditService(JsonStore store, String modVersion) {
        this.store = store;
        this.modVersion = modVersion;
        List<String> existing = store.readLines(FILE);
        sequence.set(existing.size());
        if (!existing.isEmpty()) {
            lastHash = hashOf(existing.get(existing.size() - 1));
        }
    }

    /** Enables or disables writing. Verification and reading remain available either way. */
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** @return how many records have been written since the log was created */
    public long count() {
        return sequence.get();
    }

    /**
     * Writes one audit record.
     *
     * @param actorUuid who performed the action, or null for the console
     * @param actorName display name of the actor, for example {@code CONSOLE}
     * @param action dotted action name, for example {@code moderation.ban}
     * @param targetType what kind of thing was acted on, for example {@code player}
     * @param targetId the identifier of that thing
     * @param result {@code allowed}, {@code denied}, {@code failed}, or a domain-specific outcome
     * @param reason operator-supplied reason, or null
     * @param parameters normalised parameters; sensitive values are redacted here
     * @param correlationId ties this record to the log lines from the same request
     */
    public void record(UUID actorUuid, String actorName, String action, String targetType, String targetId,
            String result, String reason, Map<String, String> parameters, String correlationId) {
        if (!enabled) {
            return;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("record_id", UUID.randomUUID().toString());
        entry.addProperty("sequence", sequence.get());
        entry.addProperty("timestamp_utc", Instant.now().toString());
        entry.addProperty("correlation_id", correlationId);
        entry.addProperty("actor_uuid", actorUuid == null ? null : actorUuid.toString());
        entry.addProperty("actor_name", actorName);
        entry.addProperty("actor_source", actorUuid == null ? "console" : "player");
        entry.addProperty("action", action);
        entry.addProperty("target_type", targetType);
        entry.addProperty("target_id", targetId);
        entry.addProperty("result", result);
        entry.addProperty("reason", reason);
        entry.addProperty("nexuscore_version", modVersion);

        JsonObject params = new JsonObject();
        for (Map.Entry<String, String> parameter : redact(parameters).entrySet()) {
            params.addProperty(parameter.getKey(), parameter.getValue());
        }
        entry.add("parameters", params);
        entry.addProperty("previous_hash", lastHash);

        String line = JsonStore.gson().newBuilder().create().toJson(entry);
        // Serialise compactly: one record per line is what makes the log greppable and
        // makes the chain well defined.
        line = line.replace("\n", "").replace("\r", "");
        try {
            store.appendLine(FILE, line);
            lastHash = hashOf(line);
            sequence.incrementAndGet();
        } catch (StorageException e) {
            // An audit write that fails must be visible. It is not swallowed, and it does not
            // abort the action that was already performed — that would be worse.
            LOGGER.error("NexusCore FAILED to write an audit record for action '{}' by '{}'. "
                    + "The action still happened; the audit trail now has a gap.", action, actorName, e);
        }
    }

    /**
     * Walks the chain from the beginning and reports the first record whose recorded
     * predecessor hash does not match the actual predecessor.
     *
     * @return the verification outcome
     */
    public Verification verify() {
        List<String> lines = store.readLines(FILE);
        String expected = GENESIS_HASH;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String recorded;
            try {
                recorded = JsonParser.parseString(line).getAsJsonObject().get("previous_hash").getAsString();
            } catch (RuntimeException e) {
                return new Verification(false, i, "record " + i + " is not a readable audit entry");
            }
            if (!expected.equals(recorded)) {
                return new Verification(false, i,
                        "record " + i + " claims a predecessor hash of " + shorten(recorded)
                                + " but the actual predecessor hashes to " + shorten(expected)
                                + " — a record before this point was altered or removed");
            }
            expected = hashOf(line);
        }
        return new Verification(true, -1, lines.size() + " record(s) verified, chain intact");
    }

    /**
     * Reads the most recent records.
     *
     * @param limit maximum records to return
     * @return the records, newest first
     */
    public List<String> tail(int limit) {
        List<String> lines = store.readLines(FILE);
        List<String> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(lines.get(i));
        }
        return out;
    }

    private static Map<String, String> redact(Map<String, String> parameters) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (parameters == null) {
            return safe;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            boolean sensitive = REDACTED_KEYS.stream().anyMatch(key::contains);
            safe.put(entry.getKey(), sensitive ? REDACTED : entry.getValue());
        }
        return safe;
    }

    private static String hashOf(String line) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform. If it is genuinely absent the
            // environment is broken in a way NexusCore cannot paper over.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static String shorten(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
    }

    /**
     * The result of verifying the chain.
     *
     * @param intact true when every link matched
     * @param firstBrokenIndex index of the first bad record, or -1 when intact
     * @param detail human-readable explanation
     */
    public record Verification(boolean intact, int firstBrokenIndex, String detail) {
    }
}
