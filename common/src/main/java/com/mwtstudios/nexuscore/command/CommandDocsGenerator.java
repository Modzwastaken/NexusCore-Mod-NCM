package com.mwtstudios.nexuscore.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes {@link CommandDocs#render()} to a file. Invoked by the {@code generateCommandDocs} task.
 *
 * <p>A {@code main} rather than a Gradle task action because rendering needs the catalogue on the
 * classpath, and the catalogue is production code. Re-implementing the renderer in Groovy inside the
 * build script would create a second thing to keep in step, which is the problem being solved.</p>
 */
public final class CommandDocsGenerator {

    private CommandDocsGenerator() {
        // Entry point only.
    }

    /**
     * @param args one argument: the path to write
     * @throws IOException if the file cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: CommandDocsGenerator <output-file>");
        }
        Path target = Path.of(args[0]);
        Files.createDirectories(target.getParent());
        Files.writeString(target, CommandDocs.render(), StandardCharsets.UTF_8);
        System.out.println("wrote " + target + " (" + CommandCatalogue.all().size() + " commands)");
    }
}
