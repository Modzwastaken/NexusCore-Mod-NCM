package com.mwtstudios.nexuscore.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Filesystem containment (§15, "Path traversal").
 *
 * <p>Every path NexusCore touches passes through here first. The rule is not "reject
 * strings containing {@code ..}" — that check is defeated by symlinks and by encodings.
 * The rule is: resolve the candidate to a real absolute path and <em>prove</em> it is
 * inside an approved root. If it cannot be proven, it is refused.</p>
 */
public final class PathSafety {

    private PathSafety() {
        // Utility holder.
    }

    /**
     * Resolves {@code candidate} against {@code root} and proves the result stays inside it.
     *
     * @param root the approved directory
     * @param candidate a relative path supplied by configuration, a command, or a payload
     * @return the resolved absolute path, guaranteed to be inside {@code root}
     * @throws StorageException if the candidate escapes the root, is absolute, or cannot be resolved
     */
    public static Path resolveWithin(Path root, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new StorageException("path is empty");
        }
        Path relative = Path.of(candidate);
        if (relative.isAbsolute()) {
            throw new StorageException("absolute paths are refused: " + candidate);
        }
        return requireWithin(root, root.resolve(relative));
    }

    /**
     * Proves that an already-built path lies inside an approved root.
     *
     * <p>Both sides are normalised, and symlinks are followed where the path already exists,
     * so a symlink pointing outside the root is caught rather than trusted.</p>
     *
     * @param root the approved directory
     * @param candidate the path to check
     * @return {@code candidate}, resolved and normalised
     * @throws StorageException if the candidate is not inside {@code root}
     */
    public static Path requireWithin(Path root, Path candidate) {
        Path realRoot = toRealPath(root.toAbsolutePath().normalize());
        Path realCandidate = toRealPath(candidate.toAbsolutePath().normalize());

        if (!realCandidate.startsWith(realRoot)) {
            throw new StorageException("path escapes the approved directory: " + candidate + " is not under " + root);
        }
        return realCandidate;
    }

    /**
     * Resolves symlinks for the deepest existing ancestor, then re-appends the missing tail.
     *
     * <p>{@link Path#toRealPath} fails on a file that does not exist yet, but new files are
     * exactly the case that matters when writing. Walking up to the nearest existing ancestor
     * lets a symlinked parent directory still be detected.</p>
     */
    private static Path toRealPath(Path path) {
        Path existing = path;
        int missingSegments = 0;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            missingSegments++;
        }
        if (existing == null) {
            return path;
        }
        try {
            Path real = existing.toRealPath();
            if (missingSegments == 0) {
                return real;
            }
            int nameCount = path.getNameCount();
            return real.resolve(path.subpath(nameCount - missingSegments, nameCount));
        } catch (IOException e) {
            throw new StorageException("could not resolve path " + path, e);
        }
    }
}
