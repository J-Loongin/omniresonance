// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authoritative candidate binding identity, consent and full terms. Thread-safe values do no I/O,
 * simulation or resource mutation; the owning service must publish transitions atomically after current authority
 * checks. Restoring or constructing a value does not itself authorize execution or client-authored terms.
 */
public record ExchangeAgreement(UUID id, ExchangeConsent consent, ExchangeTerms terms) {
    public ExchangeAgreement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(consent);
        Objects.requireNonNull(terms);
    }

    /**
     * Returns changed terms with a newly signed revision and invalidated counterparty approval. Current owner and
     * expected revision are checked before any candidate is returned. Exceptions leave the original intact;
     * no player session or online status is required for the stored approval to remain valid.
     */
    public ExchangeAgreement revise(
            ExchangeConsent.Side side,
            UUID actor,
            NetworkMetadata current,
            long expectedRevision,
            ExchangeTerms replacement) {
        Objects.requireNonNull(replacement);
        return new ExchangeAgreement(id, consent.revise(side, actor, current, expectedRevision), replacement);
    }
}
