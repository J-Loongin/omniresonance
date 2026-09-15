// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import net.minecraft.resources.ResourceLocation;

/** Detached local dialog; validates all values before applying to its owning unsaved configuration. */
final class NodeResourceSettingEditor {
    final NodeResourcePolicyDraft owner;
    final ResourceLocation id;
    final TransferDirection direction;
    final boolean unavailable;
    private final String originalRate;
    private final String originalBatch;
    private final ResourceTransferPolicy.BatchMode originalMode;
    String rate;
    String batch;
    ResourceTransferPolicy.BatchMode mode;
    boolean invalid;

    NodeResourceSettingEditor(NodeResourcePolicyDraft owner, ResourceLocation id) {
        if (!owner.hasSetting(id)) throw new IllegalArgumentException("No resource setting");
        this.owner = owner;
        this.id = id;
        direction = owner.direction;
        unavailable = owner.unavailable(id);
        rate = unavailable ? "" : owner.type(id).rate;
        batch = unavailable ? "" : owner.type(id).batch;
        mode = unavailable ? ResourceTransferPolicy.BatchMode.GREEDY : owner.type(id).batchMode;
        originalRate = rate;
        originalBatch = batch;
        originalMode = mode;
    }

    boolean dirty() {
        return !rate.equals(originalRate) || !batch.equals(originalBatch) || mode != originalMode;
    }

    void apply() {
        if (unavailable || owner.direction != direction || !owner.hasSetting(id))
            throw new IllegalStateException("Resource setting is unavailable or changed");
        int parsedRate = Integer.parseInt(rate.trim());
        ResourceTransferPolicy.TypeOverride value = direction == TransferDirection.INPUT
                ? new ResourceTransferPolicy.InputOverride(parsedRate, mode, Long.parseLong(batch.trim()))
                : new ResourceTransferPolicy.OutputOverride(parsedRate);
        var target = owner.type(id);
        target.rate = Integer.toString(value.rate());
        if (value instanceof ResourceTransferPolicy.InputOverride input) {
            target.batchMode = input.batchMode();
            target.batch = Long.toString(input.batchSize());
        }
    }
}
