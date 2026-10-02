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
    private final SplitTest splitTest = new SplitTest();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("ksell")
                    .then(ClientCommands.literal("on")
                            .then(ClientCommands.argument("price", LongArgumentType.longArg(1))
                                    .executes(context -> {
                                        splitTest.start(LongArgumentType.getLong(context, "price"));
                                        return 1;
                                    }))));

            dispatcher.register(ClientCommands.literal("ksel")
                    .then(ClientCommands.literal("off")
                            .executes(context -> {
                                splitTest.stop(true);
                                return 1;
                            })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(splitTest::tick);
    }

    private static final class SplitTest {
        private enum State {
            IDLE,
            MOVE_DEDICATED_TO_BUFFER,
            PLACE_DEDICATED_IN_BUFFER,
            PICKUP_SOURCE,
            PLACE_ONE,
            RETURN_REMAINDER,
            VERIFY,
            DONE
        }

        private boolean enabled;
        private int dedicatedHotbarSlot;
        private int sourceInventorySlot = -1;
        private int bufferInventorySlot = -1;
        private State state = State.IDLE;
        private int waitTicks;

        void start(long ignoredPrice) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }

            enabled = true;
            dedicatedHotbarSlot = client.player.getInventory().getSelectedSlot();
            sourceInventorySlot = -1;
            bufferInventorySlot = -1;
            state = State.IDLE;
            waitTicks = 0;

            client.player.sendSystemMessage(Component.literal(
                    "KSell: DIAGNOSTIC ON. Splitting exactly 1 Respawn Anchor. No selling."
            ));
        }

        void stop(boolean announce) {
            enabled = false;
            state = State.IDLE;
            waitTicks = 0;
            sourceInventorySlot = -1;
            bufferInventorySlot = -1;

            if (announce && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("KSell: OFF")
                );
            }
        }

        void tick(Minecraft client) {
            if (!enabled || client.player == null || client.level == null) {
                return;
            }

            if (client.gui.screen() != null) {
                return;
            }

            LocalPlayer player = client.player;
            Inventory inventory = player.getInventory();

            if (inventory.getSelectedSlot() != dedicatedHotbarSlot) {
                inventory.setSelectedSlot(dedicatedHotbarSlot);
                return;
            }

            if (waitTicks > 0) {
                waitTicks--;
                return;
            }

            AbstractContainerMenu menu = player.containerMenu;
            ItemStack carried = menu.getCarried();
            ItemStack dedicated = inventory.getItem(dedicatedHotbarSlot);

            switch (state) {
                case IDLE -> begin(client, inventory, dedicated, carried);
                case MOVE_DEDICATED_TO_BUFFER ->
                        moveDedicatedToBuffer(client, inventory, dedicated, carried);
                case PLACE_DEDICATED_IN_BUFFER ->
                        placeDedicatedInBuffer(client, inventory, dedicated, carried);
                case PICKUP_SOURCE ->
                        pickupSource(client, inventory, dedicated, carried);
                case PLACE_ONE ->
                        placeOne(client, inventory, dedicated, carried);
                case RETURN_REMAINDER ->
                        returnRemainder(client, inventory, dedicated, carried);
                case VERIFY ->
                        verify(client, inventory, dedicated, carried);
                case DONE -> {
                    enabled = false;
                    state = State.IDLE;
                    player.sendSystemMessage(Component.literal(
                            "KSell: TEST COMPLETE. Selected slot contains exactly 1 Respawn Anchor. No sale performed."
                    ));
                }
            }
        }

        private void begin(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!carried.isEmpty()) {
                return;
            }

            sourceInventorySlot = findAnchorSlot(inventory, dedicatedHotbarSlot);
            if (sourceInventorySlot < 0) {
                fail(client, "no Respawn Anchors found");
                return;
            }

            if (!dedicated.isEmpty()) {
                bufferInventorySlot = findEmptyInventorySlot(
                        inventory, sourceInventorySlot, dedicatedHotbarSlot
                );
                if (bufferInventorySlot < 0) {
                    fail(client, "selected hotbar slot is occupied and no empty buffer slot exists");
                    return;
                }
                state = State.MOVE_DEDICATED_TO_BUFFER;
                return;
            }

            state = State.PICKUP_SOURCE;
        }

        private void moveDedicatedToBuffer(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!carried.isEmpty()) {
                fail(client, "cursor was not empty before moving the selected item");
                return;
            }

            if (dedicated.isEmpty()) {
                state = State.PICKUP_SOURCE;
                return;
            }

            click(client, playerScreenSlot(dedicatedHotbarSlot), 0, ContainerInput.PICKUP);
            waitTicks = 1;
            state = State.PLACE_DEDICATED_IN_BUFFER;
        }

        private void placeDedicatedInBuffer(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (dedicated.isEmpty() && !carried.isEmpty()) {
                click(client, playerScreenSlot(bufferInventorySlot), 0, ContainerInput.PICKUP);
                waitTicks = 1;
                return;
            }

            if (carried.isEmpty() && dedicated.isEmpty()
                    && !inventory.getItem(bufferInventorySlot).isEmpty()) {
                state = State.PICKUP_SOURCE;
                return;
            }

            if (!carried.isEmpty() || !dedicated.isEmpty()) {
                return;
            }

            fail(client, "failed to move the selected item into the buffer");
        }

        private void pickupSource(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!carried.isEmpty() || !dedicated.isEmpty()) {
                return;
            }

            ItemStack source = inventory.getItem(sourceInventorySlot);
            if (!isAnchor(source)) {
                fail(client, "source anchor stack disappeared or changed");
                return;
            }

            click(client, playerScreenSlot(sourceInventorySlot), 0, ContainerInput.PICKUP);
            waitTicks = 1;
            state = State.PLACE_ONE;
        }

        private void placeOne(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!dedicated.isEmpty()) {
                fail(client, "selected slot became occupied before the split");
                return;
            }

            if (!isAnchor(carried)) {
                if (carried.isEmpty()) {
                    fail(client, "server did not synchronize the source stack to the cursor");
                } else {
                    fail(client, "cursor contains a non-anchor item");
                }
                return;
            }

            click(client, playerScreenSlot(dedicatedHotbarSlot), 1, ContainerInput.PICKUP);
            waitTicks = 1;
            state = State.RETURN_REMAINDER;
        }

        private void returnRemainder(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!isExactlyOneAnchor(dedicated)) {
                return;
            }

            if (carried.isEmpty()) {
                state = State.VERIFY;
                return;
            }

            if (!isAnchor(carried)) {
                fail(client, "cursor changed to a non-anchor item after placing one");
                return;
            }

            ItemStack source = inventory.getItem(sourceInventorySlot);
            if (!source.isEmpty()) {
                fail(client, "source slot was not empty when returning the remainder");
                return;
            }

            click(client, playerScreenSlot(sourceInventorySlot), 0, ContainerInput.PICKUP);
            waitTicks = 1;
        }

        private void verify(
                Minecraft client,
                Inventory inventory,
                ItemStack dedicated,
                ItemStack carried
        ) {
            if (!carried.isEmpty()) {
                return;
            }

            if (!isExactlyOneAnchor(dedicated)) {
                fail(client, "selected slot is not exactly 1 Respawn Anchor");
                return;
            }

            ItemStack source = inventory.getItem(sourceInventorySlot);
            if (!source.isEmpty() && !isAnchor(source)) {
                fail(client, "source slot contains the wrong item");
                return;
            }

            state = State.DONE;
        }

        private void fail(Minecraft client, String reason) {
            enabled = false;
            state = State.IDLE;
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "KSell: TEST FAILED: " + reason
                ));
            }
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

            AbstractContainerMenu menu = client.player.containerMenu;
            client.gameMode.handleContainerInput(
                    menu.containerId,
                    slot,
                    button,
                    action,
                    client.player
            );
        }

        private static int findAnchorSlot(Inventory inventory, int excludedHotbarSlot) {
            for (int slot = 0; slot < 36; slot++) {
                if (slot != excludedHotbarSlot && isAnchor(inventory.getItem(slot))) {
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
                if (slot != excludedA
                        && slot != excludedB
                        && inventory.getItem(slot).isEmpty()) {
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
            return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
        }
    }
}
