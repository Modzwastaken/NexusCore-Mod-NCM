package com.mwtstudios.nexuscore.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * <p><b>Read protocol.</b> A missing file yields the caller's default. A corrupt file is
 * <em>not</em> silently replaced with defaults — it is moved aside to
 * {@code <name>.json.corrupt-<timestamp>} and reported, because silently discarding an
 * operator's permission or punishment data is worse than failing loudly (§5, "Backward-compatible
 * data": never silently discard unknown or older data).</p>
 */
public final class JsonStore {

    private static final Logger LOGGER = LogUtils.getLogger();

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
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
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
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StorageException("could not write " + file, e);
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
                channel.write(StandardCharsets.UTF_8.encode(line + "\n"));
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

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot do this. Report the weaker guarantee rather than hide it.
            LOGGER.warn("filesystem at {} does not support atomic move; falling back to a replace, "
                    + "which is not crash-safe", to.getParent(), e);
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        // Directory fsync makes the rename itself durable. It is not supported everywhere,
        // and a failure here weakens durability without corrupting anything, so it is logged
        // rather than thrown.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            LOGGER.debug("could not fsync directory {}; the rename may not be durable across a power loss", directory, e);
        }
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
