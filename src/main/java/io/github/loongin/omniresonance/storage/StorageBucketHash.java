// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Pure thread-safe persistent partitioning; no world, simulation, mutation or retained caller state. */
public final class StorageBucketHash {
    public static final int VERSION = 1;
    public static final int COUNT = 64;
    private static final byte[] DOMAIN = "omniresonance:storage_bucket\0".getBytes(StandardCharsets.UTF_8);

    private StorageBucketHash() {}

    /**
     * Returns the SHA-256 low six bits for one immutable key, rejecting null before work.
     * Cost is linear in key bytes; callers retain the bucket for existing ledger entries instead of rehashing
     * on quantity updates. The digest and detached arrays live only for this call; no cache is allocated.
     */
    public static int bucket(ResourceVariantKey key) {
        Objects.requireNonNull(key);
        byte[] type = key.typeId().toString().getBytes(StandardCharsets.UTF_8);
        byte[] canonical = key.canonicalBytes();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", failure);
        }
        digest.update(DOMAIN);
        updateLength(digest, type.length);
        digest.update(type);
        updateLength(digest, canonical.length);
        digest.update(canonical);
        byte[] result = digest.digest();
        return result[result.length - 1] & (COUNT - 1);
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
    }
}
