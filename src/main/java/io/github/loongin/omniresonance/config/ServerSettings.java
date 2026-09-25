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
        RecoveryLimits recoveryLimits,
        long storageVariantLimitPerNetwork,
        TerminalSync terminalSync,
        DirectStorageAccess directStorageAccess,
        ChunkLoading chunkLoading,
        Navigation navigation,
        int auditEntriesPerScope,
        Exchange exchange) {
    public ServerSettings {
        ServerConfig.validateNetworksPerOwner(networksPerOwner);
        ServerConfig.validateTunnelsPerNetwork(tunnelsPerNetwork);
        ServerConfig.validateChannelsPerTunnel(channelsPerTunnel);
        ServerConfig.validateChannelBindingsPerDirectNode(channelBindingsPerDirectNode);
        ServerConfig.validateAdministratorsPerNetwork(administratorsPerNetwork);
        java.util.Objects.requireNonNull(scheduler, "scheduler");
        java.util.Objects.requireNonNull(filterLimits, "filterLimits");
        java.util.Objects.requireNonNull(recoveryLimits, "recoveryLimits");
        ServerConfig.validateM2("storage.variant_limit_per_network", storageVariantLimitPerNetwork);
        java.util.Objects.requireNonNull(terminalSync, "terminalSync");
        java.util.Objects.requireNonNull(directStorageAccess, "directStorageAccess");
        java.util.Objects.requireNonNull(chunkLoading, "chunkLoading");
        java.util.Objects.requireNonNull(navigation, "navigation");
        ServerConfig.validateM2("audit.entries_per_scope", auditEntriesPerScope);
        java.util.Objects.requireNonNull(exchange, "exchange");
    }

    /** Preserves existing construction while exchange uses the centrally registered defaults. */
    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork,
            TerminalSync terminalSync,
            DirectStorageAccess directStorageAccess,
            ChunkLoading chunkLoading,
            Navigation navigation,
            int auditEntriesPerScope) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                terminalSync,
                directStorageAccess,
                chunkLoading,
                navigation,
                auditEntriesPerScope,
                Exchange.defaults());
    }

    /** Immutable exchange admission and history policy; lowering admission never revokes existing rules. */
    public record Exchange(
            int rulesPerNetwork,
            int invitesPerNetwork,
            int rulesServer,
            int historyServer,
            int tunnelsPerNetwork,
            int tunnelsServer) {
        public Exchange(int rulesPerNetwork, int invitesPerNetwork, int rulesServer, int historyServer) {
            this(
                    rulesPerNetwork,
                    invitesPerNetwork,
                    rulesServer,
                    historyServer,
                    (Integer) ServerConfig.defaultM2("exchange.tunnels_per_network"),
                    (Integer) ServerConfig.defaultM2("exchange.tunnels_server"));
        }

        public Exchange {
            ServerConfig.validateM2("exchange.rules_per_network", rulesPerNetwork);
            ServerConfig.validateM2("exchange.invites_per_network", invitesPerNetwork);
            ServerConfig.validateM2("exchange.rules_server", rulesServer);
            ServerConfig.validateM2("exchange.history_server", historyServer);
            ServerConfig.validateM2("exchange.tunnels_per_network", tunnelsPerNetwork);
            ServerConfig.validateM2("exchange.tunnels_server", tunnelsServer);
        }

        public static Exchange defaults() {
            return new Exchange(
                    (Integer) ServerConfig.defaultM2("exchange.rules_per_network"),
                    (Integer) ServerConfig.defaultM2("exchange.invites_per_network"),
                    (Integer) ServerConfig.defaultM2("exchange.rules_server"),
                    (Integer) ServerConfig.defaultM2("exchange.history_server"));
        }
    }

    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork,
            TerminalSync terminalSync,
            DirectStorageAccess directStorageAccess,
            ChunkLoading chunkLoading,
            Navigation navigation) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                terminalSync,
                directStorageAccess,
                chunkLoading,
                navigation,
                (Integer) ServerConfig.defaultM2("audit.entries_per_scope"));
    }

    public record Navigation(
            boolean highlightEnabled,
            int highlightDurationTicks,
            boolean teleportEnabled,
            boolean crossDimension,
            boolean temporaryLoading,
            int cooldownTicks,
            int ticketTtlTicks,
            int maximumPending) {
        public Navigation {
            ServerConfig.validateM2("terminal.highlight_enabled", highlightEnabled);
            ServerConfig.validateM2("terminal.highlight_duration_ticks", highlightDurationTicks);
            ServerConfig.validateM2("terminal.teleport_enabled", teleportEnabled);
            ServerConfig.validateM2("terminal.cross_dimension_teleport_enabled", crossDimension);
            ServerConfig.validateM2("terminal.temporary_chunk_loading_enabled", temporaryLoading);
            ServerConfig.validateM2("terminal.teleport_cooldown_ticks", cooldownTicks);
            ServerConfig.validateM2("terminal.temporary_ticket_ttl_ticks", ticketTtlTicks);
            ServerConfig.validateM2("terminal.max_pending_teleports_server", maximumPending);
        }

        public static Navigation defaults() {
            return new Navigation(
                    (Boolean) ServerConfig.defaultM2("terminal.highlight_enabled"),
                    (Integer) ServerConfig.defaultM2("terminal.highlight_duration_ticks"),
                    (Boolean) ServerConfig.defaultM2("terminal.teleport_enabled"),
                    (Boolean) ServerConfig.defaultM2("terminal.cross_dimension_teleport_enabled"),
                    (Boolean) ServerConfig.defaultM2("terminal.temporary_chunk_loading_enabled"),
                    (Integer) ServerConfig.defaultM2("terminal.teleport_cooldown_ticks"),
                    (Integer) ServerConfig.defaultM2("terminal.temporary_ticket_ttl_ticks"),
                    (Integer) ServerConfig.defaultM2("terminal.max_pending_teleports_server"));
        }
    }

    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork,
            TerminalSync terminalSync,
            DirectStorageAccess directStorageAccess,
            ChunkLoading chunkLoading) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                terminalSync,
                directStorageAccess,
                chunkLoading,
                Navigation.defaults());
    }

    public record ChunkLoading(boolean enabled, int perOwner, int server) {
        public ChunkLoading {
            ServerConfig.validateM2("chunk_loading.enabled", enabled);
            ServerConfig.validateM2("chunk_loading.chunks_per_owner", perOwner);
            ServerConfig.validateM2("chunk_loading.chunks_server", server);
        }

        public static ChunkLoading defaults() {
            return new ChunkLoading(
                    (Boolean) ServerConfig.defaultM2("chunk_loading.enabled"),
                    (Integer) ServerConfig.defaultM2("chunk_loading.chunks_per_owner"),
                    (Integer) ServerConfig.defaultM2("chunk_loading.chunks_server"));
        }
    }

    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork,
            TerminalSync terminalSync,
            DirectStorageAccess directStorageAccess) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                terminalSync,
                directStorageAccess,
                ChunkLoading.defaults());
    }

    /** Immutable terminal policy; parsing is strict and never changes player or storage state. */
    public enum DirectStorageAccess {
        READ_ONLY("read_only"),
        READ_WRITE("read_write");

        private final String id;

        DirectStorageAccess(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static DirectStorageAccess parse(String id) {
            for (DirectStorageAccess mode : values()) if (mode.id.equals(id)) return mode;
            throw new IllegalArgumentException("Unknown terminal storage access mode");
        }
    }

    /** Existing callers retain the registered read-only terminal policy. */
    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork,
            TerminalSync terminalSync) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                terminalSync,
                DirectStorageAccess.parse((String) ServerConfig.defaultM2("terminal.direct_storage_access")));
    }

    /** Preserves prior callers while terminal synchronization uses the confirmed default limits. */
    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits,
            long storageVariantLimitPerNetwork) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                storageVariantLimitPerNetwork,
                TerminalSync.defaults());
    }

    /** Immutable synchronization limits, validated atomically before server-thread publication. */
    public record TerminalSync(long bytesPerPlayer, long bytesServer, int concurrentFull, int pendingEntries) {
        public TerminalSync {
            ServerConfig.validateM2("terminal_sync.bytes_per_player_per_tick", bytesPerPlayer);
            ServerConfig.validateM2("terminal_sync.bytes_server_per_tick", bytesServer);
            ServerConfig.validateM2("terminal_sync.max_concurrent_full_syncs", concurrentFull);
            ServerConfig.validateM2("terminal_sync.pending_delta_entries", pendingEntries);
            if (bytesServer < bytesPerPlayer)
                throw new IllegalArgumentException("Server byte budget is below player budget");
        }

        public static TerminalSync defaults() {
            return new TerminalSync(
                    (Long) ServerConfig.defaultM2("terminal_sync.bytes_per_player_per_tick"),
                    (Long) ServerConfig.defaultM2("terminal_sync.bytes_server_per_tick"),
                    (Integer) ServerConfig.defaultM2("terminal_sync.max_concurrent_full_syncs"),
                    (Integer) ServerConfig.defaultM2("terminal_sync.pending_delta_entries"));
        }
    }

    /** Preserves existing construction contracts while storage uses the registered default quota. */
    public ServerSettings(
            int networksPerOwner,
            int tunnelsPerNetwork,
            int channelsPerTunnel,
            int channelBindingsPerDirectNode,
            int administratorsPerNetwork,
            Scheduler scheduler,
            FilterLimits filterLimits,
            RecoveryLimits recoveryLimits) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                administratorsPerNetwork,
                scheduler,
                filterLimits,
                recoveryLimits,
                (Long) ServerConfig.defaultM2("storage.variant_limit_per_network"));
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
