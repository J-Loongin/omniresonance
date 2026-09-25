// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.networking.ExchangeIntent;
import io.github.loongin.omniresonance.persistence.ExchangeSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Internal server-thread terminal operation router, called only after the existing session/envelope/page checks.
 * The current network argument must come from that session, never the untrusted operation body. Every operation
 * revalidates actual player ownership/roles against directory and loaded authority. Management work must be
 * admitted before calling: results are internal immutable objects requiring bounded, permission-checked projection
 * before transmission. No packet registration, clipboard access, simulation, automatic retry or forced save occurs.
 */
public final class ExchangeTerminalController implements AutoCloseable {
    public sealed interface Result permits Rules, Rule, Code, Done, Tunnels, Channels {}

    public record Tunnels(boolean history, List<ExchangeTunnel> entries) implements Result {
        public Tunnels {
            entries = List.copyOf(entries);
        }
    }

    public record Channels(ExchangeTunnel tunnel, boolean history, List<ExchangeAgreement> entries) implements Result {
        public Channels {
            entries = List.copyOf(entries);
        }
    }
    /** Internal placement projection after the caller has authorized the associated agreement. */
    public @org.jetbrains.annotations.Nullable ExchangeChannel channelPlacement(UUID id) {
        checkThread();
        return repository.exchangeRepository().find().orElseThrow().channel(id).orElse(null);
    }

    public record Rules(long revision, boolean history, List<ExchangeAgreement> entries) implements Result {
        public Rules {
            entries = List.copyOf(entries);
        }
    }

    public record Rule(ExchangeAgreement agreement) implements Result {
        public Rule {
            Objects.requireNonNull(agreement);
        }
    }

    public record Code(UUID id, long revision, long expiresTick, String value) implements Result {
        public Code {
            if (!Objects.requireNonNull(id).equals(ExchangeInvitationCode.decode(value))
                    || revision < 0
                    || expiresTick < 0) throw new IllegalArgumentException("Invalid receiving code result");
        }

        @Override
        public String toString() {
            return "ExchangeCode[redacted]";
        }
    }

    public record Done() implements Result {}

    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory networks;
    private final Supplier<ServerSettings> settings;
    private final Runnable authorityAvailable;
    private final ExchangeDraftResolver drafts;
    private java.util.function.Function<ExchangeAgreement, ExchangeFilterStatus> filterStatus =
            agreement -> ExchangeFilterStatus.of(agreement.terms(), null);
    /** Installs the runtime-owned, read-only filter diagnostic source on the server thread. */
    public void installFilterStatus(java.util.function.Function<ExchangeAgreement, ExchangeFilterStatus> source) {
        checkThread();
        filterStatus = Objects.requireNonNull(source);
    }
    /** Reads diagnostics for a previously authorized agreement; performs no compilation or storage activation. */
    public ExchangeFilterStatus filterStatus(ExchangeAgreement agreement) {
        checkThread();
        return filterStatus.apply(agreement);
    }

    private boolean closed;

    public ExchangeTerminalController(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            Supplier<ServerSettings> settings,
            Runnable authorityAvailable) {
        this.server = Objects.requireNonNull(server);
        this.repository = Objects.requireNonNull(repository);
        this.networks = Objects.requireNonNull(networks);
        this.settings = Objects.requireNonNull(settings);
        this.authorityAvailable = Objects.requireNonNull(authorityAvailable);
        checkThread();
        drafts = new ExchangeDraftResolver(repository, networks);
    }

    /** Returns a client-safe summary after live role/relationship checks; the transport still verifies its session. */
    public io.github.loongin.omniresonance.networking.ExchangeRuleView projectRule(
            ServerPlayer actor, UUID network, UUID id) {
        Rule result = (Rule) execute(actor, network, new ExchangeIntent.Detail(id));
        return io.github.loongin.omniresonance.networking.ExchangeRuleView.from(
                result.agreement(),
                networks.find(result.agreement().consent().sourceNetwork()).orElse(null),
                networks.find(result.agreement().consent().targetNetwork()).orElse(null));
    }

    /** Executes one already session-admitted operation, with the live sender and server-selected network context. */
    public Result execute(ServerPlayer actor, UUID network, ExchangeIntent intent) {
        checkThread();
        Objects.requireNonNull(actor);
        Objects.requireNonNull(intent);
        if (actor.server != server) throw new SecurityException("Exchange sender belongs to another server");
        var metadata = networks.find(network).orElseThrow(() -> new IllegalStateException("Network unavailable"));
        var loaded = repository
                .findLoadedNetwork(network)
                .orElseThrow(() -> new IllegalStateException("Network unavailable"));
        if (!metadata.equals(loaded.metadata())) throw new IllegalStateException("Network authority changed");
        boolean read = intent instanceof ExchangeIntent.ListRules
                || intent instanceof ExchangeIntent.Detail
                || intent instanceof ExchangeIntent.ListTunnels
                || intent instanceof ExchangeIntent.ListChannels;
        boolean pause =
                intent instanceof ExchangeIntent.Change change && change.action() == ExchangeIntent.Action.PAUSE;
        if (read || pause) {
            if (!NetworkPermissions.canManage(actor.getUUID(), metadata.ownerId(), metadata.administrators()))
                throw new SecurityException("Exchange network role denied");
        } else if (!actor.getUUID().equals(metadata.ownerId()))
            throw new SecurityException("Exchange ownership required");
        var policy = settings.get().exchange();
        ExchangeSavedData data = repository.exchangeRepository().find().orElse(null);
        if (data == null) {
            if (intent instanceof ExchangeIntent.ListRules list) return new Rules(0, list.history(), List.of());
            if (intent instanceof ExchangeIntent.ListTunnels list) return new Tunnels(list.history(), List.of());
            if (!(intent instanceof ExchangeIntent.IssueCode))
                throw new IllegalStateException("Exchange authority unavailable");
            if (policy.invitesPerNetwork() == 0) throw new IllegalStateException("Receiving code quota reached");
            Math.addExact(server.overworld().getGameTime(), ExchangeInvitation.LIFETIME_TICKS);
            data = repository.exchangeRepository().create();
        }
        data.configureLimits(new ExchangeSavedData.Limits(262144, 262144));
        data.configurePolicy(policy);
        authorityAvailable.run();
        if (intent instanceof ExchangeIntent.ListTunnels list) return listTunnels(data, network, list.history());
        if (intent instanceof ExchangeIntent.Pair request) {
            var invitation = data.findUsableInvitation(
                            request.code(), server.overworld().getGameTime())
                    .orElseThrow();
            var peer = peerMetadata(invitation.targetNetwork());
            UUID id = UUID.randomUUID();
            data.proposePair(
                    id,
                    invitation.id(),
                    actor.getUUID(),
                    metadata,
                    peer,
                    invitation.revision(),
                    server.overworld().getGameTime());
            pairAudit(network, actor, id, "exchange_pair_propose");
            return new Channels(data.tunnel(id).orElseThrow(), false, List.of());
        }
        if (intent instanceof ExchangeIntent.ApprovePair request) {
            var pair = data.tunnel(request.tunnel()).orElseThrow();
            var peer = peerMetadata(pair.peer(network));
            data.approvePair(
                    pair.id(),
                    actor.getUUID(),
                    metadata,
                    peer,
                    request.revision(),
                    server.overworld().getGameTime());
            if (data.tunnel(pair.id()).orElseThrow().consent().revision()
                    != pair.consent().revision()) pairAudit(network, actor, pair.id(), "exchange_pair_approve");
            return listChannels(data, network, pair.id(), false);
        }
        if (intent instanceof ExchangeIntent.ClosePair request) {
            var pair = data.tunnel(request.tunnel()).orElseThrow();
            pair.peer(network);
            data.closePair(pair.id(), actor.getUUID(), metadata, request.revision());
            pairAudit(network, actor, pair.id(), "exchange_pair_close");
            return listTunnels(data, network, false);
        }
        if (intent instanceof ExchangeIntent.ListChannels request)
            return listChannels(data, network, request.tunnel(), request.history());
        if (intent instanceof ExchangeIntent.CreateChannel request) {
            var pair = data.tunnel(request.tunnel()).orElseThrow();
            var peer = peerMetadata(pair.peer(network));
            var terms = drafts.resolve(actor.getUUID(), network, request.draft().terms(), null, -1);
            UUID id = UUID.randomUUID();
            data.createChannel(
                    id,
                    pair.id(),
                    actor.getUUID(),
                    metadata,
                    peer,
                    request.revision(),
                    request.draft().name(),
                    request.draft().sending(),
                    terms);
            pairAudit(network, actor, id, "exchange_channel_create");
            return new Rule(data.agreement(id).orElseThrow());
        }
        if (intent instanceof ExchangeIntent.ReviseChannel request) {
            var link = data.channel(request.channel()).orElseThrow();
            var pair = data.tunnel(link.tunnel()).orElseThrow();
            var peer = peerMetadata(pair.peer(network));
            var terms = drafts.resolve(
                    actor.getUUID(), network, request.draft().terms(), request.channel(), request.revision());
            data.reviseChannel(
                    request.channel(),
                    actor.getUUID(),
                    metadata,
                    peer,
                    request.revision(),
                    request.draft().name(),
                    request.draft().sending(),
                    terms);
            pairAudit(network, actor, request.channel(), "exchange_channel_revise");
            return new Rule(data.agreement(request.channel()).orElseThrow());
        }
        try (var service = new ExchangeManagementService(
                networks,
                data,
                repository::auditNetwork,
                () -> server.overworld().getGameTime(),
                UUID::randomUUID)) {
            if (intent instanceof ExchangeIntent.ListRules list) {
                var entries = new ArrayList<ExchangeAgreement>();
                for (UUID id : data.agreementsFor(network)) {
                    ExchangeAgreement agreement = data.agreement(id).orElseThrow();
                    if (agreement.consent().revoked() == list.history()) entries.add(agreement);
                }
                ExchangeSavedData authority = data;
                Comparator<ExchangeAgreement> order = Comparator.comparing(ExchangeAgreement::id);
                if (list.history())
                    order = Comparator.comparingLong((ExchangeAgreement a) -> authority.terminationRevision(a.id()))
                            .reversed()
                            .thenComparing(order);
                entries.sort(order);
                return new Rules(data.agreementsSnapshot().revision(), list.history(), entries);
            }
            if (intent instanceof ExchangeIntent.Detail detail)
                return new Rule(service.view(actor.getUUID(), network, detail.agreement()));
            if (intent instanceof ExchangeIntent.IssueCode) {
                String code = service.issueCode(actor.getUUID(), network);
                var invitation =
                        data.invitation(ExchangeInvitationCode.decode(code)).orElseThrow();
                return new Code(invitation.id(), invitation.revision(), invitation.expiresTick(), code);
            }
            if (intent instanceof ExchangeIntent.RevokeCode revoke) {
                service.revokeCode(actor.getUUID(), network, revoke.invitation(), revoke.revision());
                return new Done();
            }
            if (intent instanceof ExchangeIntent.Propose proposal) {
                ExchangeTerms terms = drafts.resolve(actor.getUUID(), network, proposal.draft(), null, -1);
                UUID id = service.propose(actor.getUUID(), network, proposal.code(), terms);
                return new Rule(service.view(actor.getUUID(), network, id));
            }
            if (intent instanceof ExchangeIntent.Revise revise) {
                ExchangeTerms terms =
                        drafts.resolve(actor.getUUID(), network, revise.draft(), revise.agreement(), revise.revision());
                service.revise(actor.getUUID(), network, revise.agreement(), revise.revision(), terms);
                return new Rule(service.view(actor.getUUID(), network, revise.agreement()));
            }
            if (intent instanceof ExchangeIntent.Change change) {
                if (change.action() == ExchangeIntent.Action.APPROVE)
                    service.approve(actor.getUUID(), network, change.agreement(), change.revision());
                else
                    service.act(
                            actor.getUUID(),
                            network,
                            change.agreement(),
                            change.revision(),
                            switch (change.action()) {
                                case PAUSE -> ExchangeSavedData.Action.PAUSE;
                                case RESUME -> ExchangeSavedData.Action.RESUME;
                                case TERMINATE -> ExchangeSavedData.Action.REVOKE;
                                default -> throw new IllegalArgumentException("Invalid exchange change");
                            });
                return new Rule(service.view(actor.getUUID(), network, change.agreement()));
            }
            throw new IllegalArgumentException("Unknown exchange operation");
        }
    }

    private io.github.loongin.omniresonance.network.NetworkMetadata peerMetadata(UUID id) {
        var peer = networks.find(id).orElseThrow();
        var loaded = repository.findLoadedNetwork(id).orElseThrow();
        if (!loaded.metadata().equals(peer)) throw new IllegalStateException("Pair peer authority changed");
        return peer;
    }

    private Tunnels listTunnels(ExchangeSavedData data, UUID network, boolean history) {
        var rows = new ArrayList<ExchangeTunnel>();
        for (var pair : data.tunnels())
            if (pair.consent().revoked() == history
                    && (pair.consent().sourceNetwork().equals(network)
                            || pair.consent().targetNetwork().equals(network))) rows.add(pair);
        rows.sort(Comparator.comparing(ExchangeTunnel::id));
        return new Tunnels(history, rows);
    }

    private Channels listChannels(ExchangeSavedData data, UUID network, UUID tunnel, boolean history) {
        var pair = data.tunnel(tunnel).orElseThrow();
        pair.peer(network);
        var rows = new ArrayList<ExchangeAgreement>();
        for (UUID id : data.agreementsFor(network)) {
            var link = data.channel(id).orElse(null);
            var a = data.agreement(id).orElseThrow();
            if (link != null && link.tunnel().equals(tunnel) && a.consent().revoked() == history) rows.add(a);
        }
        rows.sort(Comparator.comparing(
                a -> data.channel(a.id()).orElseThrow().name().value()));
        return new Channels(pair, history, rows);
    }

    private void pairAudit(UUID network, ServerPlayer actor, UUID id, String action) {
        try {
            repository.auditNetwork(
                    network, io.github.loongin.omniresonance.persistence.AuditEntry.of(action, actor, id, ""));
        } catch (RuntimeException failure) {
            throw new ExchangeManagementService.CommittedAuditFailure(failure);
        }
    }

    private void checkThread() {
        if (closed || !server.isSameThread()) throw new IllegalStateException("Closed or off-thread exchange terminal");
    }

    @Override
    public void close() {
        if (!server.isSameThread()) throw new IllegalStateException("Exchange terminal closed off server thread");
        closed = true;
    }
}
