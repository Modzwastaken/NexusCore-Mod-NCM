package com.mwtstudios.nexuscore;

import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The single command registered at M0: {@code /nexus version}.
 *
 * <p>The message is sent as a literal component rather than a translatable one. NexusCore
 * v0.1 is server-only and players join with unmodified vanilla clients, which have no copy
 * of this mod's language file and would render the raw translation key. Server-side
 * resolution against NexusCore's own catalogue arrives with {@code MessageService} in M1;
 * see docs/architecture/ADR-0003.md.</p>
 *
 * <p>The format string below is the single source of truth shared with
 * {@code assets/nexuscore/lang/en_us.json} under {@link #VERSION_MESSAGE_KEY}. The two are
 * held equal by {@code NexusVersionCommandTest}, so adopting the key in M1 cannot silently
 * change the wording.</p>
 */
public final class NexusVersionCommand {

    /** Translation key that {@code MessageService} will resolve from M1 onward. */
    public static final String VERSION_MESSAGE_KEY = "commands.nexuscore.version";

    /** Message format: display name, then version. Kept identical to the en_us.json value. */
    public static final String VERSION_MESSAGE_FORMAT = "%s version %s";

    private NexusVersionCommand() {
        // Utility holder for the M0 command registration.
    }

    /**
     * Registers {@code /nexus version} on the given dispatcher.
     *
     * @param dispatcher the server command dispatcher supplied by {@code RegisterCommandsEvent}
     * @param displayName the mod display name taken from the mod container
     * @param version the mod version taken from the mod container
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String displayName, String version) {
        dispatcher.register(
                Commands.literal("nexus")
                        .then(Commands.literal("version")
                                .executes(context -> reportVersion(context, displayName, version))));
    }

    private static int reportVersion(CommandContext<CommandSourceStack> context, String displayName, String version) {
        String message = formatVersion(displayName, version);
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Renders the version line.
     *
     * @param displayName the mod display name
     * @param version the mod version
     * @return the exact text sent to the command source
     */
    public static String formatVersion(String displayName, String version) {
        return String.format(Locale.ROOT, VERSION_MESSAGE_FORMAT, displayName, version);
    }
}
