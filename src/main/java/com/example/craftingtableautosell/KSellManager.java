package com.example.craftingtableautosell;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class KSellManager {
    private static final int INTERVAL_TICKS = 30;
    private boolean enabled;
    private int price;
    private int waitTicks;
    private int sellHotbarSlot;

    public void start(int price, Minecraft client) {
        if (client.player == null) return;
        this.price = price;
        this.waitTicks = 0;
        this.sellHotbarSlot = client.player.getInventory().getSelectedSlot();
        this.enabled = true;
        msg(client, "KSell: ON, selling Respawn Anchors for $" + price + " each.", ChatFormatting.GREEN);
    }

    public void stop(Minecraft client) {
        enabled = false;
        waitTicks = 0;
        msg(client, "KSell: OFF", ChatFormatting.RED);
    }

    public void tick(Minecraft client) {
        if (!enabled || client.player == null || client.player.connection == null) return;
        if (client.gui.screen() != null) return;
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        InventoryMenu menu = client.player.inventoryMenu;
        int handSlot = 36 + sellHotbarSlot;
        ItemStack hand = menu.getSlot(handSlot).getItem();

        if (isRespawnAnchor(hand)) {
            if (hand.getCount() > 1) {
                if (!splitHandStack(client, menu, handSlot)) return;
            }
            if (isSingleRespawnAnchor(menu.getSlot(handSlot).getItem())) sell(client);
            return;
        }

        int sourceSlot = findRespawnAnchor(menu);
        if (sourceSlot < 0) {
            stopNoItems(client);
            return;
        }

        ItemStack source = menu.getSlot(sourceSlot).getItem();
        if (source.getCount() == 1) {
            swapIntoHand(client, menu, sourceSlot);
        } else {
            int scratchSlot = findEmptySlot(menu, sourceSlot, handSlot);
            if (scratchSlot < 0) {
                msg(client, "KSell: Need one empty inventory slot to split the stack.", ChatFormatting.YELLOW);
                return;
            }
            splitOneToSlot(client, menu, sourceSlot, scratchSlot);
            if (isSingleRespawnAnchor(menu.getSlot(scratchSlot).getItem())) {
                swapIntoHand(client, menu, scratchSlot);
            }
        }

        if (isSingleRespawnAnchor(menu.getSlot(handSlot).getItem())) sell(client);
    }

    private void sell(Minecraft client) {
        client.player.connection.sendCommand("ah sell " + price);
        waitTicks = INTERVAL_TICKS;
    }

    private boolean splitHandStack(Minecraft client, InventoryMenu menu, int handSlot) {
        int scratchSlot = findEmptySlot(menu, handSlot, -1);
        if (scratchSlot < 0) return false;

        click(client, menu, handSlot, 0, ContainerInput.PICKUP);
        click(client, menu, handSlot, 1, ContainerInput.PICKUP);
        click(client, menu, scratchSlot, 0, ContainerInput.PICKUP);
        return isSingleRespawnAnchor(menu.getSlot(handSlot).getItem());
    }

    private void splitOneToSlot(Minecraft client, InventoryMenu menu, int sourceSlot, int targetSlot) {
        click(client, menu, sourceSlot, 0, ContainerInput.PICKUP);
        click(client, menu, targetSlot, 1, ContainerInput.PICKUP);
        click(client, menu, sourceSlot, 0, ContainerInput.PICKUP);
    }

    private void swapIntoHand(Minecraft client, InventoryMenu menu, int sourceSlot) {
        click(client, menu, sourceSlot, sellHotbarSlot, ContainerInput.SWAP);
    }

    private void click(Minecraft client, InventoryMenu menu, int slot, int button, ContainerInput action) {
        if (client.gameMode != null) {
            client.gameMode.handleContainerInput(menu.containerId, slot, button, action);
        }
    }

    private int findRespawnAnchor(InventoryMenu menu) {
        for (int slot = 9; slot <= 44; slot++) {
            if (isRespawnAnchor(menu.getSlot(slot).getItem())) return slot;
        }
        return -1;
    }

    private int findEmptySlot(InventoryMenu menu, int excludedA, int excludedB) {
        for (int slot = 9; slot <= 44; slot++) {
            if (slot == excludedA || slot == excludedB) continue;
            if (menu.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private boolean isRespawnAnchor(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.RESPAWN_ANCHOR);
    }

    private boolean isSingleRespawnAnchor(ItemStack stack) {
        return isRespawnAnchor(stack) && stack.getCount() == 1;
    }

    private void stopNoItems(Minecraft client) {
        enabled = false;
        msg(client, "KSell: OFF, no Respawn Anchors left.", ChatFormatting.YELLOW);
    }

    private void msg(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(text).withStyle(color));
        }
    }
}
