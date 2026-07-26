package com.mwtstudios.nexuscore.command;

import java.util.List;
import java.util.Map;

/**
 * Renders {@code docs/admin/commands.md} from {@link CommandCatalogue}.
 *
 * <p>The document is generated, not written, because the hand-written one drifted: it claimed
 * NexusCore does not override {@code /ban}, {@code /kick} or {@code /banlist} for two releases after
 * the v1.0 takeover made that false — in the section headed "read this first".</p>
 *
 * <p>Output is deterministic and contains no timestamp, so regenerating it produces either no diff
 * or a real one. A generator that stamped the current date would show a diff on every run and train
 * everyone to ignore it.</p>
 */
public final class CommandDocs {

    private static final String GENERATED_NOTICE =
            "<!-- GENERATED FILE. Do not edit by hand.\n"
            + "     Source: common/src/main/java/com/mwtstudios/nexuscore/command/CommandCatalogue.java\n"
            + "     Regenerate: ./gradlew :generateCommandDocs (from the neoforge project)\n"
            + "     CommandDocsTest fails the build if this file and the catalogue disagree. -->";

    private CommandDocs() {
        // Renderer only.
    }

    /**
     * @return the full contents of {@code docs/admin/commands.md}
     */
    public static String render() {
        StringBuilder out = new StringBuilder();
        out.append(GENERATED_NOTICE).append("\n\n");
        out.append("# NexusCore — Command Reference\n\n");
        out.append("Every command lives under the canonical `/nexus` root. Short aliases are registered as a\n");
        out.append("convenience where nothing else owns the name.\n\n");
        out.append("**Every command checks its permission on the server**, is rate limited, and writes an audit\n");
        out.append("record with a correlation id. Nothing here is enforced by hiding a button.\n\n");
        out.append("---\n\n");

        out.append("## Vanilla commands NexusCore takes over\n\n");
        out.append("`/ban` `/kick` `/banlist` `/pardon` `/list` `/tp` `/teleport` `/gamemode` are **NexusCore's**\n");
        out.append("when `overrideVanillaCommands` is true, which is the default. Typing `/ban` gets you\n");
        out.append("NexusCore's ban — with a duration, a reason history, an audit record and a styled screen.\n\n");
        out.append("`/tp`, `/teleport` and `/gamemode` are rebuilt from vanilla's own argument types, so\n");
        out.append("selectors, absolute and relative coordinates, rotation and `facing` all still work.\n\n");
        out.append("Set `overrideVanillaCommands=false` to leave vanilla in charge; NexusCore's versions then\n");
        out.append("stay on the `n`-prefixed names (`/nban`, `/nkick`, …). NexusCore also falls back to those\n");
        out.append("names automatically if another mod owns the command, and logs which ones at startup.\n\n");
        out.append("**Not taken over:** `/ban-ip` and `/pardon-ip` are separate vanilla commands. An IP ban is\n");
        out.append("therefore neither audited nor listed by NexusCore.\n\n");
        out.append("---\n\n");

        out.append("## Safe mode\n\n");
        out.append("Starting the server with `-Dnexuscore.safemode=true` leaves the optional modules out.\n");
        out.append("Commands belonging to them refuse with an explanation rather than disappearing:\n\n");
        for (String module : List.of("teleport", "player-utilities", "moderation")) {
            out.append("- **").append(module).append("** — ")
                    .append(countIn(module)).append(" command(s) unavailable\n");
        }
        out.append("\n---\n\n");

        for (Map.Entry<String, List<CommandDescriptor>> group : CommandCatalogue.byGroup().entrySet()) {
            out.append("## ").append(heading(group.getKey())).append("\n\n");
            out.append("| Command | Alias | Permission | Module |\n");
            out.append("|---|---|---|---|\n");
            for (CommandDescriptor descriptor : group.getValue()) {
                out.append("| `").append(descriptor.canonical()).append("` — ").append(descriptor.summary())
                        .append(" | ").append(descriptor.hasAlias() ? "`/" + descriptor.alias() + "`" : "—")
                        .append(" | `").append(descriptor.node()).append("`")
                        .append(" | ").append(descriptor.module()).append(" |\n");
            }
            out.append("\n");
        }

        out.append("---\n\n");
        out.append("## Totals\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Commands described | ").append(CommandCatalogue.all().size()).append(" |\n");
        out.append("| Permission nodes | ").append(CommandCatalogue.nodes().size()).append(" |\n");
        return out.toString();
    }

    private static long countIn(String module) {
        return CommandCatalogue.all().stream().filter(d -> d.module().equals(module)).count();
    }

    private static String heading(String group) {
        return switch (group) {
            case "core" -> "Core";
            case "system" -> "System";
            case "audit" -> "Audit";
            case "permission" -> "Permissions";
            case "teleport" -> "Teleport";
            case "player" -> "Player utilities";
            case "staff" -> "Staff";
            case "moderation" -> "Moderation";
            case "admin" -> "Admin GUI";
            default -> group;
        };
    }
}
