// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;

/** Stable persisted transfer direction; this value performs no world access, simulation or mutation. */
public enum TransferDirection {
    INPUT("input"),
    OUTPUT("output");

    private final String serializedName;

    TransferDirection(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** Strictly decodes one stable lowercase value without aliases or fallback behavior. */
    public static TransferDirection fromSerializedName(String value) {
        return switch (Objects.requireNonNull(value, "value")) {
            case "input" -> INPUT;
            case "output" -> OUTPUT;
            default -> throw new IllegalArgumentException("Unknown transfer direction");
        };
    }
}
