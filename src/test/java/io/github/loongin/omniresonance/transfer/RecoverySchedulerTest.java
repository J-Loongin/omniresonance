// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RecoverySchedulerTest {
    @Test
    void recoveryOnlyNetworksShareBudgetAndRunAtMostOneStepPerTick() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        Map<UUID, Integer> calls = new HashMap<>();
        ResourceDirectScheduler scheduler = new ResourceDirectScheduler(new ResourceDirectScheduler.Environment() {
            public List<ResourceLocation> registeredTypes() {
                return List.of();
            }

            public boolean active(ResourceDirectScheduler.Configuration config) {
                return false;
            }

            public ResourceTransferEngine.Handle resolve(
                    ResourceDirectScheduler.Configuration config,
                    ResourceLocation type,
                    Direction face,
                    TransferWorkBudget budget) {
                throw new AssertionError("Recovery discovered a native endpoint");
            }

            public RecoveryBuffer recovery(UUID id) {
                throw new AssertionError("Unexpected direct transfer");
            }

            public boolean hasRecoveryWork(UUID id) {
                return calls.getOrDefault(id, 0) < 2;
            }

            public void advanceRecovery(UUID id, ServerSettings settings) {
                calls.merge(id, 1, Integer::sum);
            }
        });
        scheduler.wakeRecovery(a, 0);
        scheduler.wakeRecovery(b, 0);
        TransferWorkBudget empty = ResourceDirectSchedulerTest.budget(1);
        empty.beforeCall();
        scheduler.tick(0, ServerSettings.defaults(), empty);
        assertEquals(Map.of(), calls);
        scheduler.tick(0, ServerSettings.defaults(), ResourceDirectSchedulerTest.budget(1));
        assertEquals(Map.of(a, 1, b, 1), calls);
        scheduler.tick(0, ServerSettings.defaults(), ResourceDirectSchedulerTest.budget(1));
        assertEquals(Map.of(a, 1, b, 1), calls);
        scheduler.tick(1, ServerSettings.defaults(), ResourceDirectSchedulerTest.budget(1));
        scheduler.tick(2, ServerSettings.defaults(), ResourceDirectSchedulerTest.budget(1));
        assertEquals(Map.of(a, 2, b, 2), calls);
    }
}
