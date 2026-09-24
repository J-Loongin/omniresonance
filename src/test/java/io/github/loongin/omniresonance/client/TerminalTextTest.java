// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class TerminalTextTest {
    private static final ResourceLocation BODY_FONT = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui");
    private static final ResourceLocation TITLE_FONT =
            ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_title");

    @Test
    void hoverHelpWrapsWithinTheViewportInsteadOfRenderingOneUnboundedLine() {
        var widths = new ArrayList<Integer>();
        var font =
                new net.minecraft.client.gui.Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public List<net.minecraft.util.FormattedCharSequence> split(
                            net.minecraft.network.chat.FormattedText text, int width) {
                        widths.add(width);
                        return List.of(Component.literal(text.getString()).getVisualOrderText());
                    }
                };
        TerminalText.tooltipLines(font, Component.literal("Long help"), 640);
        TerminalText.tooltipLines(font, Component.literal("Long help"), 180);
        TerminalText.tooltipLines(font, Component.literal("Long help"), 12);
        assertEquals(List.of(220, 156, 1), widths);
    }

    @ParameterizedTest
    @CsvSource({
        "24,5,主网",
        "25,5,主网",
        "72,7,主网",
        "110,9,主网",
        "24,5,A very long network 😀名称",
        "25,5,A very long network 😀名称",
        "72,7,A very long network 😀名称",
        "110,9,A very long network 😀名称"
    })
    void readonlyNetworkDrawMatchesButtonClippingCenterAndBaseline(int width, int glyphWidth, String name) {
        var bounds = new TerminalLayout.Rect(33, 17, width, 20);
        ToIntFunction<String> measure = value -> value.codePointCount(0, value.length()) * glyphWidth;
        String label = TerminalText.networkLabel(name, bounds, measure);
        List<String> buttonDraw = new ArrayList<>();
        List<String> readonlyDraw = new ArrayList<>();
        TerminalText.drawControlText(
                Component.literal(label),
                measure.applyAsInt(label),
                bounds,
                TerminalTheme.TEXT,
                (text, x, y, color, shadow) -> {
                    StringBuilder actual = new StringBuilder();
                    text.accept((index, style, point) -> {
                        actual.appendCodePoint(point);
                        return true;
                    });
                    buttonDraw.add(actual + ":" + x + ":" + y + ":" + color + ":" + shadow);
                });
        TerminalText.drawNetworkLabel(name, bounds, measure, TerminalTheme.TEXT, (text, x, y, color, shadow) -> {
            StringBuilder actual = new StringBuilder();
            text.accept((index, style, point) -> {
                actual.appendCodePoint(point);
                return true;
            });
            readonlyDraw.add(actual + ":" + x + ":" + y + ":" + color + ":" + shadow);
        });
        assertEquals(1, buttonDraw.size());
        assertTrue(measure.applyAsInt(label) <= width - 8);
        assertEquals(buttonDraw, readonlyDraw);
    }

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
