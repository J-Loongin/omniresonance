// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** One geometry for both channel editors; warnings do not become hidden scrolling rows. */
record ExchangeTermsLayout(
        TerminalLayout.Rect name,
        TerminalLayout.Rect peer,
        TerminalLayout.Rect form,
        TerminalLayout.Rect warning,
        TerminalActionLayout footer) {
    static ExchangeTermsLayout of(TerminalLayout.Rect body, boolean warning, boolean feedback) {
        var footer = TerminalActionLayout.of(body);
        int x = body.x() + 8, width = Math.max(0, body.width() - 16);
        int warningHeight = warning ? 14 : 0;
        int bottom = footer.content().bottom() - (feedback ? 14 : 0);
        return new ExchangeTermsLayout(
                new TerminalLayout.Rect(x, body.y(), width, 20),
                new TerminalLayout.Rect(x, body.y() + 24, width, 10),
                new TerminalLayout.Rect(x, body.y() + 38, width, Math.max(0, bottom - warningHeight - body.y() - 38)),
                new TerminalLayout.Rect(x, bottom - warningHeight, width, warning ? 10 : 0),
                footer);
    }
}
