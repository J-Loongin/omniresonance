// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

/** Internal identity. The key is immutable and shareable; native operations remain server-thread owned. */
public interface ResourceVariant {
    /** Returns the immutable identity without native access, mutation, or simulation. */
    ResourceVariantKey key();
}
