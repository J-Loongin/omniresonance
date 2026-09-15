// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

/** Bounded read-only domain status, never an authorization or a client-supplied runtime command. */
public enum NodeDomainStatus {
    UNCONFIGURED,
    PENDING,
    NO_FACES,
    NO_PRESET,
    EMPTY_BLACKLIST,
    FILTER_BLOCKED,
    STORAGE_UNAVAILABLE,
    IDLE,
    RUNNING,
    WAITING_BUDGET,
    FAILED,
    BLOCKED
}
