// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceTransferPolicyTest {
    @Test
    void legacyPolicyPreservesEveryM2FieldAndLimitsScopeToItems() {
        UUID presetId = new UUID(1, 2);
        ItemTransferPolicy legacy =
                new ItemTransferPolicy.Input(7, 41, RedstoneCondition.NO_SIGNAL, presetId, FilterMode.BLACKLIST, 19);

        ResourceTransferPolicy migrated = ResourceTransferPolicy.legacy(legacy);

        assertEquals(
                new ResourceTransferPolicy.Input(
                        7,
                        ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM)),
                        RedstoneCondition.NO_SIGNAL,
                        presetId,
                        FilterMode.BLACKLIST,
                        Map.of(
                                ResourceTypes.ITEM,
                                new ResourceTransferPolicy.InputOverride(
                                        41, ResourceTransferPolicy.BatchMode.GREEDY, 64)),
                        19),
                migrated);
    }

    @Test
    void legacyOutputPreservesPriorityAndItemRate() {
        UUID presetId = new UUID(5, 6);
        ItemTransferPolicy legacy =
                new ItemTransferPolicy.Output(11, 73, RedstoneCondition.SIGNAL, presetId, FilterMode.WHITELIST, -4);

        assertEquals(
                new ResourceTransferPolicy.Output(
                        11,
                        ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM)),
                        RedstoneCondition.SIGNAL,
                        presetId,
                        FilterMode.WHITELIST,
                        Map.of(ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(73)),
                        -4),
                ResourceTransferPolicy.legacy(legacy));
    }

    @Test
    void newPoliciesDefaultToAllResourcesAndIndependentDefaultRates() {
        ResourceTransferPolicy policy = ResourceTransferPolicy.defaults(TransferDirection.INPUT);

        assertEquals(ResourceScope.all(), policy.scope());
        assertEquals(Integer.MAX_VALUE, policy.rate(ResourceTypes.ITEM));
        assertEquals(Integer.MAX_VALUE, policy.rate(ResourceTypes.FLUID));
        assertEquals(Integer.MAX_VALUE, policy.rate(ResourceTypes.ENERGY));
    }

    @Test
    void sparseDefaultsAreNormalizedAndInputOverridesAreDetached() {
        HashMap<net.minecraft.resources.ResourceLocation, ResourceTransferPolicy.InputOverride> overrides =
                new HashMap<>();
        overrides.put(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(
                        Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 999));
        overrides.put(
                ResourceTypes.FLUID,
                new ResourceTransferPolicy.InputOverride(23, ResourceTransferPolicy.BatchMode.EXACT, 250));

        ResourceTransferPolicy.Input policy = new ResourceTransferPolicy.Input(
                1, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, overrides, 0);
        overrides.clear();

        assertEquals(
                Map.of(
                        ResourceTypes.FLUID,
                        new ResourceTransferPolicy.InputOverride(23, ResourceTransferPolicy.BatchMode.EXACT, 250)),
                policy.resourcePolicyOverrides());
        assertThrows(
                UnsupportedOperationException.class,
                () -> policy.resourcePolicyOverrides().clear());
    }

    @Test
    void directionRoundTripPreservesOnlyCommonRates() {
        ResourceTransferPolicy input = new ResourceTransferPolicy.Input(
                9,
                ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM, ResourceTypes.FLUID)),
                RedstoneCondition.SIGNAL,
                new UUID(3, 4),
                FilterMode.BLACKLIST,
                Map.of(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(12, ResourceTransferPolicy.BatchMode.EXACT, 5),
                        ResourceTypes.FLUID,
                        new ResourceTransferPolicy.InputOverride(17, ResourceTransferPolicy.BatchMode.GREEDY, 1000)),
                33);

        ResourceTransferPolicy output = input.switchDirection(TransferDirection.OUTPUT);
        assertEquals(12, output.rate(ResourceTypes.ITEM));
        assertEquals(17, output.rate(ResourceTypes.FLUID));
        assertEquals(
                Map.of(
                        ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(12),
                        ResourceTypes.FLUID, new ResourceTransferPolicy.OutputOverride(17)),
                output.resourcePolicyOverrides());
        assertEquals(0, ((ResourceTransferPolicy.Output) output).priority());

        ResourceTransferPolicy roundTrip = output.switchDirection(TransferDirection.INPUT);
        assertEquals(12, roundTrip.rate(ResourceTypes.ITEM));
        assertEquals(17, roundTrip.rate(ResourceTypes.FLUID));
        assertEquals(
                ResourceTransferPolicy.BatchMode.GREEDY,
                ((ResourceTransferPolicy.Input) roundTrip)
                        .resourcePolicyOverrides()
                        .get(ResourceTypes.ITEM)
                        .batchMode());
        assertEquals(
                64,
                ((ResourceTransferPolicy.Input) roundTrip)
                        .resourcePolicyOverrides()
                        .get(ResourceTypes.ITEM)
                        .batchSize());
        assertEquals(0, ((ResourceTransferPolicy.Input) roundTrip).keepCount());
    }

    @Test
    void invalidRatesKeepCountsAndBatchSizesFailBeforeStateExists() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceTransferPolicy.OutputOverride(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.EXACT, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.Input(
                        0, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, Map.of(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.Input(
                        1, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, Map.of(), -1));
        assertFalse(ResourceTransferPolicy.defaults(TransferDirection.OUTPUT)
                .resourcePolicyOverrides()
                .containsKey(ResourceTypes.ITEM));
    }

    @Test
    void inputAndOutputRejectOverridesOutsideCustomScopeBeforeNormalization() {
        ResourceScope itemOnly = ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.Input(
                        1,
                        itemOnly,
                        RedstoneCondition.IGNORE,
                        null,
                        FilterMode.WHITELIST,
                        Map.of(
                                ResourceTypes.FLUID,
                                new ResourceTransferPolicy.InputOverride(
                                        Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 1000)),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.Output(
                        1,
                        itemOnly,
                        RedstoneCondition.IGNORE,
                        null,
                        FilterMode.WHITELIST,
                        Map.of(ResourceTypes.FLUID, new ResourceTransferPolicy.OutputOverride(Integer.MAX_VALUE)),
                        0));
    }

    @Test
    void exactBatchMayExceedRateAndUseThePositiveLongBoundary() {
        ResourceTransferPolicy.Input policy = new ResourceTransferPolicy.Input(
                1,
                ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(
                                1, ResourceTransferPolicy.BatchMode.EXACT, Long.MAX_VALUE)),
                0);

        assertEquals(
                Long.MAX_VALUE,
                policy.resourcePolicyOverrides().get(ResourceTypes.ITEM).batchSize());
    }
}
