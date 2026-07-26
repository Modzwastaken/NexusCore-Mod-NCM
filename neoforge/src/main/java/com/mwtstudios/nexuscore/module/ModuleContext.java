package com.mwtstudios.nexuscore.module;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.platform.FlightController;

/**
 * What a module is given when it starts, and where it puts what it built.
 *
 * <p>Everything a module could legitimately need, and nothing else. In particular there is no
 * {@code MinecraftServer} here: no NexusCore service needs one to be constructed, and depending on
 * it is what forced the original bootstrap into {@code ServerAboutToStartEvent} — which fires
 * <em>after</em> {@code RegisterCommandsEvent} and produced a server with no commands registered.
 * Keeping the server out of this contract keeps that defect unreachable by construction.</p>
 *
 * <p>Not thread-safe, and not meant to be: {@link ModuleManager} starts modules one at a time on
 * the thread that called it.</p>
 */
public final class ModuleContext {

    private final Path dataRoot;
    private final String modVersion;
    private final FlightController flightController;
    private final Map<Class<?>, Object> services = new LinkedHashMap<>();

    private NexusSettings settings;

    /**
     * @param dataRoot the NexusCore data directory
     * @param modVersion this build's version, recorded into documents and audit records
     * @param flightController the loader's flight mechanism — the one genuine capability difference
     */
    public ModuleContext(Path dataRoot, String modVersion, FlightController flightController) {
        this.dataRoot = dataRoot;
        this.modVersion = modVersion;
        this.flightController = flightController;
    }

    /** @return the NexusCore data directory */
    public Path dataRoot() {
        return dataRoot;
    }

    /** @return this build's version */
    public String modVersion() {
        return modVersion;
    }

    /** @return the loader's flight mechanism */
    public FlightController flightController() {
        return flightController;
    }

    /**
     * Publishes a service so later modules can depend on it.
     *
     * @param <T> service type
     * @param type the type callers will look it up by
     * @param service the constructed service
     * @throws ModuleException if something is already registered under this type
     */
    public <T> void provide(Class<T> type, T service) {
        if (services.containsKey(type)) {
            throw new ModuleException("two modules both provide " + type.getSimpleName()
                    + "; a service type has exactly one owner");
        }
        services.put(type, service);
    }

    /**
     * Resolves a service published by an earlier module.
     *
     * @param <T> service type
     * @param type the service type
     * @return the service
     * @throws ModuleException if no started module provides it — which means either the dependency
     *     was not declared in {@link NexusModule#dependsOn()}, or the module providing it is
     *     disabled
     */
    public <T> T service(Class<T> type) {
        Object found = services.get(type);
        if (found == null) {
            throw new ModuleException(type.getSimpleName() + " is not available. Either the module "
                    + "needing it does not declare the dependency, or the module providing it did not start.");
        }
        return type.cast(found);
    }

    /**
     * The settings snapshot, available to every module after the configuration module has started.
     *
     * @return the current settings
     * @throws ModuleException if called before configuration has started
     */
    public NexusSettings settings() {
        if (settings == null) {
            throw new ModuleException("settings are not loaded yet; declare a dependency on the "
                    + "'configuration' module");
        }
        return settings;
    }

    /**
     * Records the loaded settings. Called by the configuration module only.
     *
     * @param loaded the settings just read from disk
     */
    public void settingsLoaded(NexusSettings loaded) {
        this.settings = loaded;
    }

    /** @return the service types published so far, in registration order */
    public Map<Class<?>, Object> provided() {
        return Map.copyOf(services);
    }
}
