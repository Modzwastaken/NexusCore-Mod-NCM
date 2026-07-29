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
/* bus = MOD is load-bearing, not decoration. RegisterGameTestsEvent implements
   IModBusEvent, and @EventBusSubscriber defaults to the GAME bus — so without this the
   subscriber registers on a bus that never fires this event, no tests are registered,
   and the run exits 0 having executed NOTHING. That is precisely the green-run-of-zero
   this move was designed to avoid, and it shipped anyway; tools/verify-gametests.sh
   caught it on its first run. */
@EventBusSubscriber(modid = NexusServices.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NexusGameTestRegistration {

    private NexusGameTestRegistration() {
        // Event subscriber holder.
    }

    /**
     * @param event NeoForge's registration hook
     */
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        // The HOLDERS, not the shared classes: the shared classes cannot carry @GameTestHolder
        // (a NeoForge annotation), so their methods derive namespace "minecraft" and the
        // enabled-namespace filter drops all of them — a green run of zero tests.
        event.register(NexusGameTestsHolder.class);
        event.register(NexusWorldGameTestsHolder.class);
    }
}
