// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec;
import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

class DomainInventoryDragTest {
    @Test
    void shiftZeroPlaceholdersReuseTheirVisibleMetadataAndWidgets() {
        try (var ui = new Harness()) {
            int described = ui.input.registryReads;
            var original = List.copyOf(ui.widgets);
            for (int id = 1; id <= 6; id++) {
                byte[] data = DomainInventoryRecordCodec.encode(new DomainLedger.Change(id, ui.keys.get(id), 0, id));
                ui.view.accept(new DomainInventoryFrame.Data(ui.session, 1, 10 + id, false, data.length, 0, data));
                ui.view.tick(true, () -> 0);
                assertEquals(
                        described, ui.input.registryReads, "Taking another stack decoded old zero placeholders again");
                assertTrue(ui.widgets.containsAll(original), "Unchanged physical cells were recreated");
            }
        }
    }

    @Test
    void fluidStartAndFluidCrossingUseTheSameShiftBucketIntentBeforeAndAfterAcknowledgement() {
        for (boolean fluidStart : new boolean[] {true, false}) {
            for (boolean acknowledgeFirst : new boolean[] {true, false}) {
                try (var ui = new Harness(true)) {
                    ui.input.shift = true;
                    var water = ui.find(value -> value instanceof FluidStack stack && stack.getFluid() == Fluids.WATER);
                    var lava = ui.find(value -> value instanceof FluidStack stack && stack.getFluid() == Fluids.LAVA);
                    var iron = ui.find(value -> value instanceof ItemStack stack && stack.is(Items.IRON_INGOT));
                    var first = fluidStart ? water : iron;
                    var second = fluidStart ? iron : water;
                    ui.input.inventory.setItem(9, new ItemStack(Items.BUCKET, 4));
                    ui.view.mouseClicked(first.getX() + 1, first.getY() + 1, 0);
                    if (acknowledgeFirst) ui.acknowledge(0, TerminalStorageResponse.Status.COMPLETE);
                    ui.view.mouseDragged(second.getX() + 1, second.getY() + 1, 0);
                    ui.view.mouseDragged(lava.getX() + 1, lava.getY() + 1, 0);
                    ui.view.mouseDragged(lava.getX() + 2, lava.getY() + 1, 0);
                    ui.view.mouseReleased(0);
                    for (int i = acknowledgeFirst ? 1 : 0; i < ui.requests.size(); i++)
                        ui.acknowledge(i, TerminalStorageResponse.Status.COMPLETE);
                    assertEquals(
                            fluidStart ? List.of(10L, 1L, 11L) : List.of(1L, 10L, 11L),
                            ui.requests.stream()
                                    .map(TerminalStorageRequest::resourceId)
                                    .toList());
                    for (var request : ui.requests) {
                        assertEquals(-1, request.inventorySlot());
                        assertEquals(0, request.button());
                        assertTrue(request.shift());
                        assertFalse(request.bulk());
                    }
                    assertTrue(ui.input.cursor.isEmpty(), "Drag must not predict bucket output on the cursor");
                }
            }
        }
    }

    @Test
    void unsupportedCarrierAtSweepStartDoesNotCancelFollowingItemOrFluidMoves() {
        try (var ui = new Harness(true)) {
            ui.input.shift = true;
            var energy = ui.find(value -> value == EnergyVariant.INSTANCE);
            var iron = ui.find(value -> value instanceof ItemStack stack && stack.is(Items.IRON_INGOT));
            var water = ui.find(value -> value instanceof FluidStack stack && stack.getFluid() == Fluids.WATER);
            ui.view.mouseClicked(energy.getX() + 1, energy.getY() + 1, 0);
            assertTrue(ui.requests.isEmpty(), "An unsupported empty-hand start should be skipped like a crossing");
            ui.view.mouseDragged(iron.getX() + 1, iron.getY() + 1, 0);
            ui.view.mouseDragged(water.getX() + 1, water.getY() + 1, 0);
            ui.view.mouseReleased(0);
            ui.finishAll();
            assertEquals(
                    List.of(1L, 10L),
                    ui.requests.stream().map(TerminalStorageRequest::resourceId).toList());
        }
    }

    @Test
    void emptyHandSweepSkipsEnergyInsteadOfSendingAnUnsupportedCarrierOperation() {
        try (var ui = new Harness(true)) {
            ui.input.shift = true;
            var water = ui.find(value -> value instanceof FluidStack stack && stack.getFluid() == Fluids.WATER);
            var energy = ui.find(value -> value == EnergyVariant.INSTANCE);
            var iron = ui.find(value -> value instanceof ItemStack stack && stack.is(Items.IRON_INGOT));
            ui.view.mouseClicked(water.getX() + 1, water.getY() + 1, 0);
            ui.view.mouseDragged(energy.getX() + 1, energy.getY() + 1, 0);
            ui.view.mouseDragged(iron.getX() + 1, iron.getY() + 1, 0);
            ui.finishAll();
            assertEquals(
                    List.of(10L, 1L),
                    ui.requests.stream().map(TerminalStorageRequest::resourceId).toList());
        }
    }

    @Test
    void bucketRefusalDoesNotCancelIndependentSentMovesOrInventContainers() {
        for (var status : List.of(
                TerminalStorageResponse.Status.NO_BUCKET,
                TerminalStorageResponse.Status.INSUFFICIENT_FLUID,
                TerminalStorageResponse.Status.NO_SPACE)) {
            try (var ui = new Harness(true)) {
                ui.input.shift = true;
                var water = ui.find(value -> value instanceof FluidStack stack && stack.getFluid() == Fluids.WATER);
                var iron = ui.find(value -> value instanceof ItemStack stack && stack.is(Items.IRON_INGOT));
                ui.view.mouseClicked(water.getX() + 1, water.getY() + 1, 0);
                ui.view.mouseDragged(iron.getX() + 1, iron.getY() + 1, 0);
                ui.acknowledge(0, status);
                assertEquals(2, ui.requests.size());
                ui.acknowledge(1, TerminalStorageResponse.Status.COMPLETE);
                assertEquals(2, ui.requests.size());
                assertEquals(0, ui.input.inventory.countItem(Items.WATER_BUCKET));
            }
        }
    }

    @Test
    void rapidPickupThenPlacementMustKeepTheSecondClickWhileTheCursorUpdateIsInFlight() {
        for (boolean playerDestination : new boolean[] {true, false}) {
            try (var ui = new Harness()) {
                ui.view.mouseClicked(ui.x(false, 0), ui.y(false, 0), 0);
                double x = playerDestination ? ui.layout.slotX(9) + 1 : ui.layout.gridX() + 1;
                double y = playerDestination ? ui.layout.slotY(9) + 1 : ui.layout.gridY() + 19;
                ui.view.mouseClicked(x, y, 0);
                assertEquals(2, ui.requests.size());
                ui.input.cursor = new ItemStack(Items.IRON_INGOT, 64);
                ui.acknowledge(0, TerminalStorageResponse.Status.COMPLETE);
                assertEquals(2, ui.requests.size(), "Pending pickup means an empty-looking destination is not a no-op");
                assertEquals(playerDestination ? 9 : -1, ui.requests.getLast().inventorySlot());
                assertEquals(0, ui.requests.getLast().resourceId());
            }
        }
    }

    @Test
    void doubleClickingAnEmptySlotCannotInventAServerBulkOperation() {
        try (var ui = new Harness()) {
            ui.input.shift = true;
            double x = ui.layout.slotX(9) + 1, y = ui.layout.slotY(9) + 1;
            ui.view.mouseClicked(x, y, 0);
            ui.view.mouseReleased(0);
            ui.input.now += 100;
            ui.view.mouseClicked(x, y, 0);
            assertTrue(ui.requests.isEmpty(), "An unsent empty click cannot authorize a bulk request");
            ui.view.mouseDragged(ui.x(true, 0), ui.y(true, 0), 0);
            assertEquals(1, ui.requests.size());
            assertFalse(ui.requests.getFirst().bulk());
        }
    }

    @Test
    void pendingNormalPickupCannotTurnIntoAnEmptyCursorSweepBeforeAcknowledgement() {
        for (boolean deposit : new boolean[] {true, false}) {
            try (var ui = new Harness()) {
                ui.view.mouseClicked(ui.x(deposit, 0), ui.y(deposit, 0), 0);
                ui.input.shift = true;
                ui.view.mouseDragged(ui.x(deposit, 1), ui.y(deposit, 1), 0);
                ui.finishAll();
                assertEquals(1, ui.requests.size());
                assertFalse(ui.requests.getFirst().shift());
            }
        }
    }

    @Test
    void fullWindowReportsBusyWithoutGrowingOrReplayingRejectedIntents() {
        try (var ui = new Harness()) {
            ui.input.shift = true;
            ui.view.mouseClicked(ui.x(false, 0), ui.y(false, 0), 0);
            for (int i = 1; i <= 64; i++) ui.view.mouseDragged(ui.x(false, i % 9), ui.y(false, i % 9), 0);
            assertEquals(64, ui.requests.size());
            assertEquals(DomainInventoryView.text("access_busy"), ui.view.statusMessage());
            ui.finishAll();
            assertEquals(64, ui.requests.size());
            ui.view.mouseClicked(ui.x(false, 0), ui.y(false, 0), 0);
            assertEquals(65, ui.requests.size());
        }
    }

    @Test
    void realPressDragReleaseAndAcknowledgementsMoveNineSlotsInBothDirections() {
        for (boolean deposit : new boolean[] {true, false}) {
            try (var ui = new Harness()) {
                ui.input.shift = true;
                assertTrue(ui.view.mouseClicked(ui.x(deposit, 0), ui.y(deposit, 0), 0));
                for (int i = 1; i < 9; i++) {
                    assertTrue(ui.view.mouseDragged(ui.x(deposit, i), ui.y(deposit, i), 0));
                    ui.view.mouseDragged(ui.x(deposit, i) + 1, ui.y(deposit, i), 0);
                }
                ui.view.mouseReleased(0);
                assertEquals(
                        9, ui.requests.size(), "Every swept slot must be sent before waiting for acknowledgements");
                ui.finishAll();
                assertEquals(9, ui.requests.size());
                for (var request : ui.requests) {
                    assertTrue(request.shift());
                    assertEquals(0, request.button());
                    assertFalse(request.bulk());
                    assertEquals(deposit, request.inventorySlot() >= 0);
                }
                assertEquals(
                        9,
                        ui.requests.stream()
                                .map(r -> deposit ? (long) r.inventorySlot() : r.resourceId())
                                .distinct()
                                .count());
            }
        }
    }

    @Test
    void startingOnAnEmptyGridCellOrInventoryGapDoesNotSubmitAnEmptyOperation() {
        for (boolean grid : new boolean[] {true, false}) {
            try (var ui = new Harness()) {
                ui.input.shift = true;
                double x = ui.layout.gridX() + 1;
                double y = grid ? ui.layout.gridY() + 19 : ui.layout.inventoryY() + 55;
                assertTrue(ui.view.mouseClicked(x, y, 0));
                assertTrue(ui.requests.isEmpty(), "Empty gesture start must not send a server operation");
                for (int i = 0; i < 9; i++) ui.view.mouseDragged(ui.x(!grid, i), ui.y(!grid, i), 0);
                ui.view.mouseReleased(0);
                ui.finishAll();
                assertEquals(9, ui.requests.size(), "Starting between slots must still allow the following sweep");
            }
        }
    }

    @Test
    void pressingShiftAfterTheMouseOrTemporarilyReleasingShiftDoesNotLoseTheHeldMouse() {
        try (var ui = new Harness()) {
            assertTrue(ui.view.mouseClicked(ui.layout.gridX() + 1, ui.layout.gridY() + 19, 0));
            ui.view.tick(false, () -> 0);
            ui.input.shift = true;
            ui.view.mouseDragged(ui.x(true, 0), ui.y(true, 0), 0);
            assertEquals(1, ui.requests.size());
            ui.input.shift = false;
            ui.view.tick(false, () -> 0);
            ui.view.mouseDragged(ui.x(true, 1), ui.y(true, 1), 0);
            ui.input.shift = true;
            ui.view.mouseDragged(ui.x(true, 2), ui.y(true, 2), 0);
            ui.view.mouseReleased(0);
            ui.finishAll();
            assertEquals(
                    List.of(0, 2),
                    ui.requests.stream()
                            .map(TerminalStorageRequest::inventorySlot)
                            .toList());
        }
    }

    @Test
    void emptyPlayerSlotDoesNotSendButIntentionalShiftDoubleClickStillUsesBulk() {
        try (var ui = new Harness()) {
            ui.input.shift = true;
            ui.view.mouseClicked(ui.layout.slotX(9) + 1, ui.layout.slotY(9) + 1, 0);
            assertTrue(ui.requests.isEmpty());
            ui.view.mouseReleased(0);
            ui.view.mouseClicked(ui.x(true, 0), ui.y(true, 0), 0);
            ui.acknowledge(0, TerminalStorageResponse.Status.COMPLETE);
            ui.view.mouseReleased(0);
            ui.input.now += 100;
            ui.view.mouseClicked(ui.x(true, 0), ui.y(true, 0), 0);
            assertEquals(2, ui.requests.size());
            assertTrue(ui.requests.getLast().bulk(), "The emptied source slot still owns the intentional double click");
        }
    }

    @Test
    void unknownFailureStopsTheGestureAndReadOnlyCannotStartASweep() {
        try (var ui = new Harness()) {
            ui.input.shift = true;
            ui.view.mouseClicked(ui.x(true, 0), ui.y(true, 0), 0);
            ui.view.mouseDragged(ui.x(true, 1), ui.y(true, 1), 0);
            ui.acknowledge(0, TerminalStorageResponse.Status.UNKNOWN);
            ui.view.mouseDragged(ui.x(true, 2), ui.y(true, 2), 0);
            assertEquals(2, ui.requests.size());
            ui.mode(false);
            ui.view.mouseClicked(ui.x(true, 3), ui.y(true, 3), 0);
            ui.view.mouseDragged(ui.x(true, 4), ui.y(true, 4), 0);
            assertEquals(2, ui.requests.size());
        }
    }

    private static final class Harness implements AutoCloseable {
        final UUID session = new UUID(30, 1);
        final FakeInput input = new FakeInput();
        final DomainInventoryView view = new DomainInventoryView(() -> {}, input);
        final List<TerminalStorageRequest> requests = new ArrayList<>();
        final List<AbstractWidget> widgets = new ArrayList<>();
        final java.util.Map<Integer, io.github.loongin.omniresonance.transfer.ResourceVariantKey> keys =
                new java.util.HashMap<>();
        final DomainInventoryLayout layout;

        Harness() {
            this(false);
        }

        Harness(boolean fluids) {
            view.request(session, 1);
            view.bindStorage(new UUID(30, 2), requests::add);
            var body = DomainInventoryLayout.window(427, 240).content();
            layout = new DomainInventoryLayout(body, true);
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget), ignored -> {});
            var items = List.of(
                    Items.IRON_INGOT,
                    Items.GOLD_INGOT,
                    Items.COPPER_INGOT,
                    Items.DIAMOND,
                    Items.EMERALD,
                    Items.REDSTONE,
                    Items.COAL,
                    Items.LAPIS_LAZULI,
                    Items.QUARTZ);
            int count = items.size() + (fluids ? 3 : 0);
            view.accept(new DomainInventoryFrame.Begin(session, 1, 0, count, count));
            for (int i = 0; i < items.size(); i++) {
                var stack = new ItemStack(items.get(i), 64);
                input.inventory.setItem(i, stack);
                var key = ItemVariant.from(stack, input.registries()).key();
                keys.put(i + 1, key);
                byte[] data = DomainInventoryRecordCodec.encode(new DomainLedger.Change(i + 1, key, 128, 0));
                view.accept(new DomainInventoryFrame.Data(session, 1, i + 1, true, data.length, 0, data));
            }
            if (fluids) {
                var water = FluidVariant.from(new FluidStack(Fluids.WATER, 1), input.registries());
                var lava = FluidVariant.from(new FluidStack(Fluids.LAVA, 1), input.registries());
                var keys = List.of(water.key(), lava.key(), EnergyVariant.INSTANCE.key());
                for (int i = 0; i < keys.size(); i++) {
                    int id = 10 + i;
                    byte[] data = DomainInventoryRecordCodec.encode(new DomainLedger.Change(id, keys.get(i), 4000, 0));
                    view.accept(new DomainInventoryFrame.Data(session, 1, id, true, data.length, 0, data));
                }
            }
            view.accept(new DomainInventoryFrame.End(session, 1, count + 1, count, 0));
            mode(true);
            view.tick(false, () -> 0);
        }

        AbstractWidget find(java.util.function.Predicate<Object> matching) {
            for (var widget : widgets) {
                var hover = view.recipeHover(widget.getX() + 1, widget.getY() + 1, input.registries());
                if (hover != null && matching.test(hover.value())) return widget;
            }
            throw new AssertionError("Expected resource was not exposed by the inventory surface");
        }

        double x(boolean inventory, int slot) {
            return inventory ? layout.slotX(slot) + 1 : layout.gridX() + slot * 18 + 1;
        }

        double y(boolean inventory, int slot) {
            return inventory ? layout.slotY(slot) + 1 : layout.gridY() + 1;
        }

        void mode(boolean writable) {
            view.accept(new TerminalStorageResponse(
                    session, 1, 0, TerminalStorageResponse.Status.MODE, 0, writable, input.state));
        }

        void acknowledge(int index, TerminalStorageResponse.Status status) {
            var request = requests.get(index);
            if (status == TerminalStorageResponse.Status.COMPLETE && request.inventorySlot() >= 0)
                input.inventory.setItem(request.inventorySlot(), ItemStack.EMPTY);
            input.state++;
            view.accept(new TerminalStorageResponse(
                    session,
                    1,
                    request.sequence(),
                    status,
                    0,
                    status != TerminalStorageResponse.Status.UNKNOWN && status != TerminalStorageResponse.Status.FAILED,
                    input.state));
        }

        void finishAll() {
            for (int i = 0; i < requests.size(); i++) {
                assertTrue(i < 128, "A sweep replayed work");
                acknowledge(i, TerminalStorageResponse.Status.COMPLETE);
            }
        }

        public void close() {
            view.close();
        }
    }

    private static final class FakeInput implements DomainInventoryInput {
        final Inventory inventory = new Inventory(null);
        ItemStack cursor = ItemStack.EMPTY;
        boolean shift;
        long now = 1000;
        int state;
        int registryReads;

        public boolean shiftDown() {
            return shift;
        }

        public long millis() {
            return now;
        }

        public Inventory inventory() {
            return inventory;
        }

        public ItemStack carried() {
            return cursor;
        }

        public int menuState() {
            return state;
        }

        public HolderLookup.Provider registries() {
            registryReads++;
            return HolderLookup.Provider.create(
                    BuiltInRegistries.REGISTRY.stream().map(r -> r.asLookup()));
        }

        public void clickFeedback() {}
    }

    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No glyph rendering");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }
        };
    }
}
