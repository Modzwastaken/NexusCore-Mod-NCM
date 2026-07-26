package com.mwtstudios.nexuscore.moderation;

import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.message.TimeText;

import net.minecraft.network.chat.Component;

/**
 * Builds the punishment screens a player actually sees.
 *
 * <p>This exists because the ban screen was previously assembled in four places — the ban
 * command, and the login-enforcement handler of each of the three loader entry points. Four
 * assemblies of the same screen drift apart one placeholder at a time, and a banned player on
 * Fabric would eventually see a different screen from the same ban on NeoForge. Every call
 * site now goes through here, so the screen is defined once and the entry points cannot
 * disagree about it.</p>
 */
public final class PunishmentMessages {

    private PunishmentMessages() {
        // Utility holder.
    }

    /**
     * Builds the full-screen disconnect message for a ban.
     *
     * @param messages the catalogue
     * @param settings current settings, for the appeal line
     * @param ban the active ban record
     * @param nowEpochMillis the current time, for the countdown
     * @return the component shown on the disconnect screen
     */
    public static Component banScreen(MessageService messages, NexusSettings settings,
            ModerationService.Record ban, long nowEpochMillis) {
        if (ban.permanent()) {
            return messages.render("moderation.ban.disconnect",
                    "player", ban.targetName(),
                    "date", TimeText.date(ban.issuedAt()),
                    "reason", ban.reason(),
                    "appeal", settings.banAppealMessage);
        }
        return messages.render("moderation.tempban.disconnect",
                "player", ban.targetName(),
                "date", TimeText.date(ban.issuedAt()),
                "reason", ban.reason(),
                "until", TimeText.date(ban.expiresAt()),
                "remaining", TimeText.remaining(ban.expiresAt(), nowEpochMillis),
                "appeal", settings.banAppealMessage);
    }

    /**
     * Builds the chat notice shown to a muted player who tried to speak.
     *
     * @param messages the catalogue
     * @param mute the active mute record
     * @param nowEpochMillis the current time, for the countdown
     * @return the component sent in chat
     */
    public static Component muteNotice(MessageService messages, ModerationService.Record mute, long nowEpochMillis) {
        return messages.render("moderation.mute.blocked",
                "reason", mute.reason(),
                "remaining", TimeText.remaining(mute.expiresAt(), nowEpochMillis));
    }
}
