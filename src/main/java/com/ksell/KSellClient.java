package com.ksell;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public final class KSellClient implements ClientModInitializer {
    private final Seller seller = new Seller();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("ksell")
                    .then(ClientCommandManager.literal("on")
                            .then(ClientCommandManager.argument("price", LongArgumentType.longArg(1))
                                    .executes(context -> {
                                        seller.start(LongArgumentType.getLong(context, "price"));
                                        return 1;
                                    }))));

            dispatcher.register(ClientCommandManager.literal("ksel")
                    .then(ClientCommandManager.literal("off")
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
            MinecraftClient client = MinecraftClient.getInstance();
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

            client.player.sendMessage(
                    Text.literal("KSell: ON, selling Respawn Anchors for $" + newPrice + " each."),
                    false
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
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("KSell: OFF"), false);
                }
            }
        }

        void tick(MinecraftClient client) {
            if (!enabled || client.player == null || client.world == null) {
                return;
            }

            if (client.currentScreen != null) {
                return;
            }

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            ClientPlayerEntity player = client.player;
            PlayerInventory inventory = player.getInventory();

            if (inventory.getSelectedSlot() != dedicatedHotbarSlot) {
                inventory.setSelectedSlot(dedicatedHotbarSlot);
                return;
            }

            int dedicatedInventorySlot = dedicatedHotbarSlot;
            ItemStack dedicatedStack = inventory.getStack(dedicatedInventorySlot);

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

                click(client, dedicatedScreenSlot, 0, SlotActionType.PICKUP);
                click(client, bufferScreenSlot, 0, SlotActionType.PICKUP);

                if (!inventory.getStack(dedicatedInventorySlot).isEmpty()) {
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
                player.sendMessage(
                        Text.literal("KSell: OFF, no Respawn Anchors left."),
                        false
                );
                return;
            }

            prepareDedicatedSlot(client, source);
        }

        private void sell(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return;
            }

            ItemStack hand = player.getInventory().getStack(dedicatedHotbarSlot);
            if (!isExactlyOneAnchor(hand)) {
                return;
            }

            client.getNetworkHandler().sendChatCommand("ah sell " + price);
            waitingForSaleSync = true;
        }

        private void prepareDedicatedSlot(MinecraftClient client, int source) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                return;
            }

            PlayerInventory inventory = player.getInventory();
            ItemStack dedicated = inventory.getStack(dedicatedHotbarSlot);

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

            click(client, dedicatedScreenSlot, 0, SlotActionType.PICKUP);
            click(client, bufferScreenSlot, 0, SlotActionType.PICKUP);

            if (!inventory.getStack(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            pendingRestoreSlot = buffer;
            pendingRestoreScreenSlot = bufferScreenSlot;

            splitOneIntoDedicated(client, source);
            operationPending = true;
        }

        private void splitOneIntoDedicated(MinecraftClient client, int source) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                return;
            }

            PlayerInventory inventory = player.getInventory();
            ItemStack sourceStack = inventory.getStack(source);

            if (!isAnchor(sourceStack) || sourceStack.isEmpty()) {
                return;
            }

            if (!inventory.getStack(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            int sourceScreenSlot = playerScreenSlot(source);
            int dedicatedScreenSlot = playerScreenSlot(dedicatedHotbarSlot);

            click(client, sourceScreenSlot, 0, SlotActionType.PICKUP);
            click(client, dedicatedScreenSlot, 1, SlotActionType.PICKUP);
            click(client, sourceScreenSlot, 0, SlotActionType.PICKUP);
        }

        private void restoreOldDedicatedItem(MinecraftClient client) {
            if (client.player == null || client.interactionManager == null || pendingRestoreSlot < 0) {
                return;
            }

            PlayerInventory inventory = client.player.getInventory();

            if (!inventory.getStack(dedicatedHotbarSlot).isEmpty()) {
                return;
            }

            ItemStack bufferStack = inventory.getStack(pendingRestoreSlot);
            if (bufferStack.isEmpty()) {
                pendingRestoreSlot = -1;
                pendingRestoreScreenSlot = -1;
                return;
            }

            click(client, pendingRestoreScreenSlot, 0, SlotActionType.PICKUP);
            click(client, playerScreenSlot(dedicatedHotbarSlot), 0, SlotActionType.PICKUP);

            pendingRestoreSlot = -1;
            pendingRestoreScreenSlot = -1;
        }

        private static void click(
                MinecraftClient client,
                int slot,
                int button,
                SlotActionType action
        ) {
            if (client.player == null || client.interactionManager == null) {
                return;
            }

            ScreenHandler handler = client.player.currentScreenHandler;
            client.interactionManager.clickSlot(
                    handler.syncId,
                    slot,
                    button,
                    action,
                    client.player
            );
        }

        private static int findAnchorSlot(
                PlayerInventory inventory,
                int excludedHotbarSlot
        ) {
            for (int slot = 0; slot < 36; slot++) {
                if (slot == excludedHotbarSlot) {
                    continue;
                }

                if (isAnchor(inventory.getStack(slot))) {
                    return slot;
                }
            }

            return -1;
        }

        private static int findEmptyInventorySlot(
                PlayerInventory inventory,
                int excludedA,
                int excludedB
        ) {
            for (int slot = 0; slot < 36; slot++) {
                if (slot == excludedA || slot == excludedB) {
                    continue;
                }

                if (inventory.getStack(slot).isEmpty()) {
                    return slot;
                }
            }

            return -1;
        }

        private static boolean isAnchor(ItemStack stack) {
            return !stack.isEmpty() && stack.isOf(Items.RESPAWN_ANCHOR);
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
