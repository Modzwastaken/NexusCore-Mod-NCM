package com.mwtstudios.nexuscore.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Flight granted by writing {@code Abilities.mayfly} directly.
 *
 * <p>Shared by the Fabric and Forge builds, because neither loader has an equivalent of
 * NeoForge's {@code creative_flight} attribute — verified against Forge 52.1.16, whose
 * {@code ForgeMod} registers no such attribute.</p>
 *
 * <p>This is the weaker of the two mechanisms NexusCore uses, and the weakness is real rather
 * than cosmetic. The ability is a single boolean, so the last mod to write it wins and any
 * other mod's grant is silently lost. NeoForge's attribute modifiers are additive and keyed
 * per-mod, so there each mod can revoke only its own grant.</p>
 *
 * <p>The one thing that can be done safely here is done: flight is never revoked from a player
 * whose game mode grants it, because that flag is not NexusCore's to clear and the game mode
 * would immediately reinstate it anyway — which would look to an operator like a failed
 * command.</p>
 */
public final class MayflyFlightController implements FlightController {

    @Override
    public void setFlight(ServerPlayer player, boolean enabled) {
        if (!enabled && (player.isCreative() || player.isSpectator())) {
            return;
        }
        player.getAbilities().mayfly = enabled;
        if (!enabled) {
            player.getAbilities().flying = false;
        }
        player.onUpdateAbilities();
    }

    @Override
    public boolean hasNexusFlight(ServerPlayer player) {
        // With no attribute there is no way to tell NexusCore's grant from another mod's, so
        // "has flight that did not come from a game mode" is the closest honest answer.
        return player.getAbilities().mayfly && !player.isCreative() && !player.isSpectator();
    }

    @Override
    public boolean canFly(ServerPlayer player) {
        return player.getAbilities().mayfly;
    }

    @Override
    public String describe() {
        return "Abilities.mayfly (single flag — may conflict with other flight mods)";
    }
}
