package com.mwtstudios.nexuscore.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.gui.AdminGuiService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.permission.PermissionDecision;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

/**
 * Registers the NexusCore command tree and enforces the §12.2 execution pipeline.
 *
 * <p>Every command routes through {@link #run}, which applies all nine steps in order:
 * parse, <b>permission</b>, validate, rate limit, confirm, execute through a service,
 * translated feedback, <b>audit with a correlation id</b>, and a consistent Brigadier
 * result. There is no second registration path, which is what makes "every command routes
 * through the pipeline" verifiable rather than aspirational.</p>
 *
 * <p>A command body never touches storage directly and never formats its own error text. It
 * either returns feedback or throws {@link Refused} with a catalogue key. Anything else that
 * escapes is caught, logged with the correlation id, and reported to the player as a generic
 * failure — <b>a stack trace never reaches a player</b> (§12.2 step 9).</p>
 */
public final class NexusCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Root literal. Collision-safe by §4.3. */
    public static final String ROOT = "nexus";

    /**
     * Prefix used when a short alias collides with a command something else already owns.
     * {@code /ban} stays vanilla's; {@code /nban} is NexusCore's.
     */
    public static final String COLLISION_PREFIX = "n";

    private NexusCommands() {
        // Utility holder.
    }

    /**
     * Registers everything.
     *
     * @param dispatcher the server dispatcher
     * @param services the shared service registry
     * @param gui the admin panel
     * @param displayName the mod display name, for {@code /nexus version}
     * @param version the mod version
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, NexusServices services,
            AdminGuiService gui, String displayName, String version) {

        dispatcher.register(Commands.literal(ROOT)
                .executes(context -> run(context, services, "nexuscore.command.core.help", "core.help",
                        source -> help(source, services)))
                .then(Commands.literal("version")
                        .executes(context -> run(context, services, "nexuscore.command.core.version", "core.version",
                                source -> Feedback.of(Component.literal(displayName + " version " + version)))))
                .then(Commands.literal("help")
                        .executes(context -> run(context, services, "nexuscore.command.core.help", "core.help",
                                source -> help(source, services))))
                .then(Commands.literal("reload")
                        .executes(context -> run(context, services, "nexuscore.command.core.reload", "core.reload",
                                source -> reload(services))))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(context -> run(context, services, "nexuscore.command.core.confirm", "core.confirm",
                                        source -> confirm(source, services, StringArgumentType.getString(context, "token"))))))
                .then(permissionTree(services))
                .then(auditTree(services))
                .then(systemTree(services))
                .then(Commands.literal("player")
                        .then(healNode(services)).then(feedNode(services)).then(flyNode(services))
                        .then(godNode(services)).then(speedNode(services)).then(vanishNode(services))
                        .then(infoNode(services)))
                .then(Commands.literal("teleport")
                        .then(homeNode(services)).then(setHomeNode(services)).then(delHomeNode(services))
                        .then(homesNode(services)).then(warpNode(services)).then(setWarpNode(services))
                        .then(delWarpNode(services)).then(warpsNode(services)).then(spawnNode(services))
                        .then(setSpawnNode(services)).then(backNode(services)).then(tpaNode(services))
                        .then(tpAcceptNode(services)).then(tpDenyNode(services)))
                .then(Commands.literal("moderation")
                        .then(kickNode(services)).then(banNode(services)).then(tempBanNode(services))
                        .then(unbanNode(services)).then(muteNode(services)).then(unmuteNode(services))
                        .then(warnNode(services)).then(warningsNode(services)).then(banListNode(services)))
                .then(Commands.literal("gui")
                        .executes(context -> run(context, services, AdminGuiService.NODE_OPEN, "gui.admin.open",
                                source -> openGui(source, gui)))));

        if (services.settings().registerAliases) {
            registerAliases(dispatcher, services, gui);
        }
    }

    /**
     * Registers short top-level aliases.
     *
     * <p>Only names no vanilla command owns. {@code /tp} and {@code /gamemode} are deliberately
     * absent: §12.3 forbids overriding vanilla by default, and those two stay under
     * {@code /nexus teleport tp} and vanilla respectively.</p>
     *
     * <p>Each registration is attempted independently and a failure is logged, never thrown —
     * §12.1 requires that alias registration failure must not prevent NexusCore from loading.</p>
     */
    private static void registerAliases(CommandDispatcher<CommandSourceStack> dispatcher, NexusServices services,
            AdminGuiService gui) {
        Map<String, LiteralArgumentBuilder<CommandSourceStack>> aliases = new LinkedHashMap<>();
        aliases.put("heal", healNode(services));
        aliases.put("feed", feedNode(services));
        aliases.put("fly", flyNode(services));
        aliases.put("god", godNode(services));
        aliases.put("speed", speedNode(services));
        aliases.put("vanish", vanishNode(services));
        aliases.put("playerinfo", infoNode(services));
        aliases.put("home", homeNode(services));
        aliases.put("sethome", setHomeNode(services));
        aliases.put("delhome", delHomeNode(services));
        aliases.put("homes", homesNode(services));
        aliases.put("warp", warpNode(services));
        aliases.put("setwarp", setWarpNode(services));
        aliases.put("delwarp", delWarpNode(services));
        aliases.put("warps", warpsNode(services));
        aliases.put("spawn", spawnNode(services));
        aliases.put("setspawn", setSpawnNode(services));
        aliases.put("back", backNode(services));
        aliases.put("tpa", tpaNode(services));
        aliases.put("tpaccept", tpAcceptNode(services));
        aliases.put("tpdeny", tpDenyNode(services));
        aliases.put("kick", kickNode(services));
        aliases.put("ban", banNode(services));
        aliases.put("tempban", tempBanNode(services));
        aliases.put("unban", unbanNode(services));
        aliases.put("mute", muteNode(services));
        aliases.put("unmute", unmuteNode(services));
        aliases.put("warn", warnNode(services));
        aliases.put("warnings", warningsNode(services));
        aliases.put("banlist", banListNode(services));

        for (Map.Entry<String, LiteralArgumentBuilder<CommandSourceStack>> entry : aliases.entrySet()) {
            String name = entry.getKey();
            try {
                if (dispatcher.getRoot().getChild(name) == null) {
                    dispatcher.register(rename(name, entry.getValue()));
                    continue;
                }
                // Vanilla (or another mod) owns this name. §12.3 forbids overriding it, but
                // leaving the operator with nothing is worse than useless: they type /ban,
                // silently get vanilla's ban, and none of NexusCore's duration, audit, or
                // history applies. So register the collision-free form §12.3 asks for.
                String fallback = COLLISION_PREFIX + name;
                if (dispatcher.getRoot().getChild(fallback) == null) {
                    dispatcher.register(rename(fallback, entry.getValue()));
                    LOGGER.info("NexusCore did not override /{} — another command owns it. "
                            + "Use /{} or /nexus ... for the NexusCore version.", name, fallback);
                } else {
                    LOGGER.warn("NexusCore could not register /{} or /{}; use the canonical /nexus command", name, fallback);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("NexusCore could not register the alias /{}; the canonical /nexus command still works", name, e);
            }
        }

        try {
            if (dispatcher.getRoot().getChild("adminpanel") == null) {
                dispatcher.register(Commands.literal("adminpanel")
                        .executes(context -> run(context, services, AdminGuiService.NODE_OPEN, "gui.admin.open",
                                source -> openGui(source, gui))));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("NexusCore could not register /adminpanel; /nexus gui still works", e);
        }
    }

    /** Rebuilds a node under a different literal name, since a builder cannot be reused. */
    private static LiteralArgumentBuilder<CommandSourceStack> rename(String name,
            LiteralArgumentBuilder<CommandSourceStack> source) {
        LiteralArgumentBuilder<CommandSourceStack> renamed = Commands.literal(name);
        if (source.getCommand() != null) {
            renamed.executes(source.getCommand());
        }
        source.getArguments().forEach(renamed::then);
        return renamed;
    }

    // ---- the pipeline ------------------------------------------------------------------

    /**
     * The §12.2 execution pipeline. Every command in NexusCore passes through here.
     *
     * @param context the Brigadier context
     * @param services the service registry
     * @param node the canonical permission node
     * @param action the audit action name
     * @param body the work to do
     * @return a Brigadier result
     */
    private static int run(CommandContext<CommandSourceStack> context, NexusServices services,
            String node, String action, Body body) {
        CommandSourceStack source = context.getSource();
        String correlationId = UUID.randomUUID().toString();

        // Step 2 — authorise, server-side, through the one evaluator.
        PermissionDecision decision = services.authorise(source, node);
        if (!decision.allowed()) {
            services.audit(source, action, "command", node, "denied", decision.explanation(), Map.of(), correlationId);
            source.sendFailure(services.messages().render("error.no-permission", "node", node));
            return 0;
        }

        // Step 4 — rate limit.
        if (!services.rateLimiter().tryAcquire(NexusServices.subjectOf(source))) {
            services.audit(source, action, "command", node, "rate-limited", null, Map.of(), correlationId);
            source.sendFailure(services.messages().render("error.rate-limited"));
            return 0;
        }

        try {
            // Steps 3, 5, 6 — validation, confirmation, and execution happen inside the body,
            // which reaches the work only through a service.
            Feedback feedback = body.run(source);

            // Step 7 — translated feedback.
            source.sendSuccess(feedback::message, feedback.broadcastToOperators());

            // Step 8 — audit with a correlation id.
            services.audit(source, action, feedback.targetType(), feedback.targetId(), "allowed", null,
                    feedback.auditParameters(), correlationId);
            return Command.SINGLE_SUCCESS;

        } catch (Refused refused) {
            services.audit(source, action, "command", node, "refused", refused.getMessage(), Map.of(), correlationId);
            source.sendFailure(Component.literal(refused.getMessage()));
            return 0;
        } catch (CommandSyntaxException e) {
            // Brigadier's own argument failures: the message is already player-safe.
            services.audit(source, action, "command", node, "invalid", e.getRawMessage().getString(), Map.of(), correlationId);
            source.sendFailure(Component.literal(e.getRawMessage().getString()));
            return 0;
        } catch (RuntimeException e) {
            // Step 9 — never leak a stack trace to a player. The operator gets a correlation
            // id they can grep for; the full detail goes to the server log.
            LOGGER.error("NexusCore command '{}' failed unexpectedly [correlation_id={}]", action, correlationId, e);
            services.audit(source, action, "command", node, "failed", e.getClass().getSimpleName(), Map.of(), correlationId);
            source.sendFailure(services.messages().render("error.internal", "id", correlationId.substring(0, 8)));
            return 0;
        }
    }

    // ---- core --------------------------------------------------------------------------

    private static Feedback help(CommandSourceStack source, NexusServices services) {
        List<String> lines = new ArrayList<>();
        lines.add("&b--- NexusCore " + services.modVersion() + " ---");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.home", "&f/home [name] &7- go to a home");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.sethome", "&f/sethome <name> &7- save a home");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.warp", "&f/warp <name> &7- go to a warp");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.spawn", "&f/spawn &7- go to spawn");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.back", "&f/back &7- return to your last location");
        addIfPermitted(lines, source, services, "nexuscore.command.teleport.request", "&f/tpa <player> &7- ask to teleport");
        addIfPermitted(lines, source, services, "nexuscore.command.player.heal", "&f/heal [player] &7- restore health");
        addIfPermitted(lines, source, services, "nexuscore.command.player.fly", "&f/fly [player] &7- toggle flight");
        addIfPermitted(lines, source, services, "nexuscore.command.moderation.kick",
                "&f/nexus moderation kick <player> [reason] &8(/kick is vanilla's)");
        addIfPermitted(lines, source, services, "nexuscore.command.moderation.ban",
                "&f/nexus moderation ban <player> [reason] &7- needs confirmation &8(/ban is vanilla's)");
        addIfPermitted(lines, source, services, "nexuscore.command.moderation.mute", "&f/mute <player> [duration] [reason]");
        addIfPermitted(lines, source, services, AdminGuiService.NODE_OPEN, "&f/adminpanel &7- open the admin panel");
        addIfPermitted(lines, source, services, "nexuscore.command.permission.check",
                "&f/nexus permission check <player> <node>");
        lines.add("&8Only commands you may use are listed.");

        return Feedback.of(Component.literal(MessageService.colourise(String.join("\n", lines))));
    }

    private static void addIfPermitted(List<String> lines, CommandSourceStack source, NexusServices services,
            String node, String line) {
        if (services.authorise(source, node).allowed()) {
            lines.add(line);
        }
    }

    private static Feedback reload(NexusServices services) throws Refused {
        var result = services.configuration().reload();
        if (!result.succeeded()) {
            throw new Refused("reload refused: " + result.failure() + " — the previous settings are still in effect");
        }
        services.applySettings();
        String summary = result.report().isClean()
                ? "configuration reloaded, no problems found"
                : "configuration reloaded with " + result.report().findings().size() + " corrected value(s):\n"
                        + result.report().render();
        return Feedback.of(Component.literal(summary));
    }

    private static Feedback confirm(CommandSourceStack source, NexusServices services, String token) throws Refused {
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        ConfirmationService.Taken taken = services.confirmations().take(token, actor);

        if (taken.outcome() != ConfirmationService.Outcome.CONFIRMED) {
            throw new Refused(services.messages().raw("confirm.rejected",
                    "reason", taken.outcome().name().toLowerCase(Locale.ROOT)));
        }
        taken.body().run();
        return Feedback.of(services.messages().render("confirm.done", "action", taken.description()));
    }

    private static Feedback openGui(CommandSourceStack source, AdminGuiService gui) throws Refused {
        ServerPlayer player = requirePlayer(source);
        gui.openDashboard(player);
        return Feedback.of(Component.literal("Opening the NexusCore admin panel."));
    }

    // ---- permission, audit, system trees -----------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> permissionTree(NexusServices services) {
        return Commands.literal("permission")
                .then(Commands.literal("check")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("node", StringArgumentType.word())
                                        .executes(context -> run(context, services, "nexuscore.command.permission.check",
                                                "permission.check",
                                                source -> permissionCheck(source, services,
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "node")))))))
                .then(Commands.literal("group")
                        .then(Commands.literal("list")
                                .executes(context -> run(context, services, "nexuscore.command.permission.group",
                                        "permission.group.list",
                                        source -> Feedback.of(Component.literal("Groups: "
                                                + String.join(", ", services.permissions().groupNames())
                                                + " (default: " + services.permissions().defaultGroup() + ")")))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("group", StringArgumentType.word())
                                                .executes(context -> run(context, services,
                                                        "nexuscore.command.permission.group", "permission.group.add",
                                                        source -> groupChange(source, services,
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "group"), true))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("group", StringArgumentType.word())
                                                .executes(context -> run(context, services,
                                                        "nexuscore.command.permission.group", "permission.group.remove",
                                                        source -> groupChange(source, services,
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "group"), false)))))));
    }

    private static Feedback permissionCheck(CommandSourceStack source, NexusServices services, String name, String node)
            throws Refused {
        UUID target = resolve(source, services, name);
        PermissionDecision decision = services.permissions().evaluate(target, node);
        String text = "&b" + name + " &7-> &f" + node + "\n"
                + "&7Result: &f" + decision.result() + "\n"
                + "&7" + decision.explanation() + "\n"
                + "&7Groups: &f" + String.join(", ", services.permissions().groupsOf(target));
        return Feedback.of(Component.literal(MessageService.colourise(text)))
                .withTarget("player", target.toString());
    }

    private static Feedback groupChange(CommandSourceStack source, NexusServices services, String name,
            String group, boolean add) throws Refused {
        UUID target = resolve(source, services, name);
        boolean changed = add
                ? services.permissions().addToGroup(target, group)
                : services.permissions().removeFromGroup(target, group);
        if (!changed) {
            throw new Refused(add
                    ? "could not add " + name + " to '" + group + "' — the group does not exist, or they are already in it"
                    : name + " is not a member of '" + group + "'");
        }
        return Feedback.of(Component.literal((add ? "Added " : "Removed ") + name
                        + (add ? " to group " : " from group ") + group))
                .withTarget("player", target.toString())
                .withAudit("group", group);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> auditTree(NexusServices services) {
        return Commands.literal("audit")
                .then(Commands.literal("verify")
                        .executes(context -> run(context, services, "nexuscore.command.audit.verify", "audit.verify",
                                source -> {
                                    var verification = services.audit().verify();
                                    return Feedback.of(Component.literal(
                                            (verification.intact() ? "Audit chain intact: " : "AUDIT CHAIN BROKEN: ")
                                                    + verification.detail()));
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> systemTree(NexusServices services) {
        return Commands.literal("system")
                .then(Commands.literal("status")
                        .executes(context -> run(context, services, "nexuscore.command.system.status", "system.status",
                                source -> {
                                    String text = "&b--- NexusCore " + services.modVersion() + " ---\n"
                                            + "&7Data directory: &f" + services.store().root() + "\n"
                                            + "&7Groups: &f" + services.permissions().groupNames().size()
                                            + "  &7Permission cache: &f" + services.permissions().cacheStats() + "\n"
                                            + "&7Audit records: &f" + services.audit().count()
                                            + "  &7Punishments: &f" + services.moderation().totalRecords() + "\n"
                                            + "&7Warps: &f" + services.teleport().warpNames().size()
                                            + "  &7Messages: &f" + services.messages().size() + "\n"
                                            + "&7Pending confirmations: &f" + services.confirmations().pendingCount()
                                            + "  &7Rate-limited subjects: &f" + services.rateLimiter().tracked() + "\n"
                                            + (services.settings().operatorBootstrap
                                                    ? "&cOperator bootstrap is ENABLED — disable it once groups exist"
                                                    : "&aOperator bootstrap is off");
                                    return Feedback.of(Component.literal(MessageService.colourise(text)));
                                })));
    }

    // ---- player utilities --------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> healNode(NexusServices services) {
        return Commands.literal("heal")
                .executes(context -> run(context, services, "nexuscore.command.player.heal", "player.heal",
                        source -> healOne(services, requirePlayer(source))))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.player.heal", "player.heal",
                                source -> healOne(services, EntityArgument.getPlayer(context, "target")))));
    }

    private static Feedback healOne(NexusServices services, ServerPlayer target) {
        services.players().heal(target);
        return Feedback.of(services.messages().render("player.heal.success", "target", target.getGameProfile().getName()))
                .withTarget("player", target.getUUID().toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> feedNode(NexusServices services) {
        return Commands.literal("feed")
                .executes(context -> run(context, services, "nexuscore.command.player.feed", "player.feed",
                        source -> feedOne(services, requirePlayer(source))))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.player.feed", "player.feed",
                                source -> feedOne(services, EntityArgument.getPlayer(context, "target")))));
    }

    private static Feedback feedOne(NexusServices services, ServerPlayer target) {
        services.players().feed(target);
        return Feedback.of(services.messages().render("player.feed.success", "target", target.getGameProfile().getName()))
                .withTarget("player", target.getUUID().toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> flyNode(NexusServices services) {
        return Commands.literal("fly")
                .executes(context -> run(context, services, "nexuscore.command.player.fly", "player.fly",
                        source -> toggleFly(services, requirePlayer(source))))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.player.fly", "player.fly",
                                source -> toggleFly(services, EntityArgument.getPlayer(context, "target")))));
    }

    private static Feedback toggleFly(NexusServices services, ServerPlayer target) {
        boolean enabled = !services.players().hasNexusFlight(target);
        services.players().setFlight(target, enabled);
        return Feedback.of(services.messages().render(enabled ? "player.fly.enabled" : "player.fly.disabled",
                        "target", target.getGameProfile().getName()))
                .withTarget("player", target.getUUID().toString())
                .withAudit("enabled", String.valueOf(enabled));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> godNode(NexusServices services) {
        return Commands.literal("god")
                .executes(context -> run(context, services, "nexuscore.command.player.god", "player.god",
                        source -> toggleGod(services, requirePlayer(source))))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.player.god", "player.god",
                                source -> toggleGod(services, EntityArgument.getPlayer(context, "target")))));
    }

    private static Feedback toggleGod(NexusServices services, ServerPlayer target) {
        boolean enabled = !services.players().isGodMode(target);
        services.players().setGodMode(target, enabled);
        return Feedback.of(services.messages().render(enabled ? "player.god.enabled" : "player.god.disabled",
                        "target", target.getGameProfile().getName()))
                .withTarget("player", target.getUUID().toString())
                .withAudit("enabled", String.valueOf(enabled));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> speedNode(NexusServices services) {
        return Commands.literal("speed")
                .then(Commands.literal("reset")
                        .executes(context -> run(context, services, "nexuscore.command.player.speed", "player.speed",
                                source -> {
                                    ServerPlayer player = requirePlayer(source);
                                    services.players().resetSpeed(player);
                                    return Feedback.of(services.messages().render("player.speed.reset"))
                                            .withTarget("player", player.getUUID().toString());
                                })))
                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(context -> run(context, services, "nexuscore.command.player.speed", "player.speed",
                                source -> setSpeed(services, requirePlayer(source),
                                        FloatArgumentType.getFloat(context, "multiplier"), false)))
                        .then(Commands.literal("fly")
                                .executes(context -> run(context, services, "nexuscore.command.player.speed", "player.speed",
                                        source -> setSpeed(services, requirePlayer(source),
                                                FloatArgumentType.getFloat(context, "multiplier"), true)))));
    }

    private static Feedback setSpeed(NexusServices services, ServerPlayer player, float multiplier, boolean flying)
            throws Refused {
        try {
            services.players().setSpeed(player, multiplier, flying);
        } catch (IllegalArgumentException e) {
            throw new Refused(e.getMessage());
        }
        return Feedback.of(services.messages().render("player.speed.set",
                        "kind", flying ? "fly" : "walk", "value", String.valueOf(multiplier)))
                .withTarget("player", player.getUUID().toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> vanishNode(NexusServices services) {
        return Commands.literal("vanish")
                .executes(context -> run(context, services, "nexuscore.command.player.vanish", "player.vanish",
                        source -> {
                            ServerPlayer player = requirePlayer(source);
                            boolean hidden = !services.players().isVanished(player.getUUID());
                            services.players().setVanished(source.getServer(), player, hidden);
                            return Feedback.of(services.messages().render(
                                            hidden ? "player.vanish.enabled" : "player.vanish.disabled"))
                                    .withTarget("player", player.getUUID().toString());
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> infoNode(NexusServices services) {
        return Commands.literal("info")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.player.info", "player.info",
                                source -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "target");
                                    var snapshot = services.players().describe(target);
                                    String text = "&b--- " + snapshot.name() + " ---\n"
                                            + "&7UUID: &f" + snapshot.uuid() + "\n"
                                            + "&7Mode: &f" + snapshot.gameMode()
                                            + "  &7Health: &f" + String.format(Locale.ROOT, "%.1f/%.1f",
                                                    snapshot.health(), snapshot.maxHealth())
                                            + "  &7Food: &f" + snapshot.foodLevel() + "\n"
                                            + "&7World: &f" + snapshot.dimension() + "  &7At: &f" + snapshot.position() + "\n"
                                            + "&7Ping: &f" + snapshot.latencyMillis() + "ms"
                                            + "  &7Fly: &f" + snapshot.canFly()
                                            + "  &7God: &f" + snapshot.godMode()
                                            + "  &7Vanished: &f" + snapshot.vanished() + "\n"
                                            + "&7Groups: &f" + String.join(", ",
                                                    services.permissions().groupsOf(target.getUUID()));
                                    return Feedback.of(Component.literal(MessageService.colourise(text)))
                                            .withTarget("player", target.getUUID().toString());
                                })));
    }

    // ---- teleport ----------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> homeNode(NexusServices services) {
        return Commands.literal("home")
                .executes(context -> run(context, services, "nexuscore.command.teleport.home", "teleport.home",
                        source -> goHome(services, requirePlayer(source), "home")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.home", "teleport.home",
                                source -> goHome(services, requirePlayer(source),
                                        StringArgumentType.getString(context, "name")))));
    }

    private static Feedback goHome(NexusServices services, ServerPlayer player, String name) throws Refused {
        TeleportService.Location location = services.teleport().home(player.getUUID(), name)
                .orElseThrow(() -> new Refused(services.messages().raw("teleport.home.unknown", "name", name)));
        return teleport(services, player, location, "home '" + name + "'");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setHomeNode(NexusServices services) {
        return Commands.literal("sethome")
                .executes(context -> run(context, services, "nexuscore.command.teleport.sethome", "teleport.sethome",
                        source -> setHome(services, requirePlayer(source), "home")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.sethome", "teleport.sethome",
                                source -> setHome(services, requirePlayer(source),
                                        StringArgumentType.getString(context, "name")))));
    }

    private static Feedback setHome(NexusServices services, ServerPlayer player, String name) throws Refused {
        requireSimpleName(name);
        int limit = services.settings().maxHomesPerPlayer;
        if (!services.teleport().setHome(player.getUUID(), name, TeleportService.Location.of(player), limit)) {
            throw new Refused(services.messages().raw("teleport.sethome.limit", "limit", String.valueOf(limit)));
        }
        return Feedback.of(services.messages().render("teleport.sethome.success", "name", name))
                .withTarget("home", name);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> delHomeNode(NexusServices services) {
        return Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.delhome", "teleport.delhome",
                                source -> {
                                    ServerPlayer player = requirePlayer(source);
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!services.teleport().deleteHome(player.getUUID(), name)) {
                                        throw new Refused(services.messages().raw("teleport.home.unknown", "name", name));
                                    }
                                    return Feedback.of(services.messages().render("teleport.delhome.success", "name", name))
                                            .withTarget("home", name);
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> homesNode(NexusServices services) {
        return Commands.literal("homes")
                .executes(context -> run(context, services, "nexuscore.command.teleport.homes", "teleport.homes",
                        source -> {
                            ServerPlayer player = requirePlayer(source);
                            var names = services.teleport().homeNames(player.getUUID());
                            return Feedback.of(names.isEmpty()
                                    ? services.messages().render("teleport.homes.none")
                                    : services.messages().render("teleport.homes.list",
                                            "count", String.valueOf(names.size()), "names", String.join(", ", names)));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warpNode(NexusServices services) {
        return Commands.literal("warp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.warp", "teleport.warp",
                                source -> {
                                    ServerPlayer player = requirePlayer(source);
                                    String name = StringArgumentType.getString(context, "name");
                                    TeleportService.Location location = services.teleport().warp(name)
                                            .orElseThrow(() -> new Refused(
                                                    services.messages().raw("teleport.warp.unknown", "name", name)));
                                    return teleport(services, player, location, "warp '" + name + "'");
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setWarpNode(NexusServices services) {
        return Commands.literal("setwarp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.setwarp", "teleport.setwarp",
                                source -> {
                                    ServerPlayer player = requirePlayer(source);
                                    String name = StringArgumentType.getString(context, "name");
                                    requireSimpleName(name);
                                    services.teleport().setWarp(name, TeleportService.Location.of(player));
                                    return Feedback.of(services.messages().render("teleport.setwarp.success", "name", name))
                                            .withTarget("warp", name);
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> delWarpNode(NexusServices services) {
        return Commands.literal("delwarp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.setwarp", "teleport.delwarp",
                                source -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!services.teleport().deleteWarp(name)) {
                                        throw new Refused(services.messages().raw("teleport.warp.unknown", "name", name));
                                    }
                                    return Feedback.of(services.messages().render("teleport.delwarp.success", "name", name))
                                            .withTarget("warp", name);
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warpsNode(NexusServices services) {
        return Commands.literal("warps")
                .executes(context -> run(context, services, "nexuscore.command.teleport.warps", "teleport.warps",
                        source -> {
                            var names = services.teleport().warpNames();
                            return Feedback.of(names.isEmpty()
                                    ? services.messages().render("teleport.warps.none")
                                    : services.messages().render("teleport.warps.list",
                                            "count", String.valueOf(names.size()), "names", String.join(", ", names)));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode(NexusServices services) {
        return Commands.literal("spawn")
                .executes(context -> run(context, services, "nexuscore.command.teleport.spawn", "teleport.spawn",
                        source -> {
                            ServerPlayer player = requirePlayer(source);
                            TeleportService.Location location = services.teleport().spawn()
                                    .orElseGet(() -> TeleportService.Location.worldSpawn(source.getServer().overworld()));
                            return teleport(services, player, location, "spawn");
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setSpawnNode(NexusServices services) {
        return Commands.literal("setspawn")
                .executes(context -> run(context, services, "nexuscore.command.teleport.setspawn", "teleport.setspawn",
                        source -> {
                            ServerPlayer player = requirePlayer(source);
                            services.teleport().setSpawn(TeleportService.Location.of(player));
                            return Feedback.of(services.messages().render("teleport.setspawn.success"))
                                    .withTarget("spawn", "server");
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> backNode(NexusServices services) {
        return Commands.literal("back")
                .executes(context -> run(context, services, "nexuscore.command.teleport.back", "teleport.back",
                        source -> {
                            ServerPlayer player = requirePlayer(source);
                            TeleportService.Location location = services.teleport().lastLocation(player.getUUID())
                                    .orElseThrow(() -> new Refused(services.messages().raw("teleport.back.none")));
                            return teleport(services, player, location, "your previous location");
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tpaNode(NexusServices services) {
        return Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.request", "teleport.request",
                                source -> {
                                    ServerPlayer requester = requirePlayer(source);
                                    ServerPlayer target = EntityArgument.getPlayer(context, "target");
                                    if (target.getUUID().equals(requester.getUUID())) {
                                        throw new Refused(services.messages().raw("teleport.request.self"));
                                    }
                                    services.teleport().request(requester.getUUID(), target.getUUID());
                                    target.sendSystemMessage(services.messages().render("teleport.request.received",
                                            "player", requester.getGameProfile().getName()));
                                    return Feedback.of(services.messages().render("teleport.request.sent",
                                                    "player", target.getGameProfile().getName()))
                                            .withTarget("player", target.getUUID().toString());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tpAcceptNode(NexusServices services) {
        return Commands.literal("tpaccept")
                .executes(context -> run(context, services, "nexuscore.command.teleport.accept", "teleport.accept",
                        source -> {
                            ServerPlayer target = requirePlayer(source);
                            UUID requesterId = services.teleport().consumeRequest(target.getUUID())
                                    .orElseThrow(() -> new Refused(services.messages().raw("teleport.request.none")));
                            ServerPlayer requester = source.getServer().getPlayerList().getPlayer(requesterId);
                            if (requester == null) {
                                throw new Refused(services.messages().raw("teleport.request.gone"));
                            }
                            Feedback feedback = teleport(services, requester,
                                    TeleportService.Location.of(target), "to " + target.getGameProfile().getName());
                            requester.sendSystemMessage(feedback.message());
                            return Feedback.of(services.messages().render("teleport.request.accepted",
                                            "player", requester.getGameProfile().getName()))
                                    .withTarget("player", requesterId.toString());
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tpDenyNode(NexusServices services) {
        return Commands.literal("tpdeny")
                .executes(context -> run(context, services, "nexuscore.command.teleport.deny", "teleport.deny",
                        source -> {
                            ServerPlayer target = requirePlayer(source);
                            UUID requesterId = services.teleport().consumeRequest(target.getUUID())
                                    .orElseThrow(() -> new Refused(services.messages().raw("teleport.request.none")));
                            ServerPlayer requester = source.getServer().getPlayerList().getPlayer(requesterId);
                            if (requester != null) {
                                requester.sendSystemMessage(services.messages().render("teleport.request.denied",
                                        "player", target.getGameProfile().getName()));
                            }
                            return Feedback.of(services.messages().render("teleport.request.denied-by-you"))
                                    .withTarget("player", requesterId.toString());
                        }));
    }

    private static Feedback teleport(NexusServices services, ServerPlayer player, TeleportService.Location location,
            String label) throws Refused {
        TeleportService.Outcome outcome = services.teleport().begin(player, location, label, false);
        return switch (outcome.status()) {
            case TELEPORTED -> Feedback.of(services.messages().render("teleport.done", "label", label))
                    .withTarget("location", location.describe());
            case WARMUP -> Feedback.of(services.messages().render("teleport.warmup",
                            "seconds", String.valueOf(outcome.warmupSeconds()), "label", label))
                    .withTarget("location", location.describe());
            case FAILED -> throw new Refused(services.messages().raw("teleport.failed", "reason", outcome.detail()));
        };
    }

    // ---- moderation --------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> kickNode(NexusServices services) {
        return Commands.literal("kick")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.kick", "moderation.kick",
                                source -> kick(services, source, EntityArgument.getPlayer(context, "target"), null)))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> run(context, services, "nexuscore.command.moderation.kick",
                                        "moderation.kick",
                                        source -> kick(services, source, EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "reason"))))));
    }

    private static Feedback kick(NexusServices services, CommandSourceStack source, ServerPlayer target, String rawReason) {
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        services.moderation().issue(ModerationService.Type.KICK, target.getUUID(),
                target.getGameProfile().getName(), actor, source.getTextName(), reason, Long.MAX_VALUE);
        target.connection.disconnect(services.messages().render("moderation.kick.disconnect", "reason", reason));
        broadcast(services, source, "moderation.kick.broadcast", target.getGameProfile().getName(), reason);

        return Feedback.of(services.messages().render("moderation.kick.success",
                        "target", target.getGameProfile().getName()))
                .withTarget("player", target.getUUID().toString())
                .withAudit("reason", reason);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> banNode(NexusServices services) {
        return Commands.literal("ban")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.ban", "moderation.ban",
                                source -> proposeBan(services, source,
                                        StringArgumentType.getString(context, "player"), null)))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> run(context, services, "nexuscore.command.moderation.ban",
                                        "moderation.ban",
                                        source -> proposeBan(services, source,
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "reason"))))));
    }

    /**
     * A permanent ban is destructive, so it is proposed rather than executed (§15.1). The
     * token carries this exact ban and nothing else.
     */
    private static Feedback proposeBan(NexusServices services, CommandSourceStack source, String name, String rawReason)
            throws Refused {
        UUID target = resolve(source, services, name);
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        String actorName = source.getTextName();

        String token = services.confirmations().issue(actor, "moderation.ban", target.toString(), reason,
                "permanently ban " + name + " (" + reason + ")",
                () -> applyBan(services, source, target, name, actor, actorName, reason, Long.MAX_VALUE));

        return Feedback.of(services.messages().render("moderation.ban.confirm",
                        "target", name, "reason", reason, "token", token,
                        "seconds", String.valueOf(services.settings().confirmationTimeoutSeconds)))
                .withTarget("player", target.toString());
    }

    private static void applyBan(NexusServices services, CommandSourceStack source, UUID target, String name,
            UUID actor, String actorName, String reason, long expiresAt) {
        services.moderation().issue(ModerationService.Type.BAN, target, name, actor, actorName, reason, expiresAt);
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
        if (online != null) {
            online.connection.disconnect(banScreen(services, reason, expiresAt));
        }
        broadcast(services, source, "moderation.ban.broadcast", name, reason);
    }

    private static Component banScreen(NexusServices services, String reason, long expiresAt) {
        String remaining = DurationParser.describeRemaining(expiresAt, System.currentTimeMillis());
        return services.messages().render(expiresAt == Long.MAX_VALUE
                        ? "moderation.ban.disconnect" : "moderation.tempban.disconnect",
                "reason", reason, "remaining", remaining, "appeal", services.settings().banAppealMessage);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tempBanNode(NexusServices services) {
        return Commands.literal("tempban")
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(context -> run(context, services, "nexuscore.command.moderation.tempban",
                                        "moderation.tempban",
                                        source -> tempBan(services, source,
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "duration"), null)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> run(context, services, "nexuscore.command.moderation.tempban",
                                                "moderation.tempban",
                                                source -> tempBan(services, source,
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "duration"),
                                                        StringArgumentType.getString(context, "reason")))))));
    }

    private static Feedback tempBan(NexusServices services, CommandSourceStack source, String name, String durationText,
            String rawReason) throws Refused {
        UUID target = resolve(source, services, name);
        DurationParser.ParsedDuration parsed = parseDuration(durationText);
        if (parsed.permanent()) {
            throw new Refused("use /ban for a permanent ban — it requires confirmation");
        }
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        long expiresAt = parsed.expiresAt(System.currentTimeMillis());

        applyBan(services, source, target, name, actor, source.getTextName(), reason, expiresAt);

        return Feedback.of(services.messages().render("moderation.tempban.success",
                        "target", name, "duration", parsed.toString(), "reason", reason))
                .withTarget("player", target.toString())
                .withAudit("reason", reason)
                .withAudit("duration", parsed.toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> unbanNode(NexusServices services) {
        return Commands.literal("unban")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.unban", "moderation.unban",
                                source -> liftPunishment(services, source, ModerationService.Type.BAN,
                                        StringArgumentType.getString(context, "player"), "moderation.unban.success"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> muteNode(NexusServices services) {
        return Commands.literal("mute")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.mute", "moderation.mute",
                                source -> mute(services, source, StringArgumentType.getString(context, "player"),
                                        "permanent", null)))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(context -> run(context, services, "nexuscore.command.moderation.mute",
                                        "moderation.mute",
                                        source -> mute(services, source, StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "duration"), null)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> run(context, services, "nexuscore.command.moderation.mute",
                                                "moderation.mute",
                                                source -> mute(services, source,
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "duration"),
                                                        StringArgumentType.getString(context, "reason")))))));
    }

    private static Feedback mute(NexusServices services, CommandSourceStack source, String name, String durationText,
            String rawReason) throws Refused {
        UUID target = resolve(source, services, name);
        DurationParser.ParsedDuration parsed = parseDuration(durationText);
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        services.moderation().issue(ModerationService.Type.MUTE, target, name, actor, source.getTextName(),
                reason, parsed.expiresAt(System.currentTimeMillis()));

        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(services.messages().render("moderation.mute.notify",
                    "duration", parsed.toString(), "reason", reason));
        }
        return Feedback.of(services.messages().render("moderation.mute.success",
                        "target", name, "duration", parsed.toString(), "reason", reason))
                .withTarget("player", target.toString())
                .withAudit("reason", reason)
                .withAudit("duration", parsed.toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> unmuteNode(NexusServices services) {
        return Commands.literal("unmute")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.unmute", "moderation.unmute",
                                source -> liftPunishment(services, source, ModerationService.Type.MUTE,
                                        StringArgumentType.getString(context, "player"), "moderation.unmute.success"))));
    }

    private static Feedback liftPunishment(NexusServices services, CommandSourceStack source, ModerationService.Type type,
            String name, String messageKey) throws Refused {
        UUID target = resolve(source, services, name);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        if (services.moderation().lift(type, target, actor, source.getTextName()).isEmpty()) {
            throw new Refused(services.messages().raw("moderation.lift.none",
                    "target", name, "type", type.name().toLowerCase(Locale.ROOT)));
        }
        return Feedback.of(services.messages().render(messageKey, "target", name))
                .withTarget("player", target.toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warnNode(NexusServices services) {
        return Commands.literal("warn")
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> run(context, services, "nexuscore.command.moderation.warn",
                                        "moderation.warn",
                                        source -> {
                                            String name = StringArgumentType.getString(context, "player");
                                            UUID target = resolve(source, services, name);
                                            String reason = ModerationService.sanitiseReason(
                                                    StringArgumentType.getString(context, "reason"),
                                                    services.settings().maxReasonLength);
                                            UUID actor = source.getEntity() instanceof ServerPlayer player
                                                    ? player.getUUID() : null;
                                            services.moderation().issue(ModerationService.Type.WARNING, target, name,
                                                    actor, source.getTextName(), reason, Long.MAX_VALUE);
                                            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
                                            if (online != null) {
                                                online.sendSystemMessage(services.messages()
                                                        .render("moderation.warn.notify", "reason", reason));
                                            }
                                            int total = services.moderation().warnings(target).size();
                                            return Feedback.of(services.messages().render("moderation.warn.success",
                                                            "target", name, "reason", reason,
                                                            "count", String.valueOf(total)))
                                                    .withTarget("player", target.toString())
                                                    .withAudit("reason", reason);
                                        }))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warningsNode(NexusServices services) {
        return Commands.literal("warnings")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.moderation.warnings",
                                "moderation.warnings",
                                source -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    UUID target = resolve(source, services, name);
                                    List<ModerationService.Record> found = services.moderation().warnings(target);
                                    if (found.isEmpty()) {
                                        return Feedback.of(services.messages().render("moderation.warnings.none",
                                                "target", name));
                                    }
                                    StringBuilder text = new StringBuilder("&b--- Warnings for " + name
                                            + " (" + found.size() + ") ---");
                                    for (ModerationService.Record record : found) {
                                        text.append("\n&7- &f").append(record.reason())
                                                .append(" &8(by ").append(record.actorName()).append(')');
                                    }
                                    return Feedback.of(Component.literal(MessageService.colourise(text.toString())))
                                            .withTarget("player", target.toString());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> banListNode(NexusServices services) {
        return Commands.literal("banlist")
                .executes(context -> run(context, services, "nexuscore.command.moderation.banlist", "moderation.banlist",
                        source -> {
                            List<ModerationService.Record> bans = services.moderation().activeBans();
                            if (bans.isEmpty()) {
                                return Feedback.of(services.messages().render("moderation.banlist.none"));
                            }
                            StringBuilder text = new StringBuilder("&b--- Active bans (" + bans.size() + ") ---");
                            for (ModerationService.Record record : bans) {
                                text.append("\n&7- &c").append(record.targetName()).append(" &7")
                                        .append(record.reason()).append(" &8(")
                                        .append(record.permanent() ? "permanent"
                                                : DurationParser.describeRemaining(record.expiresAt(),
                                                        System.currentTimeMillis()))
                                        .append(')');
                            }
                            return Feedback.of(Component.literal(MessageService.colourise(text.toString())));
                        }));
    }

    // ---- shared helpers ----------------------------------------------------------------

    private static void broadcast(NexusServices services, CommandSourceStack source, String key,
            String target, String reason) {
        if (!services.settings().broadcastPunishments) {
            return;
        }
        source.getServer().getPlayerList().broadcastSystemMessage(
                services.messages().render(key, "target", target, "reason", reason, "actor", source.getTextName()), false);
    }

    private static DurationParser.ParsedDuration parseDuration(String text) throws Refused {
        try {
            return DurationParser.parse(text);
        } catch (IllegalArgumentException e) {
            throw new Refused(e.getMessage());
        }
    }

    /** Bounds a player-supplied name for a home or warp (§15, "Input validation"). */
    private static void requireSimpleName(String name) throws Refused {
        if (!name.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new Refused("a name must be 1-32 characters of letters, numbers, underscore, or hyphen");
        }
    }

    private static UUID resolve(CommandSourceStack source, NexusServices services, String name) throws Refused {
        Optional<UUID> found = services.identity().resolve(source.getServer(), name);
        return found.orElseThrow(() -> new Refused(services.messages().raw("error.unknown-player", "name", name)));
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) throws Refused {
        try {
            return source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            throw new Refused("this command must be run by a player, not the console");
        }
    }

    // ---- pipeline types ----------------------------------------------------------------

    /** The work a command does, once it has been authorised and rate limited. */
    @FunctionalInterface
    private interface Body {
        Feedback run(CommandSourceStack source) throws Refused, CommandSyntaxException;
    }

    /**
     * A command refusing to act, with a reason already fit to show a player.
     *
     * <p>Distinct from an unexpected exception: a refusal is an expected outcome that the
     * player caused and can fix, so its message is shown verbatim.</p>
     */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        private Refused(String message) {
            super(message);
        }
    }

    /** What a command produced: the message to send, plus what to record in the audit log. */
    private static final class Feedback {
        private final Component message;
        private String targetType = "none";
        private String targetId = "-";
        private final Map<String, String> auditParameters = new LinkedHashMap<>();
        private boolean broadcastToOperators;

        private Feedback(Component message) {
            this.message = message;
        }

        private static Feedback of(Component message) {
            return new Feedback(message);
        }

        private Feedback withTarget(String type, String id) {
            this.targetType = type;
            this.targetId = id;
            return this;
        }

        private Feedback withAudit(String key, String value) {
            this.auditParameters.put(key, value);
            return this;
        }

        private Component message() {
            return message;
        }

        private String targetType() {
            return targetType;
        }

        private String targetId() {
            return targetId;
        }

        private Map<String, String> auditParameters() {
            return auditParameters;
        }

        private boolean broadcastToOperators() {
            return broadcastToOperators;
        }
    }
}
