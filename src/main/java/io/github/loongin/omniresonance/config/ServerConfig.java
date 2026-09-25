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
                    "exchange.tunnels_per_network",
                    Kind.INT,
                    32,
                    0,
                    262144,
                    "tunnels / 隧道",
                    "Active and pending exchange tunnels per network, counted separately from channels.",
                    "每网络未解除的交换隧道（含待确认），与频道独立计数。"),
            new M2Definition(
                    "exchange.tunnels_server",
                    Kind.INT,
                    1024,
                    0,
                    262144,
                    "tunnels / 隧道",
                    "Active and pending exchange tunnels across the server, counted separately from channels.",
                    "全服未解除的交换隧道（含待确认），与频道独立计数。"),
            new M2Definition(
                    "exchange.rules_per_network",
                    Kind.INT,
                    32,
                    0,
                    262144,
                    "channels / 频道",
                    "Unterminated exchange channels involving one network, including pending and paused channels.",
                    "每网络关联的未终止交换频道，包含待审批和暂停频道。"),
            new M2Definition(
                    "exchange.invites_per_network",
                    Kind.INT,
                    8,
                    0,
                    262144,
                    "codes / 接收码",
                    "Usable receiving codes for one network; expired, consumed and revoked codes do not count.",
                    "每网络当前可用接收码，过期、已消费或已撤销的不计入。"),
            new M2Definition(
                    "exchange.rules_server",
                    Kind.INT,
                    1024,
                    0,
                    262144,
                    "channels / 频道",
                    "Unterminated exchange channels across the server; count each directed channel once.",
                    "全服未终止交换频道，每个单向频道只计一次。"),
            new M2Definition(
                    "exchange.history_server",
                    Kind.INT,
                    256,
                    0,
                    262144,
                    "records / 历史记录",
                    "Latest terminated exchange records across the server; both networks share the retained history.",
                    "全服保留的最近终止交换记录，双方共享同一历史。"),
            new M2Definition(
                    "audit.entries_per_scope",
                    Kind.INT,
                    1000,
                    0,
                    10000,
                    "entries / 条目",
                    "Ring-audit entries per owner or network; no separate files.",
                    "每所有者或网络环形审计条目上限，不独立创建文件。"),
            new M2Definition(
                    "terminal.highlight_enabled",
                    Kind.BOOLEAN,
                    true,
                    0,
                    1,
                    "toggle / 开关",
                    "Enable node highlighting; disabling cancels active highlights.",
                    "允许节点高亮；关闭取消已有高亮。"),
            new M2Definition(
                    "terminal.highlight_duration_ticks",
                    Kind.INT,
                    200,
                    1,
                    72000,
                    "gt",
                    "Lifetime of new highlights, including after teleport.",
                    "新高亮的持续时间，包含传送后高亮。"),
            new M2Definition(
                    "terminal.teleport_enabled",
                    Kind.BOOLEAN,
                    true,
                    0,
                    1,
                    "toggle / 开关",
                    "Enable terminal travel; disabling cancels pending travel.",
                    "允许终端传送；关闭取消待处理传送。"),
            new M2Definition(
                    "terminal.cross_dimension_teleport_enabled",
                    Kind.BOOLEAN,
                    true,
                    0,
                    1,
                    "toggle / 开关",
                    "Allow cross-dimension travel; disabling cancels cross-dimension tasks.",
                    "允许跨维度传送；关闭取消跨维度任务。"),
            new M2Definition(
                    "terminal.temporary_chunk_loading_enabled",
                    Kind.BOOLEAN,
                    true,
                    0,
                    1,
                    "toggle / 开关",
                    "Allow temporary travel tickets; disabling cancels ticket-dependent tasks.",
                    "允许传送临时票据；关闭取消依赖临时票据的任务。"),
            new M2Definition(
                    "terminal.teleport_cooldown_ticks",
                    Kind.INT,
                    20,
                    0,
                    72000,
                    "gt",
                    "Cooldown after success; zero removes success cooldown, not request interval.",
                    "成功后的冷却；0不设成功冷却，仍保留请求间隔。"),
            new M2Definition(
                    "terminal.temporary_ticket_ttl_ticks",
                    Kind.INT,
                    100,
                    1,
                    1200,
                    "gt",
                    "Lifetime from acceptance; reload does not extend pending tasks.",
                    "从接受起的期限；重载不延长已有任务。"),
            new M2Definition(
                    "terminal.max_pending_teleports_server",
                    Kind.INT,
                    64,
                    1,
                    1024,
                    "tasks / 任务",
                    "Global concurrent travel cap; lowering blocks admission, preserving existing tasks.",
                    "全服待处理传送上限；调低仅阻止新准入，保留已有任务。"),
            new M2Definition(
                    "chunk_loading.enabled",
                    Kind.BOOLEAN,
                    true,
                    0,
                    1,
                    "toggle / 开关",
                    "Enable permanent node chunk loading.",
                    "允许节点永久强加载。"),
            new M2Definition(
                    "chunk_loading.chunks_per_owner",
                    Kind.QUOTA,
                    25,
                    0,
                    100000,
                    "chunks / 区块",
                    "Distinct dimension/chunks per owner.",
                    "每所有者去重后的维度/区块配额。"),
            new M2Definition(
                    "chunk_loading.chunks_server",
                    Kind.QUOTA,
                    500,
                    0,
                    1000000,
                    "chunks / 区块",
                    "Distinct physical chunks across all owners.",
                    "全服去重后的物理区块配额。"),
            new M2Definition(
                    "terminal.direct_storage_access",
                    Kind.ACCESS,
                    "read_only",
                    0,
                    0,
                    "mode / 模式",
                    "Direct terminal storage access.",
                    "终端直接存取模式。"),
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
                    "单网络恢复编码和短期预留字节上限。"),
            new M2Definition(
                    "storage.variant_limit_per_network",
                    Kind.LONG_QUOTA,
                    -1L,
                    0,
                    Long.MAX_VALUE,
                    "variants / 变体",
                    "Distinct domain variants; existing keys may still grow or shrink. Memory capacity is not unlimited.",
                    "共鸣域变体数量，既有键仍可增减，内存承载能力并非无限。"),
            new M2Definition(
                    "terminal_sync.bytes_per_player_per_tick",
                    Kind.LONG,
                    262144L,
                    16384,
                    16777216,
                    "bytes per gt / 字节每gt",
                    "Per-player inventory send budget; frames fit available bytes.",
                    "每玩家库存发送预算，数据包按本轮可用字节拆分。"),
            new M2Definition(
                    "terminal_sync.bytes_server_per_tick",
                    Kind.LONG,
                    1048576L,
                    16384,
                    67108864,
                    "bytes per gt / 字节每gt",
                    "Server inventory send budget; cannot be below the per-player budget.",
                    "全服库存发送预算，不得低于每玩家预算。"),
            new M2Definition(
                    "terminal_sync.max_concurrent_full_syncs",
                    Kind.INT,
                    4,
                    1,
                    128,
                    "sessions / 会话",
                    "Concurrent full-sync receivers; networks share their temporary base.",
                    "同时接收全量的会话数，同网络共用临时基础快照。"),
            new M2Definition(
                    "terminal_sync.pending_delta_entries",
                    Kind.INT,
                    8192,
                    256,
                    65536,
                    "resource IDs / 资源ID",
                    "Coalesced pending changes; overflow fails the view for manual retry.",
                    "合并后的待发变化上限，超限终止镜像并等待手动重试。"));
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
                            longValue("recovery.max_encoded_bytes_per_network")),
                    longValue("storage.variant_limit_per_network"),
                    new ServerSettings.TerminalSync(
                            longValue("terminal_sync.bytes_per_player_per_tick"),
                            longValue("terminal_sync.bytes_server_per_tick"),
                            intValue("terminal_sync.max_concurrent_full_syncs"),
                            intValue("terminal_sync.pending_delta_entries")),
                    ServerSettings.DirectStorageAccess.parse((String) value("terminal.direct_storage_access")),
                    new ServerSettings.ChunkLoading(
                            (Boolean) value("chunk_loading.enabled"),
                            intValue("chunk_loading.chunks_per_owner"),
                            intValue("chunk_loading.chunks_server")),
                    new ServerSettings.Navigation(
                            (Boolean) value("terminal.highlight_enabled"),
                            intValue("terminal.highlight_duration_ticks"),
                            (Boolean) value("terminal.teleport_enabled"),
                            (Boolean) value("terminal.cross_dimension_teleport_enabled"),
                            (Boolean) value("terminal.temporary_chunk_loading_enabled"),
                            intValue("terminal.teleport_cooldown_ticks"),
                            intValue("terminal.temporary_ticket_ttl_ticks"),
                            intValue("terminal.max_pending_teleports_server")),
                    intValue("audit.entries_per_scope"),
                    new ServerSettings.Exchange(
                            intValue("exchange.rules_per_network"),
                            intValue("exchange.invites_per_network"),
                            intValue("exchange.rules_server"),
                            intValue("exchange.history_server"),
                            intValue("exchange.tunnels_per_network"),
                            intValue("exchange.tunnels_server")));
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
        BOOLEAN,
        ACCESS,
        INT,
        LONG,
        LONG_QUOTA,
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
            if (kind == Kind.BOOLEAN) return value instanceof Boolean;
            if (kind == Kind.ACCESS) return "read_only".equals(value) || "read_write".equals(value);
            if (kind == Kind.LONG_QUOTA)
                return (value instanceof Long || value instanceof Integer) && ((Number) value).longValue() >= -1;
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
                        case BOOLEAN -> "boolean";
                        case ACCESS -> "string";
                        case INT, QUOTA -> "int";
                        case LONG, LONG_QUOTA -> "long";
                        case DOUBLE -> "double";
                        case TICKS -> "int[]";
                    };
            String range = kind == Kind.BOOLEAN
                    ? "true, false"
                    : kind == Kind.ACCESS
                            ? "read_only, read_write"
                            : kind == Kind.LONG_QUOTA
                                    ? "0..9223372036854775807 or -1 / 或-1"
                                    : minimum + ".." + maximum
                                            + (kind == Kind.QUOTA
                                                    ? " or -1 / 或-1"
                                                    : kind == Kind.TICKS
                                                            ? "; 1..8 nondecreasing entries / 1..8项非递减"
                                                            : "");
            boolean quota = kind == Kind.QUOTA || kind == Kind.LONG_QUOTA;
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
                            + (key.startsWith("exchange.")
                                    ? (key.equals("exchange.history_server")
                                            ? "0 retains no terminated records after maintenance. / 0在后续维护时不保留终止记录。"
                                            : "0 rejects new admission; existing rules remain. / 0禁止新增准入，保留已有规则。")
                                    : key.startsWith("audit.")
                                            ? "0 stops appends without deleting history. / 0停止追加但不删除历史。"
                                            : key.equals("terminal.teleport_cooldown_ticks")
                                                    ? "0 disables success cooldown; request interval still applies. / 0不设成功冷却，仍限制请求间隔。"
                                                    : key.startsWith("chunk_loading.") && kind == Kind.QUOTA
                                                            ? "-1 removes admission quota; 0 blocks new distinct reservations while retaining existing ones. / -1不限准入名额；0拒绝新增不同区块资格，保留已有资格。"
                                                            : kind == Kind.BOOLEAN
                                                                    ? "None. / 无。"
                                                                    : kind == Kind.ACCESS
                                                                            ? "None. / 无。"
                                                                            : quota
                                                                                    ? "-1 removes gameplay quota only; 0 rejects additions (rules: empty only). / -1仅取消玩法限额；0禁止新增（规则仅允许空预设）。"
                                                                                    : "None; 0/-1 invalid. / 无；0/-1无效。"),
                    "Reload/重载: "
                            + (key.startsWith("exchange.")
                                    ? (key.equals("exchange.history_server")
                                            ? "NEXT - Apply updated retention during budgeted maintenance. / 后续有预算维护时应用新留存上限。"
                                            : "QUOTA - Apply before new admission; lowering preserves existing rules. / 新增前应用配额，调低保留已有规则。")
                                    : key.startsWith("audit.")
                                            ? "AUDIT - Later appends evict oldest excess entries. / 后续追加淘汰最旧超额条目。"
                                            : key.startsWith("chunk_loading.")
                                                    ? "ADMISSION - Lowering quotas preserves existing reservations; disabling releases tickets only. / 调低配额保留既有资格；关闭功能仅释放实际票据。"
                                                    : key.startsWith("terminal.") && kind == Kind.BOOLEAN
                                                            ? "CANCEL - Revalidate active navigation and cancel affected work when disabled. / 重新校验已有定位任务，关闭时取消受影响工作。"
                                                            : kind == Kind.ACCESS
                                                                    ? "NEXT - Revalidate before each operation; read_only rejects pending writes. / 每次操作前重新验证，切回只读拒绝待执行写入。"
                                                                    : key.startsWith("terminal_sync.")
                                                                            ? (key.endsWith("pending_delta_entries")
                                                                                    ? "NEXT - Apply to unsent changes; overflow fails the view for manual retry. / 对待发变化应用新上限，超限失败并等待手动重试。"
                                                                                    : "SYNC - New sends use updated byte limits; lower concurrency blocks new admission only. / 后续发送使用新字节预算，调低并发只阻止新准入。")
                                                                            : quota
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
