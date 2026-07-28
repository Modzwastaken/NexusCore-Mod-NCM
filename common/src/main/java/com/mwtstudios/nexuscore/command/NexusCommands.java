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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.core.SafeMode;
import com.mwtstudios.nexuscore.gui.AdminGuiService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.module.ModuleException;
import com.mwtstudios.nexuscore.message.TimeText;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.moderation.VanillaBans;
import com.mwtstudios.nexuscore.moderation.PunishmentMessages;
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
                        .then(infoNode(services)).then(seenNode(services)).then(listNode(services))
                        .then(nearNode(services)))
                .then(Commands.literal("teleport")
                        .then(homeNode(services)).then(setHomeNode(services)).then(delHomeNode(services))
                        .then(homesNode(services)).then(warpNode(services)).then(setWarpNode(services))
                        .then(delWarpNode(services)).then(warpsNode(services)).then(spawnNode(services))
                        .then(setSpawnNode(services)).then(backNode(services)).then(tpaNode(services))
                        .then(tpAcceptNode(services)).then(tpDenyNode(services))
                        .then(tpNode(services)).then(tpHereNode(services)))
                .then(Commands.literal("moderation")
                        .then(kickNode(services)).then(banNode(services)).then(tempBanNode(services))
                        .then(unbanNode(services)).then(muteNode(services)).then(unmuteNode(services))
                        .then(warnNode(services)).then(warningsNode(services)).then(banListNode(services)))
                .then(Commands.literal("gui")
                        .executes(context -> run(context, services, AdminGuiService.NODE_OPEN, "gui.admin.open",
                                source -> openGui(services, source, gui)))));

        // Always called. The two settings inside are independent, and conflating them was a
        // defect: the vanilla takeover and /adminpanel lived inside this branch, so
        // overrideVanillaCommands=true did nothing at all when registerAliases=false — an operator
        // who wanted NexusCore's /ban without the short aliases silently got neither.
        registerAliases(dispatcher, services, gui);
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
        // Aliases are registered ONLY for modules that started. This gating is load-bearing, not
        // tidiness: without it, safe mode removed vanilla's /kick, /ban, /banlist, /pardon and
        // /list from the dispatcher and installed NexusCore nodes that then refused, leaving an
        // operator with neither NexusCore's moderation nor vanilla's — unable to kick a griefer in
        // the one mode that exists for recovering a broken server. ModuleManager.disable's javadoc
        // predicted exactly this and the 1.0.5 caller was written without it.
        Map<String, LiteralArgumentBuilder<CommandSourceStack>> aliases = new LinkedHashMap<>();
        if (services.has("player-utilities")) {
            aliases.put("heal", healNode(services));
            aliases.put("feed", feedNode(services));
            aliases.put("fly", flyNode(services));
            aliases.put("god", godNode(services));
            aliases.put("speed", speedNode(services));
            aliases.put("vanish", vanishNode(services));
            aliases.put("playerinfo", infoNode(services));
            aliases.put("seen", seenNode(services));
            aliases.put("list", listNode(services));
            aliases.put("near", nearNode(services));
        }
        if (services.has("teleport")) {
            aliases.put("tphere", tpHereNode(services));
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
        }
        if (services.has("moderation")) {
            aliases.put("kick", kickNode(services));
            aliases.put("ban", banNode(services));
            aliases.put("tempban", tempBanNode(services));
            aliases.put("unban", unbanNode(services));
            aliases.put("mute", muteNode(services));
            aliases.put("unmute", unmuteNode(services));
            aliases.put("warn", warnNode(services));
            aliases.put("warnings", warningsNode(services));
            aliases.put("banlist", banListNode(services));
            aliases.put("pardon", unbanNode(services));
        }

        boolean takeOver = services.settings().overrideVanillaCommands;
        if (!services.settings().registerAliases) {
            // Short aliases are off, so nothing is registered under a bare name and no vanilla node
            // is touched by the loop. The takeover below still runs, because it is a separate
            // setting and an operator may want it without the aliases.
            aliases.clear();
        }

        for (Map.Entry<String, LiteralArgumentBuilder<CommandSourceStack>> entry : aliases.entrySet()) {
            String name = entry.getKey();
            try {
                if (dispatcher.getRoot().getChild(name) == null) {
                    dispatcher.register(rename(name, entry.getValue()));
                    continue;
                }
                // Vanilla (or another mod) owns this name. Leaving it alone is a trap: the
                // operator types /ban, silently gets vanilla's ban, and none of NexusCore's
                // duration, audit trail, styled screen or appeal line applies. So take the
                // name when the operator has asked for it, and fall back to the
                // collision-free form when they have not, or when the takeover fails.
                if (takeOver && VanillaCommandOverride.remove(dispatcher, name)) {
                    dispatcher.register(rename(name, entry.getValue()));
                    LOGGER.info("NexusCore took over /{}", name);
                    continue;
                }
                String fallback = COLLISION_PREFIX + name;
                if (dispatcher.getRoot().getChild(fallback) == null) {
                    dispatcher.register(rename(fallback, entry.getValue()));
                    LOGGER.info("NexusCore did not take over /{} — another command owns it. "
                            + "Use /{} or /nexus ... for the NexusCore version.", name, fallback);
                } else {
                    LOGGER.warn("NexusCore could not register /{} or /{}; use the canonical /nexus command", name, fallback);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("NexusCore could not register the alias /{}; the canonical /nexus command still works", name, e);
            }
        }

        // /tp and /gamemode are rebuilt with vanilla's full grammar rather than aliased, so
        // selectors, coordinates and `facing` keep working. They are only taken when the whole
        // vanilla node can be removed cleanly — a half-replaced /tp is worse than none.
        //
        // BOTH names must be taken. Vanilla's TeleportCommand registers `teleport` as the real
        // node and `tp` as a *redirect* to it:
        //     LiteralCommandNode<?> node = dispatcher.register(literal("teleport")…);
        //     dispatcher.register(literal("tp").requires(…hasPermission(2)).redirect(node));
        // Replacing only `tp` therefore left `/teleport` as fully working vanilla — no
        // permission check, no rate limit and no audit record — while every document claimed
        // NexusCore owned the command. `ban-ip` and `pardon-ip` are separate vanilla commands
        // for the same reason: they are not aliases of `ban` and `pardon`, so taking those two
        // never touched them.
        if (takeOver) {
            registerVanillaReplacement(dispatcher, "tp", VanillaCommands.teleport(services));
            // rename() is essential here and its absence was a real regression. The builder
            // VanillaCommands.teleport() returns is named "tp", so registering it under the name
            // "teleport" removed vanilla's /teleport and then handed Brigadier a node called "tp"
            // again — addChild finds the existing "tp" child and MERGES into it rather than
            // creating a "teleport" child, so /teleport ceased to exist while the log claimed a
            // successful takeover. Shipped in 1.0.1 and 1.0.5.
            registerVanillaReplacement(dispatcher, "teleport",
                    rename("teleport", VanillaCommands.teleport(services)));
            registerVanillaReplacement(dispatcher, "gamemode", VanillaCommands.gamemode(services));
        }

        try {
            if (dispatcher.getRoot().getChild("adminpanel") == null) {
                dispatcher.register(Commands.literal("adminpanel")
                        .executes(context -> run(context, services, AdminGuiService.NODE_OPEN, "gui.admin.open",
                                source -> openGui(services, source, gui))));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("NexusCore could not register /adminpanel; /nexus gui still works", e);
        }
    }

    /** Removes the vanilla node and installs NexusCore's equivalent, or leaves vanilla alone. */
    private static void registerVanillaReplacement(CommandDispatcher<CommandSourceStack> dispatcher, String name,
            LiteralArgumentBuilder<CommandSourceStack> replacement) {
        try {
            if (dispatcher.getRoot().getChild(name) != null && !VanillaCommandOverride.remove(dispatcher, name)) {
                LOGGER.info("NexusCore left vanilla /{} in place; its own version stays under /nexus", name);
                return;
            }
            dispatcher.register(replacement);
            LOGGER.info("NexusCore took over /{} (vanilla selector and coordinate syntax preserved)", name);
        } catch (RuntimeException e) {
            LOGGER.warn("NexusCore could not take over /{}; vanilla behaviour is unchanged", name, e);
        }
    }

    /**
     * Test hook for {@link #rename}. Package-private so {@code VanillaCommandsTest} can pin down
     * the {@code /teleport} regression without making the renamer part of the public surface.
     *
     * @param name the literal name to rebuild under
     * @param source the builder to copy
     * @return the renamed builder
     */
    static LiteralArgumentBuilder<CommandSourceStack> renameForTest(String name,
            LiteralArgumentBuilder<CommandSourceStack> source) {
        return rename(name, source);
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
    static int run(CommandContext<CommandSourceStack> context, NexusServices services,
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

        } catch (ModuleException e) {
            // A feature that safe mode left out. This is an expected refusal, not a fault: the
            // player is told plainly, and no correlation id is issued because there is nothing to
            // investigate.
            source.sendFailure(services.messages().render("error.module.disabled"));
            return 0;
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

    /**
     * The permission-filtered help output, rendered from {@link CommandCatalogue}.
     *
     * <p>Built from the same descriptors as {@code docs/admin/commands.md}, so help and the
     * reference document cannot disagree. This used to be a hand-written list of fourteen lines
     * while the document listed a different set — two hand-maintained descriptions of one command
     * surface, which is how both came to be wrong in different ways.</p>
     *
     * <p>Commands whose module is not started are left out rather than shown as refusals: in safe
     * mode a list of things you cannot do is noise, and the command itself still explains why if
     * someone types it.</p>
     */
    private static Feedback help(CommandSourceStack source, NexusServices services) {
        List<String> lines = new ArrayList<>();
        lines.add(services.messages().raw("header.help", "version", services.modVersion()));

        String currentGroup = null;
        for (CommandDescriptor descriptor : CommandCatalogue.all()) {
            if (!services.has(descriptor.module())) {
                continue;
            }
            if (!services.authorise(source, descriptor.node()).allowed()) {
                continue;
            }
            if (descriptor.canonical().startsWith("(GUI)")) {
                continue;
            }
            String group = CommandCatalogue.group(descriptor);
            if (!group.equals(currentGroup)) {
                currentGroup = group;
                lines.add("&8— &7" + group);
            }
            // The canonical form is always shown, and the alias only as an extra. Showing the alias
            // ALONE claimed `/ban`, `/kick`, `/list` and `/banlist` as NexusCore's — which is only
            // true when overrideVanillaCommands is on AND the takeover succeeded AND registerAliases
            // is on. With any of those off, help was naming vanilla's command as ours.
            String usage = descriptor.canonical()
                    + (descriptor.hasAlias() && services.settings().registerAliases
                            ? " &8(/" + descriptor.alias() + ")" : "");
            lines.add("&f" + usage + " &7- " + descriptor.summary());
        }

        lines.add(services.messages().raw("help.footer"));
        return Feedback.of(Component.literal(MessageService.colourise(String.join("\n", lines))));
    }

    private static Feedback reload(NexusServices services) throws Refused {
        var result = services.configuration().reload();
        if (!result.succeeded()) {
            throw new Refused(services.messages().raw("error.reload-refused", "reason", result.failure()));
        }
        services.applySettings();
        return Feedback.of(result.report().isClean()
                ? services.messages().render("reload.success")
                : services.messages().render("reload.corrected",
                        "count", String.valueOf(result.report().findings().size()),
                        "detail", result.report().render()));
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

    private static Feedback openGui(NexusServices services, CommandSourceStack source, AdminGuiService gui)
            throws Refused {
        ServerPlayer player = requirePlayer(services, source);
        gui.openDashboard(player);
        return Feedback.of(services.messages().render("gui.admin.opening"));
    }

    // ---- permission, audit, system trees -----------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> permissionTree(NexusServices services) {
        return Commands.literal("permission")
                .then(Commands.literal("check")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("node", PermissionNodeArgument.permissionNode())
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
                                                                StringArgumentType.getString(context, "group"), false)))))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("node", PermissionNodeArgument.permissionNode())
                                        .then(Commands.literal("allow")
                                                .executes(context -> run(context, services,
                                                        "nexuscore.command.permission.set", "permission.set",
                                                        source -> setNode(source, services,
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "node"), true))))
                                        .then(Commands.literal("deny")
                                                .executes(context -> run(context, services,
                                                        "nexuscore.command.permission.set", "permission.set",
                                                        source -> setNode(source, services,
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "node"), false)))))))
                .then(Commands.literal("unset")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("node", PermissionNodeArgument.permissionNode())
                                        .executes(context -> run(context, services,
                                                "nexuscore.command.permission.set", "permission.unset",
                                                source -> unsetNode(source, services,
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "node")))))));
    }

    private static Feedback permissionCheck(CommandSourceStack source, NexusServices services, String name, String node)
            throws Refused {
        UUID target = resolve(source, services, name);
        // Through authorise(), not the evaluator: the operator-bootstrap grant lives there, and
        // an explain command that disagrees with enforcement under-reports who can do what.
        PermissionDecision decision = services.authorise(source.getServer(), target, name, node);
        return Feedback.of(services.messages().render("permission.check.result",
                        "target", name, "node", node,
                        "result", decision.result().name(),
                        "explanation", decision.explanation(),
                        "groups", String.join(", ", services.permissions().groupsOf(target))))
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
                .then(Commands.literal("tail")
                        .executes(context -> run(context, services, "nexuscore.command.audit.tail", "audit.tail",
                                source -> auditTail(services, 10)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(context -> run(context, services, "nexuscore.command.audit.tail", "audit.tail",
                                        source -> auditTail(services, IntegerArgumentType.getInteger(context, "count"))))))
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
                                    String text = services.messages().raw("header.status",
                                            "version", services.modVersion()) + "\n"
                                            + "&7Data directory: &f" + services.store().root() + "\n"
                                            + "&7Groups: &f" + services.permissions().groupNames().size()
                                            + "  &7Permission cache: &f" + services.permissions().cacheStats() + "\n"
                                            + "&7Audit records: &f" + services.audit().count()
                                            // Degrades rather than refuses. Status is the command an
                                            // operator runs to find out what state the server is in,
                                            // so it has to work when part of the server is off — it
                                            // refused outright in safe mode until 1.0.5.
                                            + "  &7Punishments: &f" + (services.has("moderation")
                                                    ? String.valueOf(services.moderation().totalRecords())
                                                    : "&8disabled") + "\n"
                                            + "&7Warps: &f" + (services.has("teleport")
                                                    ? String.valueOf(services.teleport().warpNames().size())
                                                    : "&8disabled")
                                            + "  &7Messages: &f" + services.messages().size() + "\n"
                                            + (SafeMode.requested()
                                                    ? "&e&lSAFE MODE&r&7 — disabled: &f"
                                                            + SafeMode.disabledModules() + "\n"
                                                    : "")
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
                        source -> healOne(services, requirePlayer(services, source))))
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
                        source -> feedOne(services, requirePlayer(services, source))))
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
                        source -> toggleFly(services, requirePlayer(services, source))))
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
                        source -> toggleGod(services, requirePlayer(services, source))))
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
                                    ServerPlayer player = requirePlayer(services, source);
                                    services.players().resetSpeed(player);
                                    return Feedback.of(services.messages().render("player.speed.reset"))
                                            .withTarget("player", player.getUUID().toString());
                                })))
                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(context -> run(context, services, "nexuscore.command.player.speed", "player.speed",
                                source -> setSpeed(services, requirePlayer(services, source),
                                        FloatArgumentType.getFloat(context, "multiplier"), false)))
                        .then(Commands.literal("fly")
                                .executes(context -> run(context, services, "nexuscore.command.player.speed", "player.speed",
                                        source -> setSpeed(services, requirePlayer(services, source),
                                                FloatArgumentType.getFloat(context, "multiplier"), true)))));
    }

    private static Feedback setSpeed(NexusServices services, ServerPlayer player, float multiplier, boolean flying)
            throws Refused {
        try {
            services.players().setSpeed(player, multiplier, flying);
        } catch (IllegalArgumentException e) {
            throw new Refused(services.messages().raw("error.bad-value", "reason", e.getMessage()));
        }
        return Feedback.of(services.messages().render("player.speed.set",
                        "kind", flying ? "fly" : "walk", "value", String.valueOf(multiplier)))
                .withTarget("player", player.getUUID().toString());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> vanishNode(NexusServices services) {
        return Commands.literal("vanish")
                .executes(context -> run(context, services, "nexuscore.command.player.vanish", "player.vanish",
                        source -> {
                            ServerPlayer player = requirePlayer(services, source);
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
                        source -> goHome(services, requirePlayer(services, source), "home")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.home", "teleport.home",
                                source -> goHome(services, requirePlayer(services, source),
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
                        source -> setHome(services, requirePlayer(services, source), "home")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.sethome", "teleport.sethome",
                                source -> setHome(services, requirePlayer(services, source),
                                        StringArgumentType.getString(context, "name")))));
    }

    private static Feedback setHome(NexusServices services, ServerPlayer player, String name) throws Refused {
        requireSimpleName(services, name);
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
                                    ServerPlayer player = requirePlayer(services, source);
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
                            ServerPlayer player = requirePlayer(services, source);
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
                                    ServerPlayer player = requirePlayer(services, source);
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
                                    ServerPlayer player = requirePlayer(services, source);
                                    String name = StringArgumentType.getString(context, "name");
                                    requireSimpleName(services, name);
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
                            ServerPlayer player = requirePlayer(services, source);
                            TeleportService.Location location = services.teleport().spawn()
                                    .orElseGet(() -> TeleportService.Location.worldSpawn(source.getServer().overworld()));
                            return teleport(services, player, location, "spawn");
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setSpawnNode(NexusServices services) {
        return Commands.literal("setspawn")
                .executes(context -> run(context, services, "nexuscore.command.teleport.setspawn", "teleport.setspawn",
                        source -> {
                            ServerPlayer player = requirePlayer(services, source);
                            services.teleport().setSpawn(TeleportService.Location.of(player));
                            return Feedback.of(services.messages().render("teleport.setspawn.success"))
                                    .withTarget("spawn", "server");
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> backNode(NexusServices services) {
        return Commands.literal("back")
                .executes(context -> run(context, services, "nexuscore.command.teleport.back", "teleport.back",
                        source -> {
                            ServerPlayer player = requirePlayer(services, source);
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
                                    ServerPlayer requester = requirePlayer(services, source);
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
                            ServerPlayer target = requirePlayer(services, source);
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
                            ServerPlayer target = requirePlayer(services, source);
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
        ModerationService.Record ban = services.moderation()
                .issue(ModerationService.Type.BAN, target, name, actor, actorName, reason, expiresAt);
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
        if (online != null) {
            // The screen is built in exactly one place for all loaders and all paths.
            online.connection.disconnect(
                    PunishmentMessages.banScreen(services.messages(), services.settings(), ban, System.currentTimeMillis()));
        }
        broadcast(services, source, "moderation.ban.broadcast", name, reason);
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
        DurationParser.ParsedDuration parsed = parseDuration(services, durationText);
        if (parsed.permanent()) {
            throw new Refused(services.messages().raw("error.use-ban-command"));
        }
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        long expiresAt = parsed.expiresAt(System.currentTimeMillis());

        applyBan(services, source, target, name, actor, source.getTextName(), reason, expiresAt);

        return Feedback.of(services.messages().render("moderation.tempban.success",
                        "target", name,
                        "duration", TimeText.longDuration(parsed.duration()), "reason", reason))
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
        DurationParser.ParsedDuration parsed = parseDuration(services, durationText);
        String reason = ModerationService.sanitiseReason(rawReason, services.settings().maxReasonLength);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        services.moderation().issue(ModerationService.Type.MUTE, target, name, actor, source.getTextName(),
                reason, parsed.expiresAt(System.currentTimeMillis()));

        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
        String shown = parsed.permanent() ? "permanent" : TimeText.longDuration(parsed.duration());
        if (online != null) {
            online.sendSystemMessage(services.messages().render("moderation.mute.notify",
                    "duration", shown, "reason", reason));
        }
        return Feedback.of(services.messages().render("moderation.mute.success",
                        "target", name, "duration", shown, "reason", reason))
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
        if (type == ModerationService.Type.BAN) {
            return liftBanEverywhere(services, source, name, messageKey);
        }
        UUID target = resolve(source, services, name);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        if (services.moderation().lift(type, target, actor, source.getTextName()).isEmpty()) {
            throw new Refused(services.messages().raw("moderation.lift.none",
                    "target", name, "type", type.name().toLowerCase(Locale.ROOT)));
        }
        return Feedback.of(services.messages().render(messageKey, "target", name))
                .withTarget("player", target.toString());
    }

    /**
     * {@code /pardon} means "let this player back in", and vanilla still enforces its own list
     * at login — so a ban must be lifted from <em>both</em> systems. A pre-takeover vanilla ban
     * is also often the only local record of the player, so vanilla's entry is consulted for the
     * UUID before any network lookup: without that, the one command able to free such a player
     * would first demand a Mojang round-trip to learn who they are.
     */
    private static Feedback liftBanEverywhere(NexusServices services, CommandSourceStack source,
            String name, String messageKey) throws Refused {
        VanillaBans vanilla = VanillaBans.of(source.getServer());
        UUID target = services.identity().resolve(source.getServer(), name)
                .or(() -> vanilla.uuidOfBanned(name))
                .orElse(null);
        if (target == null) {
            target = resolve(source, services, name);
        }
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        VanillaBans.LiftOutcome outcome = VanillaBans.liftBan(
                services.moderation(), vanilla, target, name, actor, source.getTextName());
        return switch (outcome) {
            case NOTHING_TO_LIFT -> throw new Refused(services.messages().raw("moderation.lift.none",
                    "target", name, "type", "ban"));
            case NEXUS_ONLY -> Feedback.of(services.messages().render(messageKey, "target", name))
                    .withTarget("player", target.toString());
            case VANILLA_ONLY -> Feedback.of(services.messages().render("moderation.lift.vanilla-only",
                            "target", name))
                    .withTarget("player", target.toString());
            case BOTH -> Feedback.of(services.messages().render("moderation.lift.both", "target", name))
                    .withTarget("player", target.toString());
        };
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
                                    StringBuilder text = new StringBuilder(services.messages().raw(
                                            "header.warnings", "target", name,
                                            "count", String.valueOf(found.size())));
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
                            VanillaBans vanilla = VanillaBans.of(source.getServer());
                            // Pre-takeover entries and IP bans live in vanilla's lists, which
                            // still enforce at login. A banlist that hides an enforced ban is
                            // wrong in the way that matters most.
                            List<String> legacy = VanillaBans.vanillaOnlyNames(bans, vanilla);
                            List<String> ips = vanilla.bannedIps();
                            if (bans.isEmpty() && legacy.isEmpty() && ips.isEmpty()) {
                                return Feedback.of(services.messages().render("moderation.banlist.none"));
                            }
                            StringBuilder text = new StringBuilder(services.messages().raw("header.bans",
                                    "count", String.valueOf(bans.size() + legacy.size())));
                            for (ModerationService.Record record : bans) {
                                text.append("\n&7- &c").append(record.targetName()).append(" &7")
                                        .append(record.reason()).append(" &8(")
                                        .append(record.permanent() ? "permanent"
                                                : TimeText.remaining(record.expiresAt(),
                                                        System.currentTimeMillis()))
                                        .append(')');
                            }
                            for (String legacyName : legacy) {
                                text.append("\n&7- &c").append(legacyName)
                                        .append(" &8(vanilla, pre-NexusCore — /pardon lifts it)");
                            }
                            if (!ips.isEmpty()) {
                                text.append('\n').append(services.messages().raw("header.bans.ip",
                                        "count", String.valueOf(ips.size())));
                                for (String ip : ips) {
                                    text.append("\n&7- &c").append(ip);
                                }
                            }
                            return Feedback.of(Component.literal(MessageService.colourise(text.toString())));
                        }));
    }


    private static Feedback setNode(CommandSourceStack source, NexusServices services, String name, String node,
            boolean allow) throws Refused {
        UUID target = resolve(source, services, name);
        try {
            services.permissions().setSubjectNode(target, node, allow);
        } catch (IllegalArgumentException e) {
            throw new Refused(services.messages().raw("error.bad-value", "reason", e.getMessage()));
        }
        return Feedback.of(services.messages().render("permission.set.success",
                        "target", name, "node", node, "value", allow ? "ALLOW" : "DENY"))
                .withTarget("player", target.toString())
                .withAudit("node", node)
                .withAudit("value", allow ? "ALLOW" : "DENY")
                .broadcast();
    }

    private static Feedback unsetNode(CommandSourceStack source, NexusServices services, String name, String node)
            throws Refused {
        UUID target = resolve(source, services, name);
        if (!services.permissions().removeSubjectNode(target, node)) {
            throw new Refused(services.messages().raw("permission.unset.none", "target", name, "node", node));
        }
        return Feedback.of(services.messages().render("permission.unset.success", "target", name, "node", node))
                .withTarget("player", target.toString())
                .withAudit("node", node)
                .broadcast();
    }

    private static Feedback auditTail(NexusServices services, int count) {
        List<String> records = services.audit().tail(count);
        if (records.isEmpty()) {
            return Feedback.of(services.messages().render("audit.tail.none"));
        }
        StringBuilder text = new StringBuilder(
                services.messages().raw("header.audit", "count", String.valueOf(records.size())));
        for (String line : records) {
            text.append("\n&7").append(summariseAuditLine(line));
        }
        return Feedback.of(Component.literal(MessageService.colourise(text.toString())));
    }

    /** Renders one audit record compactly. Never echoes redacted parameters back out. */
    private static String summariseAuditLine(String line) {
        try {
            com.google.gson.JsonObject entry = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            return entry.get("timestamp_utc").getAsString().substring(11, 19)
                    + " &f" + entry.get("actor_name").getAsString()
                    + " &7" + entry.get("action").getAsString()
                    + " &8" + entry.get("result").getAsString();
        } catch (RuntimeException e) {
            return "(unreadable record)";
        }
    }

    // ---- player lookups ----------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> seenNode(NexusServices services) {
        return Commands.literal("seen")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context, services, "nexuscore.command.player.seen", "player.seen",
                                source -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    UUID target = resolve(source, services, name);
                                    ServerPlayer online = source.getServer().getPlayerList().getPlayer(target);
                                    if (online != null && !hiddenFrom(services, source, target)) {
                                        return Feedback.of(services.messages().render("player.seen.online",
                                                "target", online.getGameProfile().getName()));
                                    }
                                    var profile = services.identity().profileOf(target)
                                            .orElseThrow(() -> new Refused(services.messages()
                                                    .raw("error.unknown-player", "name", name)));
                                    // A record filed by a lookup carries a name but no visit.
                                    // Reporting "last seen just now" for someone who has never
                                    // connected would be inventing the answer.
                                    if (profile.lastSeenEpochMillis() == 0L) {
                                        return Feedback.of(services.messages().render("player.seen.never",
                                                        "target", profile.name()))
                                                .withTarget("player", target.toString());
                                    }
                                    return Feedback.of(services.messages().render("player.seen.offline",
                                                    "target", profile.name(),
                                                    "ago", TimeText.elapsed(
                                                            profile.lastSeenEpochMillis(), System.currentTimeMillis()),
                                                    "first", TimeText.date(profile.firstSeenEpochMillis())))
                                            .withTarget("player", target.toString());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> listNode(NexusServices services) {
        return Commands.literal("list")
                .executes(context -> run(context, services, "nexuscore.command.player.list", "player.list",
                        source -> {
                            List<String> visible = new ArrayList<>();
                            int hidden = 0;
                            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                                if (hiddenFrom(services, source, player.getUUID())) {
                                    hidden++;
                                    continue;
                                }
                                String name = player.getGameProfile().getName();
                                visible.add(services.players().isVanished(player.getUUID()) ? name + " &8(vanished)&7" : name);
                            }
                            visible.sort(String::compareToIgnoreCase);
                            String text = services.messages().raw("player.list.header",
                                    "count", String.valueOf(visible.size()),
                                    "max", String.valueOf(source.getServer().getMaxPlayers()))
                                    + (visible.isEmpty() ? "" : " " + String.join("&7, &f", visible));
                            return Feedback.of(Component.literal(MessageService.colourise(text
                                    + (hidden > 0 ? " &8(+" + hidden + " hidden)" : ""))));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nearNode(NexusServices services) {
        return Commands.literal("near")
                .executes(context -> run(context, services, "nexuscore.command.player.near", "player.near",
                        source -> near(services, source, 100)))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 5000))
                        .executes(context -> run(context, services, "nexuscore.command.player.near", "player.near",
                                source -> near(services, source, IntegerArgumentType.getInteger(context, "radius")))));
    }

    private static Feedback near(NexusServices services, CommandSourceStack source, int radius) throws Refused {
        ServerPlayer self = requirePlayer(services, source);
        List<String> found = new ArrayList<>();
        for (ServerPlayer other : source.getServer().getPlayerList().getPlayers()) {
            if (other.getUUID().equals(self.getUUID()) || !other.level().equals(self.level())
                    || hiddenFrom(services, source, other.getUUID())) {
                continue;
            }
            double distance = other.position().distanceTo(self.position());
            if (distance <= radius) {
                found.add(other.getGameProfile().getName() + " &8(" + Math.round(distance) + "m)&7");
            }
        }
        if (found.isEmpty()) {
            return Feedback.of(services.messages().render("player.near.none", "radius", String.valueOf(radius)));
        }
        found.sort(String::compareToIgnoreCase);
        return Feedback.of(Component.literal(MessageService.colourise(
                services.messages().raw("player.near.header", "count", String.valueOf(found.size()),
                        "radius", String.valueOf(radius)) + " &f" + String.join("&7, &f", found))));
    }

    /**
     * A vanished player is hidden from anyone without the vanish permission, so /list, /near,
     * and /seen do not leak the presence of staff who deliberately hid themselves.
     */
    private static boolean hiddenFrom(NexusServices services, CommandSourceStack source, UUID subject) {
        if (!services.players().isVanished(subject)) {
            return false;
        }
        return !services.authorise(source, "nexuscore.command.player.vanish").allowed();
    }

    // ---- staff teleports ---------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> tpNode(NexusServices services) {
        return Commands.literal("tp")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.tp", "teleport.tp",
                                source -> staffTeleport(services, requirePlayer(services, source),
                                        EntityArgument.getPlayer(context, "target"))))
                        .then(Commands.argument("destination", EntityArgument.player())
                                .executes(context -> run(context, services, "nexuscore.command.teleport.tp", "teleport.tp",
                                        source -> staffTeleport(services, EntityArgument.getPlayer(context, "target"),
                                                EntityArgument.getPlayer(context, "destination"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tpHereNode(NexusServices services) {
        return Commands.literal("tphere")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> run(context, services, "nexuscore.command.teleport.tp", "teleport.tphere",
                                source -> staffTeleport(services, EntityArgument.getPlayer(context, "target"),
                                        requirePlayer(services, source)))));
    }

    /** Staff teleports skip warmup and cooldown: they are a moderation tool, not travel. */
    private static Feedback staffTeleport(NexusServices services, ServerPlayer moving, ServerPlayer destination)
            throws Refused {
        if (moving.getUUID().equals(destination.getUUID())) {
            throw new Refused(services.messages().raw("teleport.request.self"));
        }
        TeleportService.Outcome outcome = services.teleport().begin(moving,
                TeleportService.Location.of(destination), "to " + destination.getGameProfile().getName(), true);
        if (!outcome.moved()) {
            throw new Refused(services.messages().raw("teleport.failed", "reason", outcome.detail()));
        }
        return Feedback.of(services.messages().render("teleport.tp.success",
                        "target", moving.getGameProfile().getName(),
                        "destination", destination.getGameProfile().getName()))
                .withTarget("player", moving.getUUID().toString())
                .broadcast();
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

    private static DurationParser.ParsedDuration parseDuration(NexusServices services, String text) throws Refused {
        try {
            return DurationParser.parse(text);
        } catch (IllegalArgumentException e) {
            // The parser explains precisely what was wrong; NexusCore only supplies the styling.
            throw new Refused(services.messages().raw("error.bad-duration", "reason", e.getMessage()));
        }
    }

    /** Bounds a player-supplied name for a home or warp (§15, "Input validation"). */
    private static void requireSimpleName(NexusServices services, String name) throws Refused {
        if (!name.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new Refused(services.messages().raw("error.invalid-name"));
        }
    }

    /**
     * Resolves an operator-typed name without blocking the server thread.
     *
     * <p>The local sources answer instantly. When they miss, the vanilla profile cache is
     * consulted <em>asynchronously</em> and the command is refused with an invitation to retry,
     * rather than the server thread being parked on a Mojang HTTP request that any player could
     * trigger with {@code /seen <unknown-name>}. The async lookup files its result into the
     * identity index, so the retry resolves locally.</p>
     */
    private static UUID resolve(CommandSourceStack source, NexusServices services, String name) throws Refused {
        Optional<UUID> found = services.identity().resolve(source.getServer(), name);
        if (found.isPresent()) {
            return found.get();
        }
        // A lookup that already came back empty is a definitive answer, and saying so is the
        // difference between "try again in a moment" forever and being told the account does
        // not exist. Vanilla caches only positive results, so this memory is ours.
        if (services.identity().recentlyMissed(name)) {
            throw new Refused(services.messages().raw("error.unknown-player", "name", name));
        }
        services.identity().resolveAsync(source.getServer(), name);
        throw new Refused(services.messages().raw("error.unknown-player.looking-up", "name", name));
    }

    static ServerPlayer requirePlayer(NexusServices services, CommandSourceStack source) throws Refused {
        try {
            return source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            throw new Refused(services.messages().raw("error.player-only"));
        }
    }

    // ---- pipeline types ----------------------------------------------------------------

    /** The work a command does, once it has been authorised and rate limited. */
    @FunctionalInterface
    interface Body {
        Feedback run(CommandSourceStack source) throws Refused, CommandSyntaxException;
    }

    /**
     * A command refusing to act, with a reason already fit to show a player.
     *
     * <p>Distinct from an unexpected exception: a refusal is an expected outcome that the
     * player caused and can fix, so its message is shown verbatim.</p>
     */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** What a command produced: the message to send, plus what to record in the audit log. */
    static final class Feedback {
        private final Component message;
        private String targetType = "none";
        private String targetId = "-";
        private final Map<String, String> auditParameters = new LinkedHashMap<>();
        private boolean broadcastToOperators;

        private Feedback(Component message) {
            this.message = message;
        }

        static Feedback of(Component message) {
            return new Feedback(message);
        }

        Feedback withTarget(String type, String id) {
            this.targetType = type;
            this.targetId = id;
            return this;
        }

        Feedback withAudit(String key, String value) {
            this.auditParameters.put(key, value);
            return this;
        }

        /**
         * Also show this to other operators. Used for actions that change another player's
         * access or position, where silent success hides staff activity from other staff.
         */
        Feedback broadcast() {
            this.broadcastToOperators = true;
            return this;
        }

        Component message() {
            return message;
        }

        String targetType() {
            return targetType;
        }

        String targetId() {
            return targetId;
        }

        Map<String, String> auditParameters() {
            return auditParameters;
        }

        boolean broadcastToOperators() {
            return broadcastToOperators;
        }
    }
}
