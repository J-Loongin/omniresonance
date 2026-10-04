// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class UiFontScaleTest {
    @Test
    void commonScreensMeetTheApprovedFourTimesRasterBaseline() {
        for (double scale : new double[] {1, 2, 3, 4})
            assertEquals(4, UiFontScale.forGuiScale(scale).sampling());
    }

    @ParameterizedTest
    @CsvSource({"1,4", "2,4", "3,4", "4,4", "5,8", "8,8", "9,16", "16,16", "32,16"})
    void choosesMatchingOrHigherPrecisionFromABoundedSet(double guiScale, int sampling) {
        UiFontScale scale = UiFontScale.forGuiScale(guiScale);
        assertEquals(sampling, scale.sampling());
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("omniresonance", sampling == 2 ? "ui" : "ui_scale_" + sampling),
                scale.bodyFont());
        assertEquals(
                ResourceLocation.fromNamespaceAndPath(
                        "omniresonance", sampling == 2 ? "ui_title" : "ui_title_scale_" + sampling),
                scale.titleFont());
    }

    @Test
    void automaticOptionValueIsNotMistakenForTheResolvedWindowScale() {
        assertThrows(IllegalArgumentException.class, () -> UiFontScale.forGuiScale(0));
        assertThrows(IllegalArgumentException.class, () -> UiFontScale.forGuiScale(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> UiFontScale.forGuiScale(Double.POSITIVE_INFINITY));
    }

    @ParameterizedTest
    @CsvSource({"2", "3", "4", "8", "16"})
    void tooltipStylesAndDedicatedTextMetricsSelectTheSameFontSets(int sampling) {
        UiFontScale scale = UiFontScale.forGuiScale(sampling);
        Component body = TerminalText.body(Component.literal("频道"), scale);
        Component title = TerminalText.title(Component.literal("主网络"), scale);
        assertEquals(scale.bodyFont(), body.getStyle().getFont());
        assertEquals(scale.titleFont(), title.getStyle().getFont());
        assertFalse(title.getStyle().isBold());
        assertEquals(scale.bodyFont(), TerminalText.resolveFontId(Minecraft.DEFAULT_FONT, scale));
        assertEquals(
                scale.bodyFont(), TerminalText.resolveFontId(body.getStyle().getFont(), scale));
        assertEquals(
                scale.titleFont(), TerminalText.resolveFontId(title.getStyle().getFont(), scale));
        assertEquals(
                scale.titleFont(),
                TerminalText.resolveFontId(UiFontScale.forGuiScale(3).titleFont(), scale));
    }
}
