package com.mwtstudios.nexuscore.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mwtstudios.nexuscore.permission.PermissionNode;

import net.minecraft.network.chat.Component;

/**
 * A permission pattern typed as a command argument, wildcard included.
 *
 * <p>{@code StringArgumentType.word()} accepts {@code [0-9A-Za-z_.+-]} and therefore rejects
 * {@code *} — so {@code /nexus permission set alice nexuscore.command.* allow}, the single form
 * an operator most often wants, could not be typed at all. Quoting through
 * {@code StringArgumentType.string()} would work but is an obscure workaround for the common
 * case; this reads the pattern directly and validates it with the same
 * {@link PermissionNode#of} the engine uses, so an argument that parses is an argument the
 * permission system will accept.</p>
 */
public final class PermissionNodeArgument implements ArgumentType<String> {

    private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
            reason -> Component.literal(String.valueOf(reason)));

    /** Characters a permission pattern may contain: segment characters, separators, wildcard. */
    private static boolean isPatternCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '*';
    }

    /** @return a fresh argument type */
    public static PermissionNodeArgument permissionNode() {
        return new PermissionNodeArgument();
    }

    /**
     * @param context the parsed command
     * @param name the argument name
     * @return the pattern as typed, lowercased
     */
    public static String get(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && isPatternCharacter(reader.peek())) {
            reader.skip();
        }
        String raw = reader.getString().substring(start, reader.getCursor());
        if (raw.isEmpty()) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader, "a permission node is required, for example "
                    + "nexuscore.command.player.* or *");
        }
        String pattern = raw.toLowerCase(Locale.ROOT);
        if (!PermissionNode.isValid(pattern)) {
            reader.setCursor(start);
            // The engine's own reason, so the command and the evaluator cannot disagree about
            // what is acceptable — a mid-pattern wildcard is refused here exactly as there.
            try {
                PermissionNode.of(pattern);
            } catch (IllegalArgumentException e) {
                throw INVALID.createWithContext(reader, e.getMessage());
            }
        }
        return pattern;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String[] common = {
            "nexuscore.*",
            "nexuscore.command.*",
            "nexuscore.command.player.*",
            "nexuscore.command.moderation.*",
            "nexuscore.command.teleport.*",
            "*",
        };
        for (String candidate : common) {
            if (candidate.startsWith(remaining)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }
}
