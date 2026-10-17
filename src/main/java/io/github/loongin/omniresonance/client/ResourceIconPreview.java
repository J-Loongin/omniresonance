// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/** Shared bounded visual surface for one rule or one preset. Client owned; no authority or resource quantities. */
abstract class ResourceIconPreview implements TerminalRowButton.Icon {
    static final int MAXIMUM_IMAGES = 24;
    protected static final RulePreviewImage LOADING = RulePreviewImage.status("loading"),
            EMPTY = RulePreviewImage.status("empty"),
            UNAVAILABLE = RulePreviewImage.status("unavailable"),
            REFERENCE = RulePreviewImage.status("reference");
    protected final List<RulePreviewImage> images = new ArrayList<>();
    protected RulePreviewImage fallback = LOADING;
    protected boolean more;
    private final boolean collection;
    private boolean hovered;
    private int index, animationTicks;
    private @Nullable RulePreviewGrid hoverGrid;

    ResourceIconPreview(boolean collection) {
        this.collection = collection;
    }

    abstract int prepare(int budget);

    void tickAnimation() {
        if (!hovered) hoverGrid = null;
        if (!hovered && images.size() > 3 && ++animationTicks >= 20) {
            index = (index + 1) % images.size();
            animationTicks = 0;
        }
        hovered = false;
    }

    RulePreviewImage current() {
        return images.isEmpty() ? fallback : images.get(index);
    }

    int imageCount() {
        return images.size();
    }

    RulePreviewImage image(int index) {
        return images.get(index);
    }

    void hover() {
        hovered = true;
    }

    public int width() {
        return inlineCount() * 18 - 2;
    }

    int inlineCount() {
        return Math.max(1, Math.min(3, images.size()));
    }

    RulePreviewImage inlineImage(int fromRight) {
        if (fromRight < 0 || fromRight >= inlineCount()) throw new IndexOutOfBoundsException(fromRight);
        return images.isEmpty() ? fallback : images.get((index + fromRight) % images.size());
    }

    @Nullable
    RulePreviewGrid grid() {
        if (!collection || images.isEmpty()) return null;
        if (hoverGrid == null) hoverGrid = new RulePreviewGrid(images, more);
        return hoverGrid;
    }

    void clearGrid() {
        hoverGrid = null;
    }

    public void render(GuiGraphics graphics, int right, int y, boolean hovered) {
        if (hovered) hover();
        for (int i = 0; i < inlineCount(); i++) inlineImage(i).render(graphics, right - 16 - 18 * i, y);
    }
}
