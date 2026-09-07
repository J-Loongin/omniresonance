// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TerminalMemberLayoutTest {
    @ParameterizedTest
    @CsvSource({"320,240", "640,360", "1920,1080"})
    void memberPanesStayInsideTheEstablishedWindow(int width, int height) {
        TerminalLayout window = TerminalLayout.calculate(width, height);
        TerminalMemberLayout members = TerminalMemberLayout.calculate(window);
        assertEquals(window.compact(), members.compact());
        assertEquals(window.content().x(), members.list().x());
        assertEquals(window.content().height(), members.list().height());
        if (window.compact()) {
            assertEquals(window.content(), members.detail());
        } else {
            assertEquals(members.list().right() + 6, members.detail().x());
            assertEquals(window.content().right(), members.detail().right());
            assertTrue(members.detail().width() > members.list().width());
        }
    }
}
