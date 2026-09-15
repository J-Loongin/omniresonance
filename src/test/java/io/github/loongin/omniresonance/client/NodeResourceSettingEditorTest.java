// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import org.junit.jupiter.api.Test;

class NodeResourceSettingEditorTest {
    @Test
    void localTextDoesNotModifyParentUntilAValidWholeApply() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        draft.addType(ResourceTypes.ITEM);
        var editor = new NodeResourceSettingEditor(draft, ResourceTypes.ITEM);
        editor.rate = "64";
        assertTrue(editor.dirty());
        assertEquals("2147483647", draft.type(ResourceTypes.ITEM).rate);
        editor.batch = "bad";
        assertThrows(IllegalArgumentException.class, editor::apply);
        assertEquals("2147483647", draft.type(ResourceTypes.ITEM).rate);
        editor.batch = "32";
        editor.apply();
        assertEquals("64", draft.type(ResourceTypes.ITEM).rate);
        assertEquals("32", draft.type(ResourceTypes.ITEM).batch);
    }

    @Test
    void cancellingDetachedEditorOrRestoringDefaultDoesNotTouchOtherTypes() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        draft.addType(ResourceTypes.ITEM);
        draft.addType(ResourceTypes.FLUID);
        var cancelled = new NodeResourceSettingEditor(draft, ResourceTypes.ITEM);
        cancelled.rate = "1";
        var reopened = new NodeResourceSettingEditor(draft, ResourceTypes.ITEM);
        assertEquals("2147483647", reopened.rate);
        draft.restoreDefault(ResourceTypes.ITEM);
        assertEquals(java.util.List.of(ResourceTypes.FLUID), draft.settingIds());
        assertThrows(IllegalStateException.class, cancelled::apply);
        assertEquals("2147483647", draft.type(ResourceTypes.FLUID).rate);
    }

    @Test
    void directionChangeRejectsAStaleDialogBeforeAnyRateMutation() {
        var draft = new NodeResourcePolicyDraft(
                NodeResourcePolicyDraftTest.input(), null, NodeResourcePolicyDraftTest.catalog());
        draft.addType(ResourceTypes.ITEM);
        var editor = new NodeResourceSettingEditor(draft, ResourceTypes.ITEM);
        editor.rate = "64";
        draft.confirmDirectionChange();
        assertThrows(IllegalStateException.class, editor::apply);
        assertEquals("2147483647", draft.type(ResourceTypes.ITEM).rate);
        var output = new NodeResourceSettingEditor(draft, ResourceTypes.ITEM);
        output.rate = "128";
        output.apply();
        assertEquals("128", draft.type(ResourceTypes.ITEM).rate);
    }
}
