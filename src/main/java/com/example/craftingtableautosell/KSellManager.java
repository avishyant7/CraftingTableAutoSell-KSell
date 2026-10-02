package com.example.craftingtableautosell;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
public final class KSellManager {
    private static final int INTERVAL_TICKS = 30;
    private boolean enabled;
    private int price;
    private int waitTicks;
    private int lockedHotbarSlot = -1;
    private ItemStack target = ItemStack.EMPTY;

    public void startFromHand(int price, Minecraft client) {
        if (client.player == null) return;
        ItemStack hand = client.player.getMainHandItem();
        if (hand.isEmpty()) {
            msg(client, "KSell: hold an item in your main hand.", ChatFormatting.RED);
            return;
        }
        this.price = price;
        this.target = hand.copyWithCount(1);
        this.lockedHotbarSlot = client.player.getInventory().getSelectedSlot();
        this.waitTicks = 0;
        this.enabled = true;
        msg(client, "KSell: ON, selling 1x " + hand.getHoverName().getString() + " every 1.5s.", ChatFormatting.GREEN);
    }

    public void stop(Minecraft client) {
        enabled = false;
        waitTicks = 0;
        lockedHotbarSlot = -1;
        target = ItemStack.EMPTY;
        msg(client, "KSell: OFF", ChatFormatting.RED);
    }

    public void tick(Minecraft client) {
        if (!enabled || client.player == null || client.player.connection == null) return;
        if (client.gui.screen() != null) return;

        LocalPlayer player = client.player;
        InventoryMenu menu = player.inventoryMenu;

        if (!menu.getCarried().isEmpty()) return;
        if (lockedHotbarSlot < 0 || player.getInventory().getSelectedSlot() != lockedHotbarSlot) return;

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        int hotbarScreenSlot = 36 + lockedHotbarSlot;
        ItemStack hand = menu.getSlot(hotbarScreenSlot).getItem();

        if (isTarget(hand)) {
            if (hand.getCount() > 1) {
                if (!splitHand(client, menu, hotbarScreenSlot)) return;
                hand = menu.getSlot(hotbarScreenSlot).getItem();
            }
            if (isTarget(hand) && hand.getCount() == 1) {
                sell(client);
                return;
            }
        }

        int sourceScreenSlot = findTargetScreenSlot(menu, hotbarScreenSlot);
        if (sourceScreenSlot < 0) {
            if (hasTargetInInventory(player)) return;
            stopNoItems(client);
            return;
        }

        ItemStack source = menu.getSlot(sourceScreenSlot).getItem();
        if (source.getCount() == 1) {
            swap(client, sourceScreenSlot, lockedHotbarSlot);
        } else if (!splitAndSwap(client, menu, sourceScreenSlot, hotbarScreenSlot, lockedHotbarSlot)) {
            return;
        }

        ItemStack newHand = menu.getSlot(hotbarScreenSlot).getItem();
        if (isTarget(newHand) && newHand.getCount() == 1) {
            sell(client);
        }
    }

    private void sell(Minecraft client) {
        client.player.connection.sendCommand("ah sell " + price);
        waitTicks = INTERVAL_TICKS;
    }

    private int findTargetScreenSlot(InventoryMenu menu, int hotbarScreenSlot) {
        for (int slot = 9; slot <= 44; slot++) {
            if (slot == hotbarScreenSlot) continue;
            if (isTarget(menu.getSlot(slot).getItem())) return slot;
        }
        return -1;
    }

    private boolean hasTargetInInventory(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isTarget(stack)) return true;
        }
        return false;
    }

    private boolean isTarget(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, target);
    }

    private boolean splitHand(Minecraft client, InventoryMenu menu, int handSlot) {
        int scratch = findEmptySlot(menu, handSlot, -1);
        if (scratch < 0) return false;

        click(client, handSlot, 0, ContainerInput.PICKUP);
        if (menu.getCarried().isEmpty()) return false;

        click(client, handSlot, 1, ContainerInput.PICKUP);
        ItemStack remaining = menu.getSlot(handSlot).getItem();
        if (remaining.isEmpty() || remaining.getCount() != 1) return false;

        click(client, scratch, 0, ContainerInput.PICKUP);
        return menu.getCarried().isEmpty();
    }

    private boolean splitAndSwap(Minecraft client, InventoryMenu menu, int sourceSlot, int handSlot, int hotbarSlot) {
        int scratch = findEmptySlot(menu, sourceSlot, handSlot);
        if (scratch < 0) return false;

        click(client, sourceSlot, 0, ContainerInput.PICKUP);
        if (menu.getCarried().isEmpty()) return false;

        click(client, scratch, 1, ContainerInput.PICKUP);
        ItemStack single = menu.getSlot(scratch).getItem();
        if (single.isEmpty() || single.getCount() != 1) return false;

        click(client, sourceSlot, 0, ContainerInput.PICKUP);
        if (!menu.getCarried().isEmpty()) return false;

        click(client, scratch, hotbarSlot, ContainerInput.SWAP);
        return true;
    }

    private void swap(Minecraft client, int sourceSlot, int hotbarSlot) {
        click(client, sourceSlot, hotbarSlot, ContainerInput.SWAP);
    }

    private void click(Minecraft client, int slot, int button, ContainerInput action) {
        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, slot, button, action, client.player);
    }

    private int findEmptySlot(InventoryMenu menu, int excluded1, int excluded2) {
        for (int slot = 9; slot <= 44; slot++) {
            if (slot == excluded1 || slot == excluded2) continue;
            if (menu.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private void stopNoItems(Minecraft client) {
        enabled = false;
        msg(client, "KSell: OFF, no matching items left.", ChatFormatting.YELLOW);
    }

    private void msg(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal(text).withStyle(color));
    }
}
