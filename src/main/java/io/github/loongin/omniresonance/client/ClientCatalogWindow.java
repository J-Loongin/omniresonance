// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Client-owned bounded batch admission. The caller clears its data on failure and validates entry identities.
 * Snapshot tokens contain immutable identity/revision values only; this class grants no authority.
 */
final class ClientCatalogWindow {
    private final int maximum, batchMaximum;
    private @Nullable Object snapshot;
    private int total = -1, received;
    private boolean failed;

    ClientCatalogWindow(int maximum, int batchMaximum) {
        if (maximum < 1 || batchMaximum < 1 || batchMaximum > maximum)
            throw new IllegalArgumentException("Invalid catalog limits");
        this.maximum = maximum;
        this.batchMaximum = batchMaximum;
    }

    boolean accept(Object identity, int offset, int count, int size) {
        if (identity == null
                || failed
                || ready()
                || offset != received
                || count < 0
                || count > maximum
                || size < 0
                || size > batchMaximum
                || size > count - offset
                || size == 0 && offset < count
                || total >= 0 && (total != count || !Objects.equals(snapshot, identity))) {
            failed = true;
            return false;
        }
        snapshot = identity;
        total = count;
        received += size;
        return true;
    }

    boolean ready() {
        return !failed && total >= 0 && received == total;
    }

    void reset() {
        snapshot = null;
        total = -1;
        received = 0;
        failed = false;
    }
}
