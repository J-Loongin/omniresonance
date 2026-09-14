// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.Test;

class M2ServerConfigTest {
    static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("scheduler.cpu_budget_millis_per_tick", 2.0),
            Map.entry("scheduler.capability_calls_per_tick", 4096),
            Map.entry("scheduler.empty_checks_before_sleep", 3),
            Map.entry("scheduler.idle_backoff_ticks", List.of(2, 4, 8, 20)),
            Map.entry("scheduler.failure_threshold", 3),
            Map.entry("scheduler.breaker_backoff_ticks", List.of(200, 400, 800, 1200)),
            Map.entry("scheduler.slow_call_threshold_millis", 1.0),
            Map.entry("network_limits.filter_presets_per_owner", 512),
            Map.entry("network_limits.rules_per_filter_preset", 1024),
            Map.entry("recovery.max_variants_per_network", 1024),
            Map.entry("recovery.max_encoded_bytes_per_network", 8388608L));

    @Test
    void allElevenConsumedSettingsHaveNativeDefaultsAndCompleteComments() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = CommentedConfig.inMemory();
        config.spec().correct(values);
        for (var entry : DEFAULTS.entrySet()) {
            assertEquals(entry.getValue(), values.get(entry.getKey()), entry.getKey());
            String comment = values.getComment(entry.getKey());
            for (String label :
                    List.of("Type/类型", "Unit/单位", "Default/默认值", "Range/合法范围", "Special values/特殊值", "Reload/重载"))
                assertTrue(comment.contains(label), entry.getKey());
        }
    }

    @Test
    void invalidBackoffReloadPreservesEntireCandidateAndOldEpoch() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = load(config);
        ServerConfig.State previous = config.captureLoading(true);
        values.set("scheduler.idle_backoff_ticks", List.of(4, 2));
        values.set("network_limits.networks_per_owner", 0);
        config.spec().afterReload();
        assertSame(previous, config.captureReloading(true));
        assertEquals(32, config.latest().settings().networksPerOwner());
    }

    @Test
    void nativeCorrectionRejectsWrongTypesAndEveryBoundaryViolation() {
        Map<String, Object> invalid = Map.ofEntries(
                Map.entry("scheduler.cpu_budget_millis_per_tick", 0.01),
                Map.entry("scheduler.capability_calls_per_tick", 0),
                Map.entry("scheduler.empty_checks_before_sleep", 101),
                Map.entry("scheduler.idle_backoff_ticks", List.of(2, 1)),
                Map.entry("scheduler.failure_threshold", 0),
                Map.entry("scheduler.breaker_backoff_ticks", List.of(72001)),
                Map.entry("scheduler.slow_call_threshold_millis", Double.NaN),
                Map.entry("network_limits.filter_presets_per_owner", -2),
                Map.entry("network_limits.rules_per_filter_preset", 65536),
                Map.entry("recovery.max_variants_per_network", 63),
                Map.entry("recovery.max_encoded_bytes_per_network", 268435457L));
        ServerConfig config = new ServerConfig();
        CommentedConfig values = CommentedConfig.inMemory();
        config.spec().correct(values);
        for (var entry : invalid.entrySet()) values.set(entry.getKey(), entry.getValue());
        config.spec().correct(values);
        for (var entry : DEFAULTS.entrySet())
            assertEquals(entry.getValue(), values.get(entry.getKey()), entry.getKey());
    }

    @Test
    void immutableSnapshotsConsumeReloadedValuesAndDetachStageLists() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = load(config);
        ServerConfig.State original = config.captureLoading(true);
        values.set("scheduler.capability_calls_per_tick", 1);
        values.set("scheduler.idle_backoff_ticks", List.of(1, 7));
        values.set("recovery.max_encoded_bytes_per_network", 1048576);
        values.set("network_limits.filter_presets_per_owner", -1);
        config.spec().afterReload();
        ServerSettings next = config.captureReloading(true).settings();
        assertEquals(1, next.scheduler().capabilityCallsPerTick());
        assertEquals(List.of(1, 7), next.scheduler().idleBackoffTicks());
        assertEquals(1048576L, next.recoveryLimits().maxEncodedBytesPerNetwork());
        assertEquals(-1, next.filterLimits().filterPresetsPerOwner());
        assertEquals(4096, original.settings().scheduler().capabilityCallsPerTick());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> next.scheduler().idleBackoffTicks().clear());
        java.util.ArrayList<Integer> stages = new java.util.ArrayList<>(List.of(1, 2));
        var snapshot = new ServerSettings.Scheduler(2, 1, 1, stages, 1, List.of(1), 1);
        stages.clear();
        assertEquals(List.of(1, 2), snapshot.idleBackoffTicks());
    }

    @Test
    void directSnapshotValidationRejectsNonfiniteAndInvalidStages() {
        for (double cpu : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.09, 10.01})
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new ServerSettings.Scheduler(cpu, 1, 1, List.of(1), 1, List.of(1), 1));
        for (List<Integer> stages :
                List.of(List.<Integer>of(), List.of(2, 1), List.of(1201), List.of(1, 1, 1, 1, 1, 1, 1, 1, 1)))
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new ServerSettings.Scheduler(2, 1, 1, stages, 1, List.of(1), 1));
    }

    static CommentedConfig load(ServerConfig config) {
        CommentedConfig values = CommentedConfig.inMemory();
        config.spec().correct(values);
        ModConfig nativeConfig = new ConfigTracker()
                .registerConfig(
                        ModConfig.Type.SERVER,
                        config.spec(),
                        ModList.get().getModContainerById("omniresonance").orElseThrow(),
                        "m2-test-server.toml");
        ConfigTracker.acceptSyncedConfig(
                nativeConfig, new TomlWriter().writeToString(values).getBytes(StandardCharsets.UTF_8));
        return nativeConfig.getLoadedConfig().config();
    }
}
