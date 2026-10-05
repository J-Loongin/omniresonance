// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import org.junit.jupiter.api.Test;

class DomainInventoryViewTest {
    @Test
    void noMatchExplanationWaitsForACompletedValidResultAndKeepsStatusSeparate() {
        UUID session = new UUID(19, 1);
        try (var view = new DomainInventoryView(() -> {})) {
            view.request(session, 1);
            var widgets = new ArrayList<AbstractWidget>();
            var field = view.build(
                    font(),
                    DomainInventoryLayout.window(427, 240).content(),
                    widgets::add,
                    widget -> widgets.remove(widget),
                    ignored -> {});
            field.setValue("missing-resource");
            assertTrue(view.emptyMessage().getString().isEmpty());
            view.accept(new DomainInventoryFrame.Begin(session, 1, 0, 0, 0));
            assertTrue(view.emptyMessage().getString().isEmpty());
            view.accept(new DomainInventoryFrame.End(session, 1, 1, 0, 0));
            assertTrue(view.emptyMessage().getString().isEmpty(), "Do not reuse unprocessed query results");
            view.tick(false, () -> 0);
            assertEquals(DomainInventoryView.text("empty"), view.emptyMessage());
            assertEquals(
                    "omniresonance.inventory.read_only",
                    ((net.minecraft.network.chat.contents.TranslatableContents)
                                    view.statusMessage().getContents())
                            .getKey());
            field.setValue("next-query");
            assertTrue(view.emptyMessage().getString().isEmpty());
            field.setValue("iron |");
            view.tick(false, () -> 0);
            assertTrue(view.emptyMessage().getString().isEmpty());
            view.accept(new DomainInventoryFrame.Failed(session, 1, 2, DomainInventoryFrame.Reason.UNAVAILABLE));
            assertTrue(view.emptyMessage().getString().isEmpty());
        }
    }

    @Test
    void actualSlotPaintingReceivesMouseHoverForEmptyResourcesAndEveryPlayerSlot() {
        UUID session = new UUID(19, 2);
        try (var view = new DomainInventoryView(() -> {})) {
            view.request(session, 1);
            var widgets = new ArrayList<AbstractWidget>();
            var body = DomainInventoryLayout.window(427, 240).content();
            var geometry = new DomainInventoryLayout(body, true);
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget), ignored -> {});
            var hits = new ArrayList<Integer>();
            view.paintSlots(geometry.gridX() + 1, geometry.gridY() + 1, true, (x, y, slot, hovered) -> {
                if (hovered) hits.add(slot);
            });
            assertEquals(java.util.List.of(-1), hits);
            for (int slot = 0; slot < 36; slot++) {
                hits.clear();
                view.paintSlots(geometry.slotX(slot) + 1, geometry.slotY(slot) + 1, true, (x, y, index, hovered) -> {
                    if (hovered) hits.add(index);
                });
                assertEquals(java.util.List.of(slot), hits);
            }
            hits.clear();
            view.paintSlots(-1, -1, true, (x, y, slot, hovered) -> {
                if (hovered) hits.add(slot);
            });
            assertTrue(hits.isEmpty());
        }
    }

    @Test
    void passiveInventoryUpdatesNeverAnnounceANewSearchWithOrWithoutShift() {
        UUID session = new UUID(19, 3);
        try (var view = new DomainInventoryView(() -> {})) {
            view.request(session, 1);
            var widgets = new ArrayList<AbstractWidget>();
            var field = view.build(
                    font(),
                    DomainInventoryLayout.window(427, 240).content(),
                    widgets::add,
                    widget -> widgets.remove(widget),
                    ignored -> {});
            seed(view, session, 80);
            for (int tick = 0; tick < 4; tick++) view.tick(false, () -> 0);
            for (int revision = 1; revision <= 3; revision++) {
                byte[] data = inventoryRecord(1, 64 + revision, revision);
                view.accept(new DomainInventoryFrame.Data(session, 1, 81 + revision, false, data.length, 0, data));
                for (int tick = 0; tick < 4; tick++) {
                    view.tick(revision == 1, () -> 0);
                    assertFalse(
                            view.statusMessage().equals(DomainInventoryView.text("searching")),
                            "A background inventory refresh flashed the search status");
                }
            }
            field.setValue(" ");
            view.tick(false, () -> 0);
            assertEquals(
                    DomainInventoryView.text("searching"),
                    view.statusMessage(),
                    "An explicit unfinished query must still report its work");
            for (int tick = 0; tick < 4; tick++) view.tick(false, () -> 0);
            assertFalse(view.statusMessage().equals(DomainInventoryView.text("searching")));
        }
    }

    @Test
    void smallFirstResultCompletesWithoutAnExtraTickButLaterQueriesRemainIncremental() {
        for (int count : new int[] {86, 128}) {
            UUID session = new UUID(19, count);
            try (var view = new DomainInventoryView(() -> {})) {
                view.request(session, 1);
                var widgets = new ArrayList<AbstractWidget>();
                var field = view.build(
                        font(),
                        DomainInventoryLayout.window(427, 240).content(),
                        widgets::add,
                        widget -> widgets.remove(widget),
                        ignored -> {});
                seed(view, session, count);
                long[] clock = {0};
                long unitNanos = count == 86 ? 20_000 : 10_000;
                view.tick(false, () -> clock[0] += unitNanos);
                assertTrue(widgets.size() > 3, "Small complete inventory unnecessarily waited for another tick");
                assertFalse(view.statusMessage().equals(DomainInventoryView.text("searching")));
                assertTrue(clock[0] < 4_100_000, "Initial work exceeded its soft time boundary");
                field.setValue(" ");
                view.tick(false, () -> 0);
                assertEquals(
                        DomainInventoryView.text("searching"),
                        view.statusMessage(),
                        "The first-open allowance must not persist for ordinary queries");
            }
        }
    }

    @Test
    void initialPreparationStillWaitsForValidationAndYieldsForLargeOrSlowInventories() {
        for (int count : new int[] {86, 129}) {
            UUID session = new UUID(20, count);
            try (var view = new DomainInventoryView(() -> {})) {
                view.request(session, 1);
                var widgets = new ArrayList<AbstractWidget>();
                view.build(
                        font(),
                        DomainInventoryLayout.window(427, 240).content(),
                        widgets::add,
                        widget -> widgets.remove(widget),
                        ignored -> {});
                view.accept(new DomainInventoryFrame.Begin(session, 1, 0, count, count));
                for (int id = 1; id <= count; id++) {
                    byte[] data = inventoryRecord(id, 64, 0);
                    view.accept(new DomainInventoryFrame.Data(session, 1, id, true, data.length, 0, data));
                }
                view.tick(false, () -> 0);
                assertEquals(3, widgets.size(), "Unvalidated resources became visible");
                view.accept(new DomainInventoryFrame.End(session, 1, count + 1, count, 0));
                long[] clock = {0};
                view.tick(false, () -> count == 86 ? clock[0] += 100_000 : 0);
                assertEquals(3, widgets.size(), "Large or expensive work was forced through in one tick");
                assertTrue(clock[0] <= 4_100_000);
                for (int tick = 0; tick < 5; tick++) view.tick(false, () -> 0);
                assertTrue(widgets.size() > 3, "Yielded work did not resume");
            }
        }
    }

    @Test
    void inventoryRowReplacementClearsOldFocusAndKeepsSearchFocus() {
        UUID session = new UUID(19, 4);
        try (var view = new DomainInventoryView(() -> {})) {
            var host = new InventoryHost();
            var body = DomainInventoryLayout.window(427, 240).content();
            var geometry = new DomainInventoryLayout(body, true);
            view.request(session, 1);
            var field = view.build(font(), body, host::add, host::remove, host::setFocused);
            seed(view, session, 80);
            for (int tick = 0; tick < 4; tick++) view.tick(false, () -> 0);
            var old = host.children().get(3);
            host.setFocused(old);
            view.wheel(geometry.gridX() + 1, geometry.gridY() + 1, -1);
            org.junit.jupiter.api.Assertions.assertNull(host.getFocused(), "Invisible cell retained keyboard focus");
            assertFalse(old.isFocused());
            assertFalse(host.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0));
            host.setFocused(field);
            field.setCursorPosition(0);
            view.wheel(geometry.gridX() + 1, geometry.gridY() + 1, 1);
            org.junit.jupiter.api.Assertions.assertSame(field, host.getFocused());
            assertTrue(field.isFocused());
        }
    }

    private static byte[] inventoryRecord(int id, long amount, long revision) {
        var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                net.minecraft.resources.ResourceLocation.parse("example:opaque"),
                java.nio.ByteBuffer.allocate(4).putInt(id).array());
        return io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(
                new io.github.loongin.omniresonance.storage.DomainLedger.Change(id, key, amount, revision));
    }

    private static void seed(DomainInventoryView view, UUID session, int count) {
        view.accept(new DomainInventoryFrame.Begin(session, 1, 0, count, count));
        for (int id = 1; id <= count; id++) {
            var data = inventoryRecord(id, 64, 0);
            view.accept(new DomainInventoryFrame.Data(session, 1, id, true, data.length, 0, data));
        }
        view.accept(new DomainInventoryFrame.End(session, 1, count + 1, count, 0));
    }

    private static final class InventoryHost extends net.minecraft.client.gui.screens.Screen {
        private InventoryHost() {
            super(net.minecraft.network.chat.Component.empty());
        }

        private void add(AbstractWidget widget) {
            addRenderableWidget(widget);
        }

        private void remove(net.minecraft.client.gui.components.events.GuiEventListener widget) {
            removeWidget(widget);
        }
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

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.clamp(width, 0, text.length()));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean tail) {
                return plainSubstrByWidth(text, width);
            }
        };
    }

    @Test
    void recipeHoverUsesActualVisibleResourceAndRejectsEmptyOrOutsideCells() {
        var provider = net.minecraft.core.HolderLookup.Provider.create(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY.stream()
                        .map(registry -> registry.asLookup()));
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 3);
        stack.set(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Named iron"));
        var variant = io.github.loongin.omniresonance.transfer.ItemVariant.from(stack, provider);
        var session = new UUID(91, 1);
        try (var view = new DomainInventoryView(() -> {})) {
            view.request(session, 1);
            var widgets = new ArrayList<AbstractWidget>();
            var body = TerminalLayout.calculate(427, 240).content();
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget), ignored -> {});
            var change = new io.github.loongin.omniresonance.storage.DomainLedger.Change(1, variant.key(), 3, 0);
            var data = io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(change);
            view.accept(new DomainInventoryFrame.Begin(session, 1, 0, 1, 1));
            view.accept(new DomainInventoryFrame.Data(session, 1, 1, true, data.length, 0, data));
            view.accept(new DomainInventoryFrame.End(session, 1, 2, 1, 0));
            for (int tick = 0; tick < 4; tick++) view.tick(false, () -> 0);
            var cell = widgets.getLast();
            var hover = view.recipeHover(cell.getX() + 1, cell.getY() + 1, provider);
            var ingredient = (net.minecraft.world.item.ItemStack) hover.value();
            assertEquals(1, ingredient.getCount());
            assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, ingredient));
            assertEquals(cell.getX(), hover.area().x());
            org.junit.jupiter.api.Assertions.assertNull(
                    view.recipeHover(body.right() + 1, body.bottom() + 1, provider));
            int x = cell.getX(), y = cell.getY();
            var removed = io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(
                    new io.github.loongin.omniresonance.storage.DomainLedger.Change(1, variant.key(), 0, 1));
            view.accept(new DomainInventoryFrame.Data(session, 1, 3, false, removed.length, 0, removed));
            view.tick(true, () -> 0);
            assertEquals(4, widgets.size(), "The zero placeholder must remain while Shift is held");
            assertEquals(x, widgets.getLast().getX());
            assertEquals(y, widgets.getLast().getY());
            org.junit.jupiter.api.Assertions.assertNull(view.recipeHover(x + 1, y + 1, provider));
            var returned = io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(
                    new io.github.loongin.omniresonance.storage.DomainLedger.Change(2, variant.key(), 8, 2));
            view.accept(new DomainInventoryFrame.Data(session, 1, 4, false, returned.length, 0, returned));
            view.tick(true, () -> 0);
            assertEquals(4, widgets.size());
            assertEquals(x, widgets.getLast().getX());
            assertEquals(y, widgets.getLast().getY());
            assertTrue(view.recipeHover(x + 1, y + 1, provider) != null);
            var emptyAgain = io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(
                    new io.github.loongin.omniresonance.storage.DomainLedger.Change(2, variant.key(), 0, 3));
            view.accept(new DomainInventoryFrame.Data(session, 1, 5, false, emptyAgain.length, 0, emptyAgain));
            view.tick(true, () -> 0);
            view.tick(false, () -> 0);
            assertEquals(3, widgets.size(), "Releasing Shift removes the empty placeholder");
        }
    }

    @Test
    void realResourceButtonsCoverTheWholeCompactSlotWithoutDepositGaps() {
        UUID session = new UUID(9, 2);
        try (var view = new DomainInventoryView(() -> {})) {
            view.request(session, 1);
            var widgets = new ArrayList<AbstractWidget>();
            var body = TerminalLayout.calculate(427, 240).content();
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget), ignored -> {});
            var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    net.minecraft.resources.ResourceLocation.parse("example:opaque"), new byte[] {1});
            byte[] record = io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.encode(
                    new io.github.loongin.omniresonance.storage.DomainLedger.Change(1, key, 4, 0));
            view.accept(new DomainInventoryFrame.Begin(session, 1, 0, 1, 1));
            view.accept(new DomainInventoryFrame.Data(session, 1, 1, true, record.length, 0, record));
            view.accept(new DomainInventoryFrame.End(session, 1, 2, 1, 0));
            for (int tick = 0; tick < 4; tick++) view.tick(false, () -> 0);
            assertEquals(4, widgets.size());
            var cell = widgets.getLast();
            assertEquals(18, cell.getWidth());
            assertEquals(18, cell.getHeight());
            assertTrue(cell.isMouseOver(cell.getRight() - 1, cell.getBottom() - 1));
            assertTrue(
                    view.mouseClicked(cell.getX() + 1, cell.getY() + 1, 0),
                    "Read-only clicks must be consumed before the screen focuses a resource button");
            assertTrue(view.mouseClicked(cell.getX() + 1, cell.getY() + 1, 1));
            assertFalse(cell.isFocused());
        }
    }

    @Test
    void productionToolbarAcceptsSearchWhileLoadingAndKeepsItsInstanceAndFocus() {
        UUID session = new UUID(9, 1);
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            int[] retries = {0};
            try (var view = new DomainInventoryView(() -> retries[0]++)) {
                view.request(session, 1);
                var widgets = new ArrayList<AbstractWidget>();
                var body = TerminalLayout.calculate(size[0], size[1]).content();
                var field = view.build(font(), body, widgets::add, widget -> widgets.remove(widget), ignored -> {});
                assertTrue(field.active);
                assertEquals(3, widgets.size());
                assertTrue(widgets.stream().allMatch(w -> w.getX() >= body.x() && w.getRight() <= body.right()));
                view.accept(new DomainInventoryFrame.Begin(session, 1, 0, 0, 0));
                view.tick(false, () -> 0);
                assertTrue(field.active);
                view.accept(new DomainInventoryFrame.End(session, 1, 1, 0, 0));
                view.tick(false, () -> 0);
                assertTrue(field.active);
                field.setFocused(true);
                field.setValue("iron");
                view.tick(false, () -> 0);
                assertTrue(widgets.contains(field));
                assertTrue(field.isFocused());
                assertEquals("iron", field.getValue());
                assertEquals(0, retries[0]);
                view.accept(new DomainInventoryFrame.Failed(session, 1, 2, DomainInventoryFrame.Reason.UNAVAILABLE));
                view.tick(false, () -> 0);
                assertFalse(field.active);
                assertEquals(0, retries[0]);
                ((TerminalButton) widgets.getLast()).onPress();
                assertEquals(1, retries[0]);
            }
        }
    }

    @Test
    void slotQuantitiesUseVanillaDigitsAndFitWithoutUnits() {
        for (long amount : new long[] {0, 1, 999, 1000, 1999, 999999, 329638725824L, Long.MAX_VALUE}) {
            for (boolean buckets : new boolean[] {false, true}) {
                var count = DomainInventoryView.slotCount(amount, buckets);
                assertEquals(
                        net.minecraft.network.chat.Style.DEFAULT_FONT,
                        count.getStyle().getFont());
                assertTrue(count.getString().length() <= 5);
                assertFalse(count.getString().contains("B"));
            }
        }
        assertEquals("329M", DomainInventoryView.slotCount(329638725824L, true).getString());
    }

    @Test
    void quantityFormattingKeepsIntegerBoundariesAndNeverOverflows() {
        assertEquals("999", DomainInventoryView.compact(999));
        assertEquals("1K", DomainInventoryView.compact(1000));
        assertEquals("99K", DomainInventoryView.compact(99999));
        assertEquals("9.2E", DomainInventoryView.compact(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryView.compact(-1));
    }
}
