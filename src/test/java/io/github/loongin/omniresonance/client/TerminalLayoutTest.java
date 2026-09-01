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
