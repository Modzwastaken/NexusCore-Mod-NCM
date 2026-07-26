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
