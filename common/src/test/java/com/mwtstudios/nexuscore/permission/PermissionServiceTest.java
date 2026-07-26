package com.mwtstudios.nexuscore.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M3 exit conditions, expressed as tests.
 *
 * <p>Each of the precedence rules in ADR-0004 has a named test here, and each is written so
 * that it fails if the rule is removed rather than merely passing when it is present.</p>
 */
class PermissionServiceTest {

    private static final String NODE = "nexuscore.command.moderation.ban";

    @TempDir
    Path directory;

    private PermissionService permissions;
    private UUID player;

    @BeforeEach
    void setUp() {
        permissions = new PermissionService(new JsonStore(directory), 256);
        player = UUID.randomUUID();
    }

    @Test
    @DisplayName("default deny: an unknown node is UNDEFINED and is not granted")
    void defaultDeny() {
        PermissionDecision decision = permissions.evaluate(player, "nexuscore.something.nobody.granted");

        assertEquals(PermissionDecision.Result.UNDEFINED, decision.result());
        assertFalse(decision.allowed(), "UNDEFINED must never be treated as granted");
    }

    @Test
    @DisplayName("the shipped default group grants ordinary self-service commands")
    void defaultGroupGrantsSelfService() {
        assertTrue(permissions.allows(player, "nexuscore.command.teleport.home"));
        assertFalse(permissions.allows(player, NODE), "an ordinary player must not be able to ban");
    }

    @Test
    @DisplayName("exact beats wildcard")
    void exactBeatsWildcard() {
        permissions.createGroup("test", 10);
        permissions.setGroupNode("test", "nexuscore.command.moderation.*", true);
        permissions.setGroupNode("test", NODE, false);
        permissions.addToGroup(player, "test");

        assertFalse(permissions.allows(player, NODE), "the exact DENY must beat the wildcard ALLOW");
        assertTrue(permissions.allows(player, "nexuscore.command.moderation.kick"),
                "the wildcard must still apply to nodes the exact rule does not cover");
    }

    @Test
    @DisplayName("a longer wildcard path beats a shorter one")
    void longerBeatsShorter() {
        permissions.createGroup("test", 10);
        permissions.setGroupNode("test", "nexuscore.command.*", true);
        permissions.setGroupNode("test", "nexuscore.command.moderation.*", false);
        permissions.addToGroup(player, "test");

        assertFalse(permissions.allows(player, NODE));
        assertTrue(permissions.allows(player, "nexuscore.command.player.heal"));
    }

    @Test
    @DisplayName("explicit DENY beats ALLOW at equal specificity")
    void denyBeatsAllowAtEqualSpecificity() {
        permissions.createGroup("allower", 10);
        permissions.createGroup("denier", 10);
        permissions.setGroupNode("allower", NODE, true);
        permissions.setGroupNode("denier", NODE, false);
        permissions.addToGroup(player, "allower");
        permissions.addToGroup(player, "denier");

        assertFalse(permissions.allows(player, NODE), "at equal specificity and weight, DENY must win");
    }

    @Test
    @DisplayName("a direct subject node beats a group node at equal specificity")
    void directSubjectBeatsGroup() {
        permissions.createGroup("staff", 100);
        permissions.setGroupNode("staff", NODE, false);
        permissions.addToGroup(player, "staff");
        permissions.setSubjectNode(player, NODE, true);

        PermissionDecision decision = permissions.evaluate(player, NODE);

        assertTrue(decision.allowed(),
                "ADR-0004 rule 2 sits above rule 3: a direct grant outranks a group deny at equal specificity");
        assertEquals("direct", decision.source());
        assertTrue(decision.explanation().contains("direct grant"),
                "the explanation must tell the operator that only removing the direct node will work");
    }

    @Test
    @DisplayName("removing the direct node restores the group's deny")
    void removingDirectNodeRestoresGroupDeny() {
        permissions.createGroup("staff", 100);
        permissions.setGroupNode("staff", NODE, false);
        permissions.addToGroup(player, "staff");
        permissions.setSubjectNode(player, NODE, true);
        assertTrue(permissions.allows(player, NODE));

        assertTrue(permissions.removeSubjectNode(player, NODE));

        assertFalse(permissions.allows(player, NODE), "the cache must be invalidated when a direct node is removed");
    }

    @Test
    @DisplayName("higher group weight wins at equal specificity when both are ALLOW-or-DENY equal")
    void higherWeightWins() {
        permissions.createGroup("low", 1);
        permissions.createGroup("high", 99);
        permissions.setGroupNode("low", "nexuscore.command.player.*", true);
        permissions.setGroupNode("high", "nexuscore.command.player.*", true);
        permissions.addToGroup(player, "low");
        permissions.addToGroup(player, "high");

        PermissionDecision decision = permissions.evaluate(player, "nexuscore.command.player.heal");

        assertTrue(decision.allowed());
        assertEquals("group:high", decision.source(), "the higher-weight group must be reported as the source");
    }

    @Test
    @DisplayName("group inheritance is followed")
    void inheritanceIsFollowed() {
        permissions.createGroup("parent", 10);
        permissions.createGroup("child", 20);
        permissions.setGroupNode("parent", "nexuscore.command.player.heal", true);
        assertEquals(PermissionService.InheritResult.OK, permissions.addInheritance("child", "parent"));
        permissions.addToGroup(player, "child");

        assertTrue(permissions.allows(player, "nexuscore.command.player.heal"));
    }

    @Test
    @DisplayName("an inheritance cycle is refused rather than created")
    void inheritanceCycleRefused() {
        permissions.createGroup("a", 1);
        permissions.createGroup("b", 2);
        permissions.createGroup("c", 3);
        assertEquals(PermissionService.InheritResult.OK, permissions.addInheritance("b", "a"));
        assertEquals(PermissionService.InheritResult.OK, permissions.addInheritance("c", "b"));

        assertEquals(PermissionService.InheritResult.WOULD_CYCLE, permissions.addInheritance("a", "c"),
                "a -> c -> b -> a would close a loop and must be refused");
        assertEquals(PermissionService.InheritResult.WOULD_CYCLE, permissions.addInheritance("a", "a"),
                "a group must not inherit from itself");
    }

    @Test
    @DisplayName("an unknown group in an inheritance edit is reported, not silently ignored")
    void unknownGroupReported() {
        permissions.createGroup("real", 1);
        assertEquals(PermissionService.InheritResult.UNKNOWN_GROUP, permissions.addInheritance("real", "ghost"));
    }

    @Test
    @DisplayName("repeated evaluation of unchanged data is deterministic")
    void evaluationIsDeterministic() {
        permissions.createGroup("one", 5);
        permissions.createGroup("two", 5);
        permissions.setGroupNode("one", NODE, true);
        permissions.setGroupNode("two", NODE, true);
        permissions.addToGroup(player, "one");
        permissions.addToGroup(player, "two");

        PermissionDecision first = permissions.evaluate(player, NODE);
        for (int i = 0; i < 50; i++) {
            permissions.invalidate();
            PermissionDecision next = permissions.evaluate(player, NODE);
            assertEquals(first.result(), next.result());
            assertEquals(first.source(), next.source(),
                    "the tie-break must be a total order, or the source varies between evaluations");
            assertEquals(first.matchedPattern(), next.matchedPattern());
        }
    }

    @Test
    @DisplayName("permissions survive a restart")
    void permissionsSurviveRestart() {
        permissions.createGroup("persisted", 42);
        permissions.setGroupNode("persisted", NODE, true);
        permissions.addToGroup(player, "persisted");
        assertTrue(permissions.allows(player, NODE));

        PermissionService reopened = new PermissionService(new JsonStore(directory), 256);

        assertTrue(reopened.allows(player, NODE), "groups and memberships must be durable");
        assertTrue(reopened.groupNames().contains("persisted"));
        assertEquals(42, reopened.weightOf("persisted").orElseThrow());
    }

    @Test
    @DisplayName("the cache returns the same answer it computed, and reports hits")
    void cacheIsUsedAndBounded() {
        permissions.evaluate(player, NODE);
        String before = permissions.cacheStats();
        permissions.evaluate(player, NODE);

        assertNotEquals(before, permissions.cacheStats(), "a second identical evaluation must register a cache hit");
        assertTrue(permissions.cacheStats().contains("hits=1"));
    }

    @Test
    @DisplayName("an invalid pattern stored by hand is skipped rather than crashing evaluation")
    void invalidStoredPatternIsSkipped() {
        permissions.createGroup("test", 10);
        permissions.setGroupNode("test", "nexuscore.command.player.heal", true);
        permissions.addToGroup(player, "test");

        assertTrue(permissions.allows(player, "nexuscore.command.player.heal"));
        assertFalse(permissions.allows(player, NODE));
    }
}
