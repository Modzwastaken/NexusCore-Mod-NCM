package com.mwtstudios.nexuscore.storage;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

/**
 * Write-ahead journal for updates that span more than one file (§11.1).
 *
 * <p>{@link JsonStore#write} makes <em>one</em> document's replacement atomic. It says nothing
 * about two. Writing {@code accounts.json} and then {@code transactions.json} is two atomic
 * writes with a window between them, and a crash in that window leaves a server whose books do
 * not balance — money debited from one player and never credited to the other, or credited
 * twice. That window is what this class closes, and it is why §11.1 asks for a journal at all.</p>
 *
 * <p><b>The protocol.</b> A transaction is staged, committed, then applied:</p>
 *
 * <ol>
 *   <li><b>Stage.</b> Every document is serialised to {@code <target>.txn-<id>} beside its
 *       target and forced to disk. Nothing an operator or another module can see has changed.</li>
 *   <li><b>Commit.</b> A record naming every target, its staging file and that file's SHA-256 is
 *       written to {@code journal/<id>.json}, forced, and the journal directory forced.
 *       <b>This is the commit point.</b> Once those bytes are durable the transaction will
 *       happen — now if the process survives, at the next startup if it does not.</li>
 *   <li><b>Apply.</b> Each staging file is moved over its target with the same
 *       backup-then-atomic-move protocol {@link JsonStore#write} uses.</li>
 *   <li><b>Clear.</b> The record is deleted. The transaction is over.</li>
 * </ol>
 *
 * <p><b>Recovery.</b> {@link #replayPending()} runs before anything reads data. A record that
 * still exists means the process died between commit and clear, so the record is re-applied and
 * then cleared. Replay is idempotent by construction: a staging file that is gone was already
 * moved, so entries already applied are skipped rather than re-applied. Crashing during replay
 * is therefore the same situation as crashing during the first apply, and the next attempt
 * resumes where it stopped.</p>
 *
 * <p><b>Refusal beats guessing.</b> If a staging file's bytes do not match the SHA-256 the record
 * committed to, replay throws instead of applying them. A committed transaction that cannot be
 * completed is not something to paper over: the record is left in place, so nothing is lost and
 * the next start tries again once an operator has looked. Halting a start with a message naming
 * the file is the honest outcome — silently applying half a money transfer is not (§11.1, and
 * the same reasoning that makes {@link JsonStore} quarantine a corrupt document rather than
 * replace it with defaults).</p>
 *
 * <p><b>Threading.</b> Concurrent transactions over distinct targets are safe: each one's staging
 * files and record carry its own id. Two transactions writing the same target race exactly as two
 * {@link JsonStore#write} calls would, and are the caller's problem. {@link #replayPending()}
 * assumes it is alone, which is why it runs during module start and not later.</p>
 */
public final class JournalService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where commit records live, relative to the data root. */
    static final String JOURNAL_DIRECTORY = "journal";

    /** Marks a staging file. Chosen so it cannot collide with {@code .tmp}, {@code .bak} or a target. */
    static final String STAGING_INFIX = ".txn-";

    private static final int RECORD_SCHEMA_VERSION = 1;

    /**
     * What a transaction id may contain. Ids become file names, so this is a containment control
     * as much as a naming rule — {@link PathSafety} would catch an escape anyway, but refusing it
     * here names the actual mistake instead of reporting a path that escaped the data directory.
     */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final JsonStore store;
    private final LongSupplier clock;
    private final IntConsumer beforeEachEntry;

    /**
     * @param store the store whose data root this journal protects
     */
    public JournalService(JsonStore store) {
        this(store, System::currentTimeMillis, index -> { });
    }

    /**
     * Test seam.
     *
     * <p>{@code beforeEachEntry} is invoked with the index of each entry immediately before it is
     * applied, so a test can throw there and leave the exact on-disk state a crash mid-apply would
     * leave. That window is the entire reason this class exists; a test that could not stop inside
     * it could only ever verify the happy path. The clock is injected for the same reason
     * {@code RateLimiter} and {@code ModerationService} inject theirs.</p>
     *
     * @param store the store whose data root this journal protects
     * @param clock supplies epoch milliseconds for the commit timestamp
     * @param beforeEachEntry called with each entry index just before that entry is applied
     */
    JournalService(JsonStore store, LongSupplier clock, IntConsumer beforeEachEntry) {
        this.store = store;
        this.clock = clock;
        this.beforeEachEntry = beforeEachEntry;
    }

    /**
     * Begins a transaction under a generated id.
     *
     * @return an empty transaction
     */
    public Transaction begin() {
        return begin(UUID.randomUUID().toString());
    }

    /**
     * Begins a transaction under a caller-supplied id.
     *
     * <p>The id names the record file and appears in every log line about the transaction, so a
     * caller that already has a meaningful identifier for the operation should pass it.</p>
     *
     * @param id the transaction id; letters, digits, {@code .}, {@code _} and {@code -}, at most 64
     * @return an empty transaction
     * @throws StorageException if the id is missing or contains anything else
     */
    public Transaction begin(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new StorageException("a transaction id must be 1-64 characters of [A-Za-z0-9._-], got: " + id);
        }
        return new Transaction(this, id);
    }

    /**
     * Completes every transaction that committed but did not finish, then removes abandoned
     * staging files.
     *
     * <p>Call this once, before any module reads data. Records are replayed in commit order, so a
     * later transaction cannot overwrite a target with the value an earlier one meant to
     * supersede.</p>
     *
     * @return how many committed transactions were completed; zero on a clean shutdown
     * @throws StorageException if a record cannot be read, or a staging file does not match the
     *         hash its record committed to
     */
    public int replayPending() {
        List<Record> pending = pendingRecords();
        for (Record record : pending) {
            LOGGER.warn("recovering transaction {} committed at {} with {} file(s): the previous run stopped "
                    + "between committing it and finishing it", record.id, record.committedAtEpochMilli, record.entries.size());
            apply(record);
            clear(record.id);
        }
        int swept = sweepAbandonedStagingFiles();
        if (!pending.isEmpty() || swept > 0) {
            LOGGER.warn("journal recovery complete: {} transaction(s) finished, {} abandoned staging file(s) removed",
                    pending.size(), swept);
        }
        return pending.size();
    }

    /**
     * Runs the commit protocol for a transaction. See the class javadoc for the four steps.
     *
     * @param id the transaction id
     * @param targets document names, in the order they were added
     * @param documents the documents, positionally matching {@code targets}
     */
    void commit(String id, List<String> targets, List<Object> documents) {
        if (targets.isEmpty()) {
            return;
        }

        Record record = new Record();
        record.id = id;
        record.committedAtEpochMilli = clock.getAsLong();

        try {
            for (int i = 0; i < targets.size(); i++) {
                record.entries.add(stage(id, targets.get(i), documents.get(i)));
            }
        } catch (RuntimeException e) {
            // Nothing is committed yet, so the whole attempt can be thrown away. Leaving the
            // staging files would work too — replay sweeps them — but cleaning up here keeps a
            // repeatedly failing caller from filling the data directory.
            discardStaging(record);
            throw e;
        }

        // The commit point. Everything before this is invisible and discardable; everything after
        // it is owed, and replayPending() will deliver it if this process does not.
        store.write(recordName(id), record);

        apply(record);
        clear(id);
    }

    /** Serialises one document to its staging file, forces it, and describes it for the record. */
    private Entry stage(String id, String target, Object document) {
        Entry entry = new Entry();
        entry.target = target;
        entry.staging = target + STAGING_INFIX + id;

        Path staging = PathSafety.resolveWithin(store.root(), entry.staging);
        try {
            Files.createDirectories(staging.getParent());
            try (Writer writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                JsonStore.gson().toJson(document, writer);
                writer.write('\n');
            }
            JsonStore.force(staging);
            entry.sha256 = hash(staging);
            return entry;
        } catch (IOException e) {
            throw new StorageException("could not stage " + staging + " for transaction " + id, e);
        }
    }

    /**
     * Moves every staged file over its target, skipping entries already applied.
     *
     * <p>An entry whose staging file is gone was moved by an earlier attempt. That is what makes
     * replay idempotent, and it is why the staging file is the progress marker rather than a
     * counter written somewhere: the marker and the work it records are the same filesystem
     * operation, so they cannot disagree.</p>
     */
    private void apply(Record record) {
        for (int i = 0; i < record.entries.size(); i++) {
            beforeEachEntry.accept(i);

            Entry entry = record.entries.get(i);
            Path staging = PathSafety.resolveWithin(store.root(), entry.staging);
            if (!Files.isRegularFile(staging)) {
                continue;
            }

            String actual = hash(staging);
            if (!actual.equals(entry.sha256)) {
                throw new StorageException("transaction " + record.id + " cannot be completed: the staged file "
                        + staging + " does not match the hash committed for it (expected " + entry.sha256
                        + ", found " + actual + "). The journal record has been left in place, so nothing has been "
                        + "lost and the transaction will be retried on the next start; repair or remove the staged "
                        + "file first");
            }

            Path target = PathSafety.resolveWithin(store.root(), entry.target);
            try {
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                JsonStore.move(staging, target);
                JsonStore.forceDirectory(target.getParent());
            } catch (IOException e) {
                throw new StorageException("transaction " + record.id + " could not apply " + entry.target
                        + "; the journal record has been left in place and the transaction will be retried on the "
                        + "next start", e);
            }
        }
    }

    /** Deletes a completed record and forces the directory, so the deletion itself is durable. */
    private void clear(String id) {
        Path record = PathSafety.resolveWithin(store.root(), recordName(id));
        try {
            Files.deleteIfExists(record);
            JsonStore.forceDirectory(record.getParent());
        } catch (IOException e) {
            // The transaction is fully applied. A record left behind only causes a harmless
            // re-apply at the next start, which is idempotent, so this must not fail the caller.
            LOGGER.warn("transaction {} finished but its journal record {} could not be removed; it will be "
                    + "replayed harmlessly at the next start", id, record, e);
        }
    }

    /** Reads every pending record, oldest commit first. */
    private List<Record> pendingRecords() {
        Path directory = PathSafety.resolveWithin(store.root(), JOURNAL_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new StorageException("could not read the journal directory " + directory, e);
        }

        List<Record> records = new ArrayList<>();
        for (String name : names) {
            // A record that will not parse is reported by JsonStore, which quarantines it first.
            // Starting anyway would mean starting on data a committed transaction was mid-way
            // through changing, with no way to know what it had already done.
            records.add(store.read(JOURNAL_DIRECTORY + "/" + name, Record.class, Record::new));
        }
        records.sort(Comparator.comparingLong((Record r) -> r.committedAtEpochMilli).thenComparing(r -> r.id));
        return records;
    }

    /**
     * Removes staging files no pending record refers to.
     *
     * <p>These are transactions that died before their commit point: no record was ever written,
     * so nothing is owed and the files are garbage. Sweeping runs only after every pending record
     * has been applied and cleared, so a file that is still here belongs to no one.</p>
     */
    private int sweepAbandonedStagingFiles() {
        List<Path> abandoned = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(store.root())) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(STAGING_INFIX))
                    .forEach(abandoned::add);
        } catch (IOException e) {
            // Nothing is wrong with the data; there is just leftover scratch that could not be
            // listed. Refusing to start over that would be worse than the leak.
            LOGGER.warn("could not scan {} for abandoned staging files", store.root(), e);
            return 0;
        }

        int removed = 0;
        for (Path path : abandoned) {
            try {
                Files.delete(path);
                removed++;
            } catch (IOException e) {
                LOGGER.warn("could not remove the abandoned staging file {}", path, e);
            }
        }
        return removed;
    }

    private void discardStaging(Record record) {
        for (Entry entry : record.entries) {
            try {
                Files.deleteIfExists(PathSafety.resolveWithin(store.root(), entry.staging));
            } catch (IOException | StorageException e) {
                LOGGER.debug("could not remove the staging file for {}", entry.target, e);
            }
        }
    }

    private static String recordName(String id) {
        return JOURNAL_DIRECTORY + "/" + id + ".json";
    }

    private static String hash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform, as in AuditService.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        } catch (IOException e) {
            throw new StorageException("could not read " + file + " to verify it", e);
        }
    }

    /**
     * The on-disk commit record. Its existence is the transaction: present means owed, absent
     * means either never committed or already finished.
     */
    static final class Record {
        int schemaVersion = RECORD_SCHEMA_VERSION;
        String id = "";
        long committedAtEpochMilli;
        List<Entry> entries = new ArrayList<>();
    }

    /** One file's part of a transaction. */
    static final class Entry {
        String target = "";
        String staging = "";
        String sha256 = "";
    }
}
