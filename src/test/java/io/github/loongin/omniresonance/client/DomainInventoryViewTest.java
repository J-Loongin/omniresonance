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
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget));
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
            view.build(font(), body, widgets::add, widget -> widgets.remove(widget));
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
                var field = view.build(font(), body, widgets::add, widget -> widgets.remove(widget));
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
