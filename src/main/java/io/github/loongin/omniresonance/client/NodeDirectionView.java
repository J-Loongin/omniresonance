// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Pure client draft for one server-owned direct binding or domain direction. */
final class NodeDirectionView {
    private NodeDirectionView() {}

    static TransferDirection opposite(TransferDirection current) {
        return switch (Objects.requireNonNull(current, "current")) {
            case INPUT -> TransferDirection.OUTPUT;
            case OUTPUT -> TransferDirection.INPUT;
        };
    }

    static boolean commitsImmediately(@Nullable TransferDirection original) {
        return original != null;
    }

    record Draft(@Nullable TransferDirection original, TransferDirection selected) {
        Draft {
            Objects.requireNonNull(selected, "selected");
        }

        static Draft start(@Nullable TransferDirection original) {
            return new Draft(original, original == null ? TransferDirection.INPUT : original);
        }

        Draft select(TransferDirection direction) {
            Objects.requireNonNull(direction, "direction");
            return direction == selected ? this : new Draft(original, direction);
        }

        boolean dirty() {
            return selected != original;
        }

        boolean canRemove() {
            return original != null;
        }
    }
}
