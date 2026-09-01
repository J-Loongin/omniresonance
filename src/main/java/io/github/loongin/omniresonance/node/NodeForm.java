// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;

/** Stable physical node forms used by world blocks and later authoritative records. */
public enum NodeForm {
    BLOCK("block"),
    PANEL("panel");

    private final String serializedName;

    NodeForm(String serializedName) {
        this.serializedName = serializedName;
    }

    /** Returns the stable lowercase identifier without world or persistence mutation. */
    public String serializedName() {
        return serializedName;
    }

    /** Decodes only exact stable form names without accessing or modifying a world. */
    public static NodeForm fromSerialized(String value) {
        return switch (Objects.requireNonNull(value, "value")) {
            case "block" -> BLOCK;
            case "panel" -> PANEL;
            default -> throw new IllegalArgumentException("Unknown node form");
        };
    }
}
