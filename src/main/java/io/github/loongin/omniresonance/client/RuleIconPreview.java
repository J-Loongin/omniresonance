// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.ResourceIdGlob;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** One bounded visual sample sequence. Discovery runs only in client ticks; rendering never enumerates registries. */
final class RuleIconPreview extends ResourceIconPreview {
    private final FilterRulePreview metadata;
    private final RulePreviewSource source;

    /** Display-only identity, including known source/flowing fluid families; never used by filtering or storage. */
    record Identity(ResourceLocation type, ResourceLocation id) {}

    private final List<Identity> identities = new ArrayList<>();

    Identity identity(int index) {
        return identities.get(index);
    }

    boolean done() {
        return done;
    }

    boolean hasMore() {
        return more;
    }

    private final @Nullable ResourceIdGlob glob;
    private @Nullable Iterator<ResourceLocation> candidates;
    private @Nullable ResourceLocation candidate;
    private @Nullable ResourceIdGlob.Evaluation evaluation;
    private boolean opened, done;

    RuleIconPreview(FilterRulePreview metadata, RulePreviewSource source) {
        super(metadata.kind() == FilterRulePreview.Kind.TAG
                || metadata.kind() == FilterRulePreview.Kind.GLOB
                || metadata.kind() == FilterRulePreview.Kind.WHOLE_TYPE
                        && !io.github.loongin.omniresonance.transfer.ResourceTypes.scalar(metadata.type()));
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
        return prepare(budget, MAXIMUM_IMAGES + 1);
    }

    int prepareNext(int budget) {
        return prepare(budget, images.size() + 1);
    }

    private int prepare(int budget, int imageLimit) {
        int used = 0;
        try {
            while (!done && used < budget && images.size() < imageLimit) {
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
                        var identity = new Identity(metadata.type(), source.displayId(metadata.type(), candidate));
                        var image = identities.contains(identity) ? null : source.image(metadata.type(), candidate);
                        if (image != null) {
                            if (images.size() < MAXIMUM_IMAGES) {
                                images.add(image);
                                identities.add(identity);
                            } else {
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
}
