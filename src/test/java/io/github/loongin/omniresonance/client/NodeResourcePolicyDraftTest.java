// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.ResourceTypeCatalogPage;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeResourcePolicyDraftTest {
    static NodeResourceTypeCatalog.Snapshot catalog() {
        var catalog = new NodeResourceTypeCatalog();
        catalog.open(new UUID(1, 1));
        var request = catalog.nextRequest();
        catalog.complete(
                request,
                ResourceTypeCatalogPage.from(
                        ResourceAdapterDirectory.nativeDefaults(), new UUID(2, 2), 0, 262144, 100));
        return catalog.snapshot();
    }

    static ResourcePolicyEdit input() {
        return ResourcePolicyEdit.fromStored(
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), Map.of()));
    }

    @Test
    void choosingAllRestoresEverySupportedChoiceAndKeepsTheAllMode() {
        var draft = new NodeResourcePolicyDraft(input(), null, catalog());
        var scope = draft.openScope();
        scope.toggle(ResourceTypes.FLUID);
        scope.toggle(ResourceTypes.ENERGY);
        assertEquals(1, scope.selectedCount());
        scope.all();
        assertEquals(ResourceScope.Kind.ALL, scope.kind());
        assertEquals(3, scope.selectedCount());
        for (var descriptor : catalog().entries()) assertTrue(scope.selected(descriptor.typeId()));
        assertTrue(scope.selection().ids().isEmpty());
        assertFalse(draft.dirtyIncluding(scope));
        scope.toggle(ResourceTypes.FLUID);
        assertEquals(ResourceScope.Kind.CUSTOM_SET, scope.kind());
        assertEquals(2, scope.selectedCount());
        assertFalse(scope.selected(ResourceTypes.FLUID));
    }

    @Test
    void choosingAllKeepsRetainedMissingOverridesWithoutSelectingAnUnavailableType() {
        var missing = ResourceLocation.parse("missing:steam");
        var stored = new StoredResourcePolicy(
                ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                Map.of(missing, new StoredResourcePolicy.RawOverride(12, ResourceTransferPolicy.BatchMode.EXACT, 4L)));
        var draft = new NodeResourcePolicyDraft(ResourcePolicyEdit.fromStored(stored), null, catalog());
        var scope = draft.openScope();
        scope.toggle(ResourceTypes.ITEM);
        scope.all();
        assertFalse(scope.selected(missing));
        draft.applyScope(scope, true);
        assertTrue(draft.settingIds().contains(missing));
        assertEquals(ResourceScope.Kind.ALL, draft.edit().scope().kind());
        assertTrue(draft.edit().retainedMissingIds().contains(missing));
    }

    @Test
    void scopeCancelIsDetachedButWholeCloseSeesUnappliedChanges() {
        var draft = new NodeResourcePolicyDraft(input(), null, catalog());
        var scope = draft.openScope();
        assertEquals(3, scope.selectedCount());
        assertFalse(draft.dirty());
        assertFalse(draft.dirtyIncluding(scope));
        scope.toggle(ResourceTypes.FLUID);
        assertTrue(draft.dirtyIncluding(scope));
        assertFalse(draft.dirty());
        assertEquals(ResourceScope.Kind.ALL, draft.edit().scope().kind());
        assertFalse(draft.openScope().dirty());
        scope.toggle(ResourceTypes.ITEM);
        scope.toggle(ResourceTypes.ENERGY);
        assertThrows(IllegalArgumentException.class, () -> draft.applyScope(scope, true));
        assertEquals(ResourceScope.Kind.ALL, draft.edit().scope().kind());
    }

    @Test
    void reappliedCustomScopeUsesSetEqualityWhileRealMembershipChangesStayDirty() {
        ResourcePolicyEdit base = input();
        ResourcePolicyEdit original = new ResourcePolicyEdit(
                base.intervalTicks(),
                ResourcePolicyEdit.Scope.custom(List.of(ResourceTypes.FLUID, ResourceTypes.ITEM)),
                base.redstoneCondition(),
                base.filterPresetId(),
                base.filterMode(),
                base.fields(),
                base.rows(),
                base.retainedMissingIds(),
                base.discardPreviousDirectionFields());
        var draft = new NodeResourcePolicyDraft(original, null, catalog());
        var scope = draft.openScope();

        scope.toggle(ResourceTypes.FLUID);
        assertTrue(scope.dirty());
        scope.toggle(ResourceTypes.FLUID);
        assertFalse(scope.dirty());
        draft.applyScope(scope, true);
        assertFalse(draft.dirty());

        var changedScope = draft.openScope();
        changedScope.toggle(ResourceTypes.FLUID);
        draft.applyScope(changedScope, true);
        assertTrue(draft.dirty());
    }

    @Test
    void narrowingConfirmsAndRemovesKnownAndUnavailableRowsTogether() {
        var missing = ResourceLocation.parse("missing:steam");
        var stored = new StoredResourcePolicy(
                ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                Map.of(
                        missing,
                        new StoredResourcePolicy.RawOverride(
                                Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.EXACT, 400L)));
        var draft = new NodeResourcePolicyDraft(ResourcePolicyEdit.fromStored(stored), null, catalog());
        draft.addType(ResourceTypes.FLUID);
        draft.type(ResourceTypes.FLUID).rate = "40";
        var scope = draft.openScope();
        scope.toggle(ResourceTypes.FLUID);
        assertEquals(2, draft.removedOverrideCount(scope));
        assertThrows(IllegalStateException.class, () -> draft.applyScope(scope, false));
        assertEquals(2, draft.settingIds().size());
        draft.applyScope(scope, true);
        assertEquals(0, draft.settingIds().size());
        assertEquals(
                List.of(ResourceTypes.ITEM, ResourceTypes.ENERGY),
                draft.edit().scope().ids());
        assertThrows(IllegalArgumentException.class, () -> draft.addType(ResourceTypes.FLUID));
        assertThrows(IllegalArgumentException.class, () -> draft.addType(missing));
    }

    @Test
    void confirmedDirectionDiscardsInvalidExclusiveTextWithoutParsingOrResurrection() {
        var draft = new NodeResourcePolicyDraft(input(), null, catalog());
        draft.addType(ResourceTypes.FLUID);
        draft.interval = "invalid-common";
        draft.quantity = "invalid-keep";
        draft.type(ResourceTypes.FLUID).rate = "invalid-rate";
        draft.type(ResourceTypes.FLUID).batchMode = ResourceTransferPolicy.BatchMode.EXACT;
        draft.type(ResourceTypes.FLUID).batch = "invalid-batch";
        draft.confirmDirectionChange();
        assertEquals(TransferDirection.OUTPUT, draft.direction);
        assertEquals("0", draft.quantity);
        assertEquals("invalid-common", draft.interval);
        assertEquals("invalid-rate", draft.type(ResourceTypes.FLUID).rate);
        draft.quantity = "invalid-priority";
        draft.confirmDirectionChange();
        assertEquals("1000", draft.type(ResourceTypes.FLUID).batch);
        assertEquals(ResourceTransferPolicy.BatchMode.GREEDY, draft.type(ResourceTypes.FLUID).batchMode);
        draft.interval = "1";
        draft.type(ResourceTypes.FLUID).rate = "2";
        var edit = draft.edit();
        assertTrue(edit.discardPreviousDirectionFields());
        assertEquals(new ResourcePolicyEdit.InputFields(0), edit.fields());
    }

    @Test
    void exactGreaterThanRateRemainsValidAndUiOnlyStateIsNotDirty() {
        var draft = new NodeResourcePolicyDraft(input(), null, catalog());
        assertFalse(draft.dirty());
        draft.addType(ResourceTypes.ENERGY);
        assertEquals("2147483647", draft.type(ResourceTypes.ENERGY).rate);
        assertEquals("10000", draft.type(ResourceTypes.ENERGY).batch);
        draft.type(ResourceTypes.ENERGY).rate = "1";
        draft.type(ResourceTypes.ENERGY).batchMode = ResourceTransferPolicy.BatchMode.EXACT;
        assertEquals(
                10000,
                ((ResourceTransferPolicy.InputOverride)
                                draft.edit().rows().getFirst().value())
                        .batchSize());
        draft.restoreDefault(ResourceTypes.ENERGY);
        assertFalse(draft.dirty());
    }

    @Test
    void unavailableRowsExposeOnlyStableIdAndRetentionAndClearUnknownFieldsAfterRoundTrip() {
        ResourceLocation missing = ResourceLocation.parse("missing:steam");
        var stored = new StoredResourcePolicy(
                ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                Map.of(
                        missing,
                        new StoredResourcePolicy.RawOverride(
                                Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.EXACT, 999L)));
        var draft = new NodeResourcePolicyDraft(ResourcePolicyEdit.fromStored(stored), null, catalog());
        assertTrue(draft.unavailable(missing));
        assertThrows(IllegalArgumentException.class, () -> draft.type(missing));
        draft.confirmDirectionChange();
        draft.confirmDirectionChange();
        var reconciled = draft.edit()
                .reconcile(stored, java.util.Set.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY));
        assertEquals(
                new StoredResourcePolicy.RawOverride(Integer.MAX_VALUE, null, null),
                reconciled.missingTypeOverrides().get(missing));
    }
}
