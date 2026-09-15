// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Client-thread revision hint; native cursor packets do not advance the client menu revision in Minecraft 1.21.1. */
final class TerminalMenuRevision {
    private int acknowledged = -1, nativeAtAcknowledgement;

    void acknowledge(int server, int nativeRevision) {
        if (server < 0 || server > 32767) throw new IllegalArgumentException("Invalid menu revision");
        acknowledged = server;
        nativeAtAcknowledgement = nativeRevision;
    }

    int current(int nativeRevision) {
        return acknowledged >= 0 && nativeRevision == nativeAtAcknowledgement ? acknowledged : nativeRevision;
    }

    void clear() {
        acknowledged = -1;
    }
}
