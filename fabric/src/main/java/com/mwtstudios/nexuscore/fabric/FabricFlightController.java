package com.mwtstudios.nexuscore.fabric;

import com.mwtstudios.nexuscore.platform.FlightController;

import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric flight, granted by writing {@code Abilities.mayfly} directly.
 *
 * <p>This is the weaker of the two mechanisms NexusCore uses, and the difference is real
 * rather than cosmetic. NeoForge has a {@code creative_flight} attribute whose modifiers are
 * additive, so several mods can grant flight independently and each can revoke only its own.
 * Fabric has no such attribute: the ability is a single boolean, so the last mod to write it
 * wins and any other mod's grant is silently lost.</p>
 *
 * <p>NexusCore therefore does the one thing it safely can — it refuses to revoke flight from
 * a player who is in creative or spectator mode, where the flag is not NexusCore's to clear.
 * Beyond that, an operator running another flight-granting mod on Fabric should expect the
 * two to interfere, and the admin documentation says so.</p>
 */
public final class FabricFlightController implements FlightController {

    @Override
    public void setFlight(ServerPlayer player, boolean enabled) {
        if (!enabled && (player.isCreative() || player.isSpectator())) {
            // Their game mode grants flight, not NexusCore. Clearing the flag here would be
            // overwritten by the game mode anyway, and would look like a failed command.
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
        // Without an attribute there is no way to distinguish NexusCore's grant from anyone
        // else's, so "does this player have flight we did not get from a game mode" is the
        // closest honest answer available on this platform.
        return player.getAbilities().mayfly && !player.isCreative() && !player.isSpectator();
    }

    @Override
    public boolean canFly(ServerPlayer player) {
        return player.getAbilities().mayfly;
    }

    @Override
    public String describe() {
        return "Fabric Abilities.mayfly (single flag — may conflict with other flight mods)";
    }
}
