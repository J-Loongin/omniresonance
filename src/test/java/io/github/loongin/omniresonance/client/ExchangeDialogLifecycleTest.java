// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class ExchangeDialogLifecycleTest {
    @Test
    void nonNumericNameFailureUsesGenericFeedbackAndSuccessfulCorrectionClearsIt() {
        var screen = new ExchangeScreen(null, null);
        String[] name = {"Original"};
        screen.input(
                Component.literal("Channel name"),
                name[0],
                value -> name[0] = new io.github.loongin.omniresonance.network.ManagedName(value).value());
        screen.applyInput("");
        assertEquals("Original", name[0]);
        assertEquals(
                "omniresonance.exchange.invalid",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                                screen.inputError().getContents())
                        .getKey());
        screen.applyInput("Corrected");
        assertEquals("Corrected", name[0]);
        assertNull(screen.inputError());
        assertFalse(screen.draftDirty());
    }

    private static ResourceParameterDraft editor() {
        return new ResourceParameterDraft(
                ResourceTypes.ITEM,
                new ResourceTransferPolicy.InputOverride(
                        ExchangeTerms.DEFAULT_RATE, ResourceTransferPolicy.BatchMode.GREEDY, 64));
    }

    @Test
    void cancellingAnUnappliedParameterDoesNotLeaveAGhostDirtyEditor() {
        var screen = new ExchangeScreen(null, null);
        var editor = editor();
        var values = new HashMap<>();
        screen.openParameterEditor(editor, Component.literal("items"), value -> values.put(editor.id, value));
        editor.mode = ResourceTransferPolicy.BatchMode.EXACT;
        editor.batch = "32";
        assertTrue(screen.draftDirty());
        screen.cancelDialog();
        assertFalse(screen.editingParameter());
        assertFalse(screen.draftDirty());
        assertTrue(values.isEmpty());
    }

    @Test
    void completedLocalApplyDoesNotLeaveItsOldDirtyStateInTheScreen() {
        var screen = new ExchangeScreen(null, null);
        var editor = editor();
        var values = new HashMap<>();
        screen.openParameterEditor(editor, Component.literal("items"), value -> values.put(editor.id, value));
        editor.rate = "128";
        editor.mode = ResourceTransferPolicy.BatchMode.EXACT;
        editor.batch = "64";
        screen.applyInput(editor.rate);
        assertEquals(
                new ResourceTransferPolicy.InputOverride(128, ResourceTransferPolicy.BatchMode.EXACT, 64),
                values.get(editor.id));
        assertFalse(screen.editingParameter());
        assertFalse(screen.draftDirty(), "The owner callback, not a closed editor, owns the parent draft state");
    }

    @Test
    void cancellingRootDiscardReturnsToTheUnappliedRateAndBatch() {
        var screen = new ExchangeScreen(null, null);
        var editor = editor();
        screen.openParameterEditor(editor, Component.literal("items"), ignored -> {});
        editor.rate = "128";
        editor.mode = ResourceTransferPolicy.BatchMode.EXACT;
        editor.batch = "32";
        screen.back(true);
        assertFalse(screen.editingParameter());
        screen.cancelDialog();
        assertTrue(screen.editingParameter());
        assertEquals("128", screen.parameterForm().rate());
        assertEquals("32", screen.parameterForm().batch().quantity());
        assertTrue(screen.parameterForm().batch().quantityActive());
        assertTrue(screen.draftDirty());
    }
}
