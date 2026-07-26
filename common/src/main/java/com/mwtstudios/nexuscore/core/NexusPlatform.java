package com.mwtstudios.nexuscore.core;

import java.nio.file.Path;

import com.mwtstudios.nexuscore.platform.FlightController;

/**
 * The only two things that genuinely differ between the three loaders.
 *
 * <p>ADR-0008 measured it: of 24 source files, 22 touch no loader API at all. The differences that
 * survive are where the game directory comes from — {@code FMLPaths.GAMEDIR} on NeoForge and Forge,
 * {@code FabricLoader.getGameDir()} on Fabric — and how flight is granted, which is the one real
 * capability gap between the loaders.</p>
 *
 * <p>Putting exactly those behind an interface is what lets {@link NexusBootstrap} be shared. The
 * alternative, which is what existed before, was the same twelve-line wiring block copied into
 * three entry points.</p>
 */
public interface NexusPlatform {

    /**
     * @return the loader's name, for the startup log line. Previously NeoForge logged
     *     {@code NexusCore ready} while the other two logged {@code NexusCore ready on Fabric} and
     *     {@code on Forge}; naming it here makes the line the same shape everywhere
     */
    String name();

    /**
     * @return the NexusCore data directory, normally {@code <game dir>/nexuscore}
     */
    Path dataRoot();

    /**
     * @return this loader's flight mechanism. NeoForge grants an additive, per-mod
     *     {@code creative_flight} attribute; Fabric and Forge write a single shared
     *     {@code Abilities.mayfly} flag where the last writer wins
     */
    FlightController flightController();

    /**
     * @return a short description of the flight mechanism, logged at startup so an operator can see
     *     which of the two behaviours applies on their server without reading ADR-0008
     */
    String flightDescription();
}
