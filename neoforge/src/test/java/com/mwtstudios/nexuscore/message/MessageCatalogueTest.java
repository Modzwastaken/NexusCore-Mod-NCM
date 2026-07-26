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

    /** Keys resolved dynamically or reserved for the mod's own metadata. */
    private static final Set<String> NOT_REFERENCED_DIRECTLY = Set.of("nexuscore.name");

    private static Path projectRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("src/main/java"))) {
            here = here.getParent();
        }
        return here;
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
        Path root = projectRoot();
        assertTrue(root != null, "could not locate the project root from " + Path.of("").toAbsolutePath());

        Set<String> referenced = scan(root.resolve("src/main/java"), KEY_CALL);
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
        Path root = projectRoot();
        Set<String> referenced = scan(root.resolve("src/main/java"), ANY_LITERAL);
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
}
