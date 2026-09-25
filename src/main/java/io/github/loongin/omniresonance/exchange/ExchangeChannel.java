// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable channel placement and shared display name. The matching agreement owns consent and terms; the
 * authoritative store must validate parent membership, name uniqueness and publication atomically. Construction
 * is thread-safe and pure, grants no access and performs no simulation, resource mutation or persistence.
 */
public record ExchangeChannel(UUID id, UUID tunnel, ManagedName name) {
    public ExchangeChannel {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tunnel);
        Objects.requireNonNull(name);
    }
}
