package com.mwtstudios.nexuscore.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.message.TimeText;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.module.ModuleException;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The NexusCore admin panel, rendered as vanilla chest menus.
 *
 * <p>Works on an unmodified vanilla client. No client mod, no handshake, no resource pack.
 * See {@link AdminMenu} for why this is a chest and not a custom screen.</p>
 *
 * <p><b>Every action re-checks permission when it is clicked</b>, not only when the panel was
 * drawn. Hiding a button is a courtesy to the operator; it is not a security control (§15,
 * "UI visibility is not security"). A staff member whose access is revoked while the panel is
 * open is refused on the next click, and the refusal is audited.</p>
 *
 * <p>Every panel has a command equivalent (§6.3), so nothing here is the only way to do
 * anything.</p>
 */
public final class AdminGuiService {

    /** Permission to open the panel at all. */
    public static final String NODE_OPEN = "nexuscore.gui.admin.open";
    /** Permission to see the player list. */
    public static final String NODE_PLAYERS = "nexuscore.gui.admin.players";
    /** Permission to see the punishment list. */
    public static final String NODE_MODERATION = "nexuscore.gui.admin.moderation";
    /** Permission to see the permission overview. */
    public static final String NODE_PERMISSIONS = "nexuscore.gui.admin.permissions";

    private static final Style PLAIN = Style.EMPTY.withItalic(false);
    private static final int ROW = AdminMenu.COLUMNS;

    private final NexusServices services;

    /**
     * @param services the shared service registry
     */
    public AdminGuiService(NexusServices services) {
        this.services = services;
    }

    // ---- dashboard ---------------------------------------------------------------------

    /**
     * Opens the main dashboard.
     *
     * @param viewer the staff member
     */
    public void openDashboard(ServerPlayer viewer) {
        if (!services.settings().adminGuiEnabled) {
            viewer.sendSystemMessage(services.messages().render("gui.admin.disabled"));
            return;
        }
        if (!guard(viewer, NODE_OPEN, "gui.admin.open")) {
            return;
        }
        Panel panel = new Panel(3, "NexusCore — Dashboard");
        int online = viewer.server.getPlayerList().getPlayers().size();

        panel.set(10, icon(Items.PLAYER_HEAD, "&aPlayer Manager",
                "&7" + online + " player(s) online",
                "&7Heal, feed, fly, teleport, kick",
                "",
                "&8Command: /nexus player ..."),
                clicker -> openPlayerList(clicker, 0));

        // Degrades rather than throwing: reading moderation counts here made the WHOLE dashboard
        // unopenable in safe mode, so the one screen an operator would use to look around was the
        // first thing to refuse.
        if (services.has("moderation")) {
            panel.set(12, icon(Items.IRON_SWORD, "&cModeration",
                    "&7" + services.moderation().activeBans().size() + " active ban(s)",
                    "&7" + services.moderation().totalRecords() + " record(s) in total",
                    "",
                    "&8Command: /nexus moderation ..."),
                    this::openModeration);
        } else {
            panel.set(12, icon(Items.BARRIER, "&8Moderation",
                    "&8Module not started (safe mode)",
                    "",
                    "&8Restart without -Dnexuscore.safemode to enable"), clicker -> { });
        }

        panel.set(14, icon(Items.BOOK, "&bPermissions",
                "&7" + services.permissions().groupNames().size() + " group(s)",
                "&7Default group: &f" + services.permissions().defaultGroup(),
                "",
                "&8Command: /nexus permission check <player> <node>"),
                this::openPermissions);

        panel.set(16, icon(Items.CLOCK, "&eServer",
                "&7NexusCore &f" + services.modVersion(),
                "&7Audit records: &f" + services.audit().count(),
                "&7Permission cache: &f" + services.permissions().cacheStats(),
                "",
                "&8Command: /nexus system status"),
                this::openServerInfo);

        panel.set(22, icon(Items.BARRIER, "&cClose"), ServerPlayer::closeContainer);
        panel.open(viewer);
    }

    // ---- player list -------------------------------------------------------------------

    /**
     * Opens the paginated list of online players.
     *
     * @param viewer the staff member
     * @param page zero-based page index
     */
    public void openPlayerList(ServerPlayer viewer, int page) {
        if (!guard(viewer, NODE_PLAYERS, "gui.admin.players")) {
            return;
        }
        List<ServerPlayer> online = new ArrayList<>(viewer.server.getPlayerList().getPlayers());
        online.sort((a, b) -> a.getGameProfile().getName().compareToIgnoreCase(b.getGameProfile().getName()));

        int pageSize = Math.min(services.settings().adminGuiPageSize, 45);
        int pages = Math.max(1, (int) Math.ceil(online.size() / (double) pageSize));
        int current = Math.max(0, Math.min(page, pages - 1));

        Panel panel = new Panel(6, "Players — page " + (current + 1) + " of " + pages);

        int from = current * pageSize;
        for (int i = 0; i < pageSize && from + i < online.size(); i++) {
            ServerPlayer target = online.get(from + i);
            if (!services.has("player-utilities")) {
                panel.set(i, icon(Items.BARRIER, "&8" + target.getGameProfile().getName(),
                        "&8Player utilities not started (safe mode)"), clicker -> { });
                continue;
            }
            PlayerUtilityService.Snapshot snapshot = services.players().describe(target);
            panel.set(i, icon(Items.PLAYER_HEAD, "&f" + snapshot.name(),
                    "&7Health: &f" + String.format(Locale.ROOT, "%.1f/%.1f", snapshot.health(), snapshot.maxHealth()),
                    "&7Hunger: &f" + snapshot.foodLevel() + "/20",
                    "&7Mode: &f" + snapshot.gameMode(),
                    "&7World: &f" + snapshot.dimension().replace("minecraft:", ""),
                    "&7At: &f" + snapshot.position(),
                    "&7Ping: &f" + snapshot.latencyMillis() + "ms",
                    "",
                    "&eClick to manage"),
                    clicker -> openPlayerActions(clicker, target.getUUID()));
        }

        if (current > 0) {
            int previous = current - 1;
            panel.set(45, icon(Items.ARROW, "&aPrevious page"), clicker -> openPlayerList(clicker, previous));
        }
        if (current < pages - 1) {
            int next = current + 1;
            panel.set(53, icon(Items.ARROW, "&aNext page"), clicker -> openPlayerList(clicker, next));
        }
        panel.set(49, icon(Items.COMPASS, "&fBack to dashboard"), this::openDashboard);
        panel.open(viewer);
    }

    // ---- per-player actions ------------------------------------------------------------

    /**
     * Opens the action panel for one player.
     *
     * @param viewer the staff member
     * @param targetId the player being managed
     */
    public void openPlayerActions(ServerPlayer viewer, UUID targetId) {
        if (!guard(viewer, NODE_PLAYERS, "gui.admin.players")) {
            return;
        }
        ServerPlayer target = viewer.server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            viewer.sendSystemMessage(services.messages().render("gui.admin.target-left"));
            openPlayerList(viewer, 0);
            return;
        }
        PlayerUtilityService.Snapshot snapshot = services.players().describe(target);
        boolean flying = services.players().canFly(target);
        boolean god = services.players().isGodMode(target);
        // Everything drawn from the target is read HERE, into values. buildActionTiles then has no
        // ServerPlayer in scope at all, which is what makes the stale-capture defect unwritable
        // rather than merely fixed — a body cannot close over a session object that is not there.
        buildActionTiles(viewer, targetId, snapshot, flying, god);
    }

    /** Draws the manage-player panel from values only. No {@link ServerPlayer} is in scope. */
    private void buildActionTiles(ServerPlayer viewer, UUID targetId, PlayerUtilityService.Snapshot snapshot,
            boolean flying, boolean god) {
        Panel panel = new Panel(3, "Manage " + snapshot.name());

        panel.set(0, icon(Items.PLAYER_HEAD, "&f" + snapshot.name(),
                "&7UUID: &8" + snapshot.uuid(),
                "&7Groups: &f" + String.join(", ", services.permissions().groupsOf(targetId))));

        panel.set(10, icon(Items.GOLDEN_APPLE, "&aHeal", "&7Full health, food, and effects cleared"),
                clicker -> act(clicker, targetId, snapshot.name(), "nexuscore.command.player.heal",
                        "player.heal", fresh -> {
                        services.players().heal(fresh);
                        return "healed " + snapshot.name();
                    }));

        panel.set(11, icon(Items.COOKED_BEEF, "&aFeed", "&7Full hunger and saturation"),
                clicker -> act(clicker, targetId, snapshot.name(), "nexuscore.command.player.feed",
                        "player.feed", fresh -> {
                        services.players().feed(fresh);
                        return "fed " + snapshot.name();
                    }));

        panel.set(12, icon(Items.FEATHER, flying ? "&eFlight: &aON" : "&eFlight: &cOFF", "&7Click to toggle"),
                clicker -> act(clicker, targetId, snapshot.name(), "nexuscore.command.player.fly",
                        "player.fly", fresh -> {
                        services.players().setFlight(fresh, !flying);
                        return (flying ? "disabled" : "enabled") + " flight for " + snapshot.name();
                    }));

        panel.set(13, icon(Items.TOTEM_OF_UNDYING, god ? "&eGod mode: &aON" : "&eGod mode: &cOFF", "&7Click to toggle"),
                clicker -> act(clicker, targetId, snapshot.name(), "nexuscore.command.player.god",
                        "player.god", fresh -> {
                        services.players().setGodMode(fresh, !god);
                        return (god ? "disabled" : "enabled") + " god mode for " + snapshot.name();
                    }));

        panel.set(14, icon(Items.ENDER_PEARL, "&bTeleport to", "&7Move yourself to this player"),
                clicker -> act(clicker, targetId, snapshot.name(), "nexuscore.command.teleport.tp",
                        "teleport.tp", fresh -> {
                            TeleportService.Outcome outcome = services.teleport().begin(
                                    clicker, TeleportService.Location.of(fresh), "to " + snapshot.name(), true);
                            if (!outcome.moved()) {
                                throw new ActionRefused(outcome.detail());
                            }
                            return "teleported to " + snapshot.name();
                        }));

        panel.set(16, icon(Items.BARRIER, "&cKick", "&7Remove from the server",
                "&8Requires confirmation"),
                clicker -> openKickConfirmation(clicker, targetId, snapshot.name()));

        panel.set(22, icon(Items.COMPASS, "&fBack to player list"), clicker -> openPlayerList(clicker, 0));
        panel.open(viewer);
    }

    /**
     * Opens the confirmation panel for a kick.
     *
     * <p>§13.4 requires destructive actions to sit behind a modal naming the exact target. The
     * confirmation is bound to that target through {@link com.mwtstudios.nexuscore.command.ConfirmationService},
     * so a token minted here cannot kick anyone else.</p>
     *
     * @param viewer the staff member
     * @param targetId who would be kicked
     * @param targetName their name, for the prompt
     */
    public void openKickConfirmation(ServerPlayer viewer, UUID targetId, String targetName) {
        if (!guard(viewer, "nexuscore.command.moderation.kick", "moderation.kick")) {
            return;
        }
        String token = services.confirmations().issue(
                viewer.getUUID(), "moderation.kick", targetId.toString(), "gui");

        Panel panel = new Panel(3, "Kick " + targetName + "?");
        panel.set(4, icon(Items.PLAYER_HEAD, "&f" + targetName,
                "&7This will disconnect them immediately.",
                "&7They can rejoin straight away."));
        panel.set(11, icon(Items.LIME_DYE, "&aConfirm kick", "&7Target: &f" + targetName),
                clicker -> confirmKick(clicker, targetId, targetName, token));
        panel.set(15, icon(Items.RED_DYE, "&cCancel"), clicker -> openPlayerActions(clicker, targetId));
        panel.open(viewer);
    }

    private void confirmKick(ServerPlayer viewer, UUID targetId, String targetName, String token) {
        if (!guard(viewer, "nexuscore.command.moderation.kick", "moderation.kick")) {
            return;
        }
        var outcome = services.confirmations().consume(
                token, viewer.getUUID(), "moderation.kick", targetId.toString(), "gui");
        if (outcome != com.mwtstudios.nexuscore.command.ConfirmationService.Outcome.CONFIRMED) {
            viewer.sendSystemMessage(services.messages().render("confirm.rejected",
                    "reason", outcome.name().toLowerCase(Locale.ROOT)));
            viewer.closeContainer();
            return;
        }
        ServerPlayer target = viewer.server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            viewer.sendSystemMessage(services.messages().render("gui.admin.target-left"));
            viewer.closeContainer();
            return;
        }
        String reason = ModerationService.sanitiseReason("Kicked from the admin panel", services.settings().maxReasonLength);
        services.moderation().issue(ModerationService.Type.KICK, targetId, targetName,
                viewer.getUUID(), viewer.getGameProfile().getName(), reason, Long.MAX_VALUE);
        services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), "moderation.kick",
                "player", targetId.toString(), "allowed", reason,
                Map.of("via", "gui", "target", targetName), UUID.randomUUID().toString());

        target.connection.disconnect(services.messages().render("moderation.kick.disconnect", "reason", reason));
        viewer.sendSystemMessage(services.messages().render("moderation.kick.success", "target", targetName));
        viewer.closeContainer();
    }

    // ---- moderation and permissions overviews ------------------------------------------

    /**
     * Opens the active-punishment overview.
     *
     * @param viewer the staff member
     */
    public void openModeration(ServerPlayer viewer) {
        if (!guard(viewer, NODE_MODERATION, "gui.admin.moderation")) {
            return;
        }
        List<ModerationService.Record> bans = services.moderation().activeBans();
        Panel panel = new Panel(6, "Active bans (" + bans.size() + ")");

        for (int i = 0; i < bans.size() && i < 45; i++) {
            ModerationService.Record record = bans.get(i);
            // Tooltip layout mirrors the chat/screen style: header, quoted reason, then
            // arrow-prefixed detail lines with an hourglass on the countdown.
            String duration = record.permanent() ? "permanent"
                    : TimeText.longDuration(java.time.Duration.ofMillis(record.expiresAt() - record.issuedAt()));
            panel.set(i, icon(Items.IRON_SWORD, "&cBan &8· &aActive &8— &f" + record.targetName(),
                    "&7&o\"" + record.reason() + "\"",
                    "",
                    "&8→ &7Staff: &c" + record.actorName(),
                    "&8→ &7Date: &f" + TimeText.date(record.issuedAt()),
                    "&8→ &7Duration: &6" + duration,
                    "&8⌛ &7Expires in: &6" + (record.permanent() ? "never"
                            : TimeText.remaining(record.expiresAt(), System.currentTimeMillis())),
                    "",
                    "&8Lift with /unban " + record.targetName()));
        }
        if (bans.isEmpty()) {
            panel.set(22, icon(Items.LIME_DYE, "&aNo active bans"));
        }
        panel.set(49, icon(Items.COMPASS, "&fBack to dashboard"), this::openDashboard);
        panel.open(viewer);
    }

    /**
     * Opens the group overview.
     *
     * @param viewer the staff member
     */
    public void openPermissions(ServerPlayer viewer) {
        if (!guard(viewer, NODE_PERMISSIONS, "gui.admin.permissions")) {
            return;
        }
        List<String> groups = new ArrayList<>(services.permissions().groupNames());
        Panel panel = new Panel(3, "Permission groups (" + groups.size() + ")");

        for (int i = 0; i < groups.size() && i < 27; i++) {
            String group = groups.get(i);
            panel.set(i, icon(Items.BOOK, "&b" + group,
                    "&7Weight: &f" + services.permissions().weightOf(group).orElse(0),
                    group.equals(services.permissions().defaultGroup()) ? "&7&oThe default group" : "",
                    "",
                    "&8Edit with /nexus permission ..."));
        }
        panel.set(22, icon(Items.COMPASS, "&fBack to dashboard"), this::openDashboard);
        panel.open(viewer);
    }

    /**
     * Opens the server diagnostics panel.
     *
     * @param viewer the staff member
     */
    public void openServerInfo(ServerPlayer viewer) {
        if (!guard(viewer, NODE_OPEN, "gui.admin.open")) {
            return;
        }
        var verification = services.audit().verify();
        Panel panel = new Panel(3, "Server — NexusCore " + services.modVersion());

        panel.set(11, icon(Items.CLOCK, "&eRuntime",
                "&7Online: &f" + viewer.server.getPlayerList().getPlayers().size(),
                "&7Worlds: &f" + count(viewer.server.getAllLevels()),
                "&7Data: &f" + services.store().root()));

        panel.set(13, icon(Items.WRITABLE_BOOK, "&eAudit log",
                "&7Records: &f" + services.audit().count(),
                verification.intact() ? "&aChain intact" : "&cChain BROKEN",
                "&7" + verification.detail()));

        panel.set(15, icon(Items.NETHER_STAR, "&ePermissions",
                "&7Groups: &f" + services.permissions().groupNames().size(),
                "&7Cache: &f" + services.permissions().cacheStats(),
                services.settings().operatorBootstrap
                        ? "&cOperator bootstrap is ENABLED" : "&aOperator bootstrap is off"));

        panel.set(22, icon(Items.COMPASS, "&fBack to dashboard"), this::openDashboard);
        panel.open(viewer);
    }

    // ---- helpers -----------------------------------------------------------------------

    private static int count(Iterable<?> iterable) {
        int total = 0;
        for (Object ignored : iterable) {
            total++;
        }
        return total;
    }

    /**
     * Re-checks a permission at click time and reports refusal to both the player and the
     * audit log.
     */
    private boolean guard(ServerPlayer viewer, String node, String action) {
        if (services.allows(viewer, node)) {
            return true;
        }
        services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), action,
                "gui", node, "denied", null, Map.of("via", "gui"), UUID.randomUUID().toString());
        viewer.sendSystemMessage(services.messages().render("error.no-permission", "node", node));
        viewer.closeContainer();
        return false;
    }

    /**
     * Runs a guarded action against the target <em>as they are when the click arrives</em>.
     *
     * <p>The panel is built once and then sits open. A {@link ServerPlayer} captured while
     * building it is a snapshot of a session: if the target logs out before the staff member
     * clicks, that object is detached from the server, so the action quietly does nothing while
     * the audit log records it as {@code allowed}. An audit trail that says a heal happened when
     * it did not is worse than one that says nothing, because the whole point of this mod is that
     * the trail can be trusted.</p>
     *
     * <p>The target is therefore re-resolved from the player list here, by UUID, and a target who
     * has gone is a refusal that is audited as one — the same shape
     * {@link #openKickConfirmation} already used.</p>
     */
    private void act(ServerPlayer viewer, UUID targetId, String targetName, String node, String action,
            ActionBody body) {
        if (!guard(viewer, node, action)) {
            return;
        }
        String correlationId = UUID.randomUUID().toString();
        ServerPlayer target = viewer.server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), action,
                    "player", targetId.toString(), "failed", "target left before the click was handled",
                    Map.of("via", "gui", "target", targetName), correlationId);
            viewer.sendSystemMessage(services.messages().render("gui.admin.target-left"));
            openPlayerList(viewer, 0);
            return;
        }
        try {
            String summary = body.run(target);
            services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), action,
                    "player", targetId.toString(), "allowed", null,
                    Map.of("via", "gui", "target", target.getGameProfile().getName()), correlationId);
            viewer.sendSystemMessage(services.messages().render("gui.admin.action-done", "summary", summary));
            openPlayerActions(viewer, targetId);
        } catch (ActionRefused refused) {
            services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), action,
                    "player", targetId.toString(), "failed", refused.getMessage(),
                    Map.of("via", "gui"), correlationId);
            viewer.sendSystemMessage(services.messages().render("gui.admin.action-failed", "reason", refused.getMessage()));
        } catch (ModuleException disabled) {
            // A tile whose module safe mode left out. Audited as a failure like any other refused
            // action, so the attempt is still on the record.
            services.audit().record(viewer.getUUID(), viewer.getGameProfile().getName(), action,
                    "player", targetId.toString(), "failed", disabled.getMessage(),
                    Map.of("via", "gui"), correlationId);
            viewer.sendSystemMessage(services.messages().render("error.module.disabled"));
        }
    }

    /**
     * A GUI action body that may refuse with a reason.
     *
     * <p>Receives the target resolved at click time. Taking it as a parameter rather than letting
     * a body close over one is what makes the stale-capture defect unwritable rather than merely
     * fixed: there is no captured {@code ServerPlayer} in scope to use by mistake.</p>
     */
    @FunctionalInterface
    private interface ActionBody {
        String run(ServerPlayer target) throws ActionRefused;
    }

    /** Signals that an action could not be carried out, with an operator-readable reason. */
    private static final class ActionRefused extends Exception {
        private static final long serialVersionUID = 1L;

        private ActionRefused(String message) {
            super(message);
        }
    }

    private static ItemStack icon(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, colour(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                lines.add(colour(line));
            } else {
                lines.add(Component.literal("").setStyle(PLAIN));
            }
        }
        if (!lines.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return stack;
    }

    /**
     * Renders operator-friendly {@code &a} colour codes, and turns off the italic styling
     * Minecraft applies to renamed items by default.
     */
    private static Component colour(String text) {
        return Component.literal(text.replace('&', ChatFormatting.PREFIX_CODE)).setStyle(PLAIN);
    }

    /** A panel under construction: display items plus the handler for each slot. */
    private static final class Panel {
        private final SimpleContainer container;
        private final Map<Integer, Consumer<ServerPlayer>> actions = new LinkedHashMap<>();
        private final int rows;
        private final String title;

        private Panel(int rows, String title) {
            this.rows = rows;
            this.title = title;
            this.container = new SimpleContainer(rows * ROW);
        }

        private void set(int slot, ItemStack stack) {
            container.setItem(slot, stack);
        }

        private void set(int slot, ItemStack stack, Consumer<ServerPlayer> action) {
            container.setItem(slot, stack);
            actions.put(slot, action);
        }

        private void open(ServerPlayer viewer) {
            viewer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, player) -> new AdminMenu(containerId, inventory, container, rows, actions),
                    Component.literal(title)));
        }
    }
}
