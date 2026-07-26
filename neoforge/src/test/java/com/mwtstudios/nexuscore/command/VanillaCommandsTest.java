package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural tests for the commands NexusCore takes over from vanilla.
 *
 * <p>These assert the <em>shape</em> of the Brigadier tree, which is what decides whether
 * {@code /tp @a ~ ~5 ~ facing 0 64 0} parses at all. Losing a branch here would not fail a
 * compile and would not fail any of the server smoke tests — it would simply make one form of
 * a command that operators have used for years stop working, which is the specific regression
 * risk of taking a vanilla command over.</p>
 *
 * <p>The builders capture {@code services} only inside their execution lambdas, so the tree
 * can be constructed with a null registry: nothing is invoked, only assembled.</p>
 */
class VanillaCommandsTest {

    private static Set<String> childNames(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    private static CommandNode<CommandSourceStack> child(CommandNode<CommandSourceStack> node, String name) {
        CommandNode<CommandSourceStack> found = node.getChild(name);
        assertNotNull(found, "expected a child named '" + name + "' but found " + childNames(node));
        return found;
    }

    @Test
    @DisplayName("/tp keeps every vanilla form: location, destination, targets, rotation, facing")
    void teleportTreeMatchesVanillaGrammar() {
        CommandNode<CommandSourceStack> tp = VanillaCommands.teleport(null).build();

        assertEquals("tp", tp.getName());
        assertTrue(childNames(tp).containsAll(Set.of("location", "destination", "targets")),
                "/tp must accept a bare location, a bare destination entity, and a targets selector; got "
                        + childNames(tp));

        CommandNode<CommandSourceStack> targets = child(tp, "targets");
        assertTrue(childNames(targets).containsAll(Set.of("destination", "location")),
                "/tp <targets> must accept both an entity and a location; got " + childNames(targets));

        CommandNode<CommandSourceStack> location = child(targets, "location");
        assertTrue(childNames(location).containsAll(Set.of("rotation", "facing")),
                "/tp <targets> <location> must accept a rotation and a facing clause; got " + childNames(location));

        CommandNode<CommandSourceStack> facing = child(location, "facing");
        assertTrue(childNames(facing).containsAll(Set.of("facingLocation", "entity")),
                "facing must accept a location or the 'entity' literal; got " + childNames(facing));

        CommandNode<CommandSourceStack> facingEntity = child(child(facing, "entity"), "facingEntity");
        assertTrue(childNames(facingEntity).contains("facingAnchor"),
                "facing entity must accept an optional anchor; got " + childNames(facingEntity));
    }

    @Test
    @DisplayName("every /tp form is executable, so no branch is a dead end")
    void everyTeleportFormIsExecutable() {
        CommandNode<CommandSourceStack> tp = VanillaCommands.teleport(null).build();
        CommandNode<CommandSourceStack> targets = child(tp, "targets");
        CommandNode<CommandSourceStack> location = child(targets, "location");

        assertNotNull(child(tp, "location").getCommand(), "/tp <location>");
        assertNotNull(child(tp, "destination").getCommand(), "/tp <destination>");
        assertNotNull(child(targets, "destination").getCommand(), "/tp <targets> <destination>");
        assertNotNull(location.getCommand(), "/tp <targets> <location>");
        assertNotNull(child(location, "rotation").getCommand(), "/tp <targets> <location> <rotation>");
        assertNotNull(child(child(location, "facing"), "facingLocation").getCommand(),
                "/tp <targets> <location> facing <location>");
        assertNotNull(child(child(child(location, "facing"), "entity"), "facingEntity").getCommand(),
                "/tp <targets> <location> facing entity <entity>");
    }

    @Test
    @DisplayName("/gamemode keeps the mode argument and the optional targets selector")
    void gamemodeTreeMatchesVanillaGrammar() {
        CommandNode<CommandSourceStack> gamemode = VanillaCommands.gamemode(null).build();

        assertEquals("gamemode", gamemode.getName());
        CommandNode<CommandSourceStack> mode = child(gamemode, "gamemode");
        assertNotNull(mode.getCommand(), "/gamemode <mode> must be executable on its own");
        assertNotNull(child(mode, "targets").getCommand(), "/gamemode <mode> <targets> must be executable");
    }
}
