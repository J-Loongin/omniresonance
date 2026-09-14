// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable internal identity. Arrays are detached; construction never mutates caller state. */
public final class ResourceVariantKey {
    private final ResourceLocation typeId;
    private final byte[] canonicalBytes;
    private final int encodedSizeBytes;
    private final int hash;

    public ResourceVariantKey(ResourceLocation typeId, byte[] canonicalBytes) {
        this.typeId = Objects.requireNonNull(typeId);
        Objects.requireNonNull(canonicalBytes);
        int typeBytes = typeId.toString().getBytes(StandardCharsets.UTF_8).length;
        if (typeBytes > 128 || canonicalBytes.length > CanonicalResourceNbt.MAX_BYTES) {
            throw new IllegalArgumentException("Resource key exceeds its encoded size boundary");
        }
        this.canonicalBytes = canonicalBytes.clone();
        encodedSizeBytes = typeBytes + canonicalBytes.length;
        hash = 31 * typeId.hashCode() + Arrays.hashCode(this.canonicalBytes);
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    public int encodedSizeBytes() {
        return encodedSizeBytes;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ResourceVariantKey key
                        && typeId.equals(key.typeId)
                        && Arrays.equals(canonicalBytes, key.canonicalBytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
