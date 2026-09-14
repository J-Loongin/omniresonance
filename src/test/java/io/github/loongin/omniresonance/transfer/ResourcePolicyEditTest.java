// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ResourcePolicyEditTest {
    static final ResourceLocation MISSING = ResourceLocation.parse("example:missing");
    static final Set<ResourceLocation> REGISTERED = Set.of(ResourceTypes.ITEM);

    static ResourcePolicyEdit edit(
            ResourcePolicyEdit.Scope scope,
            List<ResourcePolicyEdit.Row> rows,
            List<ResourceLocation> retained,
            boolean discard,
            ResourcePolicyEdit.DirectionFields fields) {
        return new ResourcePolicyEdit(
                1, scope, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, fields, rows, retained, discard);
    }

    static ResourcePolicyEdit input(
            ResourcePolicyEdit.Scope scope,
            List<ResourcePolicyEdit.Row> rows,
            List<ResourceLocation> retained,
            boolean discard) {
        return edit(scope, rows, retained, discard, new ResourcePolicyEdit.InputFields(0));
    }

    static StoredResourcePolicy current() {
        return new StoredResourcePolicy(
                ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                Map.of(
                        MISSING,
                        new StoredResourcePolicy.RawOverride(
                                Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 999L)));
    }

    @Test
    void retainsOriginalExplicitDefaultsWithoutMutatingCurrent() {
        StoredResourcePolicy old = current();
        StoredResourcePolicy result = input(ResourcePolicyEdit.Scope.all(), List.of(), List.of(MISSING), false)
                .reconcile(old, REGISTERED);
        assertEquals(old, result);
        assertEquals(999L, old.missingTypeOverrides().get(MISSING).batchSize());
    }

    @Test
    void omissionDeletesAndUnknownScopeNeedsTrustedEvidence() {
        assertTrue(input(ResourcePolicyEdit.Scope.all(), List.of(), List.of(), false)
                .reconcile(current(), REGISTERED)
                .missingTypeOverrides()
                .isEmpty());
        var custom = ResourcePolicyEdit.Scope.custom(List.of(MISSING));
        assertEquals(
                Set.of(MISSING),
                input(custom, List.of(), List.of(), false)
                        .reconcile(current(), REGISTERED)
                        .effectivePolicy()
                        .scope()
                        .resourceTypeIds());
        var seed = new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> input(custom, List.of(), List.of(), false).reconcile(seed, REGISTERED));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.all(), List.of(), List.of(MISSING), false)
                        .reconcile(seed, REGISTERED));
    }

    @Test
    void forgedDefaultUnknownRowIsRejectedBeforeNormalization() {
        var row = new ResourcePolicyEdit.Row(
                MISSING,
                new ResourceTransferPolicy.InputOverride(
                        Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.all(), List.of(row), List.of(), false)
                        .reconcile(current(), REGISTERED));
        assertEquals(999L, current().missingTypeOverrides().get(MISSING).batchSize());
    }

    @Test
    void duplicateAndMixedDirectionValuesAreRejected() {
        var row = new ResourcePolicyEdit.Row(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.EXACT, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEdit.Scope.custom(List.of(MISSING, MISSING)));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.all(), List.of(row, row), List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.all(), List.of(), List.of(MISSING, MISSING), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> edit(
                        ResourcePolicyEdit.Scope.all(),
                        List.of(row),
                        List.of(),
                        false,
                        new ResourcePolicyEdit.OutputFields(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.all(), List.of(row), List.of(ResourceTypes.ITEM), false));
    }

    @Test
    void narrowingRejectsRowsAndRetainedOutsideScope() {
        var custom = ResourcePolicyEdit.Scope.custom(List.of(ResourceTypes.ITEM));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(custom, List.of(), List.of(MISSING), false).reconcile(current(), REGISTERED));
        var row = new ResourcePolicyEdit.Row(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.GREEDY, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(ResourcePolicyEdit.Scope.custom(List.of(MISSING)), List.of(row), List.of(), false)
                        .reconcile(current(), REGISTERED));
    }

    @Test
    void exactBatchMayExceedRateAndDefaultsNormalizeOnlyAfterAdmission() {
        var row = new ResourcePolicyEdit.Row(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.EXACT, Long.MAX_VALUE));
        var result = input(ResourcePolicyEdit.Scope.all(), List.of(row), List.of(), false)
                .reconcile(current(), REGISTERED);
        assertEquals(
                row.value(), result.effectivePolicy().resourcePolicyOverrides().get(ResourceTypes.ITEM));
        var defaults = new ResourcePolicyEdit.Row(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(
                        Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 999));
        assertEquals(
                1,
                input(ResourcePolicyEdit.Scope.all(), List.of(defaults), List.of(), false)
                        .rows()
                        .size());
        assertTrue(input(ResourcePolicyEdit.Scope.all(), List.of(defaults), List.of(), false)
                .reconcile(current(), REGISTERED)
                .effectivePolicy()
                .resourcePolicyOverrides()
                .isEmpty());
    }

    @Test
    void actualSwitchAndAwayBackClearOnlyOldRawDirectionFields() {
        for (var fields : List.of(new ResourcePolicyEdit.InputFields(0), new ResourcePolicyEdit.OutputFields(0))) {
            var result = edit(ResourcePolicyEdit.Scope.all(), List.of(), List.of(MISSING), true, fields)
                    .reconcile(current(), REGISTERED);
            assertEquals(
                    new StoredResourcePolicy.RawOverride(Integer.MAX_VALUE, null, null),
                    result.missingTypeOverrides().get(MISSING));
        }
        var result = edit(
                        ResourcePolicyEdit.Scope.all(),
                        List.of(),
                        List.of(MISSING),
                        false,
                        new ResourcePolicyEdit.OutputFields(0))
                .reconcile(current(), REGISTERED);
        assertNull(result.missingTypeOverrides().get(MISSING).batchMode());
    }

    @Test
    void scopeOnlyMissingMayRemainOrBeRemoved() {
        var old = new StoredResourcePolicy(
                new ResourceTransferPolicy.Input(
                        1,
                        ResourceScope.customSet(Set.of(MISSING, ResourceTypes.ITEM)),
                        RedstoneCondition.IGNORE,
                        null,
                        FilterMode.WHITELIST,
                        Map.of(),
                        0),
                Map.of());
        assertEquals(
                Set.of(MISSING),
                input(ResourcePolicyEdit.Scope.custom(List.of(MISSING)), List.of(), List.of(), false)
                        .reconcile(old, REGISTERED)
                        .effectivePolicy()
                        .scope()
                        .resourceTypeIds());
        assertTrue(input(ResourcePolicyEdit.Scope.all(), List.of(), List.of(), false)
                .reconcile(old, REGISTERED)
                .missingTypeOverrides()
                .isEmpty());
    }

    @Test
    void trustedSeedContainsNoRawValuesAndNewDefaultsRemainAll() {
        var seed = ResourcePolicyEdit.fromStored(current());
        assertFalse(seed.discardPreviousDirectionFields());
        assertEquals(List.of(MISSING), seed.retainedMissingIds());
        assertTrue(seed.rows().isEmpty());
        assertEquals(current(), seed.reconcile(current(), REGISTERED));
        for (TransferDirection direction : TransferDirection.values()) {
            var stored = new StoredResourcePolicy(ResourceTransferPolicy.defaults(direction), Map.of());
            var defaults = ResourcePolicyEdit.fromStored(stored);
            assertEquals(ResourceScope.Kind.ALL, defaults.scope().kind());
            assertTrue(defaults.scope().ids().isEmpty());
            assertEquals(stored, defaults.reconcile(stored, REGISTERED));
        }
    }

    @Test
    void snapshotsDetachMutableListsAndRejectInvalidNumbers() {
        var ids = new java.util.ArrayList<>(List.of(MISSING));
        var scope = ResourcePolicyEdit.Scope.custom(ids);
        ids.clear();
        assertEquals(List.of(MISSING), scope.ids());
        var retained = new java.util.ArrayList<>(List.of(MISSING));
        var intent = input(scope, List.of(), retained, false);
        retained.clear();
        assertEquals(List.of(MISSING), intent.retainedMissingIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> intent.retainedMissingIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new ResourcePolicyEdit.InputFields(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourcePolicyEdit(
                        0,
                        scope,
                        RedstoneCondition.IGNORE,
                        null,
                        FilterMode.WHITELIST,
                        new ResourcePolicyEdit.InputFields(0),
                        List.of(),
                        List.of(),
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.InputOverride(0, ResourceTransferPolicy.BatchMode.GREEDY, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.EXACT, 0));
    }

    @Test
    void publishRequiresActualStoredObjectSizeAndFailureLeavesCurrentUntouched() {
        var missing = new java.util.HashMap<ResourceLocation, StoredResourcePolicy.RawOverride>();
        var raw = new StoredResourcePolicy.RawOverride(
                Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, Long.MAX_VALUE);
        for (int index = 0; index < 70000; index++) {
            String prefix = "example:" + index + "_";
            missing.put(ResourceLocation.parse(prefix + "a".repeat(128 - prefix.length())), raw);
        }
        var old = new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), missing);
        io.github.loongin.omniresonance.persistence.ResourcePolicyNbt.encode(old);
        var ids = List.copyOf(missing.keySet());
        var intent = input(ResourcePolicyEdit.Scope.custom(ids), List.of(), ids, false);
        assertThrows(IllegalArgumentException.class, () -> intent.reconcile(old, REGISTERED));
        assertEquals(missing, old.missingTypeOverrides());
        assertEquals(ResourceScope.Kind.ALL, old.effectivePolicy().scope().kind());
    }
}
