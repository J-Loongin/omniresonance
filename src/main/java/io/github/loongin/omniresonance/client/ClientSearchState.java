// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Client-thread presentation state owned by one search field; no authority or world references. */
class ClientSearchState {
    private @Nullable TerminalSearchBox field;
    private boolean expanded;
    private String draft = "";
    private long dueTick = -1;

    /** One input instance per search lifetime; reflow preserves vanilla caret, selection and horizontal scroll. */
    TerminalSearchBox field(
            net.minecraft.client.gui.Font font,
            TerminalLayout.Rect bounds,
            net.minecraft.network.chat.Component label,
            int maximumLength,
            java.util.function.Consumer<String> changed) {
        if (!expanded) throw new IllegalStateException("Collapsed search has no input");
        if (field == null) {
            field = new TerminalSearchBox(font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), label);
            field.setMaxLength(maximumLength);
            field.setHint(TerminalText.body(label));
            field.setValue(draft);
        } else {
            field.setX(bounds.x());
            field.setY(bounds.y());
            field.setWidth(bounds.width());
        }
        field.setResponder(changed);
        return field;
    }

    boolean expanded() {
        return expanded;
    }

    String draft() {
        return draft;
    }

    void open() {
        expanded = true;
    }

    /** Dispatches both Enter keys to the exact action used by the icon; eligibility belongs to the page. */
    static boolean handleToggleKey(int keyCode, int modifiers, boolean eligible, Runnable toggle) {
        if (!eligible || !isToggleKey(keyCode, modifiers)) return false;
        toggle.run();
        return true;
    }

    static boolean isToggleKey(int keyCode, int modifiers) {
        return modifiers == 0 && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER);
    }

    void toggle(long currentTick) {
        if (!close(currentTick)) open();
    }

    /** Canonicalizes bounded local text without changing the input or querying a server. */
    static @Nullable String normalizedQuery(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (value.getBytes(StandardCharsets.UTF_8).length > 256) return null;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)
                    || codePoint == '§'
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) return null;
            index += Character.charCount(codePoint);
        }
        return value;
    }

    /** Restores focus after vanilla's click dispatcher assigns it to the now-detached toggle. */
    void finishToggleClick(boolean wasExpanded, ContainerEventHandler owner, @Nullable GuiEventListener field) {
        if (wasExpanded != expanded) {
            owner.setFocused(expanded ? field : null);
        }
    }

    void edit(String value, long currentTick) {
        Objects.requireNonNull(value, "value");
        if (expanded && !draft.equals(value)) {
            // Coalesce callbacks without postponing an update that is already pending.
            long deadline = dueTick < 0 ? Math.addExact(currentTick, 1) : dueTick;
            draft = value;
            dueTick = deadline;
        }
    }

    /** Returns whether search consumed this close action before normal hierarchical navigation. */
    boolean close(long currentTick) {
        if (!expanded) {
            return false;
        }
        expanded = false;
        field = null;
        draft = "";
        dueTick = currentTick;
        return true;
    }

    boolean due(long currentTick) {
        return dueTick >= 0 && currentTick >= dueTick;
    }

    void handled() {
        dueTick = -1;
    }

    void reset() {
        expanded = false;
        field = null;
        draft = "";
        dueTick = -1;
    }
}
