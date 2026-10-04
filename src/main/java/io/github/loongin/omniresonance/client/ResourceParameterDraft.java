// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Detached GUI-thread parameter edits shared by node and exchange owners; validation never mutates an owner. */
class ResourceParameterDraft {
    final ResourceLocation id;
    String rate, batch;
    ResourceTransferPolicy.BatchMode mode;
    boolean invalid;
    private final String originalRate, originalBatch;
    private final ResourceTransferPolicy.BatchMode originalMode;

    ResourceParameterDraft(ResourceLocation id, ResourceTransferPolicy.InputOverride value) {
        this(id, Long.toString(value.rate()), Long.toString(value.batchSize()), value.batchMode());
    }

    ResourceParameterDraft(ResourceLocation id, String rate, String batch, ResourceTransferPolicy.BatchMode mode) {
        this.id = id;
        this.rate = originalRate = rate;
        this.batch = originalBatch = batch;
        this.mode = originalMode = mode;
    }

    boolean dirty() {
        return !rate.equals(originalRate) || !batch.equals(originalBatch) || mode != originalMode;
    }

    long parsedRate() {
        long value = Long.parseLong(rate.trim());
        if (value < 1) throw new IllegalArgumentException("Nonpositive resource rate");
        return value;
    }

    ResourceTransferPolicy.InputOverride value() {
        return new ResourceTransferPolicy.InputOverride(parsedRate(), mode, Long.parseLong(batch.trim()));
    }

    TerminalResourceParameterView.Form form(Component unit, boolean batches, boolean active) {
        return TerminalResourceParameterView.rateForm(
                NodeResourcePolicyView.typeName(id),
                unit,
                rate,
                20,
                active,
                batches
                        ? new TerminalResourceParameterView.Batch(
                                NodeResourcePolicyView.text(
                                        mode == ResourceTransferPolicy.BatchMode.GREEDY ? "greedy" : "exact"),
                                batch,
                                mode == ResourceTransferPolicy.BatchMode.EXACT)
                        : null);
    }

    TerminalResourceParameterView.Bindings bindings(Runnable changed, Runnable rebuild) {
        return new TerminalResourceParameterView.Bindings(
                value -> {
                    rate = value;
                    invalid = false;
                    changed.run();
                },
                () -> {
                    mode = mode == ResourceTransferPolicy.BatchMode.GREEDY
                            ? ResourceTransferPolicy.BatchMode.EXACT
                            : ResourceTransferPolicy.BatchMode.GREEDY;
                    invalid = false;
                    changed.run();
                    rebuild.run();
                },
                value -> {
                    batch = value;
                    invalid = false;
                    changed.run();
                });
    }
}
