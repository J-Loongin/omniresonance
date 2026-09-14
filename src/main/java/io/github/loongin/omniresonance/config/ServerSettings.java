// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

/**
 * An immutable, world-independent configuration snapshot safe to read on any thread. Construction
 * validates the whole candidate without changing native configuration or authoritative state; invalid
 * values are rejected before publication. This value performs no simulation or resource operations.
 */
public record ServerSettings(
        int networksPerOwner,
        int tunnelsPerNetwork,
        int channelsPerTunnel,
        int channelBindingsPerDirectNode,
        int administratorsPerNetwork,
        Scheduler scheduler,
        FilterLimits filterLimits,
        RecoveryLimits recoveryLimits) {
    public ServerSettings {
        ServerConfig.validateNetworksPerOwner(networksPerOwner);
        ServerConfig.validateTunnelsPerNetwork(tunnelsPerNetwork);
        ServerConfig.validateChannelsPerTunnel(channelsPerTunnel);
        ServerConfig.validateChannelBindingsPerDirectNode(channelBindingsPerDirectNode);
        ServerConfig.validateAdministratorsPerNetwork(administratorsPerNetwork);
        java.util.Objects.requireNonNull(scheduler, "scheduler");
        java.util.Objects.requireNonNull(filterLimits, "filterLimits");
        java.util.Objects.requireNonNull(recoveryLimits, "recoveryLimits");
    }

    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                Scheduler.defaults(),
                FilterLimits.defaults(),
                RecoveryLimits.defaults());
    }
    /** Immutable scheduler snapshot; lists are copied and all candidate fields validate together. */
    public record Scheduler(
            double cpuBudgetMillisPerTick,
            int capabilityCallsPerTick,
            int emptyChecksBeforeSleep,
            java.util.List<Integer> idleBackoffTicks,
            int failureThreshold,
            java.util.List<Integer> breakerBackoffTicks,
            double slowCallThresholdMillis) {
        public Scheduler {
            ServerConfig.validateM2("scheduler.cpu_budget_millis_per_tick", cpuBudgetMillisPerTick);
            ServerConfig.validateM2("scheduler.capability_calls_per_tick", capabilityCallsPerTick);
            ServerConfig.validateM2("scheduler.empty_checks_before_sleep", emptyChecksBeforeSleep);
            ServerConfig.validateM2("scheduler.idle_backoff_ticks", idleBackoffTicks);
            ServerConfig.validateM2("scheduler.failure_threshold", failureThreshold);
            ServerConfig.validateM2("scheduler.breaker_backoff_ticks", breakerBackoffTicks);
            ServerConfig.validateM2("scheduler.slow_call_threshold_millis", slowCallThresholdMillis);
            idleBackoffTicks = java.util.List.copyOf(idleBackoffTicks);
            breakerBackoffTicks = java.util.List.copyOf(breakerBackoffTicks);
        }

        public static Scheduler defaults() {
            return new Scheduler(
                    (Double) ServerConfig.defaultM2("scheduler.cpu_budget_millis_per_tick"),
                    (Integer) ServerConfig.defaultM2("scheduler.capability_calls_per_tick"),
                    (Integer) ServerConfig.defaultM2("scheduler.empty_checks_before_sleep"),
                    ServerConfig.copyTicks(ServerConfig.defaultM2("scheduler.idle_backoff_ticks")),
                    (Integer) ServerConfig.defaultM2("scheduler.failure_threshold"),
                    ServerConfig.copyTicks(ServerConfig.defaultM2("scheduler.breaker_backoff_ticks")),
                    (Double) ServerConfig.defaultM2("scheduler.slow_call_threshold_millis"));
        }
    }
    /** Immutable gameplay quotas; -1 retains hard limits and lowering never deletes authoritative data. */
    public record FilterLimits(int filterPresetsPerOwner, int rulesPerFilterPreset) {
        public FilterLimits {
            ServerConfig.validateM2("network_limits.filter_presets_per_owner", filterPresetsPerOwner);
            ServerConfig.validateM2("network_limits.rules_per_filter_preset", rulesPerFilterPreset);
        }

        public static FilterLimits defaults() {
            return new FilterLimits(
                    (Integer) ServerConfig.defaultM2("network_limits.filter_presets_per_owner"),
                    (Integer) ServerConfig.defaultM2("network_limits.rules_per_filter_preset"));
        }
    }
    /** Immutable recovery admission limits; restore retains older content even when over these runtime limits. */
    public record RecoveryLimits(int maxVariantsPerNetwork, long maxEncodedBytesPerNetwork) {
        public RecoveryLimits {
            ServerConfig.validateM2("recovery.max_variants_per_network", maxVariantsPerNetwork);
            ServerConfig.validateM2("recovery.max_encoded_bytes_per_network", maxEncodedBytesPerNetwork);
        }

        public static RecoveryLimits defaults() {
            return new RecoveryLimits((Integer) ServerConfig.defaultM2("recovery.max_variants_per_network"), (Long)
                    ServerConfig.defaultM2("recovery.max_encoded_bytes_per_network"));
        }
    }

    /** Retains the established topology construction contract while membership uses its configured default. */
    public ServerSettings(
            int networksPerOwner, int tunnelsPerNetwork, int channelsPerTunnel, int channelBindingsPerDirectNode) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                ServerConfig.defaultAdministratorsPerNetwork());
    }

    /** Retains the established one-argument construction contract while topology settings use defaults. */
    public ServerSettings(int networksPerOwner) {
        this(
                networksPerOwner,
                ServerConfig.defaultTunnelsPerNetwork(),
                ServerConfig.defaultChannelsPerTunnel(),
                ServerConfig.defaultChannelBindingsPerDirectNode());
    }

    /** Returns a validated, independently owned default snapshot without accessing native values. */
    public static ServerSettings defaults() {
        return new ServerSettings(
                ServerConfig.defaultNetworksPerOwner(),
                ServerConfig.defaultTunnelsPerNetwork(),
                ServerConfig.defaultChannelsPerTunnel(),
                ServerConfig.defaultChannelBindingsPerDirectNode());
    }
}
