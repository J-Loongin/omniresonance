// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.networking.ManagementTransferMessage;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class NodePolicySavesTest {
    private static final UUID SESSION = new UUID(91, 1), TRANSFER = new UUID(91, 2);

    private static ResourcePolicyEdit policy() {
        var ids = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 3000; i++) ids.add(ResourceLocation.parse("missing:" + "a".repeat(100) + i));
        return new ResourcePolicyEdit(
                20,
                ResourcePolicyEdit.Scope.custom(ids),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.InputFields(0),
                List.of(),
                ids,
                false);
    }

    @Test
    void uploadContinuesWithoutAnyScreenAndWaitsForTheExactResult() {
        var saves = new NodePolicySaves();
        var sent = new ArrayList<ManagementTransferMessage>();
        var outcomes = new ArrayList<NodePolicySaves.Completion>();
        saves.begin(7, SESSION, 3, TRANSFER, policy());
        saves.receive(new NodeMenuResponse.UploadReady(7, SESSION, 4, TRANSFER));
        saves.tick(sent::add, outcomes::add);
        assertTrue(sent.isEmpty());
        saves.receive(new NodeMenuResponse.UploadReady(7, SESSION, 3, TRANSFER));
        saves.tick(sent::add, outcomes::add);
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst() instanceof ManagementTransferMessage.Chunk);
        int length = io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encode(policy()).length;
        int bound = io.github.loongin.omniresonance.networking.ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES;
        int fragments = (length + bound - 1) / bound;
        for (int i = 1; i < fragments; i++) {
            int before = sent.size();
            saves.tick(sent::add, outcomes::add);
            assertEquals(i + 1 == fragments ? 2 : 1, sent.size() - before);
        }
        assertTrue(sent.getLast() instanceof ManagementTransferMessage.Finish);
        assertTrue(saves.active());
        int count = sent.size();
        saves.tick(sent::add, outcomes::add);
        assertEquals(count, sent.size(), "The final request must never be resent");
        assertNull(saves.receive(
                new NodeMenuResponse.Failure(7, new UUID(92, 1), 3, NodeMenuResponse.Reason.INVALID_REQUEST, null)));
        assertTrue(saves.active());
        var outcome = saves.receive(
                new NodeMenuResponse.Failure(7, SESSION, 3, NodeMenuResponse.Reason.INVALID_REQUEST, null));
        assertFalse(outcome.uncertain());
        assertFalse(saves.active());
    }

    @Test
    void timeoutsDistinguishUncommittedUploadFromUnconfirmedFinalRequest() {
        for (boolean finish : new boolean[] {false, true}) {
            var saves = new NodePolicySaves();
            var sent = new ArrayList<ManagementTransferMessage>();
            var outcomes = new ArrayList<NodePolicySaves.Completion>();
            saves.begin(7, SESSION, 3, finish ? null : TRANSFER, finish ? null : policy());
            assertThrows(IllegalStateException.class, () -> saves.begin(8, SESSION, 4, null, null));
            for (int tick = 0; tick < 200; tick++) saves.tick(sent::add, outcomes::add);
            assertEquals(1, outcomes.size());
            assertEquals(finish, outcomes.getFirst().uncertain());
            assertFalse(saves.active());
            assertEquals(finish ? 0 : 1, sent.size());
            if (!finish) assertTrue(sent.getFirst() instanceof ManagementTransferMessage.Abort);
            saves.tick(sent::add, outcomes::add);
            assertEquals(1, outcomes.size());
        }
    }

    @Test
    void transportFailuresStopOnceAndDistinguishFinalCommit() {
        for (boolean finalFrame : new boolean[] {false, true}) {
            var saves = new NodePolicySaves();
            var outcomes = new ArrayList<NodePolicySaves.Completion>();
            saves.begin(7, SESSION, 3, TRANSFER, policy());
            saves.receive(new NodeMenuResponse.UploadReady(7, SESSION, 3, TRANSFER));
            for (int tick = 0; tick < 30; tick++)
                saves.tick(
                        message -> {
                            if ((message instanceof ManagementTransferMessage.Finish) == finalFrame)
                                throw new IllegalStateException("Disconnected transport");
                        },
                        outcomes::add);
            assertFalse(saves.active());
            assertEquals(1, outcomes.size());
            assertEquals(finalFrame, outcomes.getFirst().uncertain());
        }
    }

    @Test
    void failedAbortStillReleasesTimedOutSave() {
        var saves = new NodePolicySaves();
        var outcomes = new ArrayList<NodePolicySaves.Completion>();
        saves.begin(7, SESSION, 3, TRANSFER, policy());
        for (int tick = 0; tick < 201; tick++)
            saves.tick(
                    message -> {
                        throw new IllegalStateException("Disconnected transport");
                    },
                    outcomes::add);
        assertFalse(saves.active());
        assertEquals(1, outcomes.size());
        assertFalse(outcomes.getFirst().uncertain());
    }

    @Test
    void disconnectDropsTheSnapshotAndNeverResumesIt() {
        var saves = new NodePolicySaves();
        saves.begin(7, SESSION, 3, TRANSFER, policy());
        saves.clear();
        saves.receive(new NodeMenuResponse.UploadReady(7, SESSION, 3, TRANSFER));
        saves.tick(
                message -> {
                    throw new AssertionError("Stale upload resumed");
                },
                result -> {
                    throw new AssertionError("Stale result");
                });
        assertFalse(saves.active());
    }
}
