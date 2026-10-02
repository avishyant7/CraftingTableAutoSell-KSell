package com.ksell;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class KSellClient implements ClientModInitializer {
    private final Seller seller = new Seller();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("ksell")
                    .then(ClientCommands.literal("on")
                            .then(ClientCommands.argument("price", LongArgumentType.longArg(1))
                                    .executes(context -> {
                                        seller.start(LongArgumentType.getLong(context, "price"));
                                        return 1;
                                    }))));

            dispatcher.register(ClientCommands.literal("ksel")
                    .then(ClientCommands.literal("off")
                            .executes(context -> {
                                seller.stop(true);
                                return 1;
                            })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(seller::tick);
    }

    private static final class Seller {
        private static final int SELL_DELAY_TICKS = 30;

        private boolean enabled;
        private long price;
        private int dedicatedHotbarSlot;
        private int cooldown;
        private int pendingRestoreSlot = -1;
        private int pendingRestoreScreenSlot = -1;
        private boolean waitingForSaleSync;
        private boolean operationPending;

        void start(long newPrice) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }

            enabled = true;
            price = newPrice;
            dedicatedHotbarSlot = client.player.getInventory().getSelectedSlot();
            cooldown = 0;
            pendingRestoreSlot = -1;
            pendingRestoreScreenSlot = -1;
            waitingForSaleSync = false;
            operationPending = false;

            client.player.sendSystemMessage(
                    Component.literal("KSell: ON, selling Respawn Anchors for $" + newPrice + " each.")
            );
        }

        void stop(boolean announce) {
            enabled = false;
            cooldown = 0;
            waitingForSaleSync = false;
            operationPending = false;
            pendingRestoreSlot = -1;
            pendingRestoreScreenSlot = -1;

            if (announce) {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("KSell: OFF"));
                }
            }
        }

        void tick(Minecraft client) {
            if (!enabled || client.player == null || client.level == null) {
                return;
            }

            if (client.gui.screen() != null) {
                return;
            }

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            LocalPlayer player = client.player;
            Inventory inventory = player.getInventory();

            if (inventory.getSelectedSlot() != dedicatedHotbarSlot) {
                inventory.setSelectedSlot(dedicatedHotbarSlot);
                return;
            }

            int dedicatedInventorySlot = dedicatedHotbarSlot;
            ItemStack dedicatedStack = inventory.getItem(dedicatedInventorySlot);

            if (operationPending) {
                if (isExactlyOneAnchor(dedicatedStack)) {
                    operationPending = false;
                    sell(client);
                }
                return;
            }

            if (waitingForSaleSync) {
                if (!dedicatedStack.isEmpty()) {
                    return;
                }

                if (pendingRestoreSlot >= 0) {
                    restoreOldDedicatedItem(client);
                }

                waitingForSaleSync = false;
                cooldown = SELL_DELAY_TICKS;
                return;
            }

            if (isExactlyOneAnchor(dedicatedStack)) {
                sell(client);
                return;
            }

            int source = findAnchorSlot(inventory, dedicatedInventorySlot);

            if (source < 0 && isAnchor(dedicatedStack)) {
                if (dedicatedStack.getCount() == 1) {
                    sell(client);
                    return;
                }

                int buffer = findEmptyInventorySlot(inventory, dedicatedInventorySlot, -1);
                if (buffer < 0) {
                    return;
                }

                int dedicatedScreenSlot = playerScreenSlot(dedicatedInventorySlot);
                int bufferScreenSlot = playerScreenSlot(buffer);

                click(client, dedicatedScreenSlot, 0, ContainerInput.PICKUP);
                click(client, bufferScreenSlot, 0, ContainerInput.PICKUP);

                if (!inventory.getItem(dedicatedInventorySlot).isEmpty()) {
                    return;
                }

                pendingRestoreSlot = buffer;
                pendingRestoreScreenSlot = bufferScreenSlot;
                splitOneIntoDedicated(client, buffer);
                operationPending = true;
                return;
            }

            if (source < 0) {
                stop(false);
                player.sendSystemMessage(
                        Component.literal("KSell: OFF, no Respawn Anchors left.")
                );
                return;
            }

            prepareDedicatedSlot(client, source);
        }

        private void sell(Minecraft client) {
            LocalPlayer player = client.player;
            if (player == null || client.getConnection() == null) {
                return;
            }

            ItemStack hand = player.getInventory().getItem(dedicatedHotbarSlot);
            if (!isExactlyOneAnchor(hand)) {
                return;
            }

            client.getConnection().sendCommand("ah sell " + price);
            waitingForSaleSync = true;
        }

        private void prepareDedicatedSlot(Minecraft client, int source) {
            LocalPlayer player = client.player;
            if (player == null || client.gameMode == null) {
                return;
            }

            Inventory inventory = player.getInventory();
            ItemStack dedicated = inventory.getItem(dedicatedHotbarSlot);

            if (dedicated.isEmpty()) {
                splitOneIntoDedicated(client, source);
                operationPending = true;
                return;
            }

            int buffer = findEmptyInventorySlot(inventory, source, dedicatedHotbarSlot);
            if (buffer < 0) {
                return;
            }

            int dedicatedScreenSlot = playerScreenSlot(dedicatedHotbarSlot);
            int bufferScreenSlot = playerScreenSlot(buffer);

            click(client, dedicatedScreenSlot, 0, ContainerInput.PICKUP);
            click(client, bufferScreenSlot, 0, ContainerInput.PICKUP);

            if (!inventory.getItem(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            pendingRestoreSlot = buffer;
            pendingRestoreScreenSlot = bufferScreenSlot;

            splitOneIntoDedicated(client, source);
            operationPending = true;
        }

        private void splitOneIntoDedicated(Minecraft client, int source) {
            LocalPlayer player = client.player;
            if (player == null || client.gameMode == null) {
                return;
            }

            Inventory inventory = player.getInventory();
            ItemStack sourceStack = inventory.getItem(source);

            if (!isAnchor(sourceStack) || sourceStack.isEmpty()) {
                return;
            }

            if (!inventory.getItem(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            int sourceScreenSlot = playerScreenSlot(source);
            int dedicatedScreenSlot = playerScreenSlot(dedicatedHotbarSlot);

            click(client, sourceScreenSlot, 0, ContainerInput.PICKUP);
            click(client, dedicatedScreenSlot, 1, ContainerInput.PICKUP);
            click(client, sourceScreenSlot, 0, ContainerInput.PICKUP);
        }

        private void restoreOldDedicatedItem(Minecraft client) {
            if (client.player == null || client.gameMode == null || pendingRestoreSlot < 0) {
                return;
            }

            Inventory inventory = client.player.getInventory();

            if (!inventory.getItem(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            ItemStack bufferStack = inventory.getItem(pendingRestoreSlot);
            if (bufferStack.isEmpty()) {
                pendingRestoreSlot = -1;
                pendingRestoreScreenSlot = -1;
                return;
            }

            click(client, pendingRestoreScreenSlot, 0, ContainerInput.PICKUP);
            click(client, playerScreenSlot(dedicatedHotbarSlot), 0, ContainerInput.PICKUP);

            pendingRestoreSlot = -1;
            pendingRestoreScreenSlot = -1;
        }

        private static void click(
                Minecraft client,
                int slot,
                int button,
                ContainerInput action
        ) {
            if (client.player == null || client.gameMode == null) {
                return;
            }

            AbstractContainerMenu handler = client.player.containerMenu;
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    slot,
                    button,
                    action,
                    client.player
            );
        }

        private static int findAnchorSlot(
                Inventory inventory,
                int excludedHotbarSlot
        ) {
            for (int slot = 0; slot < 36; slot++) {
                if (slot == excludedHotbarSlot) {
                    continue;
                }

                if (isAnchor(inventory.getItem(slot))) {
                    return slot;
                }
            }

            return -1;
        }

        private static int findEmptyInventorySlot(
                Inventory inventory,
                int excludedA,
                int excludedB
        ) {
            for (int slot = 0; slot < 36; slot++) {
                if (slot == excludedA || slot == excludedB) {
                    continue;
                }

                if (inventory.getItem(slot).isEmpty()) {
                    return slot;
                }
            }

            return -1;
        }

        private static boolean isAnchor(ItemStack stack) {
            return !stack.isEmpty() && stack.is(Items.RESPAWN_ANCHOR);
        }

        private static boolean isExactlyOneAnchor(ItemStack stack) {
            return isAnchor(stack) && stack.getCount() == 1;
        }

        private static int playerScreenSlot(int inventorySlot) {
            if (inventorySlot >= 0 && inventorySlot < 9) {
                return 36 + inventorySlot;
            }

            return inventorySlot;
        }
    }
}
