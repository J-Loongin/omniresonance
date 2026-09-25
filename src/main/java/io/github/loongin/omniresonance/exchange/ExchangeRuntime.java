// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ResourceFilterCache;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.persistence.ExchangeSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One server-session exchange lifecycle, consuming only the existing shared tick CPU budget. Reads authority once
 * at startup or explicit creation notification; absent storage stays absent. Holds bounded publication snapshots,
 * one cursor per reconciliation phase, and at most one activation request per referenced network. No player-online
 * checks, physical-node dependency, asynchronous world access or forced saves. Failures isolate exchange only.
 */
public final class ExchangeRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeRuntime.class);
    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory networks;
    private final Supplier<ServerSettings> settings;
    private final ResourceFilterCache.TagSource tags;
    private final LongSupplier gameTick;
    private final ExchangeScheduler scheduler;
    private final ExchangeTerminalController terminal;
    private final Map<UUID, ExchangeAgreement> seen = new HashMap<>();
    private final LinkedHashMap<UUID, ExchangeAgreement> activations = new LinkedHashMap<>();
    private @Nullable ExchangeSavedData data;
    private @Nullable RuntimeException failure;
    private Map<UUID, ExchangeAgreement> snapshot = Map.of();
    private @Nullable Iterator<ExchangeAgreement> scan;
    private @Nullable Iterator<Map.Entry<UUID, ExchangeAgreement>> removal;
    private @Nullable Iterator<UUID> tagScan;
    private boolean tagsDirty, closed;
    private int phase;

    public ExchangeRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            Supplier<ServerSettings> settings,
            ResourceFilterCache.TagSource tags) {
        this(
                server,
                repository,
                networks,
                settings,
                tags,
                () -> server.overworld().getGameTime());
    }

    /** Fixed-clock seam for deterministic server-thread tests; production uses overworld game time. */
    public ExchangeRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            Supplier<ServerSettings> settings,
            ResourceFilterCache.TagSource tags,
            LongSupplier gameTick) {
        this.server = Objects.requireNonNull(server);
        this.repository = Objects.requireNonNull(repository);
        this.networks = Objects.requireNonNull(networks);
        this.settings = Objects.requireNonNull(settings);
        this.tags = Objects.requireNonNull(tags);
        this.gameTick = Objects.requireNonNull(gameTick);
        checkThread();
        scheduler = new ExchangeScheduler(
                new ExchangeTypeWork.Environment() {
                    public boolean active(ExchangeAgreement agreement) {
                        return !tagsDirty && eligible(agreement);
                    }

                    public @Nullable DomainLedger source(ExchangeAgreement agreement) {
                        return ledger(agreement.consent().sourceNetwork(), agreement, true);
                    }

                    public @Nullable DomainLedger target(ExchangeAgreement agreement) {
                        DomainLedger source = ledger(agreement.consent().sourceNetwork(), agreement, false);
                        return ledger(
                                agreement.consent().targetNetwork(),
                                agreement,
                                source != null && source.isAvailable() && source.variantCount() > 0);
                    }

                    public @Nullable ResourceVariant decode(ResourceVariantKey key) {
                        return repository
                                .resourceAdapters()
                                .decode(key, server.registryAccess())
                                .orElse(null);
                    }
                },
                repository.resourceAdapters().types(),
                262144,
                262144);
        terminal = new ExchangeTerminalController(server, repository, networks, settings, this::authorityCreated);
        terminal.installFilterStatus(agreement ->
                tagsDirty ? ExchangeFilterStatus.of(agreement.terms(), null) : scheduler.filterStatus(agreement));
        authorityCreated();
    }

    /** Borrows the runtime-owned terminal router; callers must enforce sessions and must not close it per screen. */
    public ExchangeTerminalController terminalController() {
        checkThread();
        return terminal;
    }

    /** Called only after an authorized first creation; does not itself create data or grant management authority. */
    public void authorityCreated() {
        checkThread();
        if (data != null || failure != null) return;
        try {
            data = repository.exchangeRepository().find().orElse(null);
            if (data != null) {
                data.configureLimits(new ExchangeSavedData.Limits(262144, 262144));
                data.configurePolicy(settings.get().exchange());
            }
        } catch (RuntimeException problem) {
            fail(problem);
        }
    }

    /** Immediately prevents stale tag permits from executing; bounded invalidation runs under the next budget. */
    public void tagsChanged() {
        checkThread();
        tagsDirty = true;
        tagScan = null;
    }

    /** Advances finite metadata, activation, maintenance and transfer slices under the caller's sole shared budget. */
    public void step(TransferWorkBudget budget) {
        checkThread();
        Objects.requireNonNull(budget);
        if (failure != null || data == null) return;
        try {
            ServerSettings current = settings.get();
            data.configurePolicy(current.exchange());
            if (!budget.canFit(0)) return;
            long tick = gameTick.getAsLong();
            if (tick < 0) throw new IllegalStateException("Invalid exchange clock");
            int first = phase;
            phase = (phase + 1) & 3;
            for (int index = 0; index < 4 && budget.canFit(0); index++) {
                switch ((first + index) & 3) {
                    case 0 -> reconcile(tick, budget);
                    case 1 -> activate(budget);
                    case 2 -> {
                        data.maintainHistory(tick, 4, 1);
                        if (budget.canFit(0)) data.maintainPairings(tick, 4, 1);
                        if (budget.canFit(0)) data.cleanUnusedInvitations(tick, 8);
                    }
                    case 3 -> scheduler.run(tick, 256, budget, current.storageVariantLimitPerNetwork());
                    default -> throw new IllegalStateException("Invalid exchange phase");
                }
            }
        } catch (RuntimeException problem) {
            fail(problem);
        }
    }

    private void reconcile(long tick, TransferWorkBudget budget) {
        for (int used = 0; used < 64 && budget.canFit(0); used++) {
            if (tagsDirty) {
                // Capture an immutable existing authority key set, never an iterator over the mutable publication map.
                if (tagScan == null)
                    tagScan = data.agreementsSnapshot().agreements().keySet().iterator();
                if (tagScan.hasNext()) {
                    scheduler.invalidateLiveTags(tagScan.next(), tick);
                    continue;
                }
                tagScan = null;
                tagsDirty = false;
            }
            if (scan == null && removal == null) {
                Map<UUID, ExchangeAgreement> current = data.agreementsSnapshot().agreements();
                if (current == snapshot) return;
                snapshot = current;
                scan = current.values().iterator();
            }
            if (scan != null) {
                if (scan.hasNext()) {
                    ExchangeAgreement agreement = scan.next();
                    if (data.agreement(agreement.id()).orElse(null) != agreement
                            || seen.get(agreement.id()) == agreement) continue;
                    scheduler.publishLive(agreement, tags, tick);
                    seen.put(agreement.id(), agreement);
                    continue;
                }
                scan = null;
                removal = seen.entrySet().iterator();
            }
            if (removal.hasNext()) {
                UUID id = removal.next().getKey();
                if (data.agreement(id).isEmpty()) {
                    scheduler.remove(id);
                    removal.remove();
                }
            } else {
                removal = null;
                return;
            }
        }
    }

    private @Nullable DomainLedger ledger(UUID id, ExchangeAgreement agreement, boolean request) {
        var domain = repository.inspectDomain(id);
        DomainLedger ledger = domain == null ? null : domain.activatedLedger().orElse(null);
        if (ledger == null
                && request
                && repository.findLoadedNetwork(id).isPresent()
                && (domain == null
                        || domain.state()
                                == io.github.loongin.omniresonance.persistence.DomainStorage.State.NOT_LOADED))
            activations.putIfAbsent(id, agreement);
        return ledger;
    }

    private void activate(TransferWorkBudget budget) {
        if (!budget.canFit(0) || activations.isEmpty()) return;
        var request = activations.pollFirstEntry();
        if (eligible(request.getValue())
                && repository.findLoadedNetwork(request.getKey()).isPresent())
            if (repository.domainStorage(request.getKey()).activate().isEmpty())
                scheduler.storageFailure(request.getValue(), request.getKey(), gameTick.getAsLong());
    }

    private boolean eligible(ExchangeAgreement agreement) {
        if (ExchangeFilterStatus.of(agreement.terms(), null).blocked()
                || data == null
                || data.agreement(agreement.id()).orElse(null) != agreement) return false;
        var source = networks.find(agreement.consent().sourceNetwork()).orElse(null);
        var target = networks.find(agreement.consent().targetNetwork()).orElse(null);
        if (source == null || target == null || !agreement.consent().permitsExecution(source, target)) return false;
        var link = data.channel(agreement.id()).orElse(null);
        if (link != null) {
            var pair = data.tunnel(link.tunnel()).orElse(null);
            if (pair == null
                    || !pair.permitsChannels(
                            pair.consent().sourceNetwork().equals(source.id()) ? source : target,
                            pair.consent().targetNetwork().equals(target.id()) ? target : source)) return false;
        }
        var sourceData = repository.findLoadedNetwork(source.id()).orElse(null);
        var targetData = repository.findLoadedNetwork(target.id()).orElse(null);
        return sourceData != null
                && targetData != null
                && sourceData.metadata().equals(source)
                && targetData.metadata().equals(target);
    }

    /** Server-thread read-only snapshot; resolves names only from existing metadata, never activates storage. */
    public ExchangeTelemetry.Snapshot diagnostics(UUID network, long tick) {
        checkThread();
        var snapshot = scheduler.telemetrySnapshot(network, tick).withAvailability(failure == null);
        var incident = snapshot.incident();
        if (incident == null) return snapshot;
        String channel = data == null
                ? ""
                : data.channel(incident.channel()).map(c -> c.name().value()).orElse("");
        String source =
                networks.find(incident.source()).map(n -> n.name().value()).orElse("");
        String target =
                networks.find(incident.target()).map(n -> n.name().value()).orElse("");
        return snapshot.withIncident(new ExchangeTelemetry.Incident(
                incident.channel(),
                incident.source(),
                incident.target(),
                incident.type(),
                incident.tick(),
                incident.stage(),
                incident.reason(),
                channel,
                source,
                target));
    }

    public @Nullable RuntimeException failure() {
        checkThread();
        return failure;
    }

    private void fail(RuntimeException problem) {
        failure = problem;
        LOGGER.error("Exchange runtime isolated after failure", problem);
    }

    private void checkThread() {
        if (closed || !server.isSameThread()) throw new IllegalStateException("Closed or off-thread exchange runtime");
    }

    @Override
    public void close() {
        checkThread();
        terminal.close();
        scheduler.close();
        seen.clear();
        activations.clear();
        snapshot = Map.of();
        scan = null;
        removal = null;
        tagScan = null;
        data = null;
        closed = true;
    }
}
