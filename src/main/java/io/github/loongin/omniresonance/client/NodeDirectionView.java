// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.Objects;

/** Pure direction cycle shared by the current resource forms; never submits or mutates a server policy. */
final class NodeDirectionView {
    private NodeDirectionView() {}

    static TransferDirection opposite(TransferDirection current) {
        return switch (Objects.requireNonNull(current, "current")) {
            case INPUT -> TransferDirection.OUTPUT;
            case OUTPUT -> TransferDirection.INPUT;
        };
    }
}
