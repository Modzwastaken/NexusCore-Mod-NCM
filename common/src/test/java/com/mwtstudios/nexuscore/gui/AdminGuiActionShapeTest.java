package com.mwtstudios.nexuscore.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape that makes the admin panel's stale-target defect unwritable.
 *
 * <p>A panel is drawn once and then sits open. The tiles used to close over the
 * {@link ServerPlayer} resolved while drawing, so if the target logged out before the staff
 * member clicked, the action ran against a detached session object — doing nothing — while the
 * audit log recorded it as {@code allowed}. A trail that says a heal happened when it did not is
 * worse than one that says nothing.</p>
 *
 * <p>Two things fixed that, and this pins both <em>structurally</em>, which is the strongest
 * verification available without a running server: an action body now <b>receives</b> its target
 * as a parameter, and the method that draws the tiles takes only values, so there is no session
 * object in scope for a body to capture by mistake. Reverting either shape fails to compile or
 * fails here — a behavioural test of the refusal path itself needs the command/GUI harness that
 * arrives with 1.1.2's GameTests, and until then that half is recorded as untested rather than
 * implied.</p>
 */
class AdminGuiActionShapeTest {

    @Test
    @DisplayName("regression: an action body receives its target rather than capturing one")
    void actionBodyTakesTheTargetAsAParameter() throws Exception {
        Class<?> body = Class.forName("com.mwtstudios.nexuscore.gui.AdminGuiService$ActionBody");
        assertTrue(body.isInterface(), "ActionBody should stay a functional interface");

        Method[] declared = body.getDeclaredMethods();
        assertEquals(1, declared.length, "a functional interface has exactly one abstract method");

        Method run = declared[0];
        assertArrayEquals(new Class<?>[] {ServerPlayer.class}, run.getParameterTypes(),
                "the body must RECEIVE the target resolved at click time. A no-argument body is the "
                        + "original defect: the only way to reach a target is then to close over one "
                        + "captured while the panel was drawn, which may since have logged out.");
        assertEquals(String.class, run.getReturnType(), "the body returns its audit summary");
    }

    @Test
    @DisplayName("regression: the tile builder has no ServerPlayer in scope to capture")
    void tileBuilderTakesValuesNotASession() throws Exception {
        Method builder = Arrays.stream(AdminGuiService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("buildActionTiles"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "buildActionTiles is where the panel is drawn from values only; if it has been "
                                + "inlined back into openPlayerActions, a resolved target is in scope again"));

        long sessions = Arrays.stream(builder.getParameterTypes())
                .filter(ServerPlayer.class::equals)
                .count();
        assertEquals(1, sessions,
                "exactly one ServerPlayer — the VIEWER, who is by definition present because they "
                        + "just clicked. The target must arrive as a UUID and a snapshot, so no tile "
                        + "body can reach a session object that may have ended.");
        assertTrue(Arrays.asList(builder.getParameterTypes()).contains(UUID.class),
                "the target is identified by UUID, which is what act() re-resolves at click time");
    }
}
