package com.mwtstudios.nexuscore.command;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * NexusCore's replacements for the vanilla commands it takes over.
 *
 * <p>These exist because an administration mod whose {@code /ban} is not the server's
 * {@code /ban} is a trap: an operator types the command they have always typed and silently
 * gets vanilla behaviour — no duration, no reason on the disconnect screen, no audit entry,
 * no appeal line. NexusCore now owns the name.</p>
 *
 * <p><b>{@code /tp} and {@code /gamemode} keep vanilla's full grammar.</b> They are rebuilt
 * here from the same argument types vanilla uses — {@link EntityArgument} for selectors,
 * {@link Vec3Argument} for absolute, relative ({@code ~}) and local ({@code ^}) coordinates,
 * {@link RotationArgument}, {@link EntityAnchorArgument} — so {@code @a}, {@code @e[type=…]},
 * {@code ~ ~5 ~} and {@code facing} all behave exactly as before. What is added is the
 * NexusCore layer around them: a permission check, a rate limit, an audit record, and styled
 * feedback.</p>
 *
 * <p>Teleport here deliberately does <em>not</em> run the §19.1 safety search. Staff teleports
 * are a tool, not travel: an operator who types coordinates means those coordinates, and
 * silently relocating them would be worse than landing where they asked. The safety algorithm
 * still guards {@code /home}, {@code /warp}, {@code /spawn}, {@code /back} and {@code /tpa},
 * which are player-facing.</p>
 */
public final class VanillaCommands {

    /** Permission for the teleport command NexusCore takes over from vanilla. */
    public static final String NODE_TP = "nexuscore.command.teleport.tp";

    /** Permission for the game-mode command NexusCore takes over from vanilla. */
    /**
     * Deliberately <b>not</b> under {@code nexuscore.command.player.*}.
     *
     * <p>It was, and that was a privilege escalation introduced by the v1.0 vanilla takeover.
     * The shipped {@code moderator} group is granted {@code nexuscore.command.player.*}, so the
     * moment NexusCore took ownership of {@code /gamemode} every moderator could put themselves
     * in creative — an ability that, before the takeover, needed vanilla operator level 2.
     *
     * <p>Moving the node rather than editing the shipped group is what makes this fix reach
     * servers that already exist: default groups are only written when {@code permissions.json}
     * is absent, so an installed server keeps its {@code player.*} grant forever. That grant no
     * longer matches this node.
     */
    public static final String NODE_GAMEMODE = "nexuscore.command.staff.gamemode";

    private VanillaCommands() {
        // Utility holder.
    }

    // ---- /gamemode ---------------------------------------------------------------------

    /**
     * Builds {@code /gamemode <mode> [targets]}, matching vanilla's grammar.
     *
     * @param services the shared service registry
     * @return the command node
     */
    public static LiteralArgumentBuilder<CommandSourceStack> gamemode(NexusServices services) {
        return Commands.literal("gamemode")
                .then(Commands.argument("gamemode", GameModeArgument.gameMode())
                        .executes(context -> NexusCommands.run(context, services, NODE_GAMEMODE, "player.gamemode",
                                source -> applyGameMode(services, List.of(NexusCommands.requirePlayer(services, source)),
                                        GameModeArgument.getGameMode(context, "gamemode"))))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> NexusCommands.run(context, services, NODE_GAMEMODE, "player.gamemode",
                                        source -> applyGameMode(services, EntityArgument.getPlayers(context, "targets"),
                                                GameModeArgument.getGameMode(context, "gamemode"))))));
    }

    private static NexusCommands.Feedback applyGameMode(NexusServices services,
            Collection<ServerPlayer> targets, GameType mode) throws NexusCommands.Refused {
        if (targets.isEmpty()) {
            throw new NexusCommands.Refused(services.messages().raw("error.no-targets"));
        }
        int changed = 0;
        for (ServerPlayer target : targets) {
            if (target.setGameMode(mode)) {
                changed++;
                target.sendSystemMessage(services.messages().render("player.gamemode.notify",
                        "mode", mode.getName()));
            }
        }
        String who = targets.size() == 1
                ? targets.iterator().next().getGameProfile().getName()
                : targets.size() + " players";
        return NexusCommands.Feedback.of(services.messages().render("player.gamemode.success",
                        "target", who, "mode", mode.getName()))
                .withTarget("player", who)
                .withAudit("mode", mode.getName())
                .withAudit("changed", String.valueOf(changed))
                .broadcast();
    }

    // ---- /tp ---------------------------------------------------------------------------

    /**
     * Builds {@code /tp} with vanilla's complete grammar.
     *
     * <p>Argument order mirrors vanilla's so Brigadier backtracks identically:
     * {@code /tp 100 64 100} parses as a location for the sender, {@code /tp Steve} as a
     * destination entity, and {@code /tp @a Steve} as targets plus destination.</p>
     *
     * @param services the shared service registry
     * @return the command node
     */
    public static LiteralArgumentBuilder<CommandSourceStack> teleport(NexusServices services) {
        return Commands.literal("tp")
                // /tp <location>          — sender to coordinates
                .then(Commands.argument("location", Vec3Argument.vec3())
                        .executes(context -> NexusCommands.run(context, services, NODE_TP, "teleport.tp",
                                source -> toPosition(services, List.of(NexusCommands.requirePlayer(services, source)),
                                        source.getLevel(),
                                        Vec3Argument.getCoordinates(context, "location").getPosition(source),
                                        null, null, null))))
                // /tp <destination>       — sender to an entity
                .then(Commands.argument("destination", EntityArgument.entity())
                        .executes(context -> NexusCommands.run(context, services, NODE_TP, "teleport.tp",
                                source -> toEntity(services, List.of(NexusCommands.requirePlayer(services, source)),
                                        EntityArgument.getEntity(context, "destination")))))
                .then(Commands.argument("targets", EntityArgument.entities())
                        // /tp <targets> <destination>
                        .then(Commands.argument("destination", EntityArgument.entity())
                                .executes(context -> NexusCommands.run(context, services, NODE_TP, "teleport.tp",
                                        source -> toEntity(services, EntityArgument.getEntities(context, "targets"),
                                                EntityArgument.getEntity(context, "destination")))))
                        // /tp <targets> <location> [rotation | facing ...]
                        .then(Commands.argument("location", Vec3Argument.vec3())
                                .executes(context -> NexusCommands.run(context, services, NODE_TP, "teleport.tp",
                                        source -> toPosition(services, EntityArgument.getEntities(context, "targets"),
                                                source.getLevel(),
                                                Vec3Argument.getCoordinates(context, "location").getPosition(source),
                                                null, null, null)))
                                .then(Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(context -> NexusCommands.run(context, services, NODE_TP, "teleport.tp",
                                                source -> {
                                                    Vec2 rotation = RotationArgument.getRotation(context, "rotation")
                                                            .getRotation(source);
                                                    return toPosition(services,
                                                            EntityArgument.getEntities(context, "targets"),
                                                            source.getLevel(),
                                                            Vec3Argument.getCoordinates(context, "location")
                                                                    .getPosition(source),
                                                            rotation.y, rotation.x, null);
                                                })))
                                .then(Commands.literal("facing")
                                        .then(Commands.argument("facingLocation", Vec3Argument.vec3())
                                                .executes(context -> NexusCommands.run(context, services, NODE_TP,
                                                        "teleport.tp",
                                                        source -> toPosition(services,
                                                                EntityArgument.getEntities(context, "targets"),
                                                                source.getLevel(),
                                                                Vec3Argument.getCoordinates(context, "location")
                                                                        .getPosition(source),
                                                                null, null,
                                                                Vec3Argument.getCoordinates(context, "facingLocation")
                                                                        .getPosition(source)))))
                                        .then(Commands.literal("entity")
                                                .then(Commands.argument("facingEntity", EntityArgument.entity())
                                                        .executes(context -> NexusCommands.run(context, services, NODE_TP,
                                                                "teleport.tp",
                                                                source -> facingEntity(services, context, source,
                                                                        EntityAnchorArgument.Anchor.FEET)))
                                                        .then(Commands.argument("facingAnchor", EntityAnchorArgument.anchor())
                                                                .executes(context -> NexusCommands.run(context, services,
                                                                        NODE_TP, "teleport.tp",
                                                                        source -> facingEntity(services, context, source,
                                                                                EntityAnchorArgument.getAnchor(context,
                                                                                        "facingAnchor"))))))))));
    }

    private static NexusCommands.Feedback facingEntity(NexusServices services,
            CommandContext<CommandSourceStack> context, CommandSourceStack source,
            EntityAnchorArgument.Anchor anchor) throws NexusCommands.Refused, CommandSyntaxException {
        Entity facing = EntityArgument.getEntity(context, "facingEntity");
        Vec3 look = anchor == EntityAnchorArgument.Anchor.EYES ? facing.getEyePosition() : facing.position();
        return toPosition(services, EntityArgument.getEntities(context, "targets"), source.getLevel(),
                Vec3Argument.getCoordinates(context, "location").getPosition(source), null, null, look);
    }

    /**
     * Moves entities to a position, optionally setting rotation or a facing target.
     *
     * @param services the registry
     * @param targets the entities to move
     * @param level the destination level
     * @param position where to
     * @param yaw explicit yaw, or null to keep the entity's own
     * @param pitch explicit pitch, or null to keep the entity's own
     * @param facing a point to look at afterwards, or null
     */
    private static NexusCommands.Feedback toPosition(NexusServices services, Collection<? extends Entity> targets,
            ServerLevel level, Vec3 position, Float yaw, Float pitch, Vec3 facing) throws NexusCommands.Refused {
        if (targets.isEmpty()) {
            throw new NexusCommands.Refused(services.messages().raw("error.no-targets"));
        }
        for (Entity target : targets) {
            target.teleportTo(level, position.x, position.y, position.z, EnumSet.noneOf(RelativeMovement.class),
                    yaw == null ? target.getYRot() : yaw, pitch == null ? target.getXRot() : pitch);
            if (facing != null) {
                target.lookAt(EntityAnchorArgument.Anchor.FEET, facing);
            }
        }
        String where = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", position.x, position.y, position.z);
        return NexusCommands.Feedback.of(services.messages().render("teleport.tp.position",
                        "count", String.valueOf(targets.size()), "position", where))
                .withTarget("location", where)
                .withAudit("targets", String.valueOf(targets.size()))
                .broadcast();
    }

    private static NexusCommands.Feedback toEntity(NexusServices services, Collection<? extends Entity> targets,
            Entity destination) throws NexusCommands.Refused {
        if (targets.isEmpty()) {
            throw new NexusCommands.Refused(services.messages().raw("error.no-targets"));
        }
        ServerLevel level = (ServerLevel) destination.level();
        for (Entity target : targets) {
            target.teleportTo(level, destination.getX(), destination.getY(), destination.getZ(),
                    EnumSet.noneOf(RelativeMovement.class), destination.getYRot(), destination.getXRot());
        }
        String who = targets.size() == 1 ? targets.iterator().next().getName().getString() : targets.size() + " entities";
        return NexusCommands.Feedback.of(services.messages().render("teleport.tp.success",
                        "target", who, "destination", destination.getName().getString()))
                .withTarget("player", destination.getStringUUID())
                .withAudit("targets", String.valueOf(targets.size()))
                .broadcast();
    }
}
