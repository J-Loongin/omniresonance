// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeResourcePolicyViewTest {
    @Test
    void actualOverlayWidgetsKeepSelectionLocalUntilApplyAndCollapsedSearchBuildsNoField() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        var scope = draft.openScope();
        var picker = NodeResourceTypeSelection.scope(draft, scope, Object::toString);
        var body = TerminalLayout.calculate(960, 540).content();
        var layout = NodeResourceTypeSelectionView.layout(body, picker);
        org.junit.jupiter.api.Assertions.assertNull(
                NodeResourceTypeSelectionView.buildSearch(null, layout, picker, () -> 0, () -> {}));
        var rows = new ArrayList<TerminalRowButton>();
        NodeResourceTypeSelectionView.buildRows(layout, picker, true, rows::add, () -> {});
        rows.get(1).onPress();
        assertFalse(scope.selected(ResourceTypes.FLUID));
        assertFalse(draft.dirty());
        var actions = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        NodeResourceTypeSelectionView.buildScopeActions(
                layout, picker, true, actions::add, () -> {}, () -> draft.applyScope(scope, true), () -> {});
        ((TerminalButton) actions.get(3)).onPress();
        assertEquals(
                List.of(ResourceTypes.ITEM, ResourceTypes.ENERGY), draft.scope().ids());
        assertTrue(draft.dirty());
    }

    @Test
    void largeUnavailableRowsBuildOnlyVisibleSelectionWidgetsAndKeepCachedFormOrder() {
        var missing = new LinkedHashMap<ResourceLocation, StoredResourcePolicy.RawOverride>();
        for (int index = 0; index < 4096; index++)
            missing.put(
                    ResourceLocation.parse("absent:type_" + index),
                    new StoredResourcePolicy.RawOverride(null, null, null));
        var edit = ResourcePolicyEdit.fromStored(
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), missing));
        var draft = new NodeResourcePolicyDraft(edit, null, NodeResourcePolicyDraftTest.catalog());
        draft.expanded = true;
        var order = draft.settingIds();
        var body = TerminalLayout.calculate(320, 240).content();
        var last = NodeResourcePolicyView.layout(body, Integer.MAX_VALUE, draft);
        assertEquals(4103, last.totalRows());
        assertTrue(last.visibleRows() <= 6);
        assertSame(order, draft.settingIds());
        var picker = NodeResourceTypeSelection.scope(draft, draft.openScope(), Object::toString);
        picker.search().open();
        picker.editSearch("absent:", 0);
        picker.tick(1);
        picker.wheel(-10000, 2);
        var layout = NodeResourceTypeSelectionView.layout(body, picker);
        var widgets = new ArrayList<TerminalRowButton>();
        NodeResourceTypeSelectionView.buildRows(layout, picker, true, widgets::add, () -> {});
        assertEquals(layout.list().visibleRows(), widgets.size());
        assertTrue(widgets.size() <= 4);
        for (var widget : widgets) {
            assertTrue(widget.getY() >= layout.body().y());
            assertTrue(widget.getBottom() <= layout.actions().y());
        }
        assertEquals(4096, picker.results().size());
    }

    @Test
    void sparseSettingsStartCollapsedAndSaveIsReachableAtEverySupportedSize() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            var first = NodeResourcePolicyView.layout(body, -1, draft);
            assertEquals(0, first.firstRow());
            assertEquals(6, first.totalRows());
            assertEquals(NodeResourcePolicyView.RowKind.TYPE_HEADER, NodeResourcePolicyView.rowKind(draft, 4));
            assertEquals(NodeResourcePolicyView.RowKind.SAVE, NodeResourcePolicyView.rowKind(draft, 5));
            var last = NodeResourcePolicyView.layout(body, Integer.MAX_VALUE, draft);
            assertEquals(last.totalRows(), last.firstRow() + last.visibleRows());
            for (int row = last.firstRow(); row < last.firstRow() + last.visibleRows(); row++) {
                var bounds = last.row(row);
                assertTrue(bounds.x() >= body.x() && bounds.right() <= body.right());
                assertTrue(bounds.y() >= body.y() && bounds.bottom() <= body.bottom());
            }
        }
        assertFalse(draft.dirty());
    }

    @Test
    void greedyAndExactShareBatchGeometryButOnlyExactIsEditable() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        draft.addType(ResourceTypes.FLUID);
        draft.expanded = true;
        var body = TerminalLayout.calculate(427, 240).content();
        var greedy = NodeResourcePolicyView.typeControls(
                new TerminalLayout.Rect(body.x(), body.y(), body.width(), 32), draft, ResourceTypes.FLUID);
        assertFalse(greedy.batchEnabled());
        draft.type(ResourceTypes.FLUID).batchMode = ResourceTransferPolicy.BatchMode.EXACT;
        var exact = NodeResourcePolicyView.typeControls(
                new TerminalLayout.Rect(body.x(), body.y(), body.width(), 32), draft, ResourceTypes.FLUID);
        assertEquals(greedy.batch(), exact.batch());
        assertEquals(greedy.rate(), exact.rate());
        assertTrue(exact.batchEnabled());
        assertTrue(exact.rate().right() <= exact.mode().x());
        assertTrue(exact.mode().right() <= exact.batch().x());
    }

    @Test
    void selectionSearchIsCollapsedCoalescedAndDoesNotMutateParentOnScopeChoice() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        var scope = draft.openScope();
        var picker =
                NodeResourceTypeSelection.scope(draft, scope, id -> id.equals(ResourceTypes.FLUID) ? "Water" : "Other");
        assertFalse(picker.search().expanded());
        assertEquals(3, picker.results().size());
        picker.search().open();
        picker.editSearch("wat", 10);
        picker.editSearch("water", 10);
        assertFalse(picker.tick(10));
        assertTrue(picker.tick(11));
        assertEquals(List.of(ResourceTypes.FLUID), picker.results());
        var result = picker.results();
        picker.wheel(-1, 1);
        assertSame(result, picker.results());
        picker.choose(ResourceTypes.FLUID);
        assertTrue(scope.dirty());
        assertFalse(draft.dirty());
        picker.closeSearch(12);
        assertEquals(3, picker.results().size());
        picker.search().open();
        picker.editSearch("neoforge:energy", 20);
        picker.tick(21);
        assertEquals(List.of(ResourceTypes.ENERGY), picker.results());
    }

    @Test
    void typePickerExcludesOutsideScopeAndExistingRowsAndSearchDoesNotChangeDirty() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        var scope = draft.openScope();
        scope.toggle(ResourceTypes.ENERGY);
        draft.applyScope(scope, true);
        draft.addType(ResourceTypes.ITEM);
        var picker = NodeResourceTypeSelection.overrides(draft, Object::toString);
        assertEquals(List.of(ResourceTypes.FLUID), picker.results());
        picker.choose(ResourceTypes.FLUID);
        assertEquals(List.of(), picker.results());
    }
}
