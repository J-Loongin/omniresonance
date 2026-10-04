// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeForm;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class TerminalPreviewCardTest {
    @Test
    void productionCubeAndPanelBuildTheSameComponentWithoutMakingFixedTargetsInteractive() {
        var body = TerminalLayout.terminal(640, 360).content();
        for (var form : new NodeForm[] {NodeForm.BLOCK, NodeForm.PANEL}) {
            var draft = new NodeWorkingFacesDraft(
                    form == NodeForm.PANEL ? WorkingFaces.attachedFace() : WorkingFaces.explicit(63),
                    form,
                    Direction.WEST);
            var widgets = new ArrayList<AbstractWidget>();
            NodeFaceSelectorView.build(
                    NodeFaceSelectorView.layout(body, draft.panel()),
                    draft,
                    Direction.WEST,
                    List.of(),
                    true,
                    widgets::add,
                    () -> {});
            assertEquals(draft.panel() ? 1 : 6, widgets.size());
            for (var widget : widgets) {
                assertInstanceOf(TerminalPreviewCard.class, widget);
                assertEquals(!draft.panel(), widget.active);
            }
        }
    }

    @Test
    void fixedPreviewUsesAnInlineCenteredGroupAndReadableSelectedSurface() {
        var bounds = new TerminalLayout.Rect(12, 20, 348, 56);
        var content = TerminalPreviewCard.content(bounds, 40, true, true);
        assertNull(content.mark());
        assertEquals(content.icon().y() + 8, content.label().y());
        assertEquals(
                bounds.x() + bounds.width() / 2,
                (content.icon().x() + content.label().right()) / 2);
        assertTrue(content.icon().bottom() <= bounds.bottom() - 8);
        var style = TerminalTheme.previewStyle(false, false, false, true, true);
        assertEquals(TerminalTheme.TEXT, style.text());
        assertEquals(TerminalTheme.ACCENT, style.border());
        assertFalse(style.surface() == TerminalTheme.RAISED_DISABLED);
    }
}
