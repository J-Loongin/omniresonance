// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class StorageBucketHashTest {
    @Test
    void fixedVectorsCoverNativeAndOpaqueKeys() {
        assertEquals(1, StorageBucketHash.VERSION);
        assertEquals(64, StorageBucketHash.COUNT);
        assertEquals(51, StorageBucketHash.bucket(key("minecraft:item", new byte[] {1, 2})));
        assertEquals(9, StorageBucketHash.bucket(key("neoforge:energy", new byte[0])));
        assertEquals(20, StorageBucketHash.bucket(key("example:missing", new byte[] {0, -1})));
        assertThrows(NullPointerException.class, () -> StorageBucketHash.bucket(null));
    }

    private static ResourceVariantKey key(String type, byte[] bytes) {
        return new ResourceVariantKey(ResourceLocation.parse(type), bytes);
    }
}
