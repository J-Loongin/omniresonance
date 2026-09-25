// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.AuditEntry;
import io.github.loongin.omniresonance.persistence.ExchangeSavedData;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-thread exchange management using current directory authority, never caller-supplied network metadata.
 * The server ingress must supply the authenticated actor, validated server-held terms and management budgets;
 * these internal UUID entry points are not packet handlers. No simulation, inventory access, online-player lookup
 * or forced I/O occurs. Audit follows committed changes only; a throwing audit sink must not cause a mutation retry.
 */
public final class ExchangeManagementService implements AutoCloseable {
    /** Signals a confirmed state change whose audit failed; callers must report the outcome without retrying it. */
    public static final class CommittedAuditFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CommittedAuditFailure(RuntimeException cause) {
            super("Exchange mutation committed but audit failed; do not retry", cause);
        }
    }

    private final Thread owningThread = Thread.currentThread();
    private final NetworkDirectory directory;
    private final ExchangeSavedData data;
    private final BiConsumer<UUID, AuditEntry> audit;
    private final LongSupplier gameTick;
    private final Supplier<UUID> identities;
    private boolean closed;

    public ExchangeManagementService(
            NetworkDirectory directory,
            ExchangeSavedData data,
            BiConsumer<UUID, AuditEntry> audit,
            LongSupplier gameTick,
            Supplier<UUID> identities) {
        this.directory = Objects.requireNonNull(directory);
        this.data = Objects.requireNonNull(data);
        this.audit = Objects.requireNonNull(audit);
        this.gameTick = Objects.requireNonNull(gameTick);
        this.identities = Objects.requireNonNull(identities);
    }

    /** Returns a code only to the current receiving owner; audit omits its recoverable invitation identity. */
    public String issueCode(UUID actor, UUID network) {
        NetworkMetadata target = authorize(actor, network, true);
        AuditEntry entry = entry("exchange_invite_issue", actor, network);
        String code = data.issueCode(actor, target, entry.gameTick());
        recordAudit(network, entry);
        return code;
    }

    /** Revokes only a code owned by the current receiving network; audit never contains its recoverable identity. */
    public void revokeCode(UUID actor, UUID network, UUID invitation, long revision) {
        NetworkMetadata target = authorize(actor, network, true);
        AuditEntry entry = entry("exchange_invite_revoke", actor, network);
        data.revokeInvitation(invitation, actor, target, revision);
        recordAudit(network, entry);
    }

    /** Proposes server-held terms after current source ownership and live code validation; never auto-approves. */
    public UUID propose(UUID actor, UUID network, String code, ExchangeTerms terms) {
        NetworkMetadata source = authorize(actor, network, true);
        long tick = tick();
        ExchangeInvitation invitation = data.findUsableInvitation(code, tick)
                .orElseThrow(() -> new IllegalStateException("Invitation is unavailable"));
        NetworkMetadata target = network(invitation.targetNetwork());
        UUID id = Objects.requireNonNull(identities.get());
        AuditEntry entry = new AuditEntry(action("exchange_propose"), actor, id, tick, "");
        data.propose(id, invitation.id(), actor, source, target, invitation.revision(), tick, terms);
        recordAudit(network, entry);
        return id;
    }

    /** Returns immutable agreement details only through a currently manageable participating network. */
    public ExchangeAgreement view(UUID actor, UUID network, UUID id) {
        authorize(actor, network, false);
        ExchangeAgreement agreement =
                data.agreement(id).orElseThrow(() -> new IllegalStateException("Agreement unavailable"));
        side(network, agreement);
        return agreement;
    }

    /** Approves exact stored terms for the current owner of the selected participating side. */
    public void approve(UUID actor, UUID network, UUID id, long revision) {
        authorize(actor, network, true);
        ExchangeAgreement old = view(actor, network, id);
        AuditEntry entry = entry("exchange_approve", actor, id);
        data.approve(
                id,
                side(network, old),
                actor,
                network(old.consent().sourceNetwork()),
                network(old.consent().targetNetwork()),
                revision,
                entry.gameTick());
        auditIfChanged(network, old, entry);
    }

    /** Replaces terms through owner-only revision invalidation, with no partial update on expected failure. */
    public void revise(UUID actor, UUID network, UUID id, long revision, ExchangeTerms terms) {
        authorize(actor, network, true);
        ExchangeAgreement old = view(actor, network, id);
        AuditEntry entry = entry("exchange_revise", actor, id);
        data.revise(
                id,
                side(network, old),
                actor,
                network(old.consent().sourceNetwork()),
                network(old.consent().targetNetwork()),
                revision,
                terms);
        auditIfChanged(network, old, entry);
    }

    /** Administrators may pause only their side; resume and permanent revocation remain owner-only. */
    public void act(UUID actor, UUID network, UUID id, long revision, ExchangeSavedData.Action action) {
        Objects.requireNonNull(action);
        authorize(actor, network, action != ExchangeSavedData.Action.PAUSE);
        ExchangeAgreement old = view(actor, network, id);
        AuditEntry entry = entry(
                switch (action) {
                    case PAUSE -> "exchange_pause";
                    case RESUME -> "exchange_resume";
                    case REVOKE -> "exchange_revoke";
                },
                actor,
                id);
        if (action == ExchangeSavedData.Action.REVOKE) {
            data.terminate(id, side(network, old), actor, network(network), revision);
        } else {
            data.act(
                    id,
                    action,
                    side(network, old),
                    actor,
                    network(old.consent().sourceNetwork()),
                    network(old.consent().targetNetwork()),
                    revision);
        }
        auditIfChanged(network, old, entry);
    }

    /** Pure live consent gate without requiring online players; policy, storage and work-budget gates are separate. */
    public boolean permitsExecution(UUID id) {
        requireThread();
        ExchangeAgreement agreement = data.agreement(id).orElse(null);
        if (agreement == null) return false;
        NetworkMetadata source =
                directory.find(agreement.consent().sourceNetwork()).orElse(null);
        NetworkMetadata target =
                directory.find(agreement.consent().targetNetwork()).orElse(null);
        return source != null && target != null && agreement.consent().permitsExecution(source, target);
    }

    private NetworkMetadata authorize(UUID actor, UUID network, boolean ownerOnly) {
        requireThread();
        Objects.requireNonNull(actor);
        NetworkMetadata metadata = network(network);
        if (ownerOnly
                ? !actor.equals(metadata.ownerId())
                : !NetworkPermissions.canManage(actor, metadata.ownerId(), metadata.administrators()))
            throw new SecurityException("Exchange network role denied");
        return metadata;
    }

    private NetworkMetadata network(UUID id) {
        return directory.find(id).orElseThrow(() -> new IllegalStateException("Exchange network unavailable"));
    }

    private static ExchangeConsent.Side side(UUID network, ExchangeAgreement agreement) {
        if (network.equals(agreement.consent().sourceNetwork())) return ExchangeConsent.Side.SOURCE;
        if (network.equals(agreement.consent().targetNetwork())) return ExchangeConsent.Side.TARGET;
        throw new SecurityException("Agreement belongs to another network");
    }

    private AuditEntry entry(String action, UUID actor, UUID target) {
        return new AuditEntry(action(action), actor, target, tick(), "");
    }

    private static ResourceLocation action(String value) {
        return ResourceLocation.fromNamespaceAndPath("omniresonance", value);
    }

    private long tick() {
        long value = gameTick.getAsLong();
        if (value < 0) throw new IllegalStateException("Invalid exchange clock");
        return value;
    }

    private void auditIfChanged(UUID network, ExchangeAgreement old, AuditEntry entry) {
        if (data.agreement(old.id()).orElseThrow().consent().revision()
                != old.consent().revision()) recordAudit(network, entry);
    }

    private void recordAudit(UUID network, AuditEntry entry) {
        try {
            audit.accept(network, entry);
        } catch (RuntimeException failure) {
            throw new CommittedAuditFailure(failure);
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != owningThread || closed)
            throw new IllegalStateException("Exchange service is closed or accessed outside owning thread");
    }

    @Override
    public void close() {
        requireThread();
        closed = true;
    }
}
