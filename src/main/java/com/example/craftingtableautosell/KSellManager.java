package com.example.craftingtableautosell;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
public final class KSellManager {
    private static final int INTERVAL_TICKS = 30;
    private boolean enabled;
    private int price;
    private int waitTicks;

    public void start(int price, Minecraft client) {
        if (client.player == null) return;
        this.price = price;
        this.waitTicks = 0;
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

        int slot = findRespawnAnchor(client);
        if (slot < 0) {
            stopNoItems(client);
            return;
        }

        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty() || stack.getCount() <= 0) {
            return;
        }

        client.player.getInventory().setItem(slot, stack.copyWithCount(stack.getCount() - 1));
        client.player.getInventory().getItem(slot).setCount(stack.getCount() - 1);
        client.player.getInventory().setSelectedSlot(slot < 9 ? slot : client.player.getInventory().getSelectedSlot());
        client.player.connection.sendCommand("ah sell " + price);
        waitTicks = INTERVAL_TICKS;
    }

    private int findRespawnAnchor(Minecraft client) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (isRespawnAnchor(stack)) return i;
        }
        return -1;
    }

    private boolean isRespawnAnchor(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().toString().equals("minecraft:respawn_anchor");
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
