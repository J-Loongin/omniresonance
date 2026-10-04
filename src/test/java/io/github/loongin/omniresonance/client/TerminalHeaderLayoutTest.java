// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class TerminalHeaderLayoutTest {
    @Test
    void remoteNodeNamesUseTheRightNetworkAnchorWithoutPhysicalSwitchReservations() {
        var header = TerminalHeaderLayout.atRightEdge(
                TerminalHeaderLayout.topBarContent(
                        TerminalLayout.terminal(640, 360).window()),
                true);
        var names = TerminalHeaderLayout.remoteNodeNames(header.remaining());
        assertEquals(header.remaining().right(), names.network().right());
        assertEquals(110, names.network().width());
        assertEquals(6, names.network().x() - names.node().right());
        assertTrue(names.node().width() > 180);
    }

    @Test
    void topBarReservesOnlyTheStaticTitleMarkAtTheLeft() {
        TerminalLayout.Rect window = new TerminalLayout.Rect(8, 12, 304, 216);
        assertEquals(new TerminalLayout.Rect(28, 16, 280, 20), TerminalHeaderLayout.topBarContent(window));
    }

    @Test
    void actionIsAnchoredToTheFarRightAndReservesSpaceForTheOtherControls() {
        TerminalHeaderLayout.ActionLayout layout =
                TerminalHeaderLayout.atRightEdge(new TerminalLayout.Rect(80, 16, 200, 20), true);
        assertEquals(new TerminalLayout.Rect(260, 16, 20, 20), layout.action());
        assertEquals(new TerminalLayout.Rect(80, 16, 174, 20), layout.remaining());
        assertEquals(6, layout.action().x() - layout.remaining().right());
    }

    @Test
    void missingActionReservesTheSameSlotWithoutCreatingAnEmptyButton() {
        TerminalLayout.Rect available = new TerminalLayout.Rect(80, 16, 200, 20);
        TerminalHeaderLayout.ActionLayout layout = TerminalHeaderLayout.atRightEdge(available, false);
        assertEquals(TerminalHeaderLayout.atRightEdge(available, true).remaining(), layout.remaining());
        assertEquals(0, layout.action().width());
        assertEquals(0, layout.action().height());
    }

    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "640,360", "1920,1080"})
    void nodeActionIsRightOfChunkLoadingAndLeavesBothNamesVisible(int width, int height) {
        TerminalLayout windowLayout = TerminalLayout.calculate(width, height);
        TerminalLayout.Rect window = windowLayout.window();
        int enabledWidth = windowLayout.compact() ? 52 : 66;
        int chunkWidth = windowLayout.compact() ? 64 : 92;
        TerminalLayout.Rect available = TerminalHeaderLayout.topBarContent(window);
        TerminalHeaderLayout.ActionLayout action = TerminalHeaderLayout.atRightEdge(available, true);
        int chunkRight = action.remaining().right();
        TerminalHeaderLayout.NodeNames names =
                TerminalHeaderLayout.nodeNames(action.remaining(), windowLayout.compact());
        int nameWidth = names.node().width();
        int networkWidth = names.network().width();
        assertEquals(
                names,
                TerminalHeaderLayout.nodeNames(
                        TerminalHeaderLayout.atRightEdge(available, false).remaining(), windowLayout.compact()));
        assertEquals(
                chunkRight - chunkWidth - 6 - enabledWidth - 6, names.network().right());

        assertEquals(20, action.action().width());
        assertEquals(20, action.action().height());
        assertEquals(window.right() - 4, action.action().right());
        assertTrue(nameWidth > 0);
        assertTrue(networkWidth > 0);
        assertEquals(6, action.action().x() - chunkRight);
        assertTrue(action.action().bottom() < windowLayout.content().y());
    }

    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "640,360", "1920,1080"})
    void networkContextRemainsAnchoredAcrossHomeGearAndManagement(int width, int height) {
        TerminalLayout layout = TerminalLayout.calculate(width, height);
        TerminalLayout.Rect top = TerminalHeaderLayout.topBarContent(layout.window());
        TerminalLayout.Rect withAction =
                TerminalHeaderLayout.atRightEdge(top, true).remaining();
        TerminalLayout.Rect withoutAction =
                TerminalHeaderLayout.atRightEdge(top, false).remaining();
        assertEquals(
                TerminalNetworkContext.layout(withAction, layout.compact(), false),
                TerminalNetworkContext.layout(withoutAction, layout.compact(), true));
    }

    @Test
    void contentTitleUsesTheFullInsetWidthWithoutInlineActionSpace() {
        assertEquals(
                new TerminalLayout.Rect(14, 20, 92, 20),
                TerminalHeaderLayout.contentTitle(new TerminalLayout.Rect(10, 20, 100, 200)));
    }
}
