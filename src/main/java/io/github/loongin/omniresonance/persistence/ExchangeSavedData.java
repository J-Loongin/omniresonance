// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.exchange.ExchangeAgreement;
import io.github.loongin.omniresonance.exchange.ExchangeChannel;
import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeInvitation;
import io.github.loongin.omniresonance.exchange.ExchangeInvitationCode;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.exchange.ExchangeTunnel;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-thread-owned v2 exchange authority. Management mutations validate detached candidates before one state
 * publication, including initial approval and invitation consumption. No transfer, simulation or disk I/O occurs.
 * Callers supply current authoritative metadata and enforce sessions and application budgets. Management preparation
 * is bounded by the 16 MiB shard ceiling, not a per-tick transfer path. Wrong-thread access and invalid candidates
 * fail before mutation. Successful changes mark only this shard dirty for the native save cycle.
 */
public final class ExchangeSavedData extends SavedData {
    public static final String STORAGE_ID = "omniresonance_exchange";

    /** Explicit admission limits, without gameplay defaults; lower limits do not remove already saved authority. */
    public record Limits(int invitations, int agreements) {
        public Limits {
            if (invitations < 1
                    || agreements < 1
                    || invitations > ResourceFilterPreset.MAX_ENTRIES
                    || agreements > ResourceFilterPreset.MAX_ENTRIES)
                throw new IllegalArgumentException("Invalid exchange admission limits");
        }
    }

    public enum Action {
        PAUSE,
        RESUME,
        REVOKE
    }

    private record State(
            long revision,
            Map<UUID, ExchangeInvitation> invitations,
            Map<UUID, ExchangeAgreement> agreements,
            Map<UUID, UUID> accepted,
            Map<UUID, Set<UUID>> byNetwork,
            Set<UUID> referencedInvitations,
            Map<UUID, Long> terminatedRevisions,
            int activeCount,
            Map<UUID, Integer> activeByNetwork,
            ExchangePairingCatalog pairing) {}

    private final Thread owningThread = Thread.currentThread();
    private Limits limits;
    private State state;
    private ServerSettings.Exchange policy = ServerSettings.Exchange.defaults();
    private Iterator<ExchangeAgreement> historyScan = Collections.emptyIterator();
    // One bounded immutable invitation snapshot iterator per cleanup pass; released when the pass finishes.
    private Iterator<ExchangeInvitation> cleanup = Collections.emptyIterator();

    private ExchangeSavedData(Limits limits, State state) {
        this.limits = Objects.requireNonNull(limits);
        this.state = state;
    }

    /**
     * Immutable metadata view captured on the server thread. The internal map is already immutable and shared in
     * constant work; no native world objects, mutation methods or player permission grants are exposed. Consumers
     * own the lifetime of this view and should release superseded scans rather than accumulate snapshot history.
     */
    public static final class AgreementsSnapshot {
        private final long revision;
        private final Map<UUID, ExchangeAgreement> agreements;

        private AgreementsSnapshot(State state) {
            revision = state.revision;
            agreements = state.agreements;
        }

        public long revision() {
            return revision;
        }

        public Map<UUID, ExchangeAgreement> agreements() {
            return agreements;
        }
    }

    /** Replaces validated runtime admission on the owning thread; preserves all data, dirty state and revisions. */
    public void configureLimits(Limits next) {
        requireThread();
        limits = Objects.requireNonNull(next);
    }

    /** Applies validated gameplay admission and future retention without mutation, pruning, or dirty marking. */
    public void configurePolicy(ServerSettings.Exchange next) {
        requireThread();
        policy = Objects.requireNonNull(next);
    }

    public int activeRuleCount() {
        requireThread();
        return state.activeCount;
    }

    public int activeRuleCount(UUID network) {
        requireThread();
        return state.activeByNetwork.getOrDefault(Objects.requireNonNull(network), 0);
    }

    public record HistoryProgress(int scanned, int expired, int removed) {}

    /**
     * Owner-thread management maintenance: scans at most scanBudget candidates and prunes at most pruneBudget
     * terminated records. Never expires an agreement already bound to a consumed invitation, even during reapproval.
     * Candidate encoding and history ordering use bounded whole-shard preparation, which must be separately admitted
     * under the CPU budget. No resource moves, forced saves, actor impersonation or audit fabrication occurs.
     */
    public HistoryProgress maintainHistory(long tick, int scanBudget, int pruneBudget) {
        requireThread();
        if (tick < 0 || scanBudget < 0 || pruneBudget < 0)
            throw new IllegalArgumentException("Invalid history maintenance bounds");
        Map<UUID, ExchangeAgreement> next = null;
        int scanned = 0, expired = 0, removed = 0;
        if (scanBudget > 0) {
            if (!historyScan.hasNext()) historyScan = state.agreements.values().iterator();
            while (scanned < scanBudget && historyScan.hasNext()) {
                ExchangeAgreement agreement = historyScan.next();
                scanned++;
                if (state.pairing.channels().containsKey(agreement.id())
                        || state.agreements.get(agreement.id()) != agreement
                        || agreement.consent().revoked()
                        || agreement
                                .id()
                                .equals(state.accepted.get(agreement.consent().invitationId()))) continue;
                ExchangeInvitation invitation =
                        state.invitations.get(agreement.consent().invitationId());
                if (invitation.state() == ExchangeInvitation.State.OPEN && tick < invitation.expiresTick()) continue;
                ExchangeConsent c = agreement.consent();
                ExchangeConsent ended = new ExchangeConsent(
                        c.invitationId(),
                        c.sourceNetwork(),
                        c.targetNetwork(),
                        c.sourceOwner(),
                        c.targetOwner(),
                        Math.incrementExact(c.revision()),
                        c.termsRevision(),
                        c.source(),
                        c.target(),
                        true);
                if (next == null) next = new HashMap<>(state.agreements);
                next.put(agreement.id(), new ExchangeAgreement(agreement.id(), ended, agreement.terms()));
                expired++;
            }
            if (!historyScan.hasNext()) historyScan = Collections.emptyIterator();
        }
        int excess = state.terminatedRevisions.size() + expired - policy.historyServer();
        if (excess > 0 && pruneBudget > 0) {
            if (next == null) next = new HashMap<>(state.agreements);
            long newOrder = Math.incrementExact(state.revision);
            PriorityQueue<UUID> oldest = new PriorityQueue<>(
                    Comparator.comparingLong((UUID id) -> state.terminatedRevisions.getOrDefault(id, newOrder))
                            .thenComparing(Comparator.naturalOrder()));
            for (ExchangeAgreement agreement : next.values())
                if (agreement.consent().revoked()) oldest.add(agreement.id());
            while (removed < excess && removed < pruneBudget) {
                next.remove(oldest.remove());
                removed++;
            }
        }
        if (expired > 0 || removed > 0) publish(state.invitations, Objects.requireNonNull(next), state.accepted);
        return new HistoryProgress(scanned, expired, removed);
    }

    private Iterator<ExchangeTunnel> pairCleanup = Collections.emptyIterator();
    /** Budgeted pairing expiry/history management within the same shard; does not impersonate player actions. */
    public void maintainPairings(long tick, int scanBudget, int pruneBudget) {
        requireThread();
        if (tick < 0 || scanBudget < 0 || pruneBudget < 0)
            throw new IllegalArgumentException("Invalid pairing maintenance budget");
        if (scanBudget == 0) return;
        if (!pairCleanup.hasNext())
            pairCleanup = state.pairing.tunnels().values().iterator();
        Map<UUID, ExchangeTunnel> pairs = state.pairing.tunnels();
        Map<UUID, ExchangeAgreement> agreements = state.agreements;
        boolean changed = false;
        int expired = 0;
        for (int scanned = 0; scanned < scanBudget && pairCleanup.hasNext(); scanned++) {
            var t = pairCleanup.next();
            if (state.pairing.tunnels().get(t.id()) != t
                    || t.consent().revoked()
                    || t.consent().approvedByBoth()) continue;
            var invitation = state.invitations.get(t.consent().invitationId());
            if (invitation.usable(tick)) continue;
            if (!changed) {
                pairs = new HashMap<>(pairs);
                agreements = new HashMap<>(agreements);
            }
            pairs.put(t.id(), new ExchangeTunnel(t.id(), endedConsent(t.consent())));
            changed = true;
            expired++;
            for (var channel : state.pairing.channels().values())
                if (channel.tunnel().equals(t.id())) {
                    var a = agreements.get(channel.id());
                    if (!a.consent().revoked())
                        agreements.put(a.id(), new ExchangeAgreement(a.id(), endedConsent(a.consent()), a.terms()));
                }
        }
        int excess = state.pairing.ended().size() + expired - policy.historyServer();
        if (excess > 0 && pruneBudget > 0) {
            var referenced = new HashSet<UUID>();
            for (var c : state.pairing.channels().values()) referenced.add(c.tunnel());
            var candidates = pairs.values().stream()
                    .filter(t -> t.consent().revoked() && !referenced.contains(t.id()))
                    .sorted(Comparator.comparingLong(
                                    (ExchangeTunnel t) -> state.pairing.ended().getOrDefault(t.id(), Long.MAX_VALUE))
                            .thenComparing(ExchangeTunnel::id))
                    .limit(Math.min(excess, pruneBudget))
                    .toList();
            for (var t : candidates) {
                if (!changed) pairs = new HashMap<>(pairs);
                pairs.remove(t.id());
                changed = true;
            }
        }
        if (changed)
            publish(
                    state.invitations,
                    agreements,
                    state.accepted,
                    new ExchangePairingCatalog(pairs, state.pairing.channels()));
    }

    private static ExchangeConsent endedConsent(ExchangeConsent c) {
        return new ExchangeConsent(
                c.invitationId(),
                c.sourceNetwork(),
                c.targetNetwork(),
                c.sourceOwner(),
                c.targetOwner(),
                Math.incrementExact(c.revision()),
                c.termsRevision(),
                c.source(),
                c.target(),
                true);
    }

    /** Captures a detached immutable-state reference in O(1), without copying the table, creating data or simulating. */
    public AgreementsSnapshot agreementsSnapshot() {
        requireThread();
        return new AgreementsSnapshot(state);
    }

    /** Creates empty dirty authority on the calling server thread; no file is written. */
    public static ExchangeSavedData create(Limits limits) {
        ExchangeSavedData data = new ExchangeSavedData(limits, checked(0, Map.of(), Map.of(), Map.of(), Map.of()));
        data.setDirty();
        return data;
    }

    /** Strict clean restoration; malformed, oversized or future data fails rather than creating empty authority. */
    public static ExchangeSavedData load(CompoundTag tag, Limits limits) {
        int version = ManagedDataNbt.readSchemaVersion(tag);
        if (version != 1 && version != 2 && version != 3 && version != 4)
            throw new IllegalArgumentException("Invalid exchange shard schema");
        Set<String> fields = version == 1
                ? Set.of("schema_version", "revision", "invitations", "agreements")
                : version == 2
                        ? Set.of("schema_version", "revision", "invitations", "agreements", "terminated_revisions")
                        : Set.of(
                                "schema_version",
                                "revision",
                                "invitations",
                                "agreements",
                                "terminated_revisions",
                                "pairing");
        if (!tag.getAllKeys().equals(fields)) throw new IllegalArgumentException("Invalid exchange shard fields");
        ManagedObjectNbtSize.validate(tag);
        ManagedDataNbt.requireType(tag, "revision", Tag.TAG_LONG);
        Map<UUID, ExchangeInvitation> invitations = new HashMap<>();
        Map<UUID, UUID> accepted = new HashMap<>();
        for (Tag entry : ExchangeStateNbt.list(tag, "invitations", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            Set<String> rowFields = row.contains("accepted_agreement")
                    ? Set.of("invitation", "accepted_agreement")
                    : Set.of("invitation");
            if (!row.getAllKeys().equals(rowFields)) throw new IllegalArgumentException("Invalid invitation row");
            ExchangeInvitation invitation =
                    ExchangeStateNbt.decodeInvitation(ExchangeStateNbt.compound(row, "invitation"));
            if (invitations.putIfAbsent(invitation.id(), invitation) != null)
                throw new IllegalArgumentException("Duplicate invitation");
            if (row.contains("accepted_agreement"))
                accepted.put(invitation.id(), ManagedDataNbt.readUuid(row, "accepted_agreement"));
        }
        Map<UUID, ExchangeAgreement> agreements = new HashMap<>();
        for (Tag row : ExchangeStateNbt.list(tag, "agreements", Tag.TAG_COMPOUND)) {
            ExchangeAgreement agreement = ExchangeStateNbt.decodeAgreement((CompoundTag) row);
            if (agreements.putIfAbsent(agreement.id(), agreement) != null)
                throw new IllegalArgumentException("Duplicate agreement");
        }
        Map<UUID, Long> terminated = new HashMap<>();
        if (version == 1) {
            for (UUID id : accepted.values())
                if (!agreements.containsKey(id))
                    throw new IllegalArgumentException("Legacy consumed binding is missing");
            for (ExchangeAgreement agreement : agreements.values())
                if (agreement.consent().revoked()) terminated.put(agreement.id(), 0L);
        } else {
            for (Tag entry : ExchangeStateNbt.list(tag, "terminated_revisions", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) entry;
                if (!row.getAllKeys().equals(Set.of("id", "revision")))
                    throw new IllegalArgumentException("Invalid history row");
                ManagedDataNbt.requireType(row, "revision", Tag.TAG_LONG);
                if (terminated.putIfAbsent(ManagedDataNbt.readUuid(row, "id"), row.getLong("revision")) != null)
                    throw new IllegalArgumentException("Duplicate history order");
            }
        }
        if (version < 3) checked(tag.getLong("revision"), invitations, agreements, accepted, terminated);
        var pairing = version < 3
                ? ExchangePairingCatalog.migrate(agreements, accepted)
                : ExchangePairingCatalog.decode(ExchangeStateNbt.compound(tag, "pairing"));
        ExchangeSavedData data = new ExchangeSavedData(
                limits, checked(tag.getLong("revision"), invitations, agreements, accepted, terminated, pairing));
        if (version < 4) {
            ManagedObjectNbtSize.validate(encode(data.state));
            data.setDirty();
        }
        return data;
    }

    /** Owner-only issuance from authoritative metadata; capacity and identity checks precede publication. */
    public void issue(UUID id, UUID actor, NetworkMetadata target, long tick) {
        requireThread();
        ExchangeInvitation invitation = ExchangeInvitation.issue(id, actor, target, tick);
        if (state.invitations.containsKey(id) || state.invitations.size() >= limits.invitations())
            throw new IllegalStateException("Invitation already exists or admission is full");
        int usable = 0;
        for (ExchangeInvitation current : state.invitations.values())
            if (current.targetNetwork().equals(target.id()) && current.usable(tick)) usable++;
        if (usable >= policy.invitesPerNetwork()) throw new IllegalStateException("Receiving code quota reached");
        Map<UUID, ExchangeInvitation> next = new HashMap<>(state.invitations);
        next.put(id, invitation);
        publish(next, state.agreements, state.accepted);
    }

    /** Issues a cryptographically random code after checking the receiving owner; no clipboard or logging occurs. */
    public String issueCode(UUID actor, NetworkMetadata target, long tick) {
        requireThread();
        if (!target.ownerId().equals(actor)) throw new SecurityException("Only the receiving owner may issue codes");
        for (int attempt = 0; attempt < 4; attempt++) {
            UUID id = ExchangeInvitationCode.newIdentity();
            if (state.invitations.containsKey(id)) continue;
            issue(id, actor, target, tick);
            return ExchangeInvitationCode.encode(id);
        }
        throw new IllegalStateException("Could not allocate a unique invitation identity");
    }

    /** Internal read-only code lookup; expiry, revocation and consumption hide unusable invitations without mutation. */
    public Optional<ExchangeInvitation> findUsableInvitation(String code, long tick) {
        requireThread();
        if (tick < 0) throw new IllegalArgumentException("Negative invitation tick");
        ExchangeInvitation invitation = state.invitations.get(ExchangeInvitationCode.decode(code));
        return invitation != null && invitation.usable(tick) ? Optional.of(invitation) : Optional.empty();
    }

    public record CleanupProgress(int scanned, int removed) {}

    /**
     * Management-only bounded scan of unused expired/revoked invitations. Zero budget has no side effects.
     * Referenced invitations remain for approval consistency. A nonempty removal batch uses the same bounded
     * whole-shard candidate publication as other management writes; scanBudget is not a total CPU budget.
     * The caller admits that publication cost separately. No simulation, resource movement or forced save occurs.
     */
    public CleanupProgress cleanUnusedInvitations(long tick, int scanBudget) {
        requireThread();
        if (tick < 0 || scanBudget < 0) throw new IllegalArgumentException("Invalid cleanup budget or tick");
        if (scanBudget == 0) return new CleanupProgress(0, 0);
        if (!cleanup.hasNext()) cleanup = state.invitations.values().iterator();
        Set<UUID> removals = new HashSet<>();
        int scanned = 0;
        while (scanned < scanBudget && cleanup.hasNext()) {
            ExchangeInvitation candidate = cleanup.next();
            scanned++;
            ExchangeInvitation current = state.invitations.get(candidate.id());
            if (candidate != current || state.referencedInvitations.contains(candidate.id())) continue;
            if (candidate.state() != ExchangeInvitation.State.OPEN || tick >= candidate.expiresTick())
                removals.add(candidate.id());
        }
        if (!cleanup.hasNext()) cleanup = Collections.emptyIterator();
        if (!removals.isEmpty()) {
            Map<UUID, ExchangeInvitation> invitations = new HashMap<>(state.invitations);
            Map<UUID, UUID> accepted = new HashMap<>(state.accepted);
            for (UUID id : removals) {
                invitations.remove(id);
                accepted.remove(id);
            }
            publish(invitations, state.agreements, accepted);
        }
        return new CleanupProgress(scanned, removals.size());
    }

    /** Server-thread detached pairing/channel lookups; callers separately authorize disclosure. */
    public java.util.Collection<ExchangeTunnel> tunnels() {
        requireThread();
        return state.pairing.tunnels().values();
    }

    public Optional<ExchangeTunnel> tunnel(UUID id) {
        requireThread();
        return Optional.ofNullable(state.pairing.tunnels().get(id));
    }

    public Optional<ExchangeChannel> channel(UUID id) {
        requireThread();
        return Optional.ofNullable(state.pairing.channels().get(id));
    }

    public boolean hasActiveTunnel(UUID network) {
        requireThread();
        for (var t : tunnels())
            if (!t.consent().revoked()
                    && (t.consent().sourceNetwork().equals(network)
                            || t.consent().targetNetwork().equals(network))) return true;
        return false;
    }

    public void proposePair(
            UUID id, UUID code, UUID actor, NetworkMetadata own, NetworkMetadata peer, long expectedCode, long tick) {
        requireThread();
        var invitation = invitation(code).orElseThrow();
        if (!invitation.usable(tick) || invitation.revision() != expectedCode)
            throw new IllegalStateException("Pairing code unavailable");
        if (!invitation.targetNetwork().equals(peer.id())
                || !invitation.targetOwner().equals(peer.ownerId()))
            throw new SecurityException("Pairing code target changed");
        var candidate = ExchangeTunnel.propose(id, code, actor, own, peer);
        if (state.pairing.tunnels().containsKey(id) || state.agreements.containsKey(id))
            throw new IllegalStateException("Duplicate pairing identity");
        int total = 0, first = 0, second = 0;
        for (var t : tunnels())
            if (!t.consent().revoked()) {
                total++;
                var c = t.consent();
                if (c.sourceNetwork().equals(own.id()) || c.targetNetwork().equals(own.id())) first++;
                if (c.sourceNetwork().equals(peer.id()) || c.targetNetwork().equals(peer.id())) second++;
                if (ExchangePairingCatalog.pairKey(c).equals(ExchangePairingCatalog.pairKey(candidate.consent())))
                    throw new IllegalStateException("Networks already paired or pending");
            }
        if (total >= policy.tunnelsServer()
                || first >= policy.tunnelsPerNetwork()
                || second >= policy.tunnelsPerNetwork()) throw new IllegalStateException("Pairing quota reached");
        var pairs = new HashMap<>(state.pairing.tunnels());
        pairs.put(id, candidate);
        publish(
                state.invitations,
                state.agreements,
                state.accepted,
                new ExchangePairingCatalog(pairs, state.pairing.channels()));
    }

    public void approvePair(UUID id, UUID actor, NetworkMetadata own, NetworkMetadata peer, long revision, long tick) {
        requireThread();
        var old = tunnel(id).orElseThrow();
        checkPairIdentities(old, own, peer);
        var next = old.approve(old.side(own.id()), actor, own, revision);
        if (next.equals(old)) return;
        var invitations = new HashMap<>(state.invitations);
        var accepted = new HashMap<>(state.accepted);
        if (next.consent().approvedByBoth()) {
            var invitation = invitations.get(next.consent().invitationId());
            var receiver = next.consent().targetNetwork().equals(own.id()) ? own : peer;
            invitations.put(
                    invitation.id(),
                    invitation.consumeAfterApproval(
                            receiver.ownerId(), receiver, invitation.revision(), tick, next.consent()));
            accepted.put(invitation.id(), id);
        }
        var pairs = new HashMap<>(state.pairing.tunnels());
        pairs.put(id, next);
        publish(invitations, state.agreements, accepted, new ExchangePairingCatalog(pairs, state.pairing.channels()));
    }

    public void closePair(UUID id, UUID actor, NetworkMetadata own, long revision) {
        requireThread();
        var old = tunnel(id).orElseThrow();
        var next = old.close(old.side(own.id()), actor, own, revision);
        var pairs = new HashMap<>(state.pairing.tunnels());
        pairs.put(id, next);
        var agreements = new HashMap<>(state.agreements);
        for (var link : state.pairing.channels().values())
            if (link.tunnel().equals(id)) {
                var a = agreements.get(link.id());
                var c = a.consent();
                if (c.revoked()) continue;
                var side =
                        c.sourceNetwork().equals(own.id()) ? ExchangeConsent.Side.SOURCE : ExchangeConsent.Side.TARGET;
                agreements.put(
                        a.id(), new ExchangeAgreement(a.id(), c.revoke(side, actor, own, c.revision()), a.terms()));
            }
        publish(
                state.invitations,
                agreements,
                state.accepted,
                new ExchangePairingCatalog(pairs, state.pairing.channels()));
    }

    public void createChannel(
            UUID id,
            UUID parent,
            UUID actor,
            NetworkMetadata own,
            NetworkMetadata peer,
            long revision,
            ManagedName name,
            boolean sending,
            ExchangeTerms terms) {
        requireThread();
        var pair = approvedPair(parent, own, peer);
        if (!own.ownerId().equals(actor)) throw new SecurityException("Channel owner required");
        if (pair.consent().revision() != revision
                || state.agreements.containsKey(id)
                || state.pairing.tunnels().containsKey(id))
            throw new IllegalStateException("Stale pairing or duplicate channel");
        if (state.activeCount >= policy.rulesServer()
                || activeRuleCount(own.id()) >= policy.rulesPerNetwork()
                || activeRuleCount(peer.id()) >= policy.rulesPerNetwork())
            throw new IllegalStateException("Channel quota reached");
        var source = sending ? own : peer;
        var target = sending ? peer : own;
        var c = new ExchangeConsent(
                pair.consent().invitationId(),
                source.id(),
                target.id(),
                source.ownerId(),
                target.ownerId(),
                0,
                0,
                new ExchangeConsent.Approval(sending, false),
                new ExchangeConsent.Approval(!sending, false),
                false);
        var agreements = new HashMap<>(state.agreements);
        agreements.put(id, new ExchangeAgreement(id, c, terms));
        var links = new HashMap<>(state.pairing.channels());
        links.put(id, new ExchangeChannel(id, parent, name));
        publish(
                state.invitations,
                agreements,
                state.accepted,
                new ExchangePairingCatalog(state.pairing.tunnels(), links));
    }

    public void reviseChannel(
            UUID id,
            UUID actor,
            NetworkMetadata own,
            NetworkMetadata peer,
            long revision,
            ManagedName name,
            boolean sending,
            ExchangeTerms terms) {
        requireThread();
        var link = channel(id).orElseThrow();
        approvedPair(link.tunnel(), own, peer);
        var old = agreement(id).orElseThrow();
        var side = old.consent().sourceNetwork().equals(own.id())
                ? ExchangeConsent.Side.SOURCE
                : ExchangeConsent.Side.TARGET;
        var c = sending == (side == ExchangeConsent.Side.SOURCE)
                ? old.consent().revise(side, actor, own, revision)
                : old.consent().reverse(side, actor, own, revision);
        var agreements = new HashMap<>(state.agreements);
        agreements.put(id, new ExchangeAgreement(id, c, terms));
        var links = new HashMap<>(state.pairing.channels());
        links.put(id, new ExchangeChannel(id, link.tunnel(), name));
        publish(
                state.invitations,
                agreements,
                state.accepted,
                new ExchangePairingCatalog(state.pairing.tunnels(), links));
    }

    private ExchangeTunnel approvedPair(UUID id, NetworkMetadata own, NetworkMetadata peer) {
        var t = tunnel(id).orElseThrow();
        checkPairIdentities(t, own, peer);
        if (!t.consent().approvedByBoth()) throw new IllegalStateException("Pair not approved");
        return t;
    }

    private static void checkPairIdentities(ExchangeTunnel t, NetworkMetadata own, NetworkMetadata peer) {
        if (!t.peer(own.id()).equals(peer.id())) throw new SecurityException("Pair peer changed");
        var c = t.consent();
        var first = c.sourceNetwork().equals(own.id()) ? own : peer;
        var second = c.targetNetwork().equals(own.id()) ? own : peer;
        if (!first.ownerId().equals(c.sourceOwner()) || !second.ownerId().equals(c.targetOwner()))
            throw new SecurityException("Pair owners changed");
    }

    /** Adds one owner's proposal using a current live invitation; knowing its identity never approves the receiver. */
    public void propose(
            UUID id,
            UUID invitationId,
            UUID actor,
            NetworkMetadata source,
            NetworkMetadata target,
            long expectedInvitationRevision,
            long tick,
            ExchangeTerms terms) {
        requireThread();
        Objects.requireNonNull(id);
        ExchangeInvitation invitation = invitation(invitationId).orElseThrow();
        if (invitation.revision() != expectedInvitationRevision || !invitation.usable(tick))
            throw new IllegalStateException("Stale or unavailable invitation");
        if (!invitation.targetNetwork().equals(target.id())
                || !invitation.targetOwner().equals(target.ownerId()))
            throw new SecurityException("Invitation target changed");
        ExchangeAgreement agreement =
                new ExchangeAgreement(id, ExchangeConsent.propose(invitationId, actor, source, target), terms);
        if (state.agreements.containsKey(id) || state.agreements.size() >= limits.agreements())
            throw new IllegalStateException("Agreement already exists or admission is full");
        if (state.activeCount >= policy.rulesServer()
                || state.activeByNetwork.getOrDefault(source.id(), 0) >= policy.rulesPerNetwork()
                || state.activeByNetwork.getOrDefault(target.id(), 0) >= policy.rulesPerNetwork())
            throw new IllegalStateException("Active exchange rule quota reached");
        Map<UUID, ExchangeAgreement> next = new HashMap<>(state.agreements);
        next.put(id, agreement);
        publish(state.invitations, next, state.accepted);
    }

    /** Approves exact terms and atomically binds first successful approval to the consumed invitation. */
    public void approve(
            UUID id,
            ExchangeConsent.Side side,
            UUID actor,
            NetworkMetadata source,
            NetworkMetadata target,
            long expectedRevision,
            long tick) {
        requireThread();
        if (tick < 0) throw new IllegalArgumentException("Negative exchange tick");
        ExchangeAgreement old = current(id, source, target);
        ExchangeConsent consent = old.consent()
                .approve(side, actor, side == ExchangeConsent.Side.SOURCE ? source : target, expectedRevision);
        if (consent == old.consent()) return;
        Map<UUID, ExchangeInvitation> invitations = state.invitations;
        Map<UUID, UUID> accepted = state.accepted;
        var link = state.pairing.channels().get(id);
        if (link != null) {
            var pair = state.pairing.tunnels().get(link.tunnel());
            if (!pair.consent().approvedByBoth()) throw new IllegalStateException("Pair not approved");
        }
        if (consent.approvedByBoth() && link == null) {
            UUID bound = accepted.get(consent.invitationId());
            if (bound != null && !bound.equals(id))
                throw new IllegalStateException("Invitation approved another agreement");
            if (bound == null) {
                // The stored target signature authorizes consumption when either owner completes revised terms.
                ExchangeInvitation invitation = invitations.get(consent.invitationId());
                ExchangeInvitation consumed =
                        invitation.consumeAfterApproval(target.ownerId(), target, invitation.revision(), tick, consent);
                invitations = new HashMap<>(invitations);
                invitations.put(consumed.id(), consumed);
                accepted = new HashMap<>(accepted);
                accepted.put(consumed.id(), id);
            }
        }
        Map<UUID, ExchangeAgreement> agreements = new HashMap<>(state.agreements);
        agreements.put(id, new ExchangeAgreement(id, consent, old.terms()));
        publish(invitations, agreements, accepted);
    }

    /** Replaces terms only through the owner/revision transition; old approvals cannot survive the replacement. */
    public void revise(
            UUID id,
            ExchangeConsent.Side side,
            UUID actor,
            NetworkMetadata source,
            NetworkMetadata target,
            long expectedRevision,
            ExchangeTerms terms) {
        requireThread();
        ExchangeAgreement old = current(id, source, target);
        ExchangeAgreement next =
                old.revise(side, actor, side == ExchangeConsent.Side.SOURCE ? source : target, expectedRevision, terms);
        Map<UUID, ExchangeAgreement> agreements = new HashMap<>(state.agreements);
        agreements.put(id, next);
        publish(state.invitations, agreements, state.accepted);
    }

    /**
     * Withdraws a signing owner's own consent even if the peer network cannot be read. Checks this side's exact
     * network/owner identity and revision; never grants consent, accesses the peer ledger, or removes peer history.
     */
    public void terminate(
            UUID id, ExchangeConsent.Side side, UUID actor, NetworkMetadata ownNetwork, long expectedRevision) {
        requireThread();
        ExchangeAgreement old = agreement(id).orElseThrow(() -> new IllegalStateException("Agreement unavailable"));
        ExchangeConsent revoked = old.consent().revoke(side, actor, ownNetwork, expectedRevision);
        Map<UUID, ExchangeAgreement> agreements = new HashMap<>(state.agreements);
        agreements.put(id, new ExchangeAgreement(id, revoked, old.terms()));
        publish(state.invitations, agreements, state.accepted);
    }

    /** Applies an authorized side-local action, preserving terms and the other side's intent. */
    public void act(
            UUID id,
            Action action,
            ExchangeConsent.Side side,
            UUID actor,
            NetworkMetadata source,
            NetworkMetadata target,
            long expectedRevision) {
        requireThread();
        Objects.requireNonNull(action);
        Objects.requireNonNull(side);
        if (action == Action.REVOKE) {
            terminate(id, side, actor, side == ExchangeConsent.Side.SOURCE ? source : target, expectedRevision);
            return;
        }
        ExchangeAgreement old = current(id, source, target);
        NetworkMetadata network = side == ExchangeConsent.Side.SOURCE ? source : target;
        ExchangeConsent consent =
                switch (Objects.requireNonNull(action)) {
                    case PAUSE -> old.consent().pause(side, actor, network, expectedRevision);
                    case RESUME -> old.consent().resume(side, actor, network, expectedRevision);
                    case REVOKE -> old.consent().revoke(side, actor, network, expectedRevision);
                };
        if (consent == old.consent()) return;
        Map<UUID, ExchangeAgreement> agreements = new HashMap<>(state.agreements);
        agreements.put(id, new ExchangeAgreement(id, consent, old.terms()));
        publish(state.invitations, agreements, state.accepted);
    }

    /** Revokes an unconsumed invitation without changing already published agreements. */
    public void revokeInvitation(UUID id, UUID actor, NetworkMetadata target, long expectedRevision) {
        requireThread();
        ExchangeInvitation next = invitation(id).orElseThrow().revoke(actor, target, expectedRevision);
        Map<UUID, ExchangeInvitation> invitations = new HashMap<>(state.invitations);
        invitations.put(id, next);
        publish(invitations, state.agreements, state.accepted);
    }

    /** Owner-thread immutable invitation view; callers must separately restrict code disclosure to receiving owners. */
    public java.util.Collection<ExchangeInvitation> invitations() {
        requireThread();
        return state.invitations.values();
    }

    /** Owner-thread immutable internal lookup; callers separately authorize any player-visible disclosure. */
    public Optional<ExchangeAgreement> agreement(UUID id) {
        requireThread();
        return Optional.ofNullable(state.agreements.get(id));
    }
    /** Owner-thread immutable internal lookup; not a shareable-code lookup or authority grant. */
    public Optional<ExchangeInvitation> invitation(UUID id) {
        requireThread();
        return Optional.ofNullable(state.invitations.get(id));
    }
    /** Owner-thread immutable index, rebuilt on successful state publication; no network inventory is exposed. */
    public Set<UUID> agreementsFor(UUID network) {
        requireThread();
        return state.byNetwork.getOrDefault(network, Set.of());
    }

    /** Owner-thread internal history order; -1 means not terminated. Does not grant player read permission. */
    public long terminationRevision(UUID id) {
        requireThread();
        return state.terminatedRevisions.getOrDefault(Objects.requireNonNull(id), -1L);
    }

    private ExchangeAgreement current(UUID id, NetworkMetadata source, NetworkMetadata target) {
        ExchangeAgreement agreement = agreement(id).orElseThrow();
        ExchangeConsent consent = agreement.consent();
        if (!source.id().equals(consent.sourceNetwork())
                || !target.id().equals(consent.targetNetwork())
                || !source.ownerId().equals(consent.sourceOwner())
                || !target.ownerId().equals(consent.targetOwner()))
            throw new SecurityException("Exchange network ownership changed");
        return agreement;
    }

    private void publish(
            Map<UUID, ExchangeInvitation> invitations,
            Map<UUID, ExchangeAgreement> agreements,
            Map<UUID, UUID> accepted) {
        publish(invitations, agreements, accepted, state.pairing.retain(agreements));
    }

    private void publish(
            Map<UUID, ExchangeInvitation> invitations,
            Map<UUID, ExchangeAgreement> agreements,
            Map<UUID, UUID> accepted,
            ExchangePairingCatalog pairing) {
        long revision = Math.incrementExact(state.revision);
        Map<UUID, Long> terminated = new HashMap<>();
        for (ExchangeAgreement agreement : agreements.values())
            if (agreement.consent().revoked())
                terminated.put(agreement.id(), state.terminatedRevisions.getOrDefault(agreement.id(), revision));
        State next = checked(
                revision, invitations, agreements, accepted, terminated, pairing.atRevision(state.pairing, revision));
        ManagedObjectNbtSize.validate(encode(next));
        state = next;
        super.setDirty(true);
    }

    private static State checked(
            long revision,
            Map<UUID, ExchangeInvitation> invitations,
            Map<UUID, ExchangeAgreement> agreements,
            Map<UUID, UUID> accepted,
            Map<UUID, Long> terminated) {
        return checked(revision, invitations, agreements, accepted, terminated, ExchangePairingCatalog.EMPTY);
    }

    private static State checked(
            long revision,
            Map<UUID, ExchangeInvitation> invitations,
            Map<UUID, ExchangeAgreement> agreements,
            Map<UUID, UUID> accepted,
            Map<UUID, Long> terminated,
            ExchangePairingCatalog pairing) {
        pairing.validate(agreements);
        for (long order : pairing.ended().values())
            if (order > revision) throw new IllegalArgumentException("Future pairing history");
        if (revision < 0
                || invitations.size() > ResourceFilterPreset.MAX_ENTRIES
                || agreements.size() > ResourceFilterPreset.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid exchange shard bounds");
        for (ExchangeInvitation invitation : invitations.values()) {
            UUID bound = accepted.get(invitation.id());
            if ((invitation.state() == ExchangeInvitation.State.CONSUMED) != (bound != null))
                throw new IllegalArgumentException("Unbound invitation consumption");
            if (bound != null
                    && agreements.containsKey(bound)
                    && !agreements.get(bound).consent().invitationId().equals(invitation.id()))
                throw new IllegalArgumentException("Invalid accepted agreement binding");
        }
        if (!invitations.keySet().containsAll(accepted.keySet()))
            throw new IllegalArgumentException("Orphan invitation binding");
        Map<UUID, Set<UUID>> index = new HashMap<>();
        Set<UUID> references = new HashSet<>();
        Map<UUID, Integer> activeByNetwork = new HashMap<>();
        int activeCount = 0;
        if (!agreements.keySet().containsAll(terminated.keySet()))
            throw new IllegalArgumentException("Orphan history order");
        for (ExchangeAgreement agreement : agreements.values()) {
            ExchangeConsent consent = agreement.consent();
            references.add(consent.invitationId());
            Long ended = terminated.get(agreement.id());
            if (consent.revoked() != (ended != null) || ended != null && (ended < 0 || ended > revision))
                throw new IllegalArgumentException("Invalid terminated rule order");
            if (!consent.revoked()) {
                activeCount++;
                activeByNetwork.merge(consent.sourceNetwork(), 1, Integer::sum);
                activeByNetwork.merge(consent.targetNetwork(), 1, Integer::sum);
            }
            ExchangeInvitation invitation = invitations.get(consent.invitationId());
            if (invitation == null) throw new IllegalArgumentException("Missing channel invitation identity");
            if (!pairing.channels().containsKey(agreement.id())
                    && (invitation == null
                            || !invitation.targetNetwork().equals(consent.targetNetwork())
                            || !invitation.targetOwner().equals(consent.targetOwner())))
                throw new IllegalArgumentException("Agreement invitation mismatch");
            if (!pairing.channels().containsKey(agreement.id())
                    && consent.approvedByBoth()
                    && !agreement.id().equals(accepted.get(invitation.id())))
                throw new IllegalArgumentException("Approved agreement has no consumed invitation");
            index.computeIfAbsent(consent.sourceNetwork(), ignored -> new HashSet<>())
                    .add(agreement.id());
            index.computeIfAbsent(consent.targetNetwork(), ignored -> new HashSet<>())
                    .add(agreement.id());
        }
        for (var tunnel : pairing.tunnels().values()) {
            var c = tunnel.consent();
            var invitation = invitations.get(c.invitationId());
            references.add(c.invitationId());
            if (invitation == null
                    || !invitation.targetNetwork().equals(c.targetNetwork())
                    || !invitation.targetOwner().equals(c.targetOwner()))
                throw new IllegalArgumentException("Pair invitation mismatch");
            if (c.approvedByBoth() && !tunnel.id().equals(accepted.get(c.invitationId())))
                throw new IllegalArgumentException("Unbound pairing approval");
        }
        index.replaceAll((id, values) -> Set.copyOf(values));
        return new State(
                revision,
                Map.copyOf(invitations),
                Map.copyOf(agreements),
                Map.copyOf(accepted),
                Map.copyOf(index),
                Set.copyOf(references),
                Map.copyOf(terminated),
                activeCount,
                Map.copyOf(activeByNetwork),
                pairing);
    }

    private static CompoundTag encode(State state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 4);
        tag.put("pairing", state.pairing.encode());
        tag.putLong("revision", state.revision);
        ListTag invitations = new ListTag();
        state.invitations.values().stream()
                .sorted(Comparator.comparing(ExchangeInvitation::id))
                .forEach(value -> {
                    CompoundTag row = new CompoundTag();
                    row.put("invitation", ExchangeStateNbt.encodeInvitation(value));
                    UUID accepted = state.accepted.get(value.id());
                    if (accepted != null) row.putUUID("accepted_agreement", accepted);
                    invitations.add(row);
                });
        tag.put("invitations", invitations);
        ListTag agreements = new ListTag();
        state.agreements.values().stream()
                .sorted(Comparator.comparing(ExchangeAgreement::id))
                .forEach(value -> agreements.add(ExchangeStateNbt.encodeAgreement(value)));
        tag.put("agreements", agreements);
        ListTag terminated = new ListTag();
        state.terminatedRevisions.keySet().stream().sorted().forEach(id -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", id);
            row.putLong("revision", state.terminatedRevisions.get(id));
            terminated.add(row);
        });
        tag.put("terminated_revisions", terminated);
        return tag;
    }

    /** Writes detached standard NBT on the owning thread; does not flush files or alter dirty state. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireThread();
        CompoundTag encoded = encode(state);
        for (String key : encoded.getAllKeys()) tag.put(key, encoded.get(key));
        return tag;
    }

    @Override
    public void setDirty(boolean dirty) {
        requireThread();
        super.setDirty(dirty);
    }

    @Override
    public boolean isDirty() {
        requireThread();
        return super.isDirty();
    }

    private void requireThread() {
        if (Thread.currentThread() != owningThread)
            throw new IllegalStateException("Exchange SavedData accessed outside owning thread");
    }
}
