// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final List<M2Definition> M2_DEFINITIONS = List.of(
            new M2Definition(
                    "scheduler.cpu_budget_millis_per_tick",
                    Kind.DOUBLE,
                    2.0,
                    0.1,
                    10,
                    "ms per gt / 毫秒每gt",
                    "Soft CPU budget checked between work units; the final bounded commit may exceed it.",
                    "传输工作的软时间预算，工作单元之间检查，最后一个有界提交单元可能越界。"),
            new M2Definition(
                    "scheduler.capability_calls_per_tick",
                    Kind.INT,
                    4096,
                    1,
                    65536,
                    "calls per gt / 调用每gt",
                    "Soft external-call budget; finish the final bounded commit and count all calls.",
                    "外部能力获取、查询、模拟和修改的调用次数软预算；允许最后一个有界提交单元收尾越界，全部实际调用仍计数。"),
            new M2Definition(
                    "scheduler.empty_checks_before_sleep",
                    Kind.INT,
                    3,
                    1,
                    100,
                    "checks / 次",
                    "Empty checks before idle backoff.",
                    "开始空闲退避前的连续空检查次数。"),
            new M2Definition(
                    "scheduler.idle_backoff_ticks",
                    Kind.TICKS,
                    List.of(2, 4, 8, 20),
                    1,
                    1200,
                    "gt",
                    "Idle polling delays, never shorter than the configured interval.",
                    "空闲轮询退避阶段，不短于玩家间隔。"),
            new M2Definition(
                    "scheduler.failure_threshold",
                    Kind.INT,
                    3,
                    1,
                    100,
                    "failures / 次",
                    "Consecutive failures before backoff.",
                    "进入故障退避的连续失败阈值。"),
            new M2Definition(
                    "scheduler.breaker_backoff_ticks",
                    Kind.TICKS,
                    List.of(200, 400, 800, 1200),
                    1,
                    72000,
                    "gt",
                    "Failure backoff stages; already started stages are not extended.",
                    "普通故障重试等待阶段，已开始阶段不延长。"),
            new M2Definition(
                    "scheduler.slow_call_threshold_millis",
                    Kind.DOUBLE,
                    1.0,
                    0.05,
                    50,
                    "ms / 毫秒",
                    "Threshold for a slow external call.",
                    "单次外部调用被视为慢调用的阈值。"),
            new M2Definition(
                    "network_limits.filter_presets_per_owner",
                    Kind.QUOTA,
                    512,
                    0,
                    65535,
                    "presets / 预设",
                    "Filter presets owned by one player.",
                    "每所有者过滤预设数量。"),
            new M2Definition(
                    "network_limits.rules_per_filter_preset",
                    Kind.QUOTA,
                    1024,
                    0,
                    65535,
                    "entries / 条目",
                    "Direct rules and references in a preset.",
                    "预设直接规则与引用的合计数量。"),
            new M2Definition(
                    "recovery.max_variants_per_network",
                    Kind.INT,
                    1024,
                    64,
                    65536,
                    "variants / 变体",
                    "Recovery variants and short reservations per network.",
                    "单网络恢复变体及短期预留数量上限。"),
            new M2Definition(
                    "recovery.max_encoded_bytes_per_network",
                    Kind.LONG,
                    8388608L,
                    1048576,
                    268435456,
                    "bytes / 字节",
                    "Encoded recovery bytes and short reservations per network.",
                    "单网络恢复编码和短期预留字节上限。"));
    private final Map<String, ModConfigSpec.ConfigValue<Object>> m2Values = new HashMap<>();

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
        for (M2Definition definition : M2_DEFINITIONS)
            m2Values.put(
                    definition.key(),
                    builder.comment(definition.comment())
                            .define(
                                    List.of(definition.key().split("\\.")),
                                    definition::defaultValue,
                                    definition::accepts,
                                    Object.class));
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
                    administratorsPerNetwork.get(),
                    new ServerSettings.Scheduler(
                            doubleValue("scheduler.cpu_budget_millis_per_tick"),
                            intValue("scheduler.capability_calls_per_tick"),
                            intValue("scheduler.empty_checks_before_sleep"),
                            ticksValue("scheduler.idle_backoff_ticks"),
                            intValue("scheduler.failure_threshold"),
                            ticksValue("scheduler.breaker_backoff_ticks"),
                            doubleValue("scheduler.slow_call_threshold_millis")),
                    new ServerSettings.FilterLimits(
                            intValue("network_limits.filter_presets_per_owner"),
                            intValue("network_limits.rules_per_filter_preset")),
                    new ServerSettings.RecoveryLimits(
                            intValue("recovery.max_variants_per_network"),
                            longValue("recovery.max_encoded_bytes_per_network")));
            candidate = new State(fallback.epoch(), Math.incrementExact(fallback.revision()), true, settings);
        } catch (RuntimeException failure) {
            LOGGER.warn("Rejected server configuration candidate; retaining validated lifecycle settings");
            candidate = fallback;
        }
        latest.set(candidate);
        return candidate;
    }

    private Object value(String key) {
        Object value = m2Values.get(key).get();
        validateM2(key, value);
        return value;
    }

    private int intValue(String key) {
        return (Integer) value(key);
    }

    private long longValue(String key) {
        return ((Number) value(key)).longValue();
    }

    private double doubleValue(String key) {
        return ((Number) value(key)).doubleValue();
    }

    private List<Integer> ticksValue(String key) {
        return copyTicks(value(key));
    }

    static void validateM2(String key, Object value) {
        if (!definition(key).accepts(value)) throw new IllegalArgumentException("Invalid server setting: " + key);
    }

    static Object defaultM2(String key) {
        return definition(key).defaultValue();
    }

    static List<Integer> copyTicks(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected tick stages");
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>(list.size());
        for (Object entry : list) result.add((Integer) entry);
        return List.copyOf(result);
    }

    private static M2Definition definition(String key) {
        for (M2Definition definition : M2_DEFINITIONS) if (definition.key().equals(key)) return definition;
        throw new IllegalArgumentException("Unknown server setting: " + key);
    }

    private enum Kind {
        INT,
        LONG,
        DOUBLE,
        TICKS,
        QUOTA
    }

    private record M2Definition(
            String key,
            Kind kind,
            Object defaultValue,
            double minimum,
            double maximum,
            String unit,
            String purposeEn,
            String purposeZh) {
        boolean accepts(Object value) {
            if (kind == Kind.TICKS) {
                if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 8) return false;
                int previous = 0;
                for (Object entry : list) {
                    if (!(entry instanceof Integer number) || number < minimum || number > maximum || number < previous)
                        return false;
                    previous = number;
                }
                return true;
            }
            if (kind == Kind.DOUBLE)
                return value instanceof Number number
                        && (value instanceof Double || value instanceof Integer || value instanceof Long)
                        && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= minimum
                        && number.doubleValue() <= maximum;
            if (kind == Kind.LONG)
                return (value instanceof Long || value instanceof Integer)
                        && ((Number) value).longValue() >= minimum
                        && ((Number) value).longValue() <= maximum;
            return value instanceof Integer number
                    && ((kind == Kind.QUOTA && number == -1) || number >= minimum && number <= maximum);
        }

        String comment() {
            String type =
                    switch (kind) {
                        case INT, QUOTA -> "int";
                        case LONG -> "long";
                        case DOUBLE -> "double";
                        case TICKS -> "int[]";
                    };
            String range = minimum + ".." + maximum
                    + (kind == Kind.QUOTA
                            ? " or -1 / 或-1"
                            : kind == Kind.TICKS ? "; 1..8 nondecreasing entries / 1..8项非递减" : "");
            boolean quota = kind == Kind.QUOTA;
            boolean buffer = key.startsWith("recovery.");
            return String.join(
                    "\n",
                    purposeEn,
                    purposeZh,
                    "Type/类型: " + type,
                    "Unit/单位: " + unit,
                    "Default/默认值: " + defaultValue,
                    "Range/合法范围: " + range,
                    "Special values/特殊值: "
                            + (quota
                                    ? "-1 removes gameplay quota only; 0 rejects additions (rules: empty only). / -1仅取消玩法限额；0禁止新增（规则仅允许空预设）。"
                                    : "None; 0/-1 invalid. / 无；0/-1无效。"),
                    "Reload/重载: "
                            + (quota
                                    ? "QUOTA - Keep existing entries; reject additions above lowered limits. / 调低保留已有条目，只阻止新增。"
                                    : buffer
                                            ? "BUFFER - Keep existing contents; reject positive additions when over capacity. / 调低保留已有内容，超限不得新增占用。"
                                            : "NEXT - Next work uses the new snapshot; started backoff stages are not extended. / 后续工作使用新快照，已开始退避阶段不延长。"));
        }
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
