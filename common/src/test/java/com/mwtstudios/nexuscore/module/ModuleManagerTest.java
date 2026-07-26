package com.mwtstudios.nexuscore.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the §7.3 module contract.
 *
 * <p>These use throwaway modules rather than the real ones. The behaviour worth pinning down is the
 * manager's — ordering, cycle detection, refusal to disable core modules — and driving that through
 * {@code JsonStore} and friends would make the tests depend on a data directory to assert something
 * about a topological sort.</p>
 */
class ModuleManagerTest {

    private static ModuleContext context() {
        return new ModuleContext(Path.of("unused"), "1.0.3", null);
    }

    /** A module that records the order it started in. */
    private static NexusModule recording(String id, boolean core, Set<String> dependsOn, List<String> log) {
        return probe(id, core, dependsOn, ctx -> log.add(id), () -> log.add("stop:" + id));
    }

    private static NexusModule probe(String id, boolean core, Set<String> dependsOn,
            Consumer<ModuleContext> onStart, Runnable onStop) {
        return new NexusModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean core() {
                return core;
            }

            @Override
            public Set<String> dependsOn() {
                return dependsOn;
            }

            @Override
            public void start(ModuleContext ctx) {
                onStart.accept(ctx);
            }

            @Override
            public void stop() {
                onStop.run();
            }
        };
    }

    @Test
    @DisplayName("a dependency always starts before the module that needs it")
    void dependenciesStartFirst() {
        List<String> log = new ArrayList<>();
        ModuleManager manager = new ModuleManager()
                // Registered in the WRONG order on purpose: if the manager were relying on
                // registration order rather than the declared graph, this test would fail.
                .register(recording("commands", true, Set.of("permissions"), log))
                .register(recording("permissions", true, Set.of("storage"), log))
                .register(recording("storage", true, Set.of(), log));

        manager.start(context());

        assertEquals(List.of("storage", "permissions", "commands"), log);
        assertEquals(List.of("storage", "permissions", "commands"), manager.startedIds());
    }

    @Test
    @DisplayName("ordering is deterministic when several orders are valid")
    void tiesBreakOnRegistrationOrder() {
        // Two independent leaves: nothing in the graph orders them relative to each other, so the
        // manager must fall back on registration order rather than whatever the set iterates as.
        for (int run = 0; run < 20; run++) {
            List<String> log = new ArrayList<>();
            new ModuleManager()
                    .register(recording("alpha", true, Set.of(), log))
                    .register(recording("beta", true, Set.of(), log))
                    .start(context());
            assertEquals(List.of("alpha", "beta"), log, "run " + run);
        }
    }

    @Test
    @DisplayName("a diamond starts the shared dependency exactly once")
    void diamondStartsSharedDependencyOnce() {
        List<String> log = new ArrayList<>();
        new ModuleManager()
                .register(recording("storage", true, Set.of(), log))
                .register(recording("left", true, Set.of("storage"), log))
                .register(recording("right", true, Set.of("storage"), log))
                .register(recording("top", true, Set.of("left", "right"), log))
                .start(context());

        assertEquals(1, log.stream().filter("storage"::equals).count());
        assertEquals("top", log.get(log.size() - 1));
    }

    @Test
    @DisplayName("a dependency cycle is refused, and the message names the path")
    void cycleRefused() {
        ModuleManager manager = new ModuleManager()
                .register(recording("a", true, Set.of("b"), new ArrayList<>()))
                .register(recording("b", true, Set.of("a"), new ArrayList<>()));

        ModuleException thrown = assertThrows(ModuleException.class, () -> manager.start(context()));
        assertTrue(thrown.getMessage().contains("cycle"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("a"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("b"), thrown.getMessage());
    }

    @Test
    @DisplayName("a module depending on itself is a cycle, not a no-op")
    void selfDependencyRefused() {
        ModuleManager manager = new ModuleManager()
                .register(recording("lonely", true, Set.of("lonely"), new ArrayList<>()));

        assertTrue(assertThrows(ModuleException.class, () -> manager.start(context()))
                .getMessage().contains("cycle"));
    }

    @Test
    @DisplayName("depending on a module that was never registered is refused")
    void unknownDependencyRefused() {
        ModuleManager manager = new ModuleManager()
                .register(recording("needy", true, Set.of("absent"), new ArrayList<>()));

        ModuleException thrown = assertThrows(ModuleException.class, () -> manager.start(context()));
        assertTrue(thrown.getMessage().contains("absent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not registered"), thrown.getMessage());
    }

    @Test
    @DisplayName("a duplicate module id is refused at registration")
    void duplicateIdRefused() {
        ModuleManager manager = new ModuleManager()
                .register(recording("same", true, Set.of(), new ArrayList<>()));

        assertTrue(assertThrows(ModuleException.class,
                () -> manager.register(recording("same", true, Set.of(), new ArrayList<>())))
                .getMessage().contains("duplicate"));
    }

    @Test
    @DisplayName("a core module cannot be disabled")
    void coreModuleCannotBeDisabled() {
        ModuleManager manager = new ModuleManager()
                .register(recording("permissions", true, Set.of(), new ArrayList<>()));

        assertTrue(assertThrows(ModuleException.class, () -> manager.disable("permissions"))
                .getMessage().contains("core module"));
    }

    @Test
    @DisplayName("a disabled optional module does not start")
    void disabledOptionalModuleIsSkipped() {
        List<String> log = new ArrayList<>();
        ModuleManager manager = new ModuleManager()
                .register(recording("storage", true, Set.of(), log))
                .register(recording("teleport", false, Set.of("storage"), log));

        manager.disable("teleport").start(context());

        assertEquals(List.of("storage"), log);
        assertEquals(Set.of("teleport"), manager.disabledIds());
    }

    @Test
    @DisplayName("an enabled module depending on a disabled one is refused, not silently broken")
    void enabledDependingOnDisabledRefused() {
        ModuleManager manager = new ModuleManager()
                .register(recording("teleport", false, Set.of(), new ArrayList<>()))
                .register(recording("teleport-commands", true, Set.of("teleport"), new ArrayList<>()));

        ModuleException thrown = assertThrows(ModuleException.class,
                () -> manager.disable("teleport").start(context()));
        assertTrue(thrown.getMessage().contains("disabled"), thrown.getMessage());
    }

    @Test
    @DisplayName("disabling an unknown module is refused rather than ignored")
    void disablingUnknownModuleRefused() {
        assertTrue(assertThrows(ModuleException.class, () -> new ModuleManager().disable("ghost"))
                .getMessage().contains("unknown"));
    }

    @Test
    @DisplayName("modules stop in reverse start order")
    void stopsInReverseOrder() {
        List<String> log = new ArrayList<>();
        ModuleManager manager = new ModuleManager()
                .register(recording("storage", true, Set.of(), log))
                .register(recording("permissions", true, Set.of("storage"), log));

        manager.start(context());
        log.clear();
        manager.stop();

        assertEquals(List.of("stop:permissions", "stop:storage"), log);
    }

    @Test
    @DisplayName("one module failing to stop does not abandon the rest")
    void stopContinuesAfterFailure() {
        List<String> log = new ArrayList<>();
        ModuleManager manager = new ModuleManager()
                .register(probe("storage", true, Set.of(), ctx -> { }, () -> log.add("stop:storage")))
                .register(probe("broken", true, Set.of("storage"), ctx -> { }, () -> {
                    throw new IllegalStateException("deliberate");
                }));

        manager.start(context());
        manager.stop();

        // 'broken' is stopped first and throws; 'storage' must still be stopped.
        assertEquals(List.of("stop:storage"), log);
    }

    @Test
    @DisplayName("a service published by one module resolves in a later one, and from the manager")
    void servicesResolve() {
        String published = "the-service";
        ModuleManager manager = new ModuleManager()
                .register(probe("provider", true, Set.of(),
                        ctx -> ctx.provide(String.class, published), () -> { }))
                .register(probe("consumer", true, Set.of("provider"),
                        ctx -> assertSame(published, ctx.service(String.class)), () -> { }));

        manager.start(context());

        assertSame(published, manager.service(String.class));
    }

    @Test
    @DisplayName("resolving a service nothing provides fails with a named cause, not null")
    void unknownServiceThrows() {
        ModuleManager manager = new ModuleManager();
        manager.start(context());

        // Returning null here is what would surface later as an NPE inside a command handler.
        assertTrue(assertThrows(ModuleException.class, () -> manager.service(Integer.class))
                .getMessage().contains("Integer"));
    }

    @Test
    @DisplayName("two modules providing the same service type is refused")
    void duplicateServiceTypeRefused() {
        ModuleManager manager = new ModuleManager()
                .register(probe("first", true, Set.of(), ctx -> ctx.provide(String.class, "a"), () -> { }))
                .register(probe("second", true, Set.of("first"), ctx -> ctx.provide(String.class, "b"), () -> { }));

        assertTrue(assertThrows(ModuleException.class, () -> manager.start(context()))
                .getMessage().contains("exactly one owner"));
    }

    @Test
    @DisplayName("reading settings before configuration starts fails with a usable message")
    void settingsBeforeConfigurationThrows() {
        ModuleException thrown = assertThrows(ModuleException.class, () -> context().settings());
        assertTrue(thrown.getMessage().contains("configuration"), thrown.getMessage());
    }

    @Test
    @DisplayName("registering after start is refused")
    void registerAfterStartRefused() {
        ModuleManager manager = new ModuleManager();
        manager.start(context());

        assertTrue(assertThrows(ModuleException.class,
                () -> manager.register(recording("late", true, Set.of(), new ArrayList<>())))
                .getMessage().contains("after start"));
    }

    @Test
    @DisplayName("starting twice is refused")
    void doubleStartRefused() {
        ModuleManager manager = new ModuleManager();
        manager.start(context());

        assertTrue(assertThrows(ModuleException.class, () -> manager.start(context()))
                .getMessage().contains("already started"));
    }
}
