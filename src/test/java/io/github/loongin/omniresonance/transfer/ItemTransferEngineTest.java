// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class ItemTransferEngineTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final ItemVariant IRON = ItemVariant.from(new ItemStack(Items.IRON_INGOT), PROVIDER);
    private final ItemTransferEngine engine = new ItemTransferEngine();
    private final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
    private final FakeItemHandler source = new FakeItemHandler(1), target = new FakeItemHandler(1);
    private final Endpoint s = new Endpoint(source), t = new Endpoint(target);
    private TransferWorkBudget budget = new TransferWorkBudget(1, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);

    private static final class Endpoint implements ItemTransferEngine.Handle {
        final FakeItemHandler handler;
        boolean valid = true;

        Endpoint(FakeItemHandler handler) {
            this.handler = handler;
        }

        public FakeItemHandler handler() {
            return handler;
        }

        public boolean valid() {
            return valid;
        }
    }

    private ItemTransferEngine.Result transfer(int amount) {
        source.stacks[0] = IRON.stack(amount);
        var result =
                engine.commit(s, 0, t, 0, IRON, amount, recovery, ServerSettings.RecoveryLimits.defaults(), budget);
        assertEquals(source.calls + target.calls, budget.calls());
        assertTrue(budget.calls() <= ItemTransferEngine.MAXIMUM_COMMIT_CALLS);
        return result;
    }

    @Test
    void targetSimulationRefusalDoesNotExtract() {
        target.refuseSimulation = true;
        transfer(64);
        assertEquals(64, source.stacks[0].getCount());
        assertEquals(0, source.extractionCalls);
    }

    @Test
    void partialInsertionReturnsExactlyFour() {
        target.actualInsertLimit = 60;
        var r = transfer(64);
        assertEquals(60, r.moved());
        assertEquals(4, r.returned());
        assertEquals(4, source.stacks[0].getCount());
        assertEquals(60, r.removed());
        assertTrue(recovery.isEmpty());
        assertFalse(budget.canStart());
    }

    @Test
    void refusedReturnPersistsExactlyFour() {
        target.actualInsertLimit = 60;
        source.refuseReturn = true;
        var r = transfer(64);
        assertEquals(4, r.buffered());
        assertEquals(4, recovery.amount(IRON.key()));
        assertEquals(64, r.removed());
        assertEquals(10, budget.calls());
    }

    @Test
    void extractionThrowIsNeverRetriedOrGuessed() {
        source.throwExtract = true;
        var r = transfer(64);
        assertEquals(ItemTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertNotNull(r.cause());
        assertEquals(1, source.extractionCalls);
        assertEquals(0, target.insertionCalls);
        assertTrue(recovery.isEmpty());
    }

    @Test
    void insertionThrowDoesNotCompensateUnknownResult() {
        target.throwInsert = true;
        var r = transfer(64);
        assertEquals(ItemTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertEquals(1, target.insertionCalls);
        assertEquals(0, source.insertionCalls);
        assertTrue(recovery.isEmpty());
    }

    @Test
    void wrongActualIdentityCannotMergeIntoReservedVariant() {
        source.wrongExtraction = true;
        var r = transfer(64);
        assertEquals(ItemTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertEquals(0, target.insertionCalls);
        assertTrue(recovery.isEmpty());
    }

    @Test
    void supports65556InOneExtendedApiRequest() {
        var r = transfer(65556);
        assertEquals(65556, r.moved());
        assertEquals(65556, source.maximumRequest);
        assertEquals(1, source.extractionCalls);
        assertEquals(1, target.insertionCalls);
    }

    @Test
    void nativeLimitedResponseRemainsHonest() {
        source.extractionLimit = 64;
        var r = transfer(65556);
        assertEquals(64, r.moved());
        assertEquals(65492, source.stacks[0].getCount());
        assertEquals(65556, source.maximumRequest);
    }

    @Test
    void distinctFaceWrappersForOnePhysicalInventoryCannotLoop() {
        source.stacks[0] = IRON.stack(64);
        Object shared = new Object();
        ItemTransferEngine.Handle a = new ItemTransferEngine.Handle() {
            public FakeItemHandler handler() {
                return source;
            }

            public boolean valid() {
                return true;
            }

            public Object physicalIdentity() {
                return shared;
            }
        };
        ItemTransferEngine.Handle b = new ItemTransferEngine.Handle() {
            public FakeItemHandler handler() {
                return target;
            }

            public boolean valid() {
                return true;
            }

            public Object physicalIdentity() {
                return shared;
            }
        };
        var r = engine.commit(a, 0, b, 0, IRON, 64, recovery, ServerSettings.RecoveryLimits.defaults(), budget);
        assertEquals(0, r.moved());
        assertEquals(0, budget.calls());
    }

    @Test
    void invalidationByFinalSourceQueryPreventsExtraction() {
        source.afterSlots = () -> {
            if (source.slotQueries == 2) t.valid = false;
        };
        transfer(64);
        assertEquals(0, source.extractionCalls);
    }

    @Test
    void invalidationBySourceSimulationPreventsStaleTargetSimulation() {
        source.afterSimulatedExtract = () -> t.valid = false;
        transfer(64);
        assertEquals(1, target.calls);
        assertEquals(0, source.extractionCalls);
    }

    @Test
    void invalidationDuringExtractionPreventsStaleTargetMutation() {
        source.afterExtract = () -> t.valid = false;
        var r = transfer(64);
        assertEquals(0, target.insertionCalls);
        assertEquals(64, r.returned());
        assertEquals(64, source.stacks[0].getCount());
    }

    @Test
    void invalidationOfBothHandlesBuffersKnownExtraction() {
        source.afterExtract = () -> {
            s.valid = false;
            t.valid = false;
        };
        var r = transfer(64);
        assertEquals(0, target.insertionCalls);
        assertEquals(0, source.insertionCalls);
        assertEquals(64, r.buffered());
        assertEquals(64, recovery.amount(IRON.key()));
    }

    @Test
    void recoveryRefusalStopsBeforeMutation() {
        java.util.Map<ResourceVariantKey, Long> occupied = new java.util.HashMap<>();
        for (int i = 0; i < 64; i++)
            occupied.put(
                    new ResourceVariantKey(
                            net.minecraft.resources.ResourceLocation.parse("example:raw"), new byte[] {(byte) i}),
                    1L);
        recovery.restore(occupied);
        source.stacks[0] = IRON.stack(64);
        var r = engine.commit(s, 0, t, 0, IRON, 64, recovery, new ServerSettings.RecoveryLimits(64, 1048576), budget);
        assertEquals(ItemTransferEngine.Failure.RECOVERY_FULL, r.failure());
        assertEquals(64, source.stacks[0].getCount());
    }
}
