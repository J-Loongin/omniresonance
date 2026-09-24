// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class RecipeTagCopyTest {
    @Test
    void vanillaTextInputsKeepClipboardPriority() {
        var font = new Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        var field = new EditBox(font, 0, 0, 100, 20, Component.empty());
        field.setFocused(true);
        assertTrue(RecipeTagCopy.textFocused(field));
        field.setFocused(false);
        assertFalse(RecipeTagCopy.textFocused(field));
        assertFalse(RecipeTagCopy.textFocused(null));
    }
}
