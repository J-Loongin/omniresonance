// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import net.minecraft.resources.ResourceLocation;

/** Detached local dialog; validates all values before applying to its owning unsaved configuration. */
final class NodeResourceSettingEditor extends ResourceParameterDraft {
    final NodeResourcePolicyDraft owner;
    final TransferDirection direction;
    final boolean unavailable;

    NodeResourceSettingEditor(NodeResourcePolicyDraft owner, ResourceLocation id) {
        super(
                id,
                initialRate(owner, id),
                owner.unavailable(id) ? "" : owner.type(id).batch,
                owner.unavailable(id) ? ResourceTransferPolicy.BatchMode.GREEDY : owner.type(id).batchMode);
        this.owner = owner;
        direction = owner.direction;
        unavailable = owner.unavailable(id);
    }

    private static String initialRate(NodeResourcePolicyDraft owner, ResourceLocation id) {
        if (!owner.hasSetting(id)) throw new IllegalArgumentException("No resource setting");
        return owner.unavailable(id) ? "" : owner.type(id).rate;
    }

    void apply() {
        if (unavailable || owner.direction != direction || !owner.hasSetting(id))
            throw new IllegalStateException("Resource setting is unavailable or changed");
        long parsedRate = parsedRate();
        ResourceTransferPolicy.TypeOverride value = direction == TransferDirection.INPUT
                ? new ResourceTransferPolicy.InputOverride(parsedRate, mode, Long.parseLong(batch.trim()))
                : new ResourceTransferPolicy.OutputOverride(parsedRate);
        var target = owner.type(id);
        target.rate = Long.toString(value.rate());
        if (value instanceof ResourceTransferPolicy.InputOverride input) {
            target.batchMode = input.batchMode();
            target.batch = Long.toString(input.batchSize());
        }
    }
}
