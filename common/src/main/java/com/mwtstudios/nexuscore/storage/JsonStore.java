package com.mwtstudios.nexuscore.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

/**
 * The default storage provider (§11.1): structured JSON files with atomic replacement.
 *
 * <p><b>Write protocol.</b> Serialise to {@code <name>.json.tmp}, force it to disk, then
 * atomically move it over the target. A crash can therefore leave the old file or the new
 * file, never a half-written one. When the target already exists it is first copied to
 * {@code <name>.json.bak}, so a serialisation bug cannot destroy the only copy.</p>
 *
 * <p><b>Read protocol.</b> A missing file yields the caller's default. A file whose bytes are not
 * a document is <em>not</em> silently replaced with defaults — it is moved aside to
 * {@code <name>.json.corrupt-<timestamp>} and reported, because silently discarding an
 * operator's permission or punishment data is worse than failing loudly (§5, "Backward-compatible
 * data": never silently discard unknown or older data).</p>
 *
 * <p><b>Failing to read is not the same as bad content, and only one of them quarantines.</b> An
 * {@code IOException} — a full disk, a changed permission, an interrupted read — means the bytes
 * could not be got at, not that they are wrong. Those reads report and leave the file exactly where
 * it is. Quarantine is reserved for bytes that were read and are not a document.</p>
 *
 * <p><b>Durability, honestly.</b> Replacement is atomic or it does not happen: where a filesystem
 * cannot perform an atomic rename, {@link #move} refuses rather than completing the write with a
 * weaker guarantee than this javadoc claims. The one guarantee that is genuinely weaker on some
 * platforms is the durability of the rename itself — see {@link #forceDirectory}, which cannot
 * work on Windows and says so.</p>
 */
public final class JsonStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Set the first time a directory fsync fails, so the warning is reported once rather than per write. */
    private static final AtomicBoolean DIRECTORY_FSYNC_UNAVAILABLE = new AtomicBoolean();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final Path root;

    /**
     * @param root the NexusCore data directory; every file this store touches lives under it
     */
    public JsonStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("could not create the NexusCore data directory " + this.root, e);
        }
    }

    /** @return the data directory this store writes into */
    public Path root() {
        return root;
    }

    /** @return the shared Gson instance, so callers serialise identically */
    public static Gson gson() {
        return GSON;
    }

    /**
     * Reads a document, or returns the supplied default when the file does not exist.
     *
     * @param <T> document type
     * @param name file name relative to the data root, for example {@code permissions.json}
     * @param type the document class
     * @param fallback produces a fresh default document when there is nothing to read
     * @return the parsed document, or the fallback
     * @throws StorageException if the file exists but cannot be read or parsed
     */
    public <T> T read(String name, Class<T> type, Supplier<T> fallback) {
        Path file = PathSafety.resolveWithin(root, name);
        if (!Files.isRegularFile(file)) {
            return fallback.get();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(reader, type);
            if (parsed == null) {
                throw new JsonSyntaxException("document is empty");
            }
            return parsed;
        } catch (IOException | JsonIOException e) {
            // COULD NOT READ. Not the same thing as bad content, and until 1.1.4 this branch did not
            // exist: an IOException was caught alongside the parse failures and the file was
            // quarantined. A disk that filled, a permission that changed, an interrupted read, an NFS
            // blip — any of them renamed an operator's INTACT permissions.json to
            // permissions.json.corrupt-<ts> and told them it "could not parse". The data was fine and
            // NexusCore moved it out from under them, then refused to start until they put it back.
            //
            // The file is untouched here. The same shape of error as the journal's quarantine defect:
            // both quarantined on the wrong signal, so this is a pattern in this codebase and not a
            // one-off. Quarantine means "these bytes are not a document"; it must never mean "I could
            // not get at the bytes".
            //
            // JsonIOException is in this branch and not the one below because it is exactly this case
            // wearing a Gson wrapper: Gson throws it when the underlying reader fails, not when the
            // document is malformed. It also used to escape the catch entirely — it extends
            // JsonParseException, NOT JsonSyntaxException and NOT IOException — so it propagated as an
            // unhandled RuntimeException with no quarantine, no StorageException and nothing an
            // operator could act on.
            throw new StorageException("could not read " + file + " (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "). The file has NOT been moved: this is a failure to read"
                    + " it, not evidence that its contents are bad. Fix the underlying problem — disk"
                    + " space, permissions, or the storage device — and start again.", e);
        } catch (JsonParseException | IllegalStateException e) {
            // The bytes were read and are not a document. This is what quarantine is for.
            Path quarantine = quarantine(file);
            throw new StorageException(
                    "could not parse " + file + "; the file has been preserved at " + quarantine
                            + " and must be repaired or removed before NexusCore will use this data again", e);
        }
    }

    /**
     * Writes a document atomically.
     *
     * @param <T> document type
     * @param name file name relative to the data root
     * @param document the document to serialise
     * @throws StorageException if the write cannot be completed
     */
    public <T> void write(String name, T document) {
        JournalService.rejectReservedName(name);
        Path file = PathSafety.resolveWithin(root, name);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Files.createDirectories(file.getParent());

            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                GSON.toJson(document, writer);
                writer.write('\n');
            }
            force(temp);

            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            move(temp, file);
            forceDirectory(file.getParent());
        } catch (IOException | JsonIOException e) {
            // JsonIOException is named here because it was missing until 1.1.4. Gson throws it when
            // the underlying writer fails, and it extends JsonParseException, NOT IOException, so it
            // walked straight past this catch and reached the caller as a raw RuntimeException
            // instead of the StorageException every caller is written to handle.
            throw new StorageException("could not write " + file + " (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")", e);
        } finally {
            // Unconditional, and this is the half that matters more than the catch above.
            //
            // Naming JsonIOException fixes the *reported* type; it does not fix the scratch, because
            // Gson only wraps failures coming from the writer. A serialisation failure raised
            // anywhere else — this store's own Gson calls toString on every Number it writes —
            // is an ordinary RuntimeException that no catch clause here would have matched, and the
            // `.tmp` survived it just the same. Cleaning up in a finally covers every way out of the
            // block, including the ones nobody has thought of yet. On the success path the temp file
            // has already been moved, so this is a no-op.
            deleteQuietly(temp);
        }
    }

    /** @return true if the named document exists */
    public boolean exists(String name) {
        return Files.isRegularFile(PathSafety.resolveWithin(root, name));
    }

    /**
     * Appends one JSON line to an append-only log, flushing it to disk before returning.
     *
     * <p>Used by the audit log, where every record must survive the very next crash. Append
     * plus force is the durability boundary; there is no buffering layer above it.</p>
     *
     * @param name log file name relative to the data root
     * @param line the already-serialised record, without a trailing newline
     */
    public void appendLine(String name, String line) {
        Path file = PathSafety.resolveWithin(root, name);
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                // FileChannel.write is documented to write "up to" the buffer's remaining bytes,
                // so a single call can be a short write. Ignoring the return value truncated the
                // record and still reported success — in a hash-chained log, that is a silently
                // corrupted chain in the one file whose whole purpose is being tamper-evident.
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(line + "\n");
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) {
                        throw new IOException("append stalled with " + buffer.remaining()
                                + " byte(s) of the record unwritten");
                    }
                }
                channel.force(true);
            }
        } catch (IOException e) {
            throw new StorageException("could not append to " + file, e);
        }
    }

    /**
     * Reads every line of an append-only log.
     *
     * @param name log file name relative to the data root
     * @return the lines, oldest first; empty when the log does not exist
     */
    public java.util.List<String> readLines(String name) {
        Path file = PathSafety.resolveWithin(root, name);
        if (!Files.isRegularFile(file)) {
            return java.util.List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("could not read " + file, e);
        }
    }

    // The three primitives below are package-private rather than private so JournalService can
    // reuse them. A journal that reimplemented the atomic-move protocol would be a second
    // durability implementation to keep in step with this one — including the
    // AtomicMoveNotSupported fallback and its warning, which is exactly the sort of detail that
    // gets fixed in one copy and not the other.
    static void move(Path from, Path to) throws IOException {
        // Same directory, always. Both callers already satisfy it — write()'s `.tmp` and the
        // journal's staging file are each a resolveSibling of their target — and it is what makes
        // this a same-filesystem rename, the only shape a filesystem performs atomically. Checking
        // it turns "the filesystem might not support atomic move", which no test can produce, into
        // a precondition a test can check.
        if (!Objects.equals(from.getParent(), to.getParent())) {
            throw new StorageException("refusing to move " + from + " to " + to
                    + ": an atomic replacement must stay within one directory, and a cross-directory move"
                    + " cannot be guaranteed atomic. This is a programming error, not an operator one.");
        }

        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // No fallback. Until 1.1.4 this logged a warning and completed the move non-atomically,
            // which left §11.1-R4 — "a reader after a crash sees the complete previous document or
            // the complete new one, never a partial write" — quietly false on that filesystem while
            // every document, javadoc and status row went on claiming it. A torn permissions file is
            // indistinguishable from a good one, and 1.2.2 puts money on this path.
            //
            // Refusing is the honest failure: the caller cleans up its temporary file and reports,
            // and no document is left in a state the code cannot describe.
            throw new IOException("the filesystem holding " + to.getParent() + " cannot perform an atomic"
                    + " move, so replacing this document cannot be made crash-safe (§11.1-R4). NexusCore"
                    + " refuses the write rather than completing it with a weaker guarantee than it claims."
                    + " Move the NexusCore data directory to a filesystem that supports atomic rename.", e);
        }
    }

    static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Forces a directory's own entries to disk, making a rename durable rather than merely done.
     *
     * <p><b>This is a no-op on some platforms, and that is a published limitation rather than a
     * hidden one.</b> Windows cannot open a directory as a channel at all, so every call here fails
     * and every rename NexusCore performs is durable only once the filesystem flushes its own
     * metadata on its own schedule. Nothing is corrupted by that — documents are still replaced
     * atomically — but a power loss can lose a rename that the process was told had succeeded.</p>
     *
     * <p>Until 1.1.4 the failure was logged at DEBUG, which no operator runs, so the weakening was
     * invisible on the one platform where it always happens. It is now reported once per run at
     * WARN, and named on the published defect list. Unlike the atomic-move ceiling above this one
     * cannot be closed — there is no directory-fsync equivalent to reach for — so it is ruled and
     * stated instead of fixed.</p>
     */
    static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            if (DIRECTORY_FSYNC_UNAVAILABLE.compareAndSet(false, true)) {
                LOGGER.warn("this platform cannot flush a directory to disk ({}), so a completed rename is"
                        + " durable only once the filesystem writes its own metadata. Documents are still"
                        + " replaced atomically; what is weakened is surviving a power loss immediately"
                        + " afterwards. Expected on Windows, where a directory cannot be opened as a channel"
                        + " at all, and published as a known platform limitation. Reported once per run.",
                        directory, e);
            } else {
                LOGGER.debug("could not fsync directory {}", directory, e);
            }
        }
    }

    /** @return true once a directory fsync has failed in this run; see {@link #forceDirectory} */
    static boolean directoryFsyncUnavailable() {
        return DIRECTORY_FSYNC_UNAVAILABLE.get();
    }

    private static Path quarantine(Path file) {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            LOGGER.error("could not move the unreadable file {} aside", file, e);
            return file;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("could not remove the temporary file {}", file, e);
        }
    }
}
