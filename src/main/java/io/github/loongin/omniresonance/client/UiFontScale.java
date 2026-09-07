// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.resources.ResourceLocation;

/** Finite raster profiles; font providers and lazy glyph textures remain owned by Minecraft's resource manager. */
enum UiFontScale {
    X2(2),
    X3(3),
    X4(4),
    X8(8),
    X16(16);

    private static final UiFontScale[] PROFILES = values();
    private final int sampling;
    private final ResourceLocation bodyFont;
    private final ResourceLocation titleFont;

    UiFontScale(int sampling) {
        this.sampling = sampling;
        String suffix = sampling == 2 ? "" : "_scale_" + sampling;
        bodyFont = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui" + suffix);
        titleFont = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_title" + suffix);
    }

    static UiFontScale forGuiScale(double guiScale) {
        if (!Double.isFinite(guiScale) || guiScale <= 0) {
            throw new IllegalArgumentException("Resolved GUI scale must be finite and positive");
        }
        for (UiFontScale profile : PROFILES) {
            if (guiScale <= profile.sampling) {
                return profile;
            }
        }
        // Bound loaded resources even on unusually large displays; never allocate a profile per resize.
        return X16;
    }

    static boolean isTitleFont(ResourceLocation id) {
        for (UiFontScale profile : PROFILES) {
            if (profile.titleFont.equals(id)) {
                return true;
            }
        }
        return false;
    }

    int sampling() {
        return sampling;
    }

    ResourceLocation bodyFont() {
        return bodyFont;
    }

    ResourceLocation titleFont() {
        return titleFont;
    }
}
