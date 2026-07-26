package com.mwtstudios.nexuscore.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Grants and revokes creative flight, which is the one thing NexusCore does that genuinely
 * differs between mod loaders.
 *
 * <p>NeoForge deprecated {@code Abilities.mayfly} and replaced it with a
 * {@code creative_flight} attribute, precisely because direct writes to the field fight with
 * every other mod that grants flight — the last writer wins and the loser's grant silently
 * disappears. Forge and Fabric have no such attribute, so on those loaders the field is the
 * only mechanism available.</p>
 *
 * <p>This is therefore not a cosmetic package-name difference that a shim can paper over. The
 * two implementations have <b>different compatibility characteristics</b>, and pretending
 * otherwise would mean quietly shipping worse behaviour on one platform. The difference is
 * named here, implemented per platform, and documented for operators.</p>
 */
public interface FlightController {

    /**
     * Grants or revokes NexusCore's flight for a player.
     *
     * @param player the target
     * @param enabled true to allow flight
     */
    void setFlight(ServerPlayer player, boolean enabled);

    /**
     * @param player the target
     * @return true when NexusCore specifically is what is granting flight, as opposed to
     *     creative mode or another mod
     */
    boolean hasNexusFlight(ServerPlayer player);

    /**
     * @param player the target
     * @return true when the player may fly, from any source
     */
    boolean canFly(ServerPlayer player);

    /**
     * @return a short description of how this implementation grants flight, for
     *     {@code /nexus system status} — so an operator can see which mechanism is in use
     *     without reading the source
     */
    String describe();
}
