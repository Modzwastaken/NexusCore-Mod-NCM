package com.mwtstudios.nexuscore.command;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;

import org.slf4j.Logger;

/**
 * Removes a vanilla command from the dispatcher so NexusCore can register its own in that
 * name.
 *
 * <p>Brigadier has no public removal API: {@code CommandNode} keeps its {@code children},
 * {@code literals}, and {@code arguments} maps private and final, and {@code RootCommandNode}
 * exposes no {@code removeChild}. Registering a second {@code ban} literal would merge into
 * vanilla's node rather than replace it, leaving two overlapping argument sets and letting
 * Brigadier pick whichever happened to parse first. Reflection is the only way to take the
 * name cleanly.</p>
 *
 * <p>This is not a mixin and does not rewrite bytecode (ADR-0002 still holds), but it does
 * reach into another library's internals, so it is written to fail safely: if any field is
 * missing or inaccessible the removal is abandoned, the failure is logged once with the reason,
 * and {@link NexusCommands} falls back to its {@code /n}-prefixed aliases. A NexusCore that
 * cannot take {@code /ban} still works; one that half-took it would not.</p>
 */
public final class VanillaCommandOverride {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The three maps a Brigadier node keeps its children in. All must be cleared together. */
    private static final String[] CHILD_MAPS = {"children", "literals", "arguments"};

    private VanillaCommandOverride() {
        // Utility holder.
    }

    /**
     * Removes a top-level command literal from the dispatcher.
     *
     * @param dispatcher the server command dispatcher
     * @param name the literal to remove, for example {@code ban}
     * @return true when the command was present and is now gone
     */
    public static boolean remove(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        if (root.getChild(name) == null) {
            return false;
        }
        boolean removedAny = false;
        for (String mapName : CHILD_MAPS) {
            try {
                Field field = CommandNode.class.getDeclaredField(mapName);
                field.setAccessible(true);
                Object value = field.get(root);
                if (value instanceof Map<?, ?> map && map.remove(name) != null) {
                    removedAny = true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("NexusCore could not take over /{}: Brigadier's '{}' field was not reachable ({}). "
                        + "The vanilla command stays in place; use /n{} or /nexus ... instead.",
                        name, mapName, e.getClass().getSimpleName(), name);
                return false;
            }
        }
        return removedAny;
    }

    /**
     * Removes several commands, reporting what happened to each.
     *
     * @param dispatcher the server command dispatcher
     * @param names the literals to remove
     * @return each name mapped to whether it was successfully removed
     */
    public static Map<String, Boolean> removeAll(CommandDispatcher<CommandSourceStack> dispatcher, String... names) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String name : names) {
            results.put(name, remove(dispatcher, name));
        }
        return results;
    }
}
