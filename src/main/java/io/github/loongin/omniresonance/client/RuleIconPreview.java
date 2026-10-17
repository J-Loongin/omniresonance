// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.ResourceIdGlob;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** One bounded visual sample sequence. Discovery runs only in client ticks; rendering never enumerates registries. */
final class RuleIconPreview implements TerminalRowButton.Icon {
    static final int MAXIMUM_IMAGES = 24;
    private static final RulePreviewImage LOADING = RulePreviewImage.status("loading");
    private static final RulePreviewImage EMPTY = RulePreviewImage.status("empty");
    private static final RulePreviewImage UNAVAILABLE = RulePreviewImage.status("unavailable");
    private static final RulePreviewImage REFERENCE = RulePreviewImage.status("reference");
    private final FilterRulePreview metadata;
    private final RulePreviewSource source;
    private final List<RulePreviewImage> images = new ArrayList<>();
    private final @Nullable ResourceIdGlob glob;
    private @Nullable Iterator<ResourceLocation> candidates;
    private @Nullable ResourceLocation candidate;
    private @Nullable ResourceIdGlob.Evaluation evaluation;
    private RulePreviewImage fallback = LOADING;
    private boolean opened, done, hovered, more;
    private @Nullable RulePreviewGrid hoverGrid;
    private int index, animationTicks;

    RuleIconPreview(FilterRulePreview metadata, RulePreviewSource source) {
        this.metadata = metadata;
        this.source = source;
        glob = metadata.kind() == FilterRulePreview.Kind.GLOB ? new ResourceIdGlob(metadata.selector()) : null;
        if (metadata.kind() == FilterRulePreview.Kind.REFERENCE || metadata.kind() == FilterRulePreview.Kind.NONE) {
            fallback = metadata.kind() == FilterRulePreview.Kind.REFERENCE ? REFERENCE : UNAVAILABLE;
            done = true;
        }
    }

    /** One unit per candidate lookup/render-sample preparation or glob DP cell. No whole-registry work unit. */
    int prepare(int budget) {
        int used = 0;
        try {
            while (!done && used < budget) {
                if (!opened) {
                    candidates = source.open(metadata);
                    opened = true;
                    used++;
                    if (candidates == null) {
                        finish(UNAVAILABLE);
                        break;
                    }
                } else if (candidate == null) {
                    used++;
                    if (!candidates.hasNext()) {
                        finish(metadata.kind() == FilterRulePreview.Kind.EXACT ? UNAVAILABLE : EMPTY);
                        break;
                    }
                    candidate = candidates.next();
                    evaluation = glob == null ? null : glob.evaluate(candidate.toString());
                } else if (evaluation != null && !evaluation.done()) {
                    used += evaluation.step(budget - used);
                } else {
                    used++;
                    if (evaluation == null || evaluation.matches()) {
                        var image = source.image(metadata.type(), candidate);
                        if (image != null) {
                            if (images.size() < MAXIMUM_IMAGES) images.add(image);
                            else {
                                more = true;
                                finish(EMPTY);
                            }
                        }
                    }
                    candidate = null;
                    evaluation = null;
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            finish(UNAVAILABLE);
        }
        return used;
    }

    private void finish(RulePreviewImage empty) {
        done = true;
        fallback = empty;
        candidates = null;
        candidate = null;
        evaluation = null;
    }

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
        boolean collection = metadata.kind() == FilterRulePreview.Kind.TAG
                || metadata.kind() == FilterRulePreview.Kind.GLOB
                || metadata.kind() == FilterRulePreview.Kind.WHOLE_TYPE
                        && !io.github.loongin.omniresonance.transfer.ResourceTypes.scalar(metadata.type());
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
