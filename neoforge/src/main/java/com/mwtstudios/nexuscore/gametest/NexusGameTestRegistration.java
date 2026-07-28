package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Registers the shared GameTests with NeoForge.
 *
 * <p>The tests themselves live in {@code common/} so all three loaders run the same assertions.
 * Only discovery differs per loader, and this is NeoForge's half of it — three lines rather than a
 * second copy of twelve tests.</p>
 *
 * <p><b>Why registration is explicit rather than annotation-driven.</b> NeoForge finds tests by
 * {@code @GameTestHolder}, which is a NeoForge annotation a shared class cannot carry.
 * {@link RegisterGameTestsEvent} takes the class directly and reaches the same vanilla registry, so
 * the shared classes stay free of any loader's imports.</p>
 *
 * <p><b>Why the classes are registered and not their methods.</b> Vanilla's
 * {@code GameTestRegistry.register(Class)} enumerates {@code getDeclaredMethods()} — <em>declared</em>,
 * not inherited. A subclass of a shared holder would therefore register nothing and report a green
 * run of zero tests, which is why the tests are registered where they are declared.</p>
 */
@EventBusSubscriber(modid = NexusServices.MOD_ID)
public final class NexusGameTestRegistration {

    private NexusGameTestRegistration() {
        // Event subscriber holder.
    }

    /**
     * @param event NeoForge's registration hook
     */
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(NexusGameTests.class);
        event.register(NexusWorldGameTests.class);
    }
}
