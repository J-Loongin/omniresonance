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
    // Per-key pure derived form, bounded by this immutable key's lifetime; no registry or world references.
    private volatile ResourceVariantKey normalized;

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

    /** Pure bounded component normalization for lookup/capture on any thread. Original bytes and persistent
     * bucket hashes never change. Unknown or malformed identities remain opaque and byte-exact. */
    public ResourceVariantKey normalized() {
        ResourceVariantKey result = normalized;
        if (result == null) {
            result = PersistentComponentOrder.normalize(this);
            result.normalized = result;
            normalized = result;
        }
        return result;
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

    /** Pure stable identity ordering, safe on any thread; compares owned immutable bytes without allocating copies. */
    public int compareIdentity(ResourceVariantKey other) {
        int namespace = typeId.getNamespace().compareTo(other.typeId.getNamespace());
        if (namespace != 0) return namespace;
        int path = typeId.getPath().compareTo(other.typeId.getPath());
        return path != 0 ? path : Arrays.compareUnsigned(canonicalBytes, other.canonicalBytes);
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
