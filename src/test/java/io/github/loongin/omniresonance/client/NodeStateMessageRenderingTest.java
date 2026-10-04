// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

final class NodeStateMessageRenderingTest {
    @Test
    void pageStatusHeadingRemainsVisibleWhenNoModalHasEverBeenOpened() {
        var drawn = new ArrayList<String>();
        ResonanceNodeScreen.renderStateMessage(
                font(),
                new TerminalLayout.Rect(20, 40, 364, 186),
                Component.literal("Access unavailable"),
                Component.literal("Details"),
                (text, x, y, color, shadow) -> drawn.add(plain(text)));
        assertEquals(List.of("Access unavailable", "Details"), drawn);
    }

    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No glyph provider required");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public int width(FormattedText text) {
                return text.getString().length();
            }

            @Override
            public int width(FormattedCharSequence text) {
                return plain(text).length();
            }

            @Override
            public List<FormattedCharSequence> split(FormattedText text, int width) {
                return List.of(Component.literal(text.getString()).getVisualOrderText());
            }
        };
    }

    private static String plain(FormattedCharSequence text) {
        var result = new StringBuilder();
        text.accept((index, style, codepoint) -> {
            result.appendCodePoint(codepoint);
            return true;
        });
        return result.toString();
    }
}
