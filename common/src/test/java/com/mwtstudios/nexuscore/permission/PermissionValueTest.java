package com.mwtstudios.nexuscore.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hand-edited {@code permissions.json} must never fail open.
 *
 * <p>{@code docs/admin/permissions.md} instructs operators to edit that file directly, and the
 * loader used to decide {@code allow = !"DENY".equalsIgnoreCase(value)} — so every value that was
 * not exactly {@code DENY} became a <b>grant</b>: {@code null}, {@code "denied"}, {@code "no"},
 * {@code "DENY "} with a trailing space, a JSON {@code false}. An unparseable pattern was skipped
 * silently, removing a deny. Both directions favoured access.</p>
 *
 * <p>No existing test caught it because the command path only ever writes literal
 * {@code ALLOW}/{@code DENY} — no test source contained the string {@code DENY} at all. These tests
 * go through the file, which is the path operators actually use.</p>
 */
class PermissionValueTest {

    private static final String NODE = "nexuscore.command.moderation.ban";

    private static PermissionService serviceWith(Path root, String storedValue) throws IOException {
        Files.createDirectories(root);
        String value = storedValue == null ? "null" : "\"" + storedValue + "\"";
        Files.writeString(root.resolve("permissions.json"), """
                {
                  "schemaVersion": 1,
                  "defaultGroup": "default",
                  "groups": {
                    "default": { "weight": 0, "inherits": [], "nodes": { "%s": %s } }
                  },
                  "subjects": {}
                }
                """.formatted(NODE, value), StandardCharsets.UTF_8);
        return new PermissionService(new JsonStore(root), new NexusSettings().permissionCacheSize);
    }

    private static boolean allows(PermissionService service, UUID subject) {
        return service.evaluate(subject, NODE).allowed();
    }

    @Test
    @DisplayName("a literal ALLOW grants and a literal DENY refuses")
    void theTwoLegalValuesWork(@TempDir Path root) throws IOException {
        UUID subject = UUID.randomUUID();
        assertTrue(allows(serviceWith(root.resolve("a"), "ALLOW"), subject), "ALLOW must grant");
        assertFalse(allows(serviceWith(root.resolve("b"), "DENY"), subject), "DENY must refuse");
    }

    @Test
    @DisplayName("a value that is neither ALLOW nor DENY is never treated as a grant")
    void unrecognisedValuesFailClosed(@TempDir Path root) throws IOException {
        UUID subject = UUID.randomUUID();
        // Every one of these used to grant the node.
        List<String> typos = List.of("denied", "DENY ", " deny", "no", "0", "false", "allowed", "",
                "ALLOWED", "yes", "true");

        // Guard against a vacuous pass: prove the fixture actually grants when the value is legal,
        // so a false result below means "refused", not "the group never applied".
        assertTrue(allows(serviceWith(root.resolve("control"), "ALLOW"), subject),
                "fixture is broken: a literal ALLOW must grant, or the assertions below prove nothing");

        for (int i = 0; i < typos.size(); i++) {
            Path dir = root.resolve("t" + i);
            assertFalse(allows(serviceWith(dir, typos.get(i)), subject),
                    "value '" + typos.get(i) + "' must not grant " + NODE);
        }
    }

    @Test
    @DisplayName("a null value is not a grant")
    void nullValueFailsClosed(@TempDir Path root) throws IOException {
        assertFalse(allows(serviceWith(root, null), UUID.randomUUID()),
                "a JSON null must not grant the node");
    }

    @Test
    @DisplayName("case and surrounding whitespace are tolerated on the two legal values")
    void legalValuesAreTolerant(@TempDir Path root) throws IOException {
        UUID subject = UUID.randomUUID();
        assertTrue(allows(serviceWith(root.resolve("x"), " allow "), subject),
                "a padded ALLOW is unambiguous and should still grant");
        assertFalse(allows(serviceWith(root.resolve("y"), " deny "), subject),
                "a padded DENY must still refuse");
    }

    @Test
    @DisplayName("an unparseable node pattern is skipped, so a broken deny cannot become a grant")
    void invalidPatternIsSkipped(@TempDir Path root) throws IOException {
        Files.createDirectories(root);
        // A mid-pattern wildcard is refused at construction; it must not silently become an allow.
        Files.writeString(root.resolve("permissions.json"), """
                {
                  "schemaVersion": 1,
                  "defaultGroup": "default",
                  "groups": {
                    "default": { "weight": 0, "inherits": [],
                      "nodes": { "nexuscore.*.ban": "DENY", "%s": "DENY" } }
                  },
                  "subjects": {}
                }
                """.formatted(NODE), StandardCharsets.UTF_8);

        PermissionService service = new PermissionService(new JsonStore(root), 64);
        assertFalse(allows(service, UUID.randomUUID()), "the valid DENY must still apply");
        assertEquals(1, service.groupNames().size(), "the group should still load");
    }
}
