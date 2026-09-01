// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;

/** Stable persistence states; B1 creates only blank while preserving structurally valid linked data for B2. */
public enum NodeLinkState {
    BLANK("blank"),
    LINKED("linked");

    private final String serializedName;

    NodeLinkState(String serializedName) {
        this.serializedName = serializedName;
    }

    /** Returns the stable lowercase on-disk identifier. */
    public String serializedName() {
        return serializedName;
    }

    /** Decodes only exact stable names; missing, aliases and unknown values fail before state creation. */
    public static NodeLinkState fromSerialized(String value) {
        return switch (Objects.requireNonNull(value, "value")) {
            case "blank" -> BLANK;
            case "linked" -> LINKED;
            default -> throw new IllegalArgumentException("Unknown node link state");
        };
    }
}
