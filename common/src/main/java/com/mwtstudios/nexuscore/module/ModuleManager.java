package com.mwtstudios.nexuscore.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

/**
 * Registers modules, starts them in dependency order, and resolves their services (§7.3).
 *
 * <p>This replaces twelve hand-ordered constructor calls that existed <em>verbatim in all three
 * loader entry points</em>. That duplication was the actual problem: the dependency graph was
 * expressed as statement order in three separate files, and nothing verified that they matched, so
 * a change to service wiring could silently take effect on one loader and not the others — a class
 * of divergence no test in the suite compares.</p>
 *
 * <p><b>Ordering is derived, not written down.</b> Modules declare what they depend on and the
 * manager topologically sorts them. A cycle and a reference to an unregistered module are both
 * rejected at startup with the ids named, rather than surfacing later as a null service inside a
 * command handler.</p>
 *
 * <p>Ties are broken by registration order, so a graph with several valid orderings still starts
 * the same way every run. Startup order affects log output and the order documents are first
 * touched on disk, and a non-deterministic version of either would be miserable to debug.</p>
 */
public final class ModuleManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, NexusModule> registered = new LinkedHashMap<>();
    private final Set<String> disabled = new LinkedHashSet<>();
    private final List<NexusModule> started = new ArrayList<>();

    private ModuleContext context;

    /**
     * Registers a module. Order of registration only breaks ties in the dependency sort.
     *
     * @param module the module
     * @return this manager, for chaining
     * @throws ModuleException if the id is already registered, or the manager has already started
     */
    public ModuleManager register(NexusModule module) {
        if (context != null) {
            throw new ModuleException("cannot register '" + module.id() + "' after start()");
        }
        if (registered.containsKey(module.id())) {
            throw new ModuleException("duplicate module id '" + module.id() + "'");
        }
        registered.put(module.id(), module);
        return this;
    }

    /**
     * Marks a non-core module as not to be started.
     *
     * <p>Refusing to disable a core module is the point: safe mode exists to drop features, and a
     * "safe" mode that removed permissions or the audit log would be considerably less safe than
     * not starting at all.</p>
     *
     * <p><b>Nothing calls this in production yet.</b> M1's safe mode is scheduled for 1.0.5,
     * because disabling a module also requires the commands that use its service not to be
     * registered — otherwise the command tree points at something that never started. The
     * mechanism is here and tested; the caller is not written.</p>
     *
     * @param id the module to leave out
     * @return this manager, for chaining
     * @throws ModuleException if the id is unknown or names a core module
     */
    public ModuleManager disable(String id) {
        NexusModule module = registered.get(id);
        if (module == null) {
            throw new ModuleException("cannot disable unknown module '" + id + "'");
        }
        if (module.core()) {
            throw new ModuleException("'" + id + "' is a core module and cannot be disabled");
        }
        disabled.add(id);
        return this;
    }

    /**
     * Starts every enabled module in dependency order.
     *
     * @param initial the context to populate — data root, version, platform hooks
     * @return the populated context, holding every service that started
     * @throws ModuleException on a dependency cycle, an unknown dependency, or a disabled module
     *     that an enabled one depends on
     */
    public ModuleContext start(ModuleContext initial) {
        if (context != null) {
            throw new ModuleException("already started");
        }
        this.context = initial;

        for (NexusModule module : order()) {
            module.start(initial);
            started.add(module);
        }

        LOGGER.info("NexusCore started {} module(s){}", started.size(),
                disabled.isEmpty() ? "" : ", " + disabled.size() + " disabled: " + disabled);
        return initial;
    }

    /**
     * Stops started modules in reverse start order, so a module is never torn down before
     * something that depends on it.
     *
     * <p>A failure in one module's {@code stop()} is logged and the rest still run. Shutdown that
     * abandons half its cleanup because one service threw is worse than shutdown that completes
     * and reports what went wrong.</p>
     */
    public void stop() {
        for (int i = started.size() - 1; i >= 0; i--) {
            NexusModule module = started.get(i);
            try {
                module.stop();
            } catch (RuntimeException e) {
                LOGGER.error("module '{}' failed to stop; continuing with the rest", module.id(), e);
            }
        }
        started.clear();
    }

    /**
     * @param <T> service type
     * @param type the service type
     * @return the service
     * @throws ModuleException if no started module provides it
     */
    public <T> T service(Class<T> type) {
        if (context == null) {
            throw new ModuleException("no modules have started; " + type.getSimpleName() + " is not available");
        }
        return context.service(type);
    }

    /** @return ids of modules that started, in start order */
    public List<String> startedIds() {
        return started.stream().map(NexusModule::id).toList();
    }

    /** @return ids that were registered but deliberately left out */
    public Set<String> disabledIds() {
        return Set.copyOf(disabled);
    }

    /**
     * Topologically sorts the enabled modules.
     *
     * <p>Depth-first with an explicit "visiting" set, which is what distinguishes a cycle from a
     * diamond — a node seen twice is only an error if it is still on the current path.</p>
     *
     * @return the modules in an order where every dependency precedes its dependent
     */
    private Collection<NexusModule> order() {
        List<NexusModule> sorted = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();

        for (NexusModule module : registered.values()) {
            if (!disabled.contains(module.id())) {
                visit(module, sorted, done, visiting);
            }
        }
        return sorted;
    }

    private void visit(NexusModule module, List<NexusModule> sorted, Set<String> done, Set<String> visiting) {
        if (done.contains(module.id())) {
            return;
        }
        if (!visiting.add(module.id())) {
            throw new ModuleException("module dependency cycle involving '" + module.id()
                    + "'; the path was " + String.join(" -> ", visiting) + " -> " + module.id());
        }

        for (String dependency : module.dependsOn()) {
            NexusModule next = registered.get(dependency);
            if (next == null) {
                throw new ModuleException("module '" + module.id() + "' depends on '" + dependency
                        + "', which is not registered");
            }
            if (disabled.contains(dependency)) {
                throw new ModuleException("module '" + module.id() + "' depends on '" + dependency
                        + "', which is disabled. Disable '" + module.id() + "' too, or leave '"
                        + dependency + "' enabled.");
            }
            visit(next, sorted, done, visiting);
        }

        visiting.remove(module.id());
        done.add(module.id());
        sorted.add(module);
    }
}
