// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures native SERVER configuration as immutable, world-independent candidates. FML serializes
 * lifecycle callbacks under its per-mod configuration lock; callbacks never access authoritative
 * Minecraft state. Consumers may read {@link #latest()} from any thread and must apply candidates on
 * the server thread. Failed reloads retain the previous candidate and never partially apply values.
 * This class owns no files, runtime services, resource operations, or simulation state.
 */
public final class ServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);
    private static final NetworkQuotaDefinition ADMINISTRATORS_PER_NETWORK = new NetworkQuotaDefinition(
            List.of("network_limits", "administrators_per_network"),
            "Administrators in a network.",
            "每网络管理员数量。",
            "int",
            "players",
            "玩家",
            128,
            0,
            1024,
            "-1 removes only the gameplay quota; 0 rejects additions.",
            "-1仅取消玩法限额；0禁止新增。",
            "QUOTA",
            "Lowering keeps existing administrators and rejects additions until within quota.",
            "调低不删已有管理员，回到限制内前拒绝新增。");
    private static final NetworkQuotaDefinition NETWORKS_PER_OWNER = new NetworkQuotaDefinition(
            List.of("network_limits", "networks_per_owner"),
            "Networks owned by one player.",
            "每所有者网络数量。",
            "int",
            "networks",
            "网络",
            32,
            0,
            1024,
            "-1 removes only the gameplay quota; 0 rejects additions.",
            "-1仅取消玩法限额；0禁止新增。",
            "QUOTA",
            "Lowering keeps existing objects and rejects additions until within quota.",
            "调低不删已有对象，回到限制内前拒绝新增。");
    private static final NetworkQuotaDefinition TUNNELS_PER_NETWORK = new NetworkQuotaDefinition(
            List.of("network_limits", "tunnels_per_network"),
            "Tunnels in one network.",
            "每网络隧道数量。",
            "int",
            "tunnels",
            "隧道",
            1024,
            0,
            65535,
            "-1 removes only the gameplay quota; 0 rejects additions.",
            "-1仅取消玩法限额；0禁止新增。",
            "QUOTA",
            "Lowering keeps existing objects and rejects additions until within quota.",
            "调低不删已有对象，回到限制内前拒绝新增。");
    private static final NetworkQuotaDefinition CHANNELS_PER_TUNNEL = new NetworkQuotaDefinition(
            List.of("network_limits", "channels_per_tunnel"),
            "Channels in one tunnel.",
            "每隧道频道数量。",
            "int",
            "channels",
            "频道",
            256,
            0,
            65535,
            "-1 removes only the gameplay quota; 0 rejects additions.",
            "-1仅取消玩法限额；0禁止新增。",
            "QUOTA",
            "Lowering keeps existing objects and rejects additions until within quota.",
            "调低不删已有对象，回到限制内前拒绝新增。");
    private static final NetworkQuotaDefinition CHANNEL_BINDINGS_PER_DIRECT_NODE = new NetworkQuotaDefinition(
            List.of("network_limits", "channel_bindings_per_direct_node"),
            "Direct-channel configurations on one node.",
            "每直连节点频道配置数量。",
            "int",
            "configurations",
            "配置",
            16,
            1,
            1024,
            "-1 uses only the hard limit; 0 is invalid.",
            "-1仅使用硬上限；0无效。",
            "QUOTA",
            "Lowering keeps existing configurations and rejects additions until within quota.",
            "调低不删已有配置，回到限制内前拒绝新增。");

    private final ModConfigSpec spec;
    private final ModConfigSpec.ConfigValue<Integer> networksPerOwner;
    private final ModConfigSpec.ConfigValue<Integer> tunnelsPerNetwork;
    private final ModConfigSpec.ConfigValue<Integer> channelsPerTunnel;
    private final ModConfigSpec.ConfigValue<Integer> channelBindingsPerDirectNode;
    private final ModConfigSpec.ConfigValue<Integer> administratorsPerNetwork;
    private final AtomicReference<State> latest =
            new AtomicReference<>(new State(0, 0, false, ServerSettings.defaults()));

    /** Builds the owned native spec without reading files or accessing Minecraft state. */
    public ServerConfig() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        networksPerOwner = builder.comment(NETWORKS_PER_OWNER.comment())
                .define(
                        NETWORKS_PER_OWNER.path(),
                        NETWORKS_PER_OWNER::defaultValue,
                        NETWORKS_PER_OWNER::accepts,
                        Integer.class);
        tunnelsPerNetwork = define(builder, TUNNELS_PER_NETWORK);
        channelsPerTunnel = define(builder, CHANNELS_PER_TUNNEL);
        channelBindingsPerDirectNode = define(builder, CHANNEL_BINDINGS_PER_DIRECT_NODE);
        administratorsPerNetwork = define(builder, ADMINISTRATORS_PER_NETWORK);
        spec = builder.build();
    }

    /** Returns the owned spec for FML registration; native lifecycle access remains FML-controlled. */
    public ModConfigSpec spec() {
        return spec;
    }

    /** Returns one immutable candidate atomically without reading native values or mutating state. */
    public State latest() {
        return latest.get();
    }

    /** Starts a local SERVER lifecycle; pathless sync/default loads cannot publish server candidates. */
    public void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == spec) {
            captureLoading(hasLocalFile(event.getConfig()));
        }
    }

    /** Captures a local reload in the current epoch; read or validation failures retain its state. */
    public void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == spec) {
            captureReloading(hasLocalFile(event.getConfig()));
        }
    }

    /** Clears the owned lifecycle without reading native values, which FML has already unloaded. */
    public void onUnloading(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == spec) {
            captureUnloading();
        }
    }

    State captureLoading(boolean localFile) {
        State previous = latest.get();
        if (!localFile) {
            return previous;
        }
        State fallback = new State(Math.incrementExact(previous.epoch()), 0, true, ServerSettings.defaults());
        return capture(fallback);
    }

    State captureReloading(boolean localFile) {
        State previous = latest.get();
        if (!localFile || !previous.loaded()) {
            return previous;
        }
        return capture(previous);
    }

    State captureUnloading() {
        State unloaded = new State(latest.get().epoch(), 0, false, ServerSettings.defaults());
        latest.set(unloaded);
        return unloaded;
    }

    static int defaultNetworksPerOwner() {
        return NETWORKS_PER_OWNER.defaultValue();
    }

    static int defaultAdministratorsPerNetwork() {
        return ADMINISTRATORS_PER_NETWORK.defaultValue();
    }

    static void validateAdministratorsPerNetwork(int value) {
        validate(ADMINISTRATORS_PER_NETWORK, value, "Administrator quota is outside its supported range");
    }

    static void validateNetworksPerOwner(int value) {
        if (!NETWORKS_PER_OWNER.accepts(value)) {
            throw new IllegalArgumentException("Network quota is outside its supported range");
        }
    }

    static int defaultTunnelsPerNetwork() {
        return TUNNELS_PER_NETWORK.defaultValue();
    }

    static int defaultChannelsPerTunnel() {
        return CHANNELS_PER_TUNNEL.defaultValue();
    }

    static int defaultChannelBindingsPerDirectNode() {
        return CHANNEL_BINDINGS_PER_DIRECT_NODE.defaultValue();
    }

    static void validateTunnelsPerNetwork(int value) {
        validate(TUNNELS_PER_NETWORK, value, "Tunnel quota is outside its supported range");
    }

    static void validateChannelsPerTunnel(int value) {
        validate(CHANNELS_PER_TUNNEL, value, "Channel quota is outside its supported range");
    }

    static void validateChannelBindingsPerDirectNode(int value) {
        validate(
                CHANNEL_BINDINGS_PER_DIRECT_NODE,
                value,
                "Direct-channel configuration quota is outside its supported range");
    }

    private State capture(State fallback) {
        State candidate;
        try {
            ServerSettings settings = new ServerSettings(
                    networksPerOwner.get(),
                    tunnelsPerNetwork.get(),
                    channelsPerTunnel.get(),
                    channelBindingsPerDirectNode.get(),
                    administratorsPerNetwork.get());
            candidate = new State(fallback.epoch(), Math.incrementExact(fallback.revision()), true, settings);
        } catch (RuntimeException failure) {
            LOGGER.warn("Rejected server configuration candidate; retaining validated lifecycle settings");
            candidate = fallback;
        }
        latest.set(candidate);
        return candidate;
    }

    private static boolean hasLocalFile(ModConfig config) {
        if (config.getType() != ModConfig.Type.SERVER) {
            return false;
        }
        try {
            config.getFullPath();
            return true;
        } catch (IllegalStateException noLocalFile) {
            // FML throws for unloaded, default, and synchronized in-memory configurations.
            return false;
        }
    }

    private static ModConfigSpec.ConfigValue<Integer> define(
            ModConfigSpec.Builder builder, NetworkQuotaDefinition definition) {
        return builder.comment(definition.comment())
                .define(definition.path(), definition::defaultValue, definition::accepts, Integer.class);
    }

    private static void validate(NetworkQuotaDefinition definition, int value, String message) {
        if (!definition.accepts(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Immutable candidate identity and settings, safe to retain on any thread. Epochs start on local
     * loading; revisions count successful captures within that epoch. Loaded means a local lifecycle
     * has begun, including a validated default fallback with revision zero. Initial and unloaded states
     * are not loaded. This value contains no native configuration or Minecraft object references.
     */
    public record State(long epoch, long revision, boolean loaded, ServerSettings settings) {}

    private record NetworkQuotaDefinition(
            List<String> path,
            String purposeEn,
            String purposeZh,
            String type,
            String unitEn,
            String unitZh,
            int defaultValue,
            int ordinaryMinimum,
            int maximum,
            String specialEn,
            String specialZh,
            String reloadPolicy,
            String reloadEn,
            String reloadZh) {
        boolean accepts(Object value) {
            return value instanceof Integer number && (number == -1 || number >= ordinaryMinimum && number <= maximum);
        }

        String comment() {
            return String.join(
                    "\n",
                    purposeEn,
                    purposeZh,
                    "Type/类型: " + type,
                    "Unit/单位: " + unitEn + " / " + unitZh,
                    "Default/默认值: " + defaultValue,
                    "Range/合法范围: -1 or " + ordinaryMinimum + ".." + maximum + " / -1或" + ordinaryMinimum + ".."
                            + maximum,
                    "Special values/特殊值: " + specialEn + " / " + specialZh,
                    "Reload/重载: " + reloadPolicy + " - " + reloadEn + " / " + reloadZh);
        }
    }
}
