// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import io.github.loongin.omniresonance.networking.ChunkOverviewPage;
import io.github.loongin.omniresonance.networking.ChunkOverviewRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ChunkOverviewViewTest {
    @Test
    void loadingOverviewHasNoSelectableRowsOrNavigationActions() {
        var sent = new ArrayList<ChunkOverviewRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var session = new UUID(2, 2);
        var view = new ChunkOverviewView(new UUID(1, 1), session, 1, sent::add);
        view.build(
                new Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public int width(String text) {
                        return text.length();
                    }

                    @Override
                    public java.util.List<net.minecraft.util.FormattedCharSequence> split(
                            net.minecraft.network.chat.FormattedText text, int width) {
                        return List.of(net.minecraft.network.chat.Component.literal(text.getString())
                                .getVisualOrderText());
                    }
                },
                new TerminalLayout.Rect(0, 0, 300, 172),
                widgets::add,
                widgets::remove);
        view.open();
        view.accept(page(session, 1, 1, 2, false, false));
        assertTrue(widgets.isEmpty());
        assertEquals(1, sent.size());
    }

    @Test
    void buttonsWaitForAuthorityAndWheelContinuesAtNewPageTop() {
        UUID session = new UUID(2, 2);
        var sent = new ArrayList<ChunkOverviewRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new ChunkOverviewView(new UUID(1, 1), session, 1, sent::add, true);
        view.build(
                new Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public java.util.List<net.minecraft.util.FormattedCharSequence> split(
                            net.minecraft.network.chat.FormattedText text, int width) {
                        return java.util.List.of(net.minecraft.network.chat.Component.literal(text.getString())
                                .getVisualOrderText());
                    }

                    @Override
                    public int width(String value) {
                        return value.length();
                    }
                },
                new TerminalLayout.Rect(0, 0, 300, 140),
                widgets::add,
                widgets::remove);
        view.open();
        view.accept(page(session, 1, 1, 64, false, true));
        assertEquals(1, widgets.size());
        ((TerminalButton) widgets.getFirst()).onPress();
        assertEquals(1, sent.size());
        assertEquals(new UUID(3, 1), view.selectedNode());
        for (int i = 0; i < 64; i++) view.wheel(20, 70, -1);
        assertEquals(ChunkOverviewRequest.Action.PAGE, sent.getLast().action());
        assertEquals(64, sent.getLast().anchor());
        assertFalse(widgets.getFirst().active);
        view.accept(page(session, 1, 1, 64, false, true));
        assertFalse(widgets.getFirst().active);
        view.accept(page(session, 2, 65, 64, true, false));
        assertTrue(widgets.getFirst().active);
        ((TerminalButton) widgets.getFirst()).onPress();
        assertEquals(new UUID(3, 65), view.selectedNode());
        assertEquals(2, sent.size());
        ((TerminalButton) widgets.get(widgets.size() - 2)).onPress();
        assertEquals(ChunkOverviewRequest.Action.HIGHLIGHT, sent.getLast().action());
        assertEquals(new UUID(3, 65), sent.getLast().node());
        view.accept(page(session, 3, 65, 64, true, false));
        ((TerminalButton) widgets.getLast()).onPress();
        assertEquals(ChunkOverviewRequest.Action.TELEPORT, sent.getLast().action());
        assertEquals(new UUID(3, 65), sent.getLast().node());
        view.close();
        assertEquals(ChunkOverviewRequest.Action.CLOSE, sent.getLast().action());
    }

    private static ChunkOverviewPage page(
            UUID session, long sequence, int first, int count, boolean previous, boolean next) {
        var entries = new ArrayList<ChunkOverviewPage.Entry>();
        for (int i = first; i < first + count; i++)
            entries.add(new ChunkOverviewPage.Entry(
                    new UUID(3, i),
                    i,
                    0,
                    "Node " + i,
                    ResourceLocation.parse("minecraft:overworld"),
                    BlockPos.ZERO,
                    io.github.loongin.omniresonance.node.NodeMode.DIRECT,
                    true,
                    false,
                    ChunkLoadingAllocator.Status.OFF));
        return new ChunkOverviewPage(
                session, 1, sequence, true, false, true, 0, 25, 0, 500, 128, previous, next, List.copyOf(entries));
    }
}
