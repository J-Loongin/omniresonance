// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;

/**
 * Immutable untrusted channel edit. Sending is relative to the authenticated session network, never a supplied
 * owner/network UUID. Construction owns no mutable data, performs no world access or simulation and grants no
 * authority; the server resolves pair membership, names, revisions, filter snapshots and approvals before writes.
 */
public record ExchangeChannelDraft(ManagedName name, boolean sending, ExchangeTermsDraft terms) {
    public ExchangeChannelDraft {
        Objects.requireNonNull(name);
        Objects.requireNonNull(terms);
    }
}
