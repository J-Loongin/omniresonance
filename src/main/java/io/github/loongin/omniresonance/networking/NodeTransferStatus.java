// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

/** Bounded client-safe snapshot of a current direct resource binding; never an authority or wakeup request. */
public enum NodeTransferStatus {
    IDLE,
    RUNNING,
    WAITING_BUDGET,
    BLOCKED,
    NO_WORK_FACES,
    FAILED
}
