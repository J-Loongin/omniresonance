// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared screen-owned preview lifecycle for rule and preset rows; bounded cache, visible preparation and one hover. */
final class ResourcePreviewRows {
    private final RulePreviewCache cache;
    private final List<TerminalRowButton> rows = new ArrayList<>();
    private final RulePreviewHover hover = new RulePreviewHover();

    ResourcePreviewRows() {
        this(RulePreviewSource.client());
    }

    ResourcePreviewRows(RulePreviewSource source) {
        cache = new RulePreviewCache(source);
    }

    void beginRows() {
        cache.beginRows();
        hover.clear();
        rows.clear();
    }

    void attach(TerminalRowButton row, FilterPresetSummary preset) {
        row.icon(cache.get(preset.preview()));
        rows.add(row);
    }

    void attach(TerminalRowButton row, FilterRulePreview rule) {
        row.icon(cache.get(rule));
        rows.add(row);
    }

    void tick() {
        cache.tick();
    }

    void clear() {
        hover.clear();
        rows.clear();
        cache.clear();
    }

    void hide() {
        hover.clear();
    }

    RulePreviewHover updateHover(int width, int height, int mouseX, int mouseY) {
        hover.update(rows, width, height, mouseX, mouseY);
        return hover;
    }

    void render(GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        updateHover(width, height, mouseX, mouseY).render(graphics, font, mouseX, mouseY);
    }
}
