// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot;
import io.github.loongin.omniresonance.networking.NetworkStatusFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class TerminalStatusViewTest {
    @Test
    void everyDiagnosticParameterHasASeparateDividerAndRevocationRemovesThem() {
        UUID session = new UUID(1, 1), network = new UUID(2, 2), owner = new UUID(3, 3);
        var view = new TerminalStatusView(session, network, 2, ignored -> {});
        var widgets = new ArrayList<AbstractWidget>();
        var font =
                new Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public List<FormattedCharSequence> split(FormattedText text, int width) {
                        return List.of(Component.literal(text.getString()).getVisualOrderText());
                    }
                };
        view.build(font, new TerminalLayout.Rect(20, 40, 364, 186), widgets::add);
        var snapshot = new NetworkDiagnosticsSnapshot(
                network, owner, 20, 1, 2, 3, 1, "not_loaded", -1, 0, 0, 0, 0, 0, 25, 500);
        view.accept(new NetworkStatusFrame(session, 2, 1, snapshot, "test"));
        ((TerminalButton) widgets.get(1)).onPress();
        long parameters = TerminalStatusPresentation.details(snapshot).stream()
                .filter(row -> !row.section())
                .count();
        assertEquals(parameters, view.parameterDividers().size());
        int last = -1;
        for (var line : view.parameterDividers()) {
            assertEquals(1, line.height());
            assertTrue(line.y() > last);
            last = line.y();
        }
        view.accept(new NetworkStatusFrame(session, 2, 2, null, "test"));
        assertTrue(view.parameterDividers().isEmpty());
    }

    @Test
    void copyUsesOnlyTheCurrentAuthorizedFrameAndRevocationDropsItsData() {
        UUID session = new UUID(1, 1), network = new UUID(2, 2), owner = new UUID(3, 3);
        var copied = new ArrayList<String>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalStatusView(session, network, 2, copied::add);
        var font =
                new Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public List<FormattedCharSequence> split(FormattedText text, int width) {
                        return List.of(Component.literal(text.getString()).getVisualOrderText());
                    }
                };
        view.build(font, new TerminalLayout.Rect(0, 0, 288, 180), widgets::add);
        var button = (TerminalButton) widgets.getFirst();
        assertFalse(button.active);
        var details = (TerminalButton) widgets.get(1);
        assertFalse(details.active);
        assertFalse(view.back());
        var snapshot = new NetworkDiagnosticsSnapshot(
                network, owner, 20, 1, 2, 3, 1, "not_loaded", -1, 0, 0, 0, 0, 0, 25, 500);
        view.accept(new NetworkStatusFrame(session, 1, 1, snapshot, "test"));
        assertFalse(button.active);
        view.accept(new NetworkStatusFrame(session, 2, 1, snapshot, "test"));
        assertTrue(button.active);
        assertTrue(details.active);
        details.onPress();
        assertTrue(view.back());
        assertFalse(view.back());
        details.onPress();
        button.onPress();
        assertEquals(snapshot.export("test"), copied.getFirst());
        view.accept(new NetworkStatusFrame(session, 2, 2, null, "test"));
        assertFalse(button.active);
        assertFalse(details.active);
        assertFalse(view.back());
        details.onPress();
        assertFalse(view.back());
        view.accept(new NetworkStatusFrame(session, 2, 1, snapshot, "test"));
        button.onPress();
        assertEquals(1, copied.size());
    }
}
