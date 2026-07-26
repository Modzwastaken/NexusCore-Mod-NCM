package com.mwtstudios.nexuscore.module;

import java.util.Set;

/**
 * One unit of NexusCore functionality, per §7.3.
 *
 * <p>A module owns exactly one service. It declares what it is called, whether the mod can run
 * without it, and which other modules must be started first; {@link ModuleManager} does the
 * ordering. Before this contract existed, services were constructed by hand in each loader's
 * entry point, which meant the dependency graph was implied by statement order in three separate
 * files and nothing checked that they agreed.</p>
 *
 * <p><b>Modules are started once and are not restartable.</b> Reload is a separate concern:
 * {@code ConfigurationService.reload()} re-applies settings to already-running services, which is
 * transactional and does not tear anything down. A module contract that also tried to model
 * restart would be modelling something the mod does not do.</p>
 */
public interface NexusModule {

    /**
     * The module's identity, used in dependency declarations, log lines and error messages.
     *
     * <p>Lower-case, stable, and not derived from the class name — a rename of the class must not
     * silently change what other modules depend on.</p>
     *
     * @return the module id, for example {@code permissions}
     */
    String id();

    /**
     * Whether NexusCore is unusable without this module.
     *
     * <p>Core modules are the ones every other part assumes: storage, configuration, the message
     * catalogue, identity, audit, permissions, and the two security controls (rate limiting and
     * confirmation). A non-core module is a feature — teleporting, player utilities, moderation,
     * the admin GUI — that M1's safe mode may leave out.</p>
     *
     * <p>Defaults to {@code true}, deliberately: a module that has not thought about whether it is
     * optional is treated as required, which fails safe.</p>
     *
     * @return true when this module must always start
     */
    default boolean core() {
        return true;
    }

    /**
     * Ids of modules that must be started before this one.
     *
     * <p>Declared rather than inferred. {@link ModuleManager} rejects a cycle and a reference to a
     * module that was never registered, so a wiring mistake fails at startup with a named cause
     * instead of surfacing later as a null service.</p>
     *
     * @return the ids this module needs, empty when it needs none
     */
    default Set<String> dependsOn() {
        return Set.of();
    }

    /**
     * Builds this module's service and registers it on the context.
     *
     * <p>Every module named in {@link #dependsOn()} has already started, so
     * {@link ModuleContext#service(Class)} will resolve them.</p>
     *
     * @param context the data root, settings, version, platform hooks, and already-started services
     */
    void start(ModuleContext context);

    /**
     * Releases anything this module holds. Called in reverse start order.
     *
     * <p>Most modules have nothing to do — their state is either durable on disk already or is
     * per-session and dies with the process. Overriding this is the exception.</p>
     */
    default void stop() {
        // Nothing by default. Most services hold no resource that outlives the process.
    }
}
