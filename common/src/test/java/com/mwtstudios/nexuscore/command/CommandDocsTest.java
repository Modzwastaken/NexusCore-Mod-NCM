package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the generated command reference, the catalogue and the code from disagreeing.
 *
 * <p>§12.5 warned that a hand-maintained command document drifts. It did: `docs/admin/commands.md`
 * told operators for two releases that NexusCore does <b>not</b> override {@code /ban},
 * {@code /kick} and {@code /banlist}, in the section headed "read this first", after the v1.0
 * takeover made that false. Generating the document is only half a fix — these tests are the half
 * that keeps the generator's input honest.</p>
 */
class CommandDocsTest {

    /** The permission node passed to the pipeline, as it appears at a call site. */
    private static final Pattern RUN_NODE = Pattern.compile("\"(nexuscore\\.(?:command|gui)\\.[a-z.]+)\"");

    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("common/src/main/java"))) {
            here = here.getParent();
        }
        assertTrue(here != null, "could not find common/src/main/java above " + Path.of("").toAbsolutePath());
        return here;
    }

    private static Path documentPath() {
        return repositoryRoot().resolve("docs/admin/commands.md");
    }

    /**
     * @return every permission node the production sources actually pass to the command pipeline,
     *     excluding the catalogue itself — which names all of them and would make the comparison
     *     vacuously true
     */
    private static Set<String> nodesUsedInCode() throws IOException {
        Set<String> found = new TreeSet<>();
        Path root = repositoryRoot();
        try (Stream<Path> modules = Files.list(root)) {
            List<Path> sourceRoots = modules.map(module -> module.resolve("src/main/java"))
                    .filter(Files::isDirectory).toList();
            for (Path sourceRoot : sourceRoots) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        if (file.getFileName().toString().equals("CommandCatalogue.java")) {
                            continue;
                        }
                        Matcher matcher = RUN_NODE.matcher(Files.readString(file, StandardCharsets.UTF_8));
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
    @DisplayName("the committed document matches what the catalogue renders")
    void documentIsUpToDate() throws IOException {
        String committed = Files.readString(documentPath(), StandardCharsets.UTF_8);

        assertEquals(CommandDocs.render(), committed,
                "docs/admin/commands.md is out of date. Regenerate it with "
                        + "`./gradlew generateCommandDocs` from the neoforge project. This test exists because "
                        + "the hand-maintained version drifted for two releases.");
    }

    @Test
    @DisplayName("every permission node the code checks is described in the catalogue")
    void everyNodeUsedInCodeIsDocumented() throws IOException {
        Set<String> used = nodesUsedInCode();
        assertTrue(used.size() > 20, "the node scanner found only " + used.size() + "; the pattern is probably wrong");

        Set<String> undocumented = new TreeSet<>(used);
        undocumented.removeAll(CommandCatalogue.nodes());

        assertTrue(undocumented.isEmpty(),
                "these permission nodes are checked by the code but appear in no descriptor, so they are "
                        + "absent from /nexus help and from the reference document: " + undocumented);
    }

    @Test
    @DisplayName("every catalogue descriptor names a node the code actually checks")
    void everyDocumentedNodeExistsInCode() throws IOException {
        Set<String> used = nodesUsedInCode();

        Set<String> phantom = new TreeSet<>(CommandCatalogue.nodes());
        phantom.removeAll(used);

        assertTrue(phantom.isEmpty(),
                "these nodes are documented but no code checks them, so the document promises commands that "
                        + "do not exist: " + phantom);
    }

    @Test
    @DisplayName("no descriptor is missing a summary, and none claims an unknown module")
    void descriptorsAreWellFormed() {
        Set<String> knownModules = Set.of("core", "teleport", "player-utilities", "moderation");
        Set<String> problems = new TreeSet<>();

        for (CommandDescriptor descriptor : CommandCatalogue.all()) {
            if (descriptor.summary() == null || descriptor.summary().isBlank()) {
                problems.add(descriptor.node() + " has no summary");
            }
            if (!knownModules.contains(descriptor.module())) {
                problems.add(descriptor.node() + " claims unknown module '" + descriptor.module() + "'");
            }
            if (!descriptor.canonical().startsWith("/") && !descriptor.canonical().startsWith("(GUI)")) {
                problems.add(descriptor.node() + " has a usage string that is neither a command nor a GUI page");
            }
        }

        assertTrue(problems.isEmpty(), "malformed descriptors: " + problems);
    }

    @Test
    @DisplayName("rendering is deterministic, so a regenerated document diffs only on real changes")
    void renderIsDeterministic() {
        assertEquals(CommandDocs.render(), CommandDocs.render());
    }
}
