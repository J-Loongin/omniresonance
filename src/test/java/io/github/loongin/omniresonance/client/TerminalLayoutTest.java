// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class TerminalLayoutTest {
    @ParameterizedTest
    @CsvSource({"320,240,8,12,304,216", "640,360,32,18,576,324", "480,270,24,13,432,243", "1920,1080,600,330,720,420"})
    void calculatesFixedCenteredWindow(
            int screenWidth, int screenHeight, int expectedX, int expectedY, int expectedWidth, int expectedHeight) {
        TerminalLayout.Rect window =
                TerminalLayout.calculate(screenWidth, screenHeight).window();

        assertAll(
                () -> assertEquals(expectedX, window.x()),
                () -> assertEquals(expectedY, window.y()),
                () -> assertEquals(expectedWidth, window.width()),
                () -> assertEquals(expectedHeight, window.height()));
    }

    @Test
    void derivesTitleAndContentInsideTheFixedWindow() {
        TerminalLayout layout = TerminalLayout.calculate(320, 240);

        assertAll(
                () -> assertEquals(new TerminalLayout.Rect(8, 12, 304, 28), layout.titleBar()),
                () -> assertEquals(new TerminalLayout.Rect(16, 48, 288, 172), layout.content()),
                () -> assertEquals(6, TerminalLayout.SCROLLBAR_WIDTH));
    }

    @Test
    void compactModeStartsBelowFourHundredPanelPixels() {
        assertAll(
                () -> assertTrue(TerminalLayout.calculate(443, 360).compact()),
                () -> assertFalse(TerminalLayout.calculate(445, 360).compact()));
    }

    @Test
    void scrollableContentPermanentlyReservesItsScrollbarSlot() {
        assertAll(
                () -> assertEquals(182, TerminalLayout.reservedScrollContentWidth(192, 2)),
                () -> assertEquals(
                        TerminalLayout.reservedScrollContentWidth(192, 2),
                        TerminalLayout.reservedScrollContentWidth(192, 2)),
                () -> assertEquals(0, TerminalLayout.reservedScrollContentWidth(4, 2)));
    }

    @Test
    void nodeRoutingListsReserveTheSameInternalScrollbarAtEverySize() {
        TerminalLayout.Rect body = TerminalLayout.calculate(320, 240).content();
        RoutingListLayout shortList = RoutingListLayout.calculate(body, 1, 0);
        RoutingListLayout longList = RoutingListLayout.calculate(body, 128, 999);

        assertAll(
                () -> assertEquals(shortList.rows().width(), longList.rows().width()),
                () -> assertEquals(
                        TerminalLayout.SCROLLBAR_WIDTH, shortList.scrollbar().width()),
                () -> assertTrue(shortList.visibleRows() > 0),
                () -> assertEquals(128 - longList.visibleRows(), longList.scroll()));
    }

    @Test
    void directionDraftDefaultsToInputWhileExistingDirectionsCommitTheOppositeImmediately() {
        NodeDirectionView.Draft created = NodeDirectionView.Draft.start(null);

        assertAll(
                () -> assertEquals(io.github.loongin.omniresonance.network.TransferDirection.INPUT, created.selected()),
                () -> assertFalse(NodeDirectionView.commitsImmediately(null)),
                () -> assertTrue(NodeDirectionView.commitsImmediately(
                        io.github.loongin.omniresonance.network.TransferDirection.INPUT)),
                () -> assertEquals(
                        io.github.loongin.omniresonance.network.TransferDirection.OUTPUT,
                        NodeDirectionView.opposite(io.github.loongin.omniresonance.network.TransferDirection.INPUT)),
                () -> assertEquals(
                        io.github.loongin.omniresonance.network.TransferDirection.INPUT,
                        NodeDirectionView.opposite(io.github.loongin.omniresonance.network.TransferDirection.OUTPUT)));
    }

    @Test
    void nodeListsUseEverythingBelowTheirHeaderAfterPageButtonsAreRemoved() {
        TerminalLayout.Rect body = TerminalLayout.calculate(320, 240).content();
        RoutingListLayout list = RoutingListLayout.calculate(body, 20, 0);

        assertEquals(body.y() + RoutingListLayout.HEADING_HEIGHT, list.rows().y());
        assertEquals(
                body.height() - RoutingListLayout.HEADING_HEIGHT, list.rows().height());
        assertEquals(list.rows().height(), list.scrollbar().height());
    }

    @Test
    void terminalTopologyRootDescriptionNeverTouchesItsAction() {
        for (TerminalLayout.Rect content : java.util.List.of(
                TerminalLayout.calculate(320, 240).content(),
                TerminalLayout.calculate(854, 480).content(),
                TerminalLayout.calculate(3840, 2160).content())) {
            TerminalTopologyLayout.Root root = TerminalTopologyLayout.root(content);
            assertTrue(root.description().bottom() + TerminalLayout.GAP
                    <= root.action().y());
            assertTrue(root.action().bottom() <= content.bottom());
        }
    }

    @Test
    void terminalHomeUsesSevenCardsAfterLoadingIsMergedIntoNodes() {
        TerminalLayout.Rect wide = TerminalLayout.calculate(640, 360).content();
        TerminalLayout.Rect compact = TerminalLayout.calculate(320, 240).content();
        java.util.List<TerminalLayout.Rect> wideCards = TerminalHomeLayout.cards(wide, false);
        java.util.List<TerminalLayout.Rect> compactCards = TerminalHomeLayout.cards(compact, true);

        assertTrue(!TerminalHomeLayout.MODULES.contains("loading"));
        assertTrue(TerminalHomeLayout.MODULES.contains("nodes"));
        assertEquals(7, wideCards.size());
        assertEquals(7, compactCards.size());
        assertEquals(wideCards.get(0).y(), wideCards.get(1).y());
        assertEquals(wideCards.get(0).y(), wideCards.get(2).y());
        assertTrue(wideCards.get(3).y() > wideCards.get(0).y());
        assertEquals(compactCards.get(0).y(), compactCards.get(1).y());
        assertTrue(compactCards.get(2).y() > compactCards.get(0).y());
        assertTrue(Math.abs((wideCards.getLast().x() + wideCards.getLast().right()) - (wide.x() + wide.right())) <= 1);
        assertTrue(Math.abs(
                        (compactCards.getLast().x() + compactCards.getLast().right()) - (compact.x() + compact.right()))
                <= 1);
        for (TerminalLayout.Rect card : compactCards) {
            assertTrue(card.x() >= compact.x() && card.right() <= compact.right());
            assertTrue(card.y() >= compact.y() && card.bottom() <= compact.bottom());
        }
    }

    @Test
    void nodeModeRootContainsOnlyTwoEqualCards() {
        TerminalLayout.Rect content = TerminalLayout.calculate(320, 240).content();
        java.util.List<TerminalLayout.Rect> cards = NodeModeLayout.cards(content);

        assertEquals(2, cards.size());
        assertEquals(cards.get(0).width(), cards.get(1).width());
        assertEquals(cards.get(0).height(), cards.get(1).height());
        assertTrue(cards.get(0).right() < cards.get(1).x());
    }

    @ParameterizedTest
    @CsvSource({"320,240", "640,360", "1920,1080"})
    void nodeModeCardsStayCompactAndCentered(int width, int height) {
        TerminalLayout.Rect content = TerminalLayout.calculate(width, height).content();
        java.util.List<TerminalLayout.Rect> cards = NodeModeLayout.cards(content);
        TerminalLayout.Rect first = cards.getFirst();
        TerminalLayout.Rect last = cards.getLast();
        assertTrue(first.width() <= 220);
        assertTrue(first.height() <= 84);
        assertTrue(Math.abs((first.x() - content.x()) - (content.right() - last.right())) <= 1);
        assertTrue(Math.abs((first.y() - content.y()) - (content.bottom() - first.bottom())) <= 1);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "80,45", "319,239", "320,240", "3840,2160"})
    void viewportChangesNeverProduceNegativeContentDimensions(int screenWidth, int screenHeight) {
        TerminalLayout layout = TerminalLayout.calculate(screenWidth, screenHeight);

        assertAll(
                () -> assertTrue(layout.window().width() >= 0),
                () -> assertTrue(layout.window().height() >= 0),
                () -> assertTrue(layout.content().width() >= 0),
                () -> assertTrue(layout.content().height() >= 0),
                () -> assertTrue(layout.content().x() >= layout.window().x()),
                () -> assertTrue(layout.content().y() >= layout.window().y()),
                () -> assertTrue(layout.content().right() <= layout.window().right()),
                () -> assertTrue(layout.content().bottom() <= layout.window().bottom()));
    }
}
