package com.mwtstudios.nexuscore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.mwtstudios.nexuscore.command.CommandCatalogue;
import com.mwtstudios.nexuscore.command.CommandDescriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the safe-mode guards.
 *
 * <p>{@code NexusServices.has} ends in {@code default -> true}, which is the right answer for a core
 * module and a silent disaster for a typo: {@code has("moderaton")} returns true, the guard around a
 * disabled service does nothing, and the failure appears somewhere else entirely. Every module id
 * used anywhere in the sources is therefore checked against the real set here, mechanically.</p>
 */
class SafeModeGuardTest {

    /** Module ids {@code NexusServices.has} actually knows about. */
    private static final Set<String> KNOWN = Set.of("core", "teleport", "player-utilities", "moderation");

    private static final Pattern HAS_CALL = Pattern.compile("\\.has\\(\\s*\"([^\"]*)\"\\s*\\)");

    private static final Pattern DISABLE_CALL = Pattern.compile("disable\\(\\s*\"([^\"]*)\"\\s*\\)");

    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("common/src/main/java"))) {
            here = here.getParent();
        }
        assertTrue(here != null, "could not find common/src/main/java above " + Path.of("").toAbsolutePath());
        return here;
    }

    private static Set<String> scan(Pattern pattern) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> modules = Files.list(repositoryRoot())) {
            List<Path> roots = modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList();
            for (Path root : roots) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
                        while (matcher.find()) {
                            found.add(matcher.group(1));
                        }
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every module id passed to has() is one the switch actually knows")
    void everyGuardedModuleIdIsReal() throws IOException {
        Set<String> used = scan(HAS_CALL);
        assertFalse(used.isEmpty(), "no has(\"…\") calls found; the pattern is probably wrong");

        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(KNOWN);

        assertTrue(unknown.isEmpty(),
                "these module ids are passed to NexusServices.has() but are not module ids, so has() falls "
                        + "through to `default -> true` and the guard silently does nothing: " + unknown);
    }

    @Test
    @DisplayName("safe mode disables exactly the modules StandardModules marks non-core")
    void safeModeDisablesTheOptionalModules() {
        assertEquals(List.of("teleport", "player-utilities", "moderation"), SafeMode.disabledModules());

        for (String module : SafeMode.disabledModules()) {
            assertTrue(KNOWN.contains(module), module + " is not a known module id");
            assertFalse("core".equals(module), "core is not disablable");
        }
    }

    @Test
    @DisplayName("every module id the catalogue attributes a command to is a real module id")
    void everyDescriptorModuleIsReal() {
        Set<String> unknown = new TreeSet<>();
        for (CommandDescriptor descriptor : CommandCatalogue.all()) {
            if (!KNOWN.contains(descriptor.module())) {
                unknown.add(descriptor.node() + " -> " + descriptor.module());
            }
        }

        assertTrue(unknown.isEmpty(),
                "these descriptors name a module that does not exist, so /nexus help would show the command "
                        + "in safe mode and running it would fail: " + unknown);
    }

    @Test
    @DisplayName("safe mode reads only the documented property, and rejects near-misses")
    void propertyParsingIsStrict() {
        String previous = System.getProperty(SafeMode.PROPERTY);
        try {
            for (String on : List.of("true", "TRUE", " true ", "1")) {
                System.setProperty(SafeMode.PROPERTY, on);
                assertTrue(SafeMode.requested(), "expected safe mode ON for '" + on + "'");
            }
            // "yes" is the mistake an operator actually makes. It must NOT silently enable safe
            // mode, and describe() must say safe mode is off so the typo is visible in the log.
            for (String off : List.of("yes", "on", "false", "", "0")) {
                System.setProperty(SafeMode.PROPERTY, off);
                assertFalse(SafeMode.requested(), "expected safe mode OFF for '" + off + "'");
                assertTrue(SafeMode.describe().contains("OFF"), "describe() should report OFF for '" + off + "'");
            }
        } finally {
            if (previous == null) {
                System.clearProperty(SafeMode.PROPERTY);
            } else {
                System.setProperty(SafeMode.PROPERTY, previous);
            }
        }
    }
}
