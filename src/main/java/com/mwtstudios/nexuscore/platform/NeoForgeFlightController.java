package com.mwtstudios.nexuscore.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * NeoForge flight, granted through the {@code creative_flight} attribute.
 *
 * <p>This is the better of the two mechanisms: an attribute modifier is additive and keyed by
 * {@code nexuscore:flight}, so revoking NexusCore's grant leaves grants from other mods
 * intact and vice versa.</p>
 */
public final class NeoForgeFlightController implements FlightController {

    /** Identifies NexusCore's own grant, so it can be revoked without touching others'. */
    private static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("nexuscore", "flight");

    @Override
    public void setFlight(ServerPlayer player, boolean enabled) {
        AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight == null) {
            throw new IllegalStateException("the NeoForge creative_flight attribute is not present on this player; "
                    + "NexusCore cannot grant flight on this platform build");
        }
        flight.removeModifier(MODIFIER_ID);
        if (enabled) {
            flight.addPermanentModifier(new AttributeModifier(MODIFIER_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        } else if (!player.isCreative() && !player.isSpectator()) {
            player.getAbilities().flying = false;
        }
        player.onUpdateAbilities();
    }

    @Override
    public boolean hasNexusFlight(ServerPlayer player) {
        AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        return flight != null && flight.getModifier(MODIFIER_ID) != null;
    }

    @Override
    public boolean canFly(ServerPlayer player) {
        return player.mayFly();
    }

    @Override
    public String describe() {
        return "NeoForge creative_flight attribute (coexists with other mods)";
    }
}
