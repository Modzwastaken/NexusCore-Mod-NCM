package com.mwtstudios.nexuscore.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The write-ahead journal (§11.1), and specifically what survives a crash.
 *
 * <p>The interesting cases here are not the ones where everything works. They are the ones where
 * the process dies between two file moves, which is the whole reason the journal exists — so the
 * tests below stop the commit inside that window on purpose, assert that what is left on disk is
 * genuinely torn, and then prove that recovery repairs it.</p>
 */
class JournalTest {

    /** Thrown by the injected crash point. Named so a test failure says what happened. */
    private static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(int index) {
            super("simulated crash before applying entry " + index);
        }
    }

    /** A document with one field, so a test can tell an old value from a new one. */
    static final class Doc {
        String value = "";

        static Doc of(String value) {
            Doc doc = new Doc();
            doc.value = value;
            return doc;
        }
    }

    @TempDir
    Path directory;

    private JsonStore store;
    private final AtomicLong clock = new AtomicLong(1_000L);

    @BeforeEach
    void setUp() {
        store = new JsonStore(directory);
    }

    /** A journal that dies just before applying entry {@code index}, as a real crash would. */
    private JournalService crashingBefore(int index) {
        IntConsumer crash = i -> {
            if (i == index) {
                throw new SimulatedCrash(i);
            }
        };
        return new JournalService(store, clock::get, crash);
    }

    private JournalService journal() {
        return new JournalService(store, clock::get, i -> { });
    }

    private String valueOf(String name) {
        return store.read(name, Doc.class, Doc::new).value;
    }

    private List<Path> filesMatching(String fragment) throws IOException {
        try (Stream<Path> tree = Files.walk(directory)) {
            List<Path> found = new ArrayList<>();
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(fragment))
                    .forEach(found::add);
            return found;
        }
    }

    // ---- The happy path ----------------------------------------------------------------

    @Test
    @DisplayName("every document in a transaction lands together")
    void commitWritesEveryDocument() {
        journal().begin()
                .put("accounts.json", Doc.of("debited"))
                .put("ledger.json", Doc.of("recorded"))
                .commit();

        assertEquals("debited", valueOf("accounts.json"));
        assertEquals("recorded", valueOf("ledger.json"));
    }

    @Test
    @DisplayName("nothing is written until commit is called")
    void stagingIsInvisibleBeforeCommit() throws IOException {
        Transaction txn = journal().begin();
        txn.put("accounts.json", Doc.of("pending"));

        assertFalse(store.exists("accounts.json"), "an uncommitted transaction must leave no trace");
        assertEquals(List.of(), filesMatching(JournalService.STAGING_INFIX));
    }

    @Test
    @DisplayName("a completed transaction leaves no record and no staging files behind")
    void completedTransactionLeavesNothingBehind() throws IOException {
        journal().begin().put("a.json", Doc.of("1")).put("b.json", Doc.of("2")).commit();

        assertEquals(List.of(), filesMatching(JournalService.STAGING_INFIX));
        Path journalDirectory = directory.resolve(JournalService.JOURNAL_DIRECTORY);
        if (Files.isDirectory(journalDirectory)) {
            try (Stream<Path> entries = Files.list(journalDirectory)) {
                assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".json")),
                        "the record must be cleared once the transaction is applied");
            }
        }
    }

    @Test
    @DisplayName("an overwritten target keeps its previous version as a .bak, as a plain write does")
    void overwriteKeepsBackup() {
        store.write("accounts.json", Doc.of("before"));

        journal().begin().put("accounts.json", Doc.of("after")).commit();

        assertTrue(Files.isRegularFile(directory.resolve("accounts.json.bak")));
        assertEquals("after", valueOf("accounts.json"));
    }

    @Test
    @DisplayName("replaying a directory that never crashed does nothing")
    void replayOfACleanDirectoryIsANoOp() {
        journal().begin().put("a.json", Doc.of("1")).commit();

        assertEquals(0, journal().replayPending());
        assertEquals("1", valueOf("a.json"));
    }

    // ---- The crash the journal exists for ----------------------------------------------

    @Test
    @DisplayName("a crash mid-transaction leaves a torn state, and replay repairs it")
    void crashMidTransactionIsRepairedByReplay() {
        store.write("accounts.json", Doc.of("old-accounts"));
        store.write("ledger.json", Doc.of("old-ledger"));
        store.write("summary.json", Doc.of("old-summary"));

        // Die after the first of three files has been moved into place: the books now disagree
        // with each other, which is precisely the state a plain sequence of writes could reach
        // and never recover from.
        assertThrows(SimulatedCrash.class, () -> crashingBefore(1).begin("txn-1")
                .put("accounts.json", Doc.of("new-accounts"))
                .put("ledger.json", Doc.of("new-ledger"))
                .put("summary.json", Doc.of("new-summary"))
                .commit());

        assertEquals("new-accounts", valueOf("accounts.json"));
        assertEquals("old-ledger", valueOf("ledger.json"), "the crash must land mid-transaction to test anything");
        assertEquals("old-summary", valueOf("summary.json"));
        assertTrue(Files.isRegularFile(directory.resolve(JournalService.JOURNAL_DIRECTORY).resolve("txn-1.json")),
                "the record must survive the crash: it is the only thing that knows work is owed");

        assertEquals(1, journal().replayPending());

        assertEquals("new-accounts", valueOf("accounts.json"));
        assertEquals("new-ledger", valueOf("ledger.json"));
        assertEquals("new-summary", valueOf("summary.json"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("a crash at any point after the commit point still completes on replay")
    void crashAtEveryPointIsRecoverable(int crashIndex) {
        store.write("a.json", Doc.of("old"));
        store.write("b.json", Doc.of("old"));
        store.write("c.json", Doc.of("old"));

        // Index 3 is past the last entry, so the transaction fully applies and only the record
        // removal is missed — the one crash window that leaves work already done but still owed.
        Runnable attempt = () -> crashingBefore(crashIndex).begin("txn-" + crashIndex)
                .put("a.json", Doc.of("new"))
                .put("b.json", Doc.of("new"))
                .put("c.json", Doc.of("new"))
                .commit();
        if (crashIndex < 3) {
            assertThrows(SimulatedCrash.class, attempt::run);
            assertEquals(1, journal().replayPending());
        } else {
            attempt.run();
            assertEquals(0, journal().replayPending());
        }

        assertEquals("new", valueOf("a.json"));
        assertEquals("new", valueOf("b.json"));
        assertEquals("new", valueOf("c.json"));
    }

    @Test
    @DisplayName("replay is idempotent: running it twice changes nothing")
    void replayIsIdempotent() {
        store.write("a.json", Doc.of("old"));

        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("txn-1")
                .put("a.json", Doc.of("new"))
                .commit());

        assertEquals(1, journal().replayPending());
        assertEquals(0, journal().replayPending(), "a completed replay must not leave work behind");
        assertEquals("new", valueOf("a.json"));
    }

    @Test
    @DisplayName("crashing during replay itself is recovered by the next replay")
    void crashDuringReplayIsRecoverable() {
        store.write("a.json", Doc.of("old"));
        store.write("b.json", Doc.of("old"));

        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("txn-1")
                .put("a.json", Doc.of("new"))
                .put("b.json", Doc.of("new"))
                .commit());

        // Recovery gets halfway and dies too. This is the case that makes the staging file, not a
        // progress counter, the right marker: the marker and the work are one filesystem move.
        assertThrows(SimulatedCrash.class, () -> crashingBefore(1).replayPending());
        assertEquals("new", valueOf("a.json"));
        assertEquals("old", valueOf("b.json"));

        assertEquals(1, journal().replayPending());
        assertEquals("new", valueOf("b.json"));
    }

    @Test
    @DisplayName("two pending transactions replay in the order they committed")
    void pendingRecordsReplayInCommitOrder() {
        clock.set(1_000L);
        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("earlier")
                .put("a.json", Doc.of("first"))
                .commit());

        clock.set(2_000L);
        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("later")
                .put("a.json", Doc.of("second"))
                .commit());

        assertEquals(2, journal().replayPending());
        assertEquals("second", valueOf("a.json"),
                "replaying out of commit order would resurrect a value a later transaction replaced");
    }

    // ---- Failures before the commit point ----------------------------------------------

    @Test
    @DisplayName("a transaction that fails while staging commits nothing and cleans up after itself")
    void failureBeforeCommitPointLeavesNothing() throws IOException {
        store.write("a.json", Doc.of("old"));

        // The second document escapes the data directory, so staging refuses it — a failure
        // before any record exists.
        assertThrows(StorageException.class, () -> journal().begin("txn-1")
                .put("a.json", Doc.of("new"))
                .put("../escape.json", Doc.of("new"))
                .commit());

        assertEquals("old", valueOf("a.json"), "no part of an uncommitted transaction may be visible");
        assertFalse(Files.exists(directory.resolve(JournalService.JOURNAL_DIRECTORY).resolve("txn-1.json")));
        assertEquals(List.of(), filesMatching(JournalService.STAGING_INFIX));
    }

    @Test
    @DisplayName("staging files nobody owns are swept, and pending ones are not")
    void abandonedStagingFilesAreSwept() throws IOException {
        // A transaction that died before writing its record leaves exactly this.
        Files.writeString(directory.resolve("orphan.json" + JournalService.STAGING_INFIX + "dead"),
                "{\"value\":\"x\"}\n", StandardCharsets.UTF_8);

        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("txn-1")
                .put("owed.json", Doc.of("owed"))
                .commit());

        assertEquals(1, journal().replayPending());

        assertEquals("owed", valueOf("owed.json"), "a pending record's staging file must be applied, not swept");
        assertFalse(store.exists("orphan.json"), "an orphan must not be applied to its target");
        assertEquals(List.of(), filesMatching(JournalService.STAGING_INFIX));
    }

    // ---- Refusing to guess -------------------------------------------------------------

    @Test
    @DisplayName("a staged file that does not match its recorded hash is refused, and the record is kept")
    void tamperedStagingFileIsRefused() throws IOException {
        store.write("a.json", Doc.of("old"));

        assertThrows(SimulatedCrash.class, () -> crashingBefore(0).begin("txn-1")
                .put("a.json", Doc.of("new"))
                .commit());

        List<Path> staged = filesMatching(JournalService.STAGING_INFIX);
        assertEquals(1, staged.size());
        Files.writeString(staged.get(0), "{\"value\":\"tampered\"}\n", StandardCharsets.UTF_8);

        StorageException error = assertThrows(StorageException.class, () -> journal().replayPending());

        assertTrue(error.getMessage().contains("does not match the hash"), "got: " + error.getMessage());
        assertEquals("old", valueOf("a.json"), "bytes that fail verification must not reach the target");
        assertTrue(Files.isRegularFile(directory.resolve(JournalService.JOURNAL_DIRECTORY).resolve("txn-1.json")),
                "the record must be kept so the transaction is retried rather than lost");
    }

    @Test
    @DisplayName("an unreadable journal record stops the start rather than being ignored")
    void corruptRecordIsReported() throws IOException {
        Path journalDirectory = directory.resolve(JournalService.JOURNAL_DIRECTORY);
        Files.createDirectories(journalDirectory);
        Files.writeString(journalDirectory.resolve("txn-1.json"), "{ not json", StandardCharsets.UTF_8);

        StorageException error = assertThrows(StorageException.class, () -> journal().replayPending());

        assertTrue(error.getMessage().contains("preserved"), "got: " + error.getMessage());
    }

    // ---- Caller mistakes ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "with space", "", "tab\there"})
    @DisplayName("a transaction id that could escape or confuse the journal directory is refused")
    void unsafeIdsRefused(String id) {
        assertThrows(StorageException.class, () -> journal().begin(id));
    }

    @Test
    @DisplayName("a null id is refused")
    void nullIdRefused() {
        assertThrows(StorageException.class, () -> journal().begin(null));
    }

    @Test
    @DisplayName("generated ids are distinct")
    void generatedIdsAreDistinct() {
        assertNotEquals(journal().begin().id(), journal().begin().id());
    }

    @Test
    @DisplayName("naming the same document twice in one transaction is refused")
    void duplicateTargetRefused() {
        Transaction txn = journal().begin().put("a.json", Doc.of("1"));

        assertThrows(StorageException.class, () -> txn.put("a.json", Doc.of("2")));
    }

    @Test
    @DisplayName("a document name may not impersonate a staging file, which recovery deletes")
    void stagingMarkerInTargetNameRefused() {
        assertThrows(StorageException.class,
                () -> journal().begin().put("a" + JournalService.STAGING_INFIX + "b.json", Doc.of("1")));
    }

    @Test
    @DisplayName("a transaction cannot be committed or added to twice")
    void commitIsOneShot() {
        Transaction txn = journal().begin().put("a.json", Doc.of("1"));
        txn.commit();

        assertThrows(StorageException.class, txn::commit);
        assertThrows(StorageException.class, () -> txn.put("b.json", Doc.of("2")));
    }

    @Test
    @DisplayName("an empty transaction is a no-op rather than an empty record")
    void emptyTransactionWritesNoRecord() {
        journal().begin("txn-1").commit();

        assertFalse(Files.exists(directory.resolve(JournalService.JOURNAL_DIRECTORY).resolve("txn-1.json")));
    }
}
