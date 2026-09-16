// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Shared client-only discard decision; submission means the final mutation was sent, not an upload preparation.
 * Business adapters retain their own leases, local layers and authoritative acknowledgements.
 */
record ClientDraftExit(boolean dirty, boolean submitted) {
    boolean requiresConfirmation() {
        return dirty && !submitted;
    }
}
