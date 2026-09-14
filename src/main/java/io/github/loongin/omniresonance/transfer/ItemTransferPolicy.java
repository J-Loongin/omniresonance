// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Immutable direction-discriminated item configuration. Pure validation and switching never mutate authority or simulate resources; values may be shared across threads. */
public sealed interface ItemTransferPolicy permits ItemTransferPolicy.Input, ItemTransferPolicy.Output {
    int intervalTicks();

    int rate();

    RedstoneCondition redstoneCondition();

    @Nullable
    UUID filterPresetId();

    FilterMode filterMode();

    default TransferDirection direction() {
        return this instanceof Input ? TransferDirection.INPUT : TransferDirection.OUTPUT;
    }
    /** Preserves common fields and resets the new direction's exclusive field; a same-direction request returns this value. */
    default ItemTransferPolicy switchDirection(TransferDirection direction) {
        Objects.requireNonNull(direction, "direction");
        if (direction == direction()) return this;
        return direction == TransferDirection.INPUT
                ? new Input(intervalTicks(), rate(), redstoneCondition(), filterPresetId(), filterMode(), 0)
                : new Output(intervalTicks(), rate(), redstoneCondition(), filterPresetId(), filterMode(), 0);
    }

    static ItemTransferPolicy defaults(TransferDirection direction) {
        Objects.requireNonNull(direction, "direction");
        return direction == TransferDirection.INPUT
                ? new Input(1, Integer.MAX_VALUE, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0)
                : new Output(1, Integer.MAX_VALUE, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0);
    }

    private static void validate(
            int intervalTicks, int rate, RedstoneCondition redstoneCondition, FilterMode filterMode) {
        if (intervalTicks < 1 || rate < 1) throw new IllegalArgumentException("Interval and rate must be positive");
        Objects.requireNonNull(redstoneCondition, "redstoneCondition");
        Objects.requireNonNull(filterMode, "filterMode");
    }

    record Input(
            int intervalTicks,
            int rate,
            RedstoneCondition redstoneCondition,
            @Nullable UUID filterPresetId,
            FilterMode filterMode,
            long keepCount)
            implements ItemTransferPolicy {
        public Input {
            validate(intervalTicks, rate, redstoneCondition, filterMode);
            if (keepCount < 0) throw new IllegalArgumentException("Negative keep count");
        }
    }

    record Output(
            int intervalTicks,
            int rate,
            RedstoneCondition redstoneCondition,
            @Nullable UUID filterPresetId,
            FilterMode filterMode,
            int priority)
            implements ItemTransferPolicy {
        public Output {
            validate(intervalTicks, rate, redstoneCondition, filterMode);
        }
    }
}
