// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.junit.jupiter.api.Test;

class ServerConfigTest {
    private static final String KEY = "network_limits.networks_per_owner";

    @Test
    void nativeCorrectionGeneratesTheConfiguredDefault() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = CommentedConfig.inMemory();

        config.spec().correct(values);

        assertEquals(32, values.getInt(KEY));
        assertTrue(config.spec().isCorrect(values));
    }

    @Test
    void initialStateUsesValidatedDefaultsWithoutClaimingALoadedLifecycle() {
        ServerConfig.State state = new ServerConfig().latest();

        assertEquals(0, state.epoch());
        assertEquals(0, state.revision());
        assertFalse(state.loaded());
        assertEquals(32, state.settings().networksPerOwner());
        assertEquals(state.settings(), ServerSettings.defaults());
    }

    @Test
    void nativeCorrectionPreservesLegalTomlIntegersIncludingSpecialValues() {
        for (int value : new int[] {-1, 0, 1, 1024}) {
            ServerConfig config = new ServerConfig();
            CommentedConfig values = parseValue(Integer.toString(value));
            config.spec().correct(values);
            acceptNative(config, values);

            ServerConfig.State captured = config.captureLoading(true);

            assertEquals(value, values.getInt(KEY));
            assertEquals(value, captured.settings().networksPerOwner());
            assertTrue(captured.loaded());
        }
    }

    @Test
    void nativeCorrectionDefaultsWrongTypesAndOutOfRangeValuesWithoutClamping() {
        for (String value : List.of("\"wrong\"", "\"1\"", "-2", "1025", "2147483648", "1.5", "true", "[]")) {
            ServerConfig config = new ServerConfig();
            CommentedConfig values = parseValue(value);

            config.spec().correct(values);
            acceptNative(config, values);

            assertEquals(32, values.getInt(KEY), value);
            assertEquals(32, config.captureLoading(true).settings().networksPerOwner(), value);
        }
    }

    @Test
    void nativeCorrectionRepairsWrongSectionStructure() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = new TomlParser().parse("network_limits = 1\n");

        config.spec().correct(values);

        assertEquals(32, values.getInt(KEY));
        assertTrue(config.spec().isCorrect(values));
    }

    @Test
    void generatedTomlPlacesCompleteBilingualMetadataImmediatelyBeforeTheKey() {
        ServerConfig config = new ServerConfig();
        CommentedConfig values = CommentedConfig.inMemory();
        config.spec().correct(values);
        values.set(KEY, 1);
        values.setComment(KEY, "Old handwritten comment");
        config.spec().correct(values);

        String toml = new TomlWriter().writeToString(values);
        CommentedConfig parsed = new TomlParser().parse(toml);
        String comment = parsed.getComment(KEY);

        assertEquals(1, parsed.getInt(KEY));
        assertTrue(comment.contains("Networks owned by one player."));
        assertTrue(comment.contains("每所有者网络数量。"));
        for (String label :
                List.of("Type/类型", "Unit/单位", "Default/默认值", "Range/合法范围", "Special values/特殊值", "Reload/重载")) {
            assertTrue(comment.contains(label), label);
        }
        assertTrue(comment.contains("int"));
        assertTrue(comment.contains("networks / 网络"));
        assertTrue(comment.contains("32"));
        assertTrue(comment.contains("-1 or 0..1024 / -1或0..1024"));
        assertTrue(comment.contains("-1 removes only the gameplay quota; 0 rejects additions."));
        assertTrue(comment.contains("-1仅取消玩法限额；0禁止新增。"));
        assertTrue(comment.contains("QUOTA"));
        assertTrue(comment.contains("Lowering keeps existing objects and rejects additions until within quota."));
        assertTrue(comment.contains("调低不删已有对象，回到限制内前拒绝新增。"));
        assertFalse(comment.contains("Old handwritten comment"));
        assertTrue(toml.indexOf("Reload/重载") < toml.indexOf("networks_per_owner = 1"));
    }

    @Test
    void successfulReloadAdvancesOnlyRevisionAndDoesNotMutateOldSnapshots() {
        ServerConfig config = new ServerConfig();
        loadNative(config, 1);
        ServerConfig.State loaded = config.captureLoading(true);
        loadNative(config, 0);

        ServerConfig.State reloaded = config.captureReloading(true);

        assertEquals(1, loaded.epoch());
        assertEquals(1, loaded.revision());
        assertEquals(1, loaded.settings().networksPerOwner());
        assertEquals(1, reloaded.epoch());
        assertEquals(2, reloaded.revision());
        assertEquals(0, reloaded.settings().networksPerOwner());
        assertTrue(reloaded.loaded());
        assertSame(reloaded, config.latest());
    }

    @Test
    void failedBusinessReloadRetainsTheEntirePreviousCandidate() {
        for (Object invalid : List.of(-2, 1025, "not an integer")) {
            ServerConfig config = new ServerConfig();
            CommentedConfig values = loadNative(config, 1);
            ServerConfig.State previous = config.captureLoading(true);
            values.set(KEY, invalid);
            config.spec().afterReload();

            assertSame(previous, config.captureReloading(true));
            assertSame(previous, config.latest());
            assertEquals(invalid, values.get(KEY));
        }
    }

    @Test
    void unloadingResetsDefaultsAndReloadCannotResurrectTheOldWorld() {
        ServerConfig config = new ServerConfig();
        loadNative(config, -1);
        ServerConfig.State previous = config.captureLoading(true);
        config.spec().acceptConfig(null);

        ServerConfig.State unloaded = config.captureUnloading();

        assertEquals(previous.epoch(), unloaded.epoch());
        assertEquals(0, unloaded.revision());
        assertFalse(unloaded.loaded());
        assertEquals(32, unloaded.settings().networksPerOwner());
        loadNative(config, -1);
        assertSame(unloaded, config.captureReloading(true));
        loadNative(config, 0);
        ServerConfig.State nextWorld = config.captureLoading(true);
        assertEquals(2, nextWorld.epoch());
        assertEquals(1, nextWorld.revision());
        assertEquals(0, nextWorld.settings().networksPerOwner());
        assertEquals(-1, previous.settings().networksPerOwner());
    }

    @Test
    void loadingNewLifecycleCannotRetainThePriorWorldWhenCaptureFails() {
        ServerConfig config = new ServerConfig();
        loadNative(config, -1);
        config.captureLoading(true);
        CommentedConfig values = loadNative(config, 1);
        values.set(KEY, -2);
        config.spec().afterReload();

        ServerConfig.State failed = config.captureLoading(true);

        assertEquals(2, failed.epoch());
        assertEquals(0, failed.revision());
        assertTrue(failed.loaded());
        assertEquals(32, failed.settings().networksPerOwner());
        loadNative(config, 1);
        ServerConfig.State recovered = config.captureReloading(true);
        assertEquals(2, recovered.epoch());
        assertEquals(1, recovered.revision());
        assertEquals(1, recovered.settings().networksPerOwner());
    }

    @Test
    void readFailureKeepsPreviousReloadCandidateAndUsesDefaultsForNewLifecycle() {
        ServerConfig config = new ServerConfig();
        loadNative(config, 1);
        ServerConfig.State previous = config.captureLoading(true);
        config.spec().acceptConfig(null);

        assertSame(previous, config.captureReloading(true));
        ServerConfig.State failed = config.captureLoading(true);
        assertEquals(2, failed.epoch());
        assertEquals(0, failed.revision());
        assertEquals(32, failed.settings().networksPerOwner());
    }

    @Test
    void pathlessClientSyncCannotStartOrReplaceServerCandidates() {
        ServerConfig config = new ServerConfig();
        ServerConfig.State initial = config.latest();
        loadNative(config, -1);

        assertSame(initial, config.captureLoading(false));
        assertSame(initial, config.captureReloading(false));
        loadNative(config, 1);
        ServerConfig.State local = config.captureLoading(true);
        loadNative(config, -1);
        assertSame(local, config.captureLoading(false));
        assertSame(local, config.captureReloading(false));
        assertEquals(1, config.latest().settings().networksPerOwner());
    }

    @Test
    void settingsRejectIllegalBusinessCandidatesBeforePublication() {
        for (int value : new int[] {Integer.MIN_VALUE, -2, 1025, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ServerSettings(value));
        }
    }

    @Test
    void realPathlessLifecycleEventsCannotPublishServerCandidates() {
        ServerConfig config = new ServerConfig();
        loadNative(config, 1);
        ServerConfig.State local = config.captureLoading(true);
        CommentedConfig values = parseValue("-1");
        config.spec().correct(values);
        ModConfig synced = nativeConfig(config, values);

        assertThrows(IllegalStateException.class, synced::getFullPath);
        config.onLoading(new ModConfigEvent.Loading(synced));
        config.onReloading(new ModConfigEvent.Reloading(synced));

        assertSame(local, config.latest());
        config.spec().acceptConfig(null);
        config.onUnloading(new ModConfigEvent.Unloading(synced));
        assertFalse(config.latest().loaded());
        assertEquals(32, config.latest().settings().networksPerOwner());
    }

    @Test
    void eventsForAnotherSpecDoNotClearTheLocalCandidate() {
        ServerConfig config = new ServerConfig();
        loadNative(config, 1);
        ServerConfig.State local = config.captureLoading(true);
        ServerConfig other = new ServerConfig();
        CommentedConfig values = parseValue("0");
        other.spec().correct(values);
        ModConfig foreign = nativeConfig(other, values);

        config.onLoading(new ModConfigEvent.Loading(foreign));
        config.onReloading(new ModConfigEvent.Reloading(foreign));
        config.onUnloading(new ModConfigEvent.Unloading(foreign));

        assertSame(local, config.latest());
    }

    private static CommentedConfig parseValue(String value) {
        return new TomlParser().parse("[network_limits]\nnetworks_per_owner = " + value + "\n");
    }

    private static CommentedConfig loadNative(ServerConfig config, int value) {
        CommentedConfig values = parseValue(Integer.toString(value));
        config.spec().correct(values);
        return acceptNative(config, values);
    }

    private static CommentedConfig acceptNative(ServerConfig config, CommentedConfig values) {
        return nativeConfig(config, values).getLoadedConfig().config();
    }

    private static ModConfig nativeConfig(ServerConfig config, CommentedConfig values) {
        ModConfig nativeConfig = new ConfigTracker()
                .registerConfig(
                        ModConfig.Type.SERVER,
                        config.spec(),
                        ModList.get().getModContainerById("omniresonance").orElseThrow(),
                        "test-server.toml");
        ConfigTracker.acceptSyncedConfig(
                nativeConfig, new TomlWriter().writeToString(values).getBytes(StandardCharsets.UTF_8));
        return nativeConfig;
    }
}
