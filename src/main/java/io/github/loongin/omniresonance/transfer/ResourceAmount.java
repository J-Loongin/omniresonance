// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;

/** Immutable nonempty candidate; construction performs no native access or mutation. */
public record ResourceAmount(ResourceVariant variant, long quantity) {
    public ResourceAmount {
        Objects.requireNonNull(variant);
        if (quantity <= 0) throw new IllegalArgumentException("Candidate quantity must be positive");
    }
}
