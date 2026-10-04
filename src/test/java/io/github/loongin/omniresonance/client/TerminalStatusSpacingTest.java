// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

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

final class TerminalStatusSpacingTest {
    @Test
    void separatorsHaveBreathingRoomAndGroupBoundariesDoNotCreateDoubleLines() {
        var session = new UUID(1, 1);
        var network = new UUID(2, 2);
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
                network, new UUID(3, 3), 20, 1, 2, 3, 1, "not_loaded", -1, 0, 0, 0, 0, 0, 25, 500);
        view.accept(new NetworkStatusFrame(session, 2, 1, snapshot, "test"));
        ((TerminalButton) widgets.get(1)).onPress();
        var separators = view.dividers();
        for (int i = 0; i < separators.size(); i++)
            for (int j = i + 1; j < separators.size(); j++)
                assertTrue(
                        Math.abs(separators.get(i).bounds().y()
                                        - separators.get(j).bounds().y())
                                >= 4,
                        "A section boundary must not repeat the preceding parameter separator");
        var rows = view.layoutLines();
        for (int i = 0; i + 1 < rows.size(); i++) {
            var row = rows.get(i);
            if (row.row().section() || row.card()) continue;
            int divider = view.parameterDividers().stream()
                    .filter(r -> r.y() >= row.top() && r.y() < row.top() + row.height())
                    .findFirst()
                    .orElseThrow()
                    .y();
            int textHeight = Math.max(row.labels().size(), row.values().size()) * 12;
            assertTrue(divider - row.top() - textHeight >= 4);
            assertTrue(
                    rows.get(i + 1).top() - divider - 1 >= 4, "The separator must not touch the next parameter's text");
        }
    }
}
