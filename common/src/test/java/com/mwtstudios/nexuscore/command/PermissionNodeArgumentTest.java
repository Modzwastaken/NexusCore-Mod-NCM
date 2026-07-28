package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Typing a permission pattern as a command argument.
 *
 * <p>The defect: the node argument used {@code StringArgumentType.word()}, whose character set
 * excludes {@code *} — so {@code /nexus permission set alice nexuscore.command.* allow}, the one
 * form operators most often need, could not be typed at all.</p>
 */
class PermissionNodeArgumentTest {

    private final PermissionNodeArgument argument = PermissionNodeArgument.permissionNode();

    private String parse(String input) throws CommandSyntaxException {
        return argument.parse(new StringReader(input));
    }

    @Test
    @DisplayName("regression: word() cannot read a wildcard, which is why this type exists")
    void wordArgumentStopsAtTheWildcard() throws CommandSyntaxException {
        // Not a claim about our code — a demonstration of the constraint that caused the defect.
        String viaWord = StringArgumentType.word().parse(new StringReader("nexuscore.command.*"));
        assertEquals("nexuscore.command.", viaWord,
                "word() stops at '*', so the trailing wildcard was silently lost before validation");
    }

    @Test
    @DisplayName("a trailing wildcard is read whole")
    void trailingWildcardParses() throws CommandSyntaxException {
        assertEquals("nexuscore.command.*", parse("nexuscore.command.*"));
        assertEquals("nexuscore.*", parse("nexuscore.*"));
    }

    @Test
    @DisplayName("the universal pattern is readable on its own")
    void universalWildcardParses() throws CommandSyntaxException {
        assertEquals("*", parse("*"));
    }

    @Test
    @DisplayName("a concrete node still parses exactly as before")
    void concreteNodeParses() throws CommandSyntaxException {
        assertEquals("nexuscore.command.player.seen", parse("nexuscore.command.player.seen"));
    }

    @Test
    @DisplayName("patterns are lowercased, as the engine stores them")
    void patternsAreLowercased() throws CommandSyntaxException {
        assertEquals("nexuscore.command.*", parse("NexusCore.Command.*"));
    }

    @Test
    @DisplayName("a mid-pattern wildcard is refused here, exactly as the engine refuses it")
    void midPatternWildcardRefused() {
        CommandSyntaxException thrown = assertThrows(CommandSyntaxException.class,
                () -> parse("nexuscore.*.ban"));
        assertTrue(thrown.getMessage().contains("wildcard"),
                "the refusal should carry the engine's own reason, so the command and the "
                        + "evaluator cannot disagree about what is acceptable");
    }

    @Test
    @DisplayName("parsing stops at the space before the next argument")
    void stopsBeforeTheNextArgument() throws CommandSyntaxException {
        StringReader reader = new StringReader("nexuscore.command.* allow");
        assertEquals("nexuscore.command.*", argument.parse(reader));
        assertEquals(' ', reader.peek(), "the literal that follows must still be readable");
    }

    @Test
    @DisplayName("an empty argument is refused with an example rather than a parser error")
    void emptyIsRefusedHelpfully() {
        CommandSyntaxException thrown = assertThrows(CommandSyntaxException.class, () -> parse(""));
        assertTrue(thrown.getMessage().contains("nexuscore.command.player.*"),
                "the message should show the operator what a pattern looks like");
    }
}
