package com.mwtstudios.nexuscore;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.slf4j.Logger;

/**
 * Entry point for the NexusCore Administration Framework.
 *
 * <p>Per §7.1 of the master build prompt this class owns bootstrap only: it captures the
 * mod's own metadata and wires the listeners it needs. Business logic belongs in services
 * and modules, which are introduced from M1 onward.</p>
 */
@Mod(NexusCore.MOD_ID)
public final class NexusCore {

    /** Mod id. Must match {@code mod_id} in gradle.properties and the id in neoforge.mods.toml. */
    public static final String MOD_ID = "nexuscore";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String displayName;
    private final String version;

    /**
     * Constructed by FML during mod loading. FML supplies the parameters by type.
     *
     * @param modEventBus this mod's event bus, used for registration and lifecycle events
     * @param modContainer this mod's container, the authoritative source of its own metadata
     */
    public NexusCore(IEventBus modEventBus, ModContainer modContainer) {
        this.displayName = modContainer.getModInfo().getDisplayName();
        this.version = modContainer.getModInfo().getVersion().toString();

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("{} {} bootstrapped on the {} event bus", displayName, version, modEventBus.getClass().getSimpleName());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        NexusVersionCommand.register(event.getDispatcher(), displayName, version);
    }
}
