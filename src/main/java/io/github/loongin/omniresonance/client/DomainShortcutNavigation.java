// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import org.jetbrains.annotations.Nullable;

/** Client-thread one-shot navigation intent; it never authorizes access or sends a request by itself. */
final class DomainShortcutNavigation {
    enum Action {
        NONE,
        OPEN,
        UNAVAILABLE
    }

    static boolean contextActive(boolean inWorld, boolean screenOpen, boolean domainOpen) {
        return inWorld && (!screenOpen || domainOpen);
    }

    private boolean pending;

    DomainShortcutNavigation(boolean requested) {
        pending = requested;
    }

    boolean pending() {
        return pending;
    }

    void clear() {
        pending = false;
    }

    Action resolve(@Nullable NetworkTerminalState state, boolean busy) {
        if (!pending || busy || !(state instanceof NetworkTerminalState.NetworkRoot root)) return Action.NONE;
        pending = false;
        return root.domainUnavailable() ? Action.UNAVAILABLE : Action.OPEN;
    }
}
