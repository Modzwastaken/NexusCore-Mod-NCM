package com.mwtstudios.nexuscore.gui;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.message.MessageService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;

/**
 * A read-only chest menu used as the NexusCore admin panel.
 *
 * <p><b>Why a chest menu and not a custom screen.</b> §3.1 makes v0.1 server-only: players
 * join with unmodified vanilla clients. A custom {@code Screen} cannot be opened on a client
 * that does not have the mod, so a custom-screen admin panel would be invisible to every
 * user of this release. §6.1 anticipates exactly this and permits "a vanilla-container menu
 * where one genuinely fits". A chest grid fits: it renders on any vanilla client, needs no
 * handshake, and needs no client download.</p>
 *
 * <p><b>Read-only, provably.</b> {@link #clicked} never delegates to {@code super}, and
 * {@link #quickMoveStack} always returns empty. There is therefore no code path by which an
 * item can enter or leave this container — the panel cannot be used as free storage, and a
 * click on a "ban" icon cannot pick the icon up. Clicks are routed to server-side handlers
 * instead, and every handler re-checks permission (§15: UI visibility is not security).</p>
 */
public final class AdminMenu extends ChestMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Slots per row in a chest menu. */
    public static final int COLUMNS = 9;

    private final Map<Integer, Consumer<ServerPlayer>> actions;
    private final int panelSize;

    /**
     * @param containerId assigned by the server when the menu opens
     * @param playerInventory the viewing player's inventory
     * @param container the backing container holding the panel's display items
     * @param rows how many rows the panel occupies, 1 to 6
     * @param actions slot index to handler; slots absent from this map do nothing when clicked
     */
    public AdminMenu(int containerId, Inventory playerInventory, Container container, int rows,
            Map<Integer, Consumer<ServerPlayer>> actions) {
        super(typeFor(rows), containerId, playerInventory, container, rows);
        this.actions = Map.copyOf(actions);
        this.panelSize = rows * COLUMNS;
    }

    /**
     * @param rows 1 to 6
     * @return the vanilla menu type for that many rows
     */
    public static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("a chest menu has 1 to 6 rows, not " + rows);
        };
    }

    /**
     * Handles a click without ever moving an item.
     *
     * <p>Clicks in the panel run their handler. Clicks anywhere else — including the player's
     * own inventory — are swallowed, because shift-clicking from the player inventory is the
     * one path that could otherwise push an item into the panel.</p>
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer && slotId >= 0 && slotId < panelSize) {
            Consumer<ServerPlayer> action = actions.get(slotId);
            if (action != null) {
                runGuarded(serverPlayer, action);
            }
        }
        // Deliberately no super call, for any slot or click type. Re-sync so the client's
        // optimistic prediction of the click is corrected immediately.
        sendAllDataToRemote();
    }

    /**
     * Runs a tile's action so that nothing escapes into vanilla's container handling.
     *
     * <p>§12.2's "no stack trace ever reaches a player" was true of the command pipeline and
     * <b>not</b> of this path: a click handler ran with no {@code try} anywhere above it, so any
     * runtime failure inside one propagated out of {@code clicked} into
     * {@code ServerGamePacketListenerImpl.handleContainerClick}. Safe mode made that reachable in
     * an ordinary way — a handler calling a module that was never started throws
     * {@code ModuleException} — but the hole predates safe mode and applies to any handler bug.</p>
     *
     * <p>The container is closed rather than left open, because a panel whose state was built
     * halfway through a failure is showing the player something untrue.</p>
     */
    private void runGuarded(ServerPlayer viewer, Consumer<ServerPlayer> action) {
        try {
            action.accept(viewer);
        } catch (RuntimeException e) {
            String correlationId = UUID.randomUUID().toString();
            LOGGER.error("NexusCore admin GUI action failed for {} (correlation {})",
                    viewer.getGameProfile().getName(), correlationId, e);
            viewer.closeContainer();
            viewer.sendSystemMessage(Component.literal(MessageService.colourise(
                    "&c&lNEXUS &c\u00bb &7That panel action failed. Reference: &f" + correlationId)));
        }
    }

    /** Shift-click moves nothing. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** The panel has no block behind it, so it stays valid until the player closes it. */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
