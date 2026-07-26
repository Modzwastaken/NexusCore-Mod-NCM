package com.mwtstudios.nexuscore.message;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.config.NexusSettings;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

import org.slf4j.Logger;

/**
 * Replaces the vanilla death broadcast with NexusCore's styled one.
 *
 * <p>Vanilla sends the death message from inside {@code ServerPlayer.die}, gated only by the
 * {@code showDeathMessages} game rule. No loader exposes an event that can rewrite or cancel
 * that broadcast, so the rule is the single supported lever: NexusCore turns it off and sends
 * its own message from the death event instead. The wording still comes from vanilla's combat
 * tracker, so every death cause, mob name and item name stays correct and translated — only
 * the presentation changes.</p>
 *
 * <p>Because this <b>writes a game rule into the operator's world</b>, it is announced at
 * startup with the exact command to undo it, and it only happens when
 * {@code styleDeathMessages} is enabled. Turning that setting off stops NexusCore sending its
 * own message, but does not put the rule back — NexusCore will not silently overwrite a value
 * the operator may have set deliberately in the meantime.</p>
 */
public final class DeathMessages {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathMessages() {
        // Utility holder.
    }

    /**
     * Suppresses the vanilla broadcast, once, at server start.
     *
     * @param server the running server
     * @param settings current settings
     */
    public static void takeOver(MinecraftServer server, NexusSettings settings) {
        if (!settings.styleDeathMessages) {
            return;
        }
        ServerLevel overworld = server.overworld();
        GameRules rules = overworld.getGameRules();
        if (!rules.getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return;
        }
        rules.getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(false, server);
        LOGGER.info("NexusCore styled death messages are on, so the vanilla showDeathMessages game rule "
                + "has been turned off to avoid sending each death twice. To go back to vanilla deaths, set "
                + "styleDeathMessages=false in nexuscore/config.json and run: /gamerule showDeathMessages true");
    }

    /**
     * Broadcasts the styled death message for a player.
     *
     * @param server the running server
     * @param messages the catalogue
     * @param settings current settings
     * @param player who died
     */
    public static void broadcast(MinecraftServer server, MessageService messages, NexusSettings settings,
            ServerPlayer player) {
        if (!settings.styleDeathMessages) {
            return;
        }
        // Vanilla's own wording, so causes, mob names and item names stay correct.
        String cause = player.getCombatTracker().getDeathMessage().getString();
        server.getPlayerList().broadcastSystemMessage(messages.render("death.message", "message", cause), false);
    }
}
