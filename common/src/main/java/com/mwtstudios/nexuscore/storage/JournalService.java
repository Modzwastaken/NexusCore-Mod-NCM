package com.mwtstudios.nexuscore.storage;

import java.io.IOException;
import java.io.Reader;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonParseException;
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
 * <p><b>What this does not guarantee, on the platforms where it does not.</b> The protocol above is
 * only as strong as the two primitives underneath it, and both degrade quietly rather than fail:</p>
 *
 * <ul>
 *   <li>{@link JsonStore#forceDirectory} logs at DEBUG and carries on when a directory fsync fails,
 *       and opening a directory as a channel <b>fails unconditionally on Windows</b>. Every
 *       directory fsync in this class is therefore a no-op on a common server platform, which
 *       weakens the commit point to "the record's bytes are durable" rather than "the record is
 *       durably findable".</li>
 *   <li>{@link JsonStore#move} falls back to a non-atomic replace, with a warning, on a filesystem
 *       that refuses {@code ATOMIC_MOVE}. On such a filesystem a crash can be seen mid-move, and
 *       "the old file or the new file, never a half-written one" stops holding.</li>
 * </ul>
 *
 * <p>Both are inherited from the single-document write protocol rather than introduced here; the
 * journal is what makes them consequential, because money is what will ride on them. Neither is
 * covered by a test. They are stated here rather than left to be discovered because a durability
 * claim that is false on one platform is worse than one that is qualified on all of them.</p>
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

    /** How {@link JsonStore} names a file it has moved aside. A record must never become invisible this way. */
    static final String QUARANTINE_INFIX = ".corrupt-";

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
     * Ids of transactions between their first staged file and their last. The sweep skips their
     * staging files.
     *
     * <p>Without this, {@link #replayPending()} is safe only because of a comment. It is public, so
     * an admin repair command calling it while a transfer is staging would delete that transfer's
     * staged files as ownerless — the transaction then commits a record pointing at files that no
     * longer exist, and replay reads every one of them as already-applied. The whole transfer would
     * report success having written nothing.</p>
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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

        inFlight.add(id);
        try {
            for (int i = 0; i < targets.size(); i++) {
                record.entries.add(stage(id, targets.get(i), documents.get(i)));
            }
        } catch (RuntimeException e) {
            // Nothing is committed yet, so the whole attempt can be thrown away. Leaving the
            // staging files would work too — replay sweeps them — but cleaning up here keeps a
            // repeatedly failing caller from filling the data directory.
            discardStaging(record);
            inFlight.remove(id);
            throw e;
        }

        // Every staged file's DIRECTORY ENTRY must reach disk before the record does.
        //
        // stage() forces each staged file's contents, which persists the bytes but not necessarily
        // the name that finds them. Lose power with the record durable and a dirent not yet
        // written, and replay sees a staging file that is gone — which this protocol reads as
        // "already applied" and skips. A committed transaction would silently half-apply, and the
        // skip that makes replay idempotent is exactly what turns the missing name into data loss.
        // ext4's default ordering usually hides this; XFS and anything journaling dirents
        // separately do not.
        for (Path parent : stagingDirectories(record)) {
            JsonStore.forceDirectory(parent);
        }

        // The commit point. Everything before this is invisible and discardable; everything after
        // it is owed, and replayPending() will deliver it if this process does not.
        store.write(recordName(id), record);

        try {
            apply(record);
            clear(id);
        } finally {
            // Only after the record exists. Until then the id is what keeps a concurrent sweep off
            // these staging files; afterwards the record itself does.
            inFlight.remove(id);
        }
    }

    /** The distinct directories holding a record's staged files, in first-seen order. */
    private Set<Path> stagingDirectories(Record record) {
        Set<Path> directories = new LinkedHashSet<>();
        for (Entry entry : record.entries) {
            directories.add(PathSafety.resolveWithin(store.root(), entry.staging).getParent());
        }
        return directories;
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
                throw new StorageException(repairGuidance(record, entry, staging, actual));
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

        // The last crash window, and the one a counter-based marker would get wrong: every file is
        // in place and the record still says the work is owed. Replay must find a record whose
        // staging files are ALL gone and treat that as complete rather than as damage — the state
        // escrow depends on tolerating. The seam fires here with the entry count precisely so a
        // test can stop the process in it; the loop above can only reach the windows before each
        // move, so index == size() was previously unreachable and the case that claimed to cover
        // it silently re-ran the happy path.
        beforeEachEntry.accept(record.entries.size());
    }

    /**
     * Explains a hash mismatch, and gives a repair route that does not corrupt anything.
     *
     * <p>The obvious advice — "remove the bad staged file" — is <em>wrong here</em>, and wrong in a
     * way that produces exactly the damage this refusal prevents. An absent staged file is this
     * protocol's already-applied marker, so deleting one makes the next start skip that document,
     * apply its siblings and clear the record: the transaction reports success having never written
     * that file. Deleting the record instead tears it the other way, leaving the applied entries in
     * place with nothing recording that the rest were owed. Both of the escapes an operator would
     * reach for first are traps, so the message names them as traps and says what to do instead.</p>
     */
    private String repairGuidance(Record record, Entry failed, Path staging, String actual) {
        List<String> applied = new ArrayList<>();
        List<String> outstanding = new ArrayList<>();
        for (Entry entry : record.entries) {
            Path other = PathSafety.resolveWithin(store.root(), entry.staging);
            if (Files.isRegularFile(other)) {
                outstanding.add(entry.target);
            } else {
                applied.add(entry.target);
            }
        }

        return "transaction " + record.id + " cannot be completed: the staged file " + staging
                + " does not match the hash committed for it (expected " + failed.sha256 + ", found " + actual + ")."
                + " Nothing has been lost and the journal record is left in place, so this start refuses rather than"
                + " writing bytes it cannot vouch for."
                + " Already applied, now holding their new contents: " + (applied.isEmpty() ? "none" : applied) + "."
                + " Still owed: " + outstanding + "."
                + " DO NOT delete the staged file on its own, and DO NOT delete the record on its own — a missing"
                + " staged file is this protocol's already-applied marker, so removing it makes the next start skip"
                + " that document and report the transaction complete when that write never happened, and removing"
                + " the record alone abandons the outstanding entries with no trace that they were owed."
                + " Either restore " + staging + " to contents whose SHA-256 is " + failed.sha256
                + ", after which the next start completes the transaction; or abandon it deliberately by moving the"
                + " record " + recordName(record.id) + " and every remaining staged file aside TOGETHER, which leaves"
                + " the already-applied documents applied — each one's previous contents are in <name>.bak.";
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

        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw new StorageException("could not read the journal directory " + directory, e);
        }

        List<Record> records = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.contains(QUARANTINE_INFIX)) {
                // A record an earlier run moved aside. The work it describes is still owed, and a
                // name that no longer ends in .json must not make it invisible.
                unreadable.add(file);
            } else if (name.endsWith(".json")) {
                try {
                    records.add(parseRecord(file));
                } catch (StorageException e) {
                    LOGGER.error("journal record {} cannot be read", file, e);
                    unreadable.add(file);
                }
            }
        }

        if (!unreadable.isEmpty()) {
            // Hard stop, and deliberately BEFORE the sweep. An unreadable record still describes a
            // committed transaction, so its staged files are owed work rather than litter — and the
            // sweep would delete them as ownerless. Every later start must reach this same refusal.
            throw new StorageException("the journal holds " + unreadable.size() + " record(s) that cannot be read: "
                    + unreadable + ". Each one is a transaction that committed and may be part-applied, so starting"
                    + " would mean starting on data some transaction was mid-way through changing with no way to know"
                    + " how far it got. The records and every staged file have been left exactly as they are — nothing"
                    + " is swept while any record is unreadable. Repair or move the named record(s) aside together"
                    + " with the staged files belonging to them.");
        }

        records.sort(Comparator.comparingLong((Record r) -> r.committedAtEpochMilli).thenComparing(r -> r.id));
        return records;
    }

    /**
     * Parses a record without quarantining it.
     *
     * <p>Deliberately not {@link JsonStore#read}. That method moves an unparseable file to
     * {@code <name>.corrupt-<timestamp>} <em>before</em> throwing, which is right for a data
     * document and catastrophic for a journal record: the renamed file no longer ends in
     * {@code .json}, so the very next start would list zero pending records, conclude nothing was
     * owed, and let the sweep delete that transaction's staged files as garbage. The first start
     * refused correctly and the second silently destroyed a committed transaction — with the
     * operator's reflexive restart as the trigger. A record is evidence of owed work, so it stays
     * exactly where it is.</p>
     */
    private static Record parseRecord(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Record record = JsonStore.gson().fromJson(reader, Record.class);
            if (record == null || record.id == null || record.id.isBlank() || record.entries == null) {
                throw new StorageException("journal record " + file + " is empty or missing required fields");
            }
            for (Entry entry : record.entries) {
                if (entry == null || entry.target == null || entry.staging == null || entry.sha256 == null) {
                    throw new StorageException("journal record " + file + " has an incomplete entry");
                }
            }
            return record;
        } catch (IOException | JsonParseException e) {
            throw new StorageException("could not parse journal record " + file, e);
        }
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
                    .filter(path -> !belongsToInFlightTransaction(path))
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

    /** True if this staged file belongs to a transaction that is staging right now. */
    private boolean belongsToInFlightTransaction(Path path) {
        String name = path.getFileName().toString();
        for (String id : inFlight) {
            if (name.endsWith(STAGING_INFIX + id)) {
                return true;
            }
        }
        return false;
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

    /**
     * Refuses a document name that would collide with the journal's own scratch files.
     *
     * <p>Recovery deletes every leftover file whose name carries {@link #STAGING_INFIX}, on the
     * reasoning that it is scratch nobody owns. A real document carrying that marker would be data
     * caught in that sweep, so the name is refused rather than the sweep weakened. Enforced at
     * <em>every</em> way in — {@link Transaction#put} and {@link JsonStore#write} both — because a
     * rule that only one of two writers checks is not a rule, and the sweep does not care which
     * call created the file.</p>
     *
     * @param name the caller's document name
     * @throws StorageException if it carries the staging marker
     */
    static void rejectReservedName(String name) {
        if (name != null && name.contains(STAGING_INFIX)) {
            throw new StorageException("a document name may not contain '" + STAGING_INFIX
                    + "', which is reserved for journal staging files and is deleted by recovery, got: " + name);
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
