package com.mwtstudios.nexuscore.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pattern validation and specificity scoring, per the table in ADR-0004. */
class PermissionNodeTest {

    @ParameterizedTest(name = "{0} scores {1}")
    @CsvSource({
            "nexuscore.command.moderation.ban, 9",
            "nexuscore.command.moderation.*, 6",
            "nexuscore.command.*, 4",
            "nexuscore.*, 2",
            "*, 0"
    })
    @DisplayName("specificity scores match the ADR-0004 table exactly")
    void specificityMatchesAdr(String pattern, int expected) {
        assertEquals(expected, PermissionNode.of(pattern).specificity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nexuscore.*.ban", "nexuscore.**", "*.ban", "nexuscore.com*mand.ban", "a.*.b.*"})
    @DisplayName("a mid-pattern wildcard is rejected at construction, not at evaluation")
    void midPatternWildcardsRejected(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> PermissionNode.of(pattern),
                "expected '" + pattern + "' to be refused: a mid-pattern wildcard makes "
                        + "'longer path beats shorter' undefinable");
        assertFalse(PermissionNode.isValid(pattern));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "..", "nexuscore..ban", "NEXUS core.ban", "nexus core.ban"})
    @DisplayName("malformed patterns are rejected")
    void malformedPatternsRejected(String pattern) {
        assertFalse(PermissionNode.isValid(pattern));
    }

    @Test
    @DisplayName("a null pattern is rejected rather than accepted as a wildcard")
    void nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> PermissionNode.of(null));
    }

    @Test
    @DisplayName("the universal wildcard matches everything")
    void universalWildcardMatchesEverything() {
        PermissionNode node = PermissionNode.of("*");
        assertTrue(node.matches("nexuscore.command.moderation.ban"));
        assertTrue(node.matches("anything.at.all"));
    }

    @Test
    @DisplayName("a trailing wildcard matches its prefix and nothing outside it")
    void trailingWildcardMatchesPrefixOnly() {
        PermissionNode node = PermissionNode.of("nexuscore.command.*");
        assertTrue(node.matches("nexuscore.command.moderation.ban"));
        assertTrue(node.matches("nexuscore.command.heal"));
        assertFalse(node.matches("nexuscore.gui.admin.open"));
        assertFalse(node.matches("othermod.command.heal"));
    }

    @Test
    @DisplayName("an exact pattern matches only itself")
    void exactMatchesOnlyItself() {
        PermissionNode node = PermissionNode.of("nexuscore.command.heal");
        assertTrue(node.matches("nexuscore.command.heal"));
        assertFalse(node.matches("nexuscore.command.heal.other"));
        assertFalse(node.matches("nexuscore.command"));
    }

    @Test
    @DisplayName("patterns are case-insensitive and normalised to lower case")
    void patternsAreCaseInsensitive() {
        PermissionNode node = PermissionNode.of("NexusCore.Command.Heal");
        assertEquals("nexuscore.command.heal", node.pattern());
        assertTrue(node.matches("NEXUSCORE.COMMAND.HEAL"));
    }

    @Test
    @DisplayName("an exact match always outscores the wildcard that covers it")
    void exactBeatsCoveringWildcard() {
        assertTrue(PermissionNode.of("nexuscore.command.moderation.ban").specificity()
                > PermissionNode.of("nexuscore.command.moderation.*").specificity());
    }

    @Test
    @DisplayName("a longer wildcard path always outscores a shorter one")
    void longerWildcardBeatsShorter() {
        assertTrue(PermissionNode.of("nexuscore.command.moderation.*").specificity()
                > PermissionNode.of("nexuscore.command.*").specificity());
    }
}
