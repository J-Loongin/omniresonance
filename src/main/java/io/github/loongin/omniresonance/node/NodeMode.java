// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;

/** Stable persisted node modes; this value owns no configuration, world state or simulation behavior. */
public enum NodeMode {
    UNCONFIGURED("unconfigured"),
    DIRECT("direct"),
    DOMAIN("domain");

    private final String serializedName;

    NodeMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** Returns the stable lowercase persistence identifier without mutation or world access. */
    public String serializedName() {
        return serializedName;
    }

    /** Decodes only exact stable mode identifiers without mutation, simulation or world access. */
    public static NodeMode fromSerialized(String value) {
        return switch (Objects.requireNonNull(value, "value")) {
            case "unconfigured" -> UNCONFIGURED;
            case "direct" -> DIRECT;
            case "domain" -> DOMAIN;
            default -> throw new IllegalArgumentException("Unknown node mode");
        };
    }
}
