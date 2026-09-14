// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoredResourcePolicyTest {
    static final ResourceLocation MISSING = ResourceLocation.parse("example:missing");

    @Test
    void detachedMapAndExplicitScopeRemovalPreventStaleMissingOverrides() {
        var raw = new StoredResourcePolicy.RawOverride(7, ResourceTransferPolicy.BatchMode.EXACT, 9L);
        Map<ResourceLocation, StoredResourcePolicy.RawOverride> map = new HashMap<>();
        map.put(MISSING, raw);
        var stored = new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), map);
        map.clear();
        assertEquals(raw, stored.missingTypeOverrides().get(MISSING));
        var narrow = new ResourceTransferPolicy.Input(
                1,
                ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(),
                0);
        assertThrows(
                IllegalArgumentException.class, () -> new StoredResourcePolicy(narrow, stored.missingTypeOverrides()));
        assertTrue(new StoredResourcePolicy(narrow, Map.of())
                .missingTypeOverrides()
                .isEmpty());
        assertEquals(raw, stored.missingTypeOverrides().get(MISSING));
    }

    @Test
    void rejectsOverlapOutputBatchAndInvalidRawNumbers() {
        var input = new ResourceTransferPolicy.Input(
                1,
                ResourceScope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(
                        MISSING,
                        new ResourceTransferPolicy.InputOverride(3, ResourceTransferPolicy.BatchMode.GREEDY, 1)),
                0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredResourcePolicy(
                        input, Map.of(MISSING, new StoredResourcePolicy.RawOverride(3, null, null))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredResourcePolicy(
                        ResourceTransferPolicy.defaults(TransferDirection.OUTPUT),
                        Map.of(
                                MISSING,
                                new StoredResourcePolicy.RawOverride(
                                        3, ResourceTransferPolicy.BatchMode.GREEDY, null))));
        assertThrows(IllegalArgumentException.class, () -> new StoredResourcePolicy.RawOverride(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new StoredResourcePolicy.RawOverride(-1, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredResourcePolicy.RawOverride(1, ResourceTransferPolicy.BatchMode.EXACT, null));
        assertThrows(IllegalArgumentException.class, () -> new StoredResourcePolicy.RawOverride(1, null, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredResourcePolicy.RawOverride(1, ResourceTransferPolicy.BatchMode.GREEDY, 0L));
    }
}
