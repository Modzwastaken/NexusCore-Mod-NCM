package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the shared GameTests with Forge.
 *
 * <p>Forge's {@link RegisterGameTestsEvent} is a mod-bus event with the same {@code register(Class)}
 * shape NeoForge uses, so this is the same three lines against a different import. The tests
 * themselves are shared; only discovery is per loader.</p>
 *
 * <p>Forge's implementation enumerates {@code getDeclaredMethods()} exactly as vanilla and NeoForge
 * do — checked in {@code forge-1.21.1-52.1.16-sources.jar} rather than assumed from the others —
 * which is why the shared classes are registered directly and never subclassed.</p>
 */
@Mod.EventBusSubscriber(modid = NexusServices.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NexusGameTestRegistration {

    private NexusGameTestRegistration() {
        // Event subscriber holder.
    }

    /**
     * @param event Forge's registration hook
     */
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        // The HOLDERS, not the shared classes. Forge also discovers @GameTestHolder classes by
        // annotation scan, so this event registration is belt-and-braces — the methods land in a
        // HashSet, so the duplicate is deduplicated, and the explicit path keeps this loader's
        // wiring shaped like NeoForge's. The shared classes must NOT be registered here: their
        // test names carry no "nexuscore." prefix, and Forge's enabledGameTestNamespaces filter
        // matches names, so they are dropped — a green run of zero tests.
        event.register(NexusGameTestsHolder.class);
        event.register(NexusWorldGameTestsHolder.class);
    }
}
