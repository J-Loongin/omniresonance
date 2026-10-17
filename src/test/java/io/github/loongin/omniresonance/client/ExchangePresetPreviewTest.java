// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.ExchangeFrame;
import io.github.loongin.omniresonance.networking.FilterPresetPreview;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangePresetPreviewTest {
    @Test
    void actualDecodedExchangeChoicesUseTheSamePreviewAndKeepTheSelectionDraft() {
        var network = new NetworkSummary(new UUID(1, 2), new UUID(1, 3), "Network");
        var screen = new ExchangeScreen(null, network);
        var preset = new FilterPresetSummary(
                new UUID(2, 3),
                "Iron",
                1,
                1,
                true,
                new FilterPresetPreview(List.of(FilterRulePreview.exactItem("minecraft:iron_ingot")), false));
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            bytes.writeLong(4).writeVarInt(1);
            preset.write(bytes);
            screen.read(ExchangeFrame.PRESETS, bytes);
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
        screen.build(
                new Font(
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
                        int length = Math.clamp(width, 0, text.length());
                        return tail ? text.substring(text.length() - length) : text.substring(0, length);
                    }
                },
                640,
                360);
        var rows = screen.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .toList();
        assertEquals(2, rows.size());
        assertNull(rows.getFirst().icon());
        assertNotNull(rows.getLast().icon());
        assertTrue(rows.getLast().icon() instanceof PresetIconPreview);
        assertEquals("Iron", rows.getLast().getMessage().getString());
        assertNull(rows.getLast().getTooltip());
        rows.getLast().onPress();
        assertTrue(screen.draftDirty());
    }
}
