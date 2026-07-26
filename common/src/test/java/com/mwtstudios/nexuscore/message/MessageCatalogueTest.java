package com.mwtstudios.nexuscore.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces §2.3 part 6 mechanically: <em>every</em> player-facing string has an
 * {@code en_us.json} key.
 *
 * <p>This scans the production sources for calls to {@code render("…")} and {@code raw("…")}
 * and asserts each key exists in the catalogue. A missing message would otherwise reach a
 * player as a bracketed key, and only in the rarely-taken branch that emits it. This test
 * moves that discovery to build time.</p>
 *
 * <p>It also checks the reverse direction: a key in the catalogue that no code references is
 * reported, so the file does not silently accumulate dead wording.</p>
 */
class MessageCatalogueTest {

    /** Keys passed directly as a literal, e.g. {@code render("player.heal.success", …)}. */
    private static final Pattern KEY_CALL = Pattern.compile("\\.(?:render|raw)\\(\\s*\"([a-z0-9_.-]+)\"");

    /** Any string literal at all, used for the reverse "is this key dead?" check. */
    private static final Pattern ANY_LITERAL = Pattern.compile("\"([a-z0-9_.-]+)\"");

    /**
     * Matches the first argument of {@code render}/{@code raw} however it is spelled, so a literal
     * that is <em>not</em> key-shaped is caught rather than skipped.
     *
     * <p>{@link #KEY_CALL} only matches key-shaped literals, so a malformed key was invisible to it
     * instead of being reported as missing. A bad search-and-replace produced
     * {@code render("services.teleport().done", …)} and this suite passed — the mangled key shipped
     * in two builds, and a Fabric player saw the raw string after every teleport.</p>
     */
    private static final Pattern ANY_KEY_CALL = Pattern.compile("\\.(?:render|raw)\\(\\s*\"([^\"]*)\"");

    /** Keys resolved dynamically or reserved for the mod's own metadata. */
    private static final Set<String> NOT_REFERENCED_DIRECTLY = Set.of("nexuscore.name");

    /**
     * Every production source root in the repository.
     *
     * <p>There is more than one, and assuming otherwise broke this test at 1.0.4. It used to walk
     * up from the working directory to the first folder containing {@code src/main/java} and scan
     * that — which was the NeoForge project, and back then the NeoForge project held all the shared
     * code. After the move to {@code common/}, that folder holds two files, so every key in the
     * catalogue looked unreferenced.</p>
     *
     * <p>All roots are scanned, not just {@code common/}: a key referenced only from a loader's
     * entry point is still referenced, and treating it as dead wording would be wrong.</p>
     *
     * @return the existing {@code <module>/src/main/java} directories
     * @throws IOException if the repository cannot be read
     */
    private static List<Path> sourceRoots() throws IOException {
        Path repositoryRoot = Path.of("").toAbsolutePath();
        while (repositoryRoot != null && !Files.isDirectory(repositoryRoot.resolve("common/src/main/java"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        assertTrue(repositoryRoot != null,
                "could not find common/src/main/java above " + Path.of("").toAbsolutePath());

        try (Stream<Path> modules = Files.list(repositoryRoot)) {
            return modules.map(module -> module.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> scanAll(Pattern pattern) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path root : sourceRoots()) {
            keys.addAll(scan(root, pattern));
        }
        return keys;
    }

    private static JsonObject catalogue() throws IOException {
        try (InputStream stream = MessageService.class.getResourceAsStream(MessageService.BUNDLED_RESOURCE);
                Reader reader = new InputStreamReader(java.util.Objects.requireNonNull(stream,
                        "bundled catalogue missing from the test classpath"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Set<String> scan(Path sourceRoot, Pattern pattern) throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    @Test
    @DisplayName("every message key referenced in production code exists in en_us.json")
    void everyReferencedKeyExists() throws IOException {
        Set<String> referenced = scanAll(KEY_CALL);
        assertFalse(referenced.isEmpty(), "the scanner found no message keys at all; the pattern is probably wrong");

        JsonObject catalogue = catalogue();
        Set<String> missing = new TreeSet<>();
        for (String key : referenced) {
            if (!catalogue.has(key)) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(),
                "these message keys are used in code but absent from en_us.json, so players would see a raw key: "
                        + missing);
    }

    /**
     * The reverse direction cannot use the narrow call-site pattern: plenty of keys are chosen
     * by a ternary or held in a variable, for example
     * {@code render(enabled ? "player.fly.enabled" : "player.fly.disabled")}. Scanning every
     * string literal is the honest test of "does this key appear in the code at all".
     */
    @Test
    @DisplayName("no catalogue key is dead wording that nothing references")
    void noUnreferencedKeys() throws IOException {
        Set<String> referenced = scanAll(ANY_LITERAL);
        JsonObject catalogue = catalogue();

        Set<String> unused = new TreeSet<>();
        for (String key : catalogue.keySet()) {
            if (!referenced.contains(key) && !NOT_REFERENCED_DIRECTLY.contains(key)) {
                unused.add(key);
            }
        }

        assertTrue(unused.isEmpty(),
                "these en_us.json keys are not referenced by any code and should be removed or wired up: " + unused);
    }

    @Test
    @DisplayName("every catalogue value is a string")
    void everyValueIsAString() throws IOException {
        JsonObject catalogue = catalogue();

        for (String key : catalogue.keySet()) {
            assertTrue(catalogue.get(key).isJsonPrimitive() && catalogue.get(key).getAsJsonPrimitive().isString(),
                    "catalogue value for '" + key + "' is not a string");
        }
    }

    @Test
    @DisplayName("no catalogue value uses a positional placeholder")
    void noPositionalPlaceholders() throws IOException {
        JsonObject catalogue = catalogue();

        for (String key : catalogue.keySet()) {
            String value = catalogue.get(key).getAsString();
            assertFalse(value.contains("%s") || value.contains("%d"),
                    "'" + key + "' uses a positional placeholder; NexusCore messages use named {placeholders} "
                            + "so an operator editing the file cannot silently swap two arguments");
        }
    }

    @Test
    @DisplayName("colourise converts valid codes and leaves everything else alone")
    void coloriseConvertsOnlyValidCodes() {
        assertTrue(MessageService.colourise("&aGreen").startsWith("§a"));
        assertTrue(MessageService.colourise("&zNotACode").startsWith("&z"), "an invalid code must be left visible");
        assertTrue(MessageService.colourise("trailing&").endsWith("&"), "a lone trailing & must not be swallowed");
        assertTrue(MessageService.colourise("").isEmpty());
    }


    @Test
    @DisplayName("every render/raw call passes a key-shaped literal")
    void everyKeyLiteralIsWellFormed() throws IOException {
        Set<String> malformed = new TreeSet<>();
        for (String key : scanAll(ANY_KEY_CALL)) {
            if (!ANY_LITERAL.matcher("\"" + key + "\"").matches()) {
                malformed.add(key);
            }
        }

        assertTrue(malformed.isEmpty(),
                "these are passed to render()/raw() but are not valid message keys, so the player would "
                        + "see the raw string: " + malformed);
    }
}
