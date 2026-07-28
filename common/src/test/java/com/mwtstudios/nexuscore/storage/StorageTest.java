package com.mwtstudios.nexuscore.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Atomic writes, corrupt-file handling, and path containment. */
class StorageTest {

    @TempDir
    Path directory;

    /** A trivial document, mirroring the shape every real NexusCore document uses. */
    static final class Sample {
        int schemaVersion = 1;
        String name = "";
        List<String> values = List.of();
    }

    // ---- JsonStore ---------------------------------------------------------------------

    @Test
    @DisplayName("a document round-trips through disk unchanged")
    void roundTrip() {
        JsonStore store = new JsonStore(directory);
        Sample written = new Sample();
        written.name = "alice";
        written.values = List.of("a", "b");

        store.write("sample.json", written);
        Sample read = store.read("sample.json", Sample.class, Sample::new);

        assertEquals("alice", read.name);
        assertEquals(List.of("a", "b"), read.values);
        assertEquals(1, read.schemaVersion);
    }

    @Test
    @DisplayName("reading a missing file yields the caller's default rather than failing")
    void missingFileYieldsDefault() {
        JsonStore store = new JsonStore(directory);

        assertFalse(store.exists("absent.json"));
        assertEquals("", store.read("absent.json", Sample.class, Sample::new).name);
    }

    @Test
    @DisplayName("no temporary file is left behind after a write")
    void noTemporaryFileLeftBehind() throws IOException {
        JsonStore store = new JsonStore(directory);
        store.write("sample.json", new Sample());

        try (var entries = Files.list(directory)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "the atomic-move protocol must leave no .tmp file behind");
        }
    }

    @Test
    @DisplayName("overwriting keeps the previous version as a .bak")
    void overwriteKeepsBackup() {
        JsonStore store = new JsonStore(directory);
        Sample first = new Sample();
        first.name = "first";
        store.write("sample.json", first);

        Sample second = new Sample();
        second.name = "second";
        store.write("sample.json", second);

        assertTrue(Files.isRegularFile(directory.resolve("sample.json.bak")));
        assertEquals("second", store.read("sample.json", Sample.class, Sample::new).name);
    }

    @Test
    @DisplayName("a corrupt file is preserved and reported, never silently replaced with defaults")
    void corruptFileIsQuarantinedNotDiscarded() throws IOException {
        JsonStore store = new JsonStore(directory);
        Files.writeString(directory.resolve("broken.json"), "{ this is not json", StandardCharsets.UTF_8);

        StorageException error = assertThrows(StorageException.class,
                () -> store.read("broken.json", Sample.class, Sample::new));

        assertTrue(error.getMessage().contains("preserved"),
                "the operator must be told where their data went, got: " + error.getMessage());
        try (var entries = Files.list(directory)) {
            assertTrue(entries.anyMatch(path -> path.getFileName().toString().contains(".corrupt-")),
                    "the unreadable file must be moved aside, not deleted");
        }
    }

    @Test
    @DisplayName("a file that cannot be READ is reported without being quarantined")
    void unreadableFileIsNotQuarantined() throws IOException {
        JsonStore store = new JsonStore(directory);
        store.write("permissions.json", new Sample());
        Path file = directory.resolve("permissions.json");
        String before = Files.readString(file, StandardCharsets.UTF_8);

        if (!file.toFile().setReadable(false)) {
            return;    // root, or a filesystem ignoring the permission; nothing to assert
        }
        try {
            if (Files.isReadable(file)) {
                return;    // the permission did not take effect
            }
            StorageException error = assertThrows(StorageException.class,
                    () -> store.read("permissions.json", Sample.class, Sample::new));

            // Until 1.1.4 an IOException was caught alongside the parse failures, so a full disk or a
            // changed permission renamed an operator's INTACT file to .corrupt-<ts> and told them it
            // could not be parsed. The data was fine and NexusCore moved it out from under them.
            assertTrue(error.getMessage().contains("could not read"),
                    "a failed read must not be reported as a parse failure, got: " + error.getMessage());
            assertTrue(error.getMessage().contains("NOT been moved"), "got: " + error.getMessage());
            try (var entries = Files.list(directory)) {
                assertTrue(entries.noneMatch(p -> p.getFileName().toString().contains(".corrupt-")),
                        "an unreadable file must be left exactly where it is");
            }
        } finally {
            file.toFile().setReadable(true);
        }
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), "the file's bytes must be untouched");
    }

    /**
     * A document Gson refuses to serialise.
     *
     * <p>Gson declines {@link Class} outright rather than reflecting over it, which makes this a
     * deterministic mid-write failure with no timing or filesystem dependence. An earlier attempt
     * used a {@code Number} whose {@code toString} threw; Gson reflected over the anonymous subclass
     * instead of calling it, serialised {@code {}}, and the test passed against broken code — which
     * is the failure mode these tests exist to catch, met while writing one.</p>
     */
    static final class Unserialisable {
        @SuppressWarnings("unused")
        final Object boom = String.class;
    }

    @Test
    @DisplayName("a write that fails mid-serialisation leaves no .tmp behind")
    void failedSerialisationCleansUpAfterItself() throws IOException {
        JsonStore store = new JsonStore(directory);

        // Until 1.1.4 the only cleanup was inside `catch (IOException)`. A serialisation failure is
        // not an IOException — and Gson only wraps writer failures in JsonIOException, so naming
        // that type alone would not have caught this one either. The `.tmp` survived, and the next
        // write found stale scratch while `noTemporaryFileLeftBehind` went on passing. Cleaning up
        // in a finally is what actually closes it.
        assertThrows(RuntimeException.class, () -> store.write("doomed.json", new Unserialisable()));

        try (var entries = Files.list(directory)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "a failed write must not leave scratch behind for the next write to find");
        }
    }

    @Test
    @DisplayName("a cross-directory atomic move is refused rather than attempted")
    void crossDirectoryMoveRefused() throws IOException {
        Path from = Files.writeString(directory.resolve("a.json"), "{}", StandardCharsets.UTF_8);
        Path elsewhere = Files.createDirectories(directory.resolve("sub"));

        // Same-directory is what makes the move a rename, and a rename is the only thing a filesystem
        // does atomically. Enforcing the precondition is how the atomic-move guarantee becomes
        // testable at all — the "filesystem cannot do this" branch is not reachable from a test.
        assertThrows(StorageException.class, () -> JsonStore.move(from, elsewhere.resolve("a.json")));
        assertTrue(Files.isRegularFile(from), "the refusal must not have moved anything");
    }

    @Test
    @DisplayName("a same-directory replacement still works, and is atomic")
    void sameDirectoryMoveWorks() throws IOException {
        Path from = Files.writeString(directory.resolve("a.json.tmp"), "new", StandardCharsets.UTF_8);
        Path to = Files.writeString(directory.resolve("a.json"), "old", StandardCharsets.UTF_8);

        JsonStore.move(from, to);

        assertEquals("new", Files.readString(to, StandardCharsets.UTF_8));
        assertFalse(Files.exists(from));
    }

    @Test
    @DisplayName("an empty file is treated as corrupt rather than as an empty document")
    void emptyFileIsCorrupt() throws IOException {
        JsonStore store = new JsonStore(directory);
        Files.writeString(directory.resolve("empty.json"), "", StandardCharsets.UTF_8);

        assertThrows(StorageException.class, () -> store.read("empty.json", Sample.class, Sample::new));
    }

    @Test
    @DisplayName("appended log lines survive and read back in order")
    void appendLineRoundTrips() {
        JsonStore store = new JsonStore(directory);
        store.appendLine("log.txt", "first");
        store.appendLine("log.txt", "second");

        assertEquals(List.of("first", "second"), store.readLines("log.txt"));
    }

    @Test
    @DisplayName("reading a log that does not exist yields no lines")
    void missingLogYieldsNoLines() {
        assertEquals(List.of(), new JsonStore(directory).readLines("nothing.log"));
    }

    // ---- PathSafety --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "../escape.json",
            "../../etc/passwd",
            "subdir/../../escape.json",
            "./../escape.json"
    })
    @DisplayName("traversal out of the data directory is refused")
    void traversalRefused(String candidate) {
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, candidate));
    }

    @Test
    @DisplayName("an absolute path is refused even when it points somewhere harmless")
    void absolutePathRefused() {
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, directory.toString()));
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, "/tmp/anything.json"));
    }

    @Test
    @DisplayName("an empty or null path is refused")
    void emptyPathRefused() {
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, ""));
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, "   "));
        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, null));
    }

    @Test
    @DisplayName("an ordinary relative path inside the root is allowed")
    void insideRootAllowed() {
        Path resolved = PathSafety.resolveWithin(directory, "sub/dir/file.json");

        assertTrue(resolved.startsWith(directory.toAbsolutePath().normalize()));
        assertTrue(resolved.endsWith(Path.of("sub", "dir", "file.json")));
    }

    @Test
    @DisplayName("a symlink pointing outside the root is refused, not followed")
    void symlinkEscapeRefused() throws IOException {
        Path outside = directory.getParent().resolve("outside-" + System.nanoTime());
        Files.createDirectories(outside);
        Path link = directory.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks are unavailable on this filesystem; the check itself is still exercised
            // by the traversal cases above.
            return;
        }

        assertThrows(StorageException.class, () -> PathSafety.resolveWithin(directory, "link/stolen.json"),
                "a string-only '..' check would pass this; resolving the real path must not");
    }

    @Test
    @DisplayName("a JsonStore refuses to write outside its own root")
    void storeRefusesEscape() {
        JsonStore store = new JsonStore(directory);

        assertThrows(StorageException.class, () -> store.write("../escaped.json", new Sample()));
    }
}
