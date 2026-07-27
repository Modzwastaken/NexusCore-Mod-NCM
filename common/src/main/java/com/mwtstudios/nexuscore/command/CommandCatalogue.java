package com.mwtstudios.nexuscore.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.mwtstudios.nexuscore.gui.AdminGuiService;

/**
 * Every NexusCore command, described once (§12.5).
 *
 * <p>This is the registry the {@code /nexus help} output and {@code docs/admin/commands.md} are both
 * built from, so they cannot disagree with each other. Two tests keep it from disagreeing with the
 * code: one asserts every permission node the sources actually check has a descriptor here, and the
 * other asserts every descriptor names a node the sources actually check. A command added without a
 * descriptor fails the build.</p>
 *
 * <p>Ordered by group, and within a group by the order an operator is likely to need them, not
 * alphabetically — this is read by people looking for a command, not by a machine doing lookups.</p>
 */
public final class CommandCatalogue {

    private static final List<CommandDescriptor> DESCRIPTORS = List.of(
            // ---- core ----------------------------------------------------------------------
            d("/nexus version", "nexuscore.command.core.version", "show the running NexusCore version", "core"),
            d("/nexus help", "nexuscore.command.core.help", "list the commands you may use", "core"),
            d("/nexus reload", "nexuscore.command.core.reload",
                    "re-read config.json and report what changed, transactionally", "core"),
            d("/nexus confirm <token>", "nexuscore.command.core.confirm",
                    "complete a destructive action you were prompted about", "core"),
            d("/nexus system status", "nexuscore.command.system.status",
                    "show data directory, counts, and audit chain state", "core"),

            // ---- audit ---------------------------------------------------------------------
            d("/nexus audit tail [count]", "nexuscore.command.audit.tail", "show the most recent audit records", "core"),
            d("/nexus audit verify", "nexuscore.command.audit.verify",
                    "re-hash the whole audit chain and report tampering", "core"),

            // ---- permissions ---------------------------------------------------------------
            d("/nexus permission check <player> <node>", "nexuscore.command.permission.check",
                    "explain why a player does or does not have a node", "core"),
            d("/nexus permission set <player> <node> <allow|deny>", "nexuscore.command.permission.set",
                    "grant or deny a node directly on a player", "core"),
            d("/nexus permission group <...>", "nexuscore.command.permission.group",
                    "inspect and edit groups and their inheritance", "core"),

            // ---- teleport ------------------------------------------------------------------
            d("/nexus teleport home [name]", "home", "nexuscore.command.teleport.home", "go to a home", "teleport"),
            d("/nexus teleport sethome <name>", "sethome", "nexuscore.command.teleport.sethome",
                    "save your current position as a home", "teleport"),
            d("/nexus teleport delhome <name>", "delhome", "nexuscore.command.teleport.delhome",
                    "delete one of your homes", "teleport"),
            d("/nexus teleport homes", "homes", "nexuscore.command.teleport.homes", "list your homes", "teleport"),
            d("/nexus teleport warp <name>", "warp", "nexuscore.command.teleport.warp", "go to a warp", "teleport"),
            d("/nexus teleport setwarp <name>", "setwarp", "nexuscore.command.teleport.setwarp",
                    "create a server-wide warp here", "teleport"),
            d("/nexus teleport warps", "warps", "nexuscore.command.teleport.warps", "list the warps", "teleport"),
            d("/nexus teleport spawn", "spawn", "nexuscore.command.teleport.spawn", "go to spawn", "teleport"),
            d("/nexus teleport setspawn", "setspawn", "nexuscore.command.teleport.setspawn",
                    "set the spawn point here", "teleport"),
            d("/nexus teleport back", "back", "nexuscore.command.teleport.back",
                    "return to your last position, including where you died", "teleport"),
            d("/nexus teleport request <player>", "tpa", "nexuscore.command.teleport.request",
                    "ask a player for permission to teleport to them", "teleport"),
            d("/nexus teleport accept", "tpaccept", "nexuscore.command.teleport.accept",
                    "accept a pending teleport request", "teleport"),
            d("/nexus teleport deny", "tpdeny", "nexuscore.command.teleport.deny",
                    "refuse a pending teleport request", "teleport"),
            d("/nexus teleport tp <player> [destination]", "nexuscore.command.teleport.tp",
                    "staff teleport, with no warmup, cooldown or safety search", "teleport"),
            d("/tphere <player>", "nexuscore.command.teleport.tp",
                    "bring a player to you — same node as the staff teleport above", "teleport"),
            d("/nexus teleport delwarp <name>", "delwarp", "nexuscore.command.teleport.setwarp",
                    "delete a warp — shares the setwarp node, so creating implies deleting", "teleport"),

            // ---- player utilities ----------------------------------------------------------
            d("/nexus player heal [player]", "heal", "nexuscore.command.player.heal", "restore health", "player-utilities"),
            d("/nexus player feed [player]", "feed", "nexuscore.command.player.feed", "restore hunger", "player-utilities"),
            d("/nexus player fly [player]", "fly", "nexuscore.command.player.fly", "toggle flight", "player-utilities"),
            d("/nexus player god [player]", "god", "nexuscore.command.player.god",
                    "toggle invulnerability", "player-utilities"),
            d("/nexus player speed <value>", "speed", "nexuscore.command.player.speed",
                    "set your own walk and fly speed", "player-utilities"),
            d("/nexus player vanish", "vanish", "nexuscore.command.player.vanish",
                    "hide yourself from the player list and from sight", "player-utilities"),
            d("/nexus player info <player>", "playerinfo", "nexuscore.command.player.info",
                    "show a player's state, groups and punishments", "player-utilities"),
            d("/nexus player seen <player>", "seen", "nexuscore.command.player.seen",
                    "show when a player was last online", "player-utilities"),
            d("/nexus player list", "list", "nexuscore.command.player.list",
                    "list online players, respecting vanish", "player-utilities"),
            d("/nexus player near [radius]", "near", "nexuscore.command.player.near",
                    "list players close to you", "player-utilities"),

            // ---- staff ---------------------------------------------------------------------
            d("/gamemode <mode> [targets]", "nexuscore.command.staff.gamemode",
                    "change game mode, with vanilla's full selector grammar preserved", "core"),

            // ---- moderation ----------------------------------------------------------------
            d("/nexus moderation kick <player> [reason]", "kick", "nexuscore.command.moderation.kick",
                    "disconnect a player with a styled reason", "moderation"),
            d("/nexus moderation ban <player> [reason]", "ban", "nexuscore.command.moderation.ban",
                    "ban permanently — needs confirmation", "moderation"),
            d("/nexus moderation tempban <player> <duration> [reason]", "tempban",
                    "nexuscore.command.moderation.tempban", "ban until a durable expiry", "moderation"),
            d("/nexus moderation unban <player>", "unban", "nexuscore.command.moderation.unban",
                    "lift a ban, keeping the history", "moderation"),
            d("/nexus moderation mute <player> [duration] [reason]", "mute", "nexuscore.command.moderation.mute",
                    "stop a player chatting", "moderation"),
            d("/nexus moderation unmute <player>", "unmute", "nexuscore.command.moderation.unmute",
                    "lift a mute, keeping the history", "moderation"),
            d("/nexus moderation warn <player> <reason>", "warn", "nexuscore.command.moderation.warn",
                    "record a warning against a player", "moderation"),
            d("/nexus moderation warnings <player>", "warnings", "nexuscore.command.moderation.warnings",
                    "list a player's warnings", "moderation"),
            d("/nexus moderation banlist", "banlist", "nexuscore.command.moderation.banlist",
                    "list active bans", "moderation"),

            // ---- admin GUI -----------------------------------------------------------------
            d("/adminpanel", AdminGuiService.NODE_OPEN, "open the admin panel, a vanilla chest menu", "core"),
            d("(GUI) players page", "nexuscore.gui.admin.players", "browse players and act on one",
                    "player-utilities"),
            d("(GUI) moderation page", "nexuscore.gui.admin.moderation", "review and lift punishments", "moderation"),
            d("(GUI) permissions page", "nexuscore.gui.admin.permissions", "inspect groups and nodes", "core"));

    private CommandCatalogue() {
        // Data holder.
    }

    private static CommandDescriptor d(String canonical, String node, String summary, String module) {
        return CommandDescriptor.of(canonical, node, summary, module);
    }

    private static CommandDescriptor d(String canonical, String alias, String node, String summary, String module) {
        return CommandDescriptor.of(canonical, alias, node, summary, module);
    }

    /** @return every descriptor, in reading order */
    public static List<CommandDescriptor> all() {
        return DESCRIPTORS;
    }

    /** @return every permission node the catalogue describes */
    public static Set<String> nodes() {
        return DESCRIPTORS.stream().map(CommandDescriptor::node).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @return descriptors grouped by the second-to-last node segment — {@code teleport},
     *     {@code moderation}, and so on — preserving reading order within each group
     */
    public static Map<String, List<CommandDescriptor>> byGroup() {
        Map<String, List<CommandDescriptor>> grouped = new LinkedHashMap<>();
        for (CommandDescriptor descriptor : DESCRIPTORS) {
            grouped.computeIfAbsent(group(descriptor), key -> new java.util.ArrayList<>()).add(descriptor);
        }
        return grouped;
    }

    /**
     * @param descriptor a descriptor
     * @return its group name, taken from the node rather than the usage string so that grouping
     *     cannot drift from the permission layout operators actually configure
     */
    public static String group(CommandDescriptor descriptor) {
        String[] parts = descriptor.node().split("\\.");
        return parts.length >= 3 ? parts[parts.length - 2] : "core";
    }
}
