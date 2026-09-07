// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class TerminalTextTest {
    private static final ResourceLocation BODY_FONT = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui");
    private static final ResourceLocation TITLE_FONT =
            ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_title");

    @Test
    void centeredTextUsesOneUnshadowedDrawAtTheMeasuredOrigin() {
        int[] observed = new int[3];
        boolean[] shadow = {true};
        TerminalText.drawCentered(
                Component.literal("频道").getVisualOrderText(),
                23,
                100,
                20,
                0xffeeeeee,
                (text, x, y, color, dropShadow) -> {
                    observed[0]++;
                    observed[1] = x;
                    observed[2] = y;
                    shadow[0] = dropShadow;
                });
        assertEquals(1, observed[0]);
        assertEquals(89, observed[1]);
        assertEquals(20, observed[2]);
        assertFalse(shadow[0]);
    }

    @Test
    void bodyAndTitleStylesSelectOnlyTheModFonts() {
        assertEquals(
                BODY_FONT, TerminalText.body(Component.literal("正文")).getStyle().getFont());
        assertEquals(
                TITLE_FONT,
                TerminalText.title(Component.literal("标题")).getStyle().getFont());
        assertFalse(TerminalText.title(Component.literal("标题")).getStyle().isBold());
    }

    @Test
    void titleDoesNotRequestTheOffsetDuplicateGlyphPassEvenForBoldInput() {
        Component title = TerminalText.title(Component.literal("共鸣终端").withStyle(style -> style.withBold(true)));
        title.getVisualOrderText().accept((index, style, codePoint) -> {
            assertFalse(style.isBold(), "WenKai titles must not request synthetic offset overdraw");
            return true;
        });
    }

    @Test
    void dedicatedRendererMapsPlainTextToBodyAndExplicitTitlesToTitle() {
        assertEquals(BODY_FONT, TerminalText.resolveFontId(Minecraft.DEFAULT_FONT));
        assertEquals(TITLE_FONT, TerminalText.resolveFontId(TITLE_FONT));
    }

    @Test
    void truncationUsesTheSelectedMetricWithoutSplittingCodePoints() {
        assertEquals("原料…", TerminalText.ellipsize("原料回流", 3, value -> value.codePointCount(0, value.length())));
        assertEquals("😀…", TerminalText.ellipsize("😀矿物", 2, value -> value.codePointCount(0, value.length())));
        assertEquals("", TerminalText.ellipsize("频道", 0, String::length));
    }
}
