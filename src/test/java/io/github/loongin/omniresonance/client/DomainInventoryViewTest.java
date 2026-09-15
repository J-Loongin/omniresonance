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
    void quantityFormattingKeepsIntegerBoundariesAndNeverOverflows() {
        assertEquals("999", DomainInventoryView.compact(999));
        assertEquals("1K", DomainInventoryView.compact(1000));
        assertEquals("99K", DomainInventoryView.compact(99999));
        assertEquals("9.2E", DomainInventoryView.compact(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryView.compact(-1));
    }
}
