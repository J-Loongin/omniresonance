// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Client-owned text draft. Parsing never changes the saved value; confirmed direction changes discard old fields. */
final class NodeItemPolicyDraft {
    final ItemTransferPolicy original;
    TransferDirection direction;
    String interval;
    String rate;
    String quantity;
    RedstoneCondition redstone;
    FilterMode filterMode;

    @Nullable
    UUID presetId;

    @Nullable
    String presetName;

    NodeItemPolicyDraft(ItemTransferPolicy policy, @Nullable String presetName) {
        original = Objects.requireNonNull(policy);
        direction = policy.direction();
        interval = Integer.toString(policy.intervalTicks());
        rate = Integer.toString(policy.rate());
        quantity = switch (policy) {
            case ItemTransferPolicy.Input input -> Long.toString(input.keepCount());
            case ItemTransferPolicy.Output output -> Integer.toString(output.priority());
        };
        redstone = policy.redstoneCondition();
        filterMode = policy.filterMode();
        presetId = policy.filterPresetId();
        this.presetName = presetName;
    }

    ItemTransferPolicy policy() {
        int intervalTicks = Integer.parseInt(interval.trim());
        int itemRate = Integer.parseInt(rate.trim());
        return direction == TransferDirection.INPUT
                ? new ItemTransferPolicy.Input(
                        intervalTicks, itemRate, redstone, presetId, filterMode, Long.parseLong(quantity.trim()))
                : new ItemTransferPolicy.Output(
                        intervalTicks, itemRate, redstone, presetId, filterMode, Integer.parseInt(quantity.trim()));
    }

    void confirmDirectionChange() {
        direction = NodeDirectionView.opposite(direction);
        quantity = "0";
    }
}
