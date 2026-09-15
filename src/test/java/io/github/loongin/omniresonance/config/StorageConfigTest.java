// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

final class StorageConfigTest {
    @Test
    void nativeReloadPublishesQuotaInTheSharedSnapshot() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = M2ServerConfigTest.load(config);
        config.captureLoading(true);
        values.set("storage.variant_limit_per_network", 0L);
        config.spec().afterReload();
        assertEquals(0, config.captureReloading(true).settings().storageVariantLimitPerNetwork());
        values.set("storage.variant_limit_per_network", Long.MAX_VALUE);
        config.spec().afterReload();
        assertEquals(Long.MAX_VALUE, config.captureReloading(true).settings().storageVariantLimitPerNetwork());
    }

    @Test
    void nativeStorageQuotaSupportsFullLongRangeAndCompleteBilingualContract() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = CommentedConfig.inMemory();
        config.spec().correct(values);
        String key = "storage.variant_limit_per_network";
        assertEquals(-1L, ((Number) values.get(key)).longValue());
        assertEquals(-1L, ServerSettings.defaults().storageVariantLimitPerNetwork());
        for (long value : new long[] {-1, 0, 1, Integer.MAX_VALUE + 1L, Long.MAX_VALUE}) {
            values.set(key, value);
            config.spec().correct(values);
            assertEquals(value, ((Number) values.get(key)).longValue());
        }
        for (Object invalid : new Object[] {-2L, 0.5, "unlimited"}) {
            values.set(key, invalid);
            config.spec().correct(values);
            assertEquals(-1L, ((Number) values.get(key)).longValue());
        }
        String comment = values.getComment(key);
        assertTrue(comment.contains("9223372036854775807"));
        assertTrue(comment.contains("既有"));
        assertTrue(comment.contains("Special values/特殊值"));
        assertTrue(comment.contains("Reload/重载"));
    }
}
