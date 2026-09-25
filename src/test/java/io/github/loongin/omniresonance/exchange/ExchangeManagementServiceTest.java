// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.AuditEntry;
import io.github.loongin.omniresonance.persistence.ExchangeSavedData;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeManagementServiceTest {
    @Test
    void ownerCanWithdrawConsentWhenCounterpartyMetadataIsUnavailable() {
        var source = ExchangeConsentTest.SOURCE;
        var target = ExchangeConsentTest.TARGET;
        var directory = new NetworkDirectory(List.of(source, target));
        var data = ExchangeSavedData.create(new ExchangeSavedData.Limits(8, 8));
        var id = new UUID(90, 8);
        data.issue(ExchangeConsentTest.INVITE, target.ownerId(), target, 0);
        data.propose(
                id,
                ExchangeConsentTest.INVITE,
                source.ownerId(),
                source,
                target,
                0,
                1,
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1));
        data.approve(id, ExchangeConsent.Side.TARGET, target.ownerId(), source, target, 0, 2);
        List<AuditEntry> audit = new ArrayList<>();
        var service =
                new ExchangeManagementService(directory, data, (network, entry) -> audit.add(entry), () -> 3, () -> id);
        directory.commitRemoval(directory.prepareRemoval(target));
        service.act(source.ownerId(), source.id(), id, 1, ExchangeSavedData.Action.REVOKE);
        assertTrue(data.agreement(id).orElseThrow().consent().revoked());
        assertEquals(1, audit.size());
        assertThrows(
                SecurityException.class,
                () -> service.act(ExchangeConsentTest.ADMIN, source.id(), id, 2, ExchangeSavedData.Action.REVOKE));
    }

    @Test
    void receiverOwnerRevokesCodesWithoutExposingTheirIdentityInAudit() {
        var source = ExchangeConsentTest.SOURCE;
        var target = ExchangeConsentTest.TARGET;
        ExchangeSavedData data = ExchangeSavedData.create(new ExchangeSavedData.Limits(8, 8));
        data.issue(ExchangeConsentTest.INVITE, target.ownerId(), target, 0);
        List<AuditEntry> audit = new ArrayList<>();
        var service = new ExchangeManagementService(
                new NetworkDirectory(List.of(source, target)),
                data,
                (network, entry) -> audit.add(entry),
                () -> 1,
                () -> new UUID(90, 1));
        assertThrows(
                SecurityException.class,
                () -> service.revokeCode(source.ownerId(), source.id(), ExchangeConsentTest.INVITE, 0));
        assertTrue(audit.isEmpty());
        service.revokeCode(target.ownerId(), target.id(), ExchangeConsentTest.INVITE, 0);
        assertEquals(target.id(), audit.getFirst().target());
        assertEquals("exchange_invite_revoke", audit.getFirst().action().getPath());
        assertFalse(data.invitation(ExchangeConsentTest.INVITE).orElseThrow().usable(1));
    }

    @Test
    void auditFailureExplicitlyReportsAlreadyCommittedOutcome() {
        var source = ExchangeConsentTest.SOURCE;
        var target = ExchangeConsentTest.TARGET;
        ExchangeSavedData data = ExchangeSavedData.create(new ExchangeSavedData.Limits(8, 8));
        data.issue(ExchangeConsentTest.INVITE, target.ownerId(), target, 0);
        UUID id = new UUID(90, 2);
        var service = new ExchangeManagementService(
                new NetworkDirectory(List.of(source, target)),
                data,
                (network, entry) -> {
                    throw new IllegalStateException("Test audit failure");
                },
                () -> 1,
                () -> id);
        var terms = new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1);
        assertThrows(
                ExchangeManagementService.CommittedAuditFailure.class,
                () -> service.propose(
                        source.ownerId(),
                        source.id(),
                        ExchangeInvitationCode.encode(ExchangeConsentTest.INVITE),
                        terms));
        assertTrue(data.agreement(id).isPresent());
        assertFalse(data.agreement(id).orElseThrow().consent().approvedByBoth());
    }

    @Test
    void currentRolesGateActionsAndOnlyCommittedChangesAreAudited() {
        var source = ExchangeConsentTest.SOURCE;
        var target = ExchangeConsentTest.TARGET;
        NetworkDirectory directory = new NetworkDirectory(List.of(source, target));
        ExchangeSavedData data = ExchangeSavedData.create(new ExchangeSavedData.Limits(8, 8));
        data.issue(ExchangeConsentTest.INVITE, target.ownerId(), target, 0);
        List<AuditEntry> audit = new ArrayList<>();
        UUID id = new UUID(90, 1);
        var service =
                new ExchangeManagementService(directory, data, (network, entry) -> audit.add(entry), () -> 1, () -> id);
        String code = ExchangeInvitationCode.encode(ExchangeConsentTest.INVITE);
        var terms = new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1);
        assertThrows(
                SecurityException.class, () -> service.propose(ExchangeConsentTest.ADMIN, source.id(), code, terms));
        assertEquals(id, service.propose(source.ownerId(), source.id(), code, terms));
        assertEquals(1, audit.size());
        assertThrows(SecurityException.class, () -> service.view(source.ownerId(), target.id(), id));
        assertThrows(SecurityException.class, () -> service.approve(ExchangeConsentTest.ADMIN, source.id(), id, 0));
        service.approve(target.ownerId(), target.id(), id, 0);
        assertTrue(service.permitsExecution(id));
        service.approve(target.ownerId(), target.id(), id, 1);
        assertEquals(2, audit.size());
        service.act(ExchangeConsentTest.ADMIN, source.id(), id, 1, ExchangeSavedData.Action.PAUSE);
        assertFalse(service.permitsExecution(id));
        assertThrows(
                SecurityException.class,
                () -> service.act(ExchangeConsentTest.ADMIN, source.id(), id, 2, ExchangeSavedData.Action.RESUME));
        NetworkMetadata removed =
                new NetworkMetadata(source.id(), source.ownerId(), source.name(), source.creationOrder(), Set.of());
        directory.commitMetadataReplacement(directory.prepareMetadataReplacement(source, removed));
        assertThrows(SecurityException.class, () -> service.view(ExchangeConsentTest.ADMIN, source.id(), id));
        service.act(source.ownerId(), source.id(), id, 2, ExchangeSavedData.Action.RESUME);
        assertTrue(service.permitsExecution(id));
        directory.commitRemoval(directory.prepareRemoval(target));
        assertFalse(service.permitsExecution(id));
        assertThrows(IllegalStateException.class, () -> service.approve(source.ownerId(), source.id(), id, 3));
        assertEquals(4, audit.size());
        for (AuditEntry entry : audit) {
            assertEquals("", entry.summary());
            assertFalse(entry.target().equals(ExchangeConsentTest.INVITE));
        }
        service.close();
        assertThrows(IllegalStateException.class, () -> service.permitsExecution(id));
    }
}
