// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable bilateral pairing, independent of channel terms and resource movement. Pure transitions validate the
 * signing owner and revision; callers atomically publish them with invitation consumption and affected channels.
 * Pair approval never supplies channel approval. No world access, simulation, mutation or I/O occurs here.
 */
public record ExchangeTunnel(UUID id, ExchangeConsent consent) {
    public ExchangeTunnel {
        Objects.requireNonNull(id);
        Objects.requireNonNull(consent);
        if (consent.termsRevision() != 0
                || consent.source().paused()
                || consent.target().paused())
            throw new IllegalArgumentException("Pairing cannot contain transfer terms or channel pauses");
    }

    public static ExchangeTunnel propose(
            UUID id, UUID invitation, UUID actor, NetworkMetadata first, NetworkMetadata second) {
        return new ExchangeTunnel(id, ExchangeConsent.propose(invitation, actor, first, second));
    }

    public ExchangeTunnel approve(
            ExchangeConsent.Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        return new ExchangeTunnel(id, consent.approve(side, actor, current, expectedRevision));
    }

    public ExchangeTunnel close(ExchangeConsent.Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        return new ExchangeTunnel(id, consent.revoke(side, actor, current, expectedRevision));
    }

    public boolean permitsChannels(NetworkMetadata first, NetworkMetadata second) {
        return consent.permitsExecution(first, second);
    }

    public UUID peer(UUID network) {
        if (consent.sourceNetwork().equals(network)) return consent.targetNetwork();
        if (consent.targetNetwork().equals(network)) return consent.sourceNetwork();
        throw new SecurityException("Network does not belong to exchange tunnel");
    }

    public ExchangeConsent.Side side(UUID network) {
        peer(network);
        return consent.sourceNetwork().equals(network) ? ExchangeConsent.Side.SOURCE : ExchangeConsent.Side.TARGET;
    }
}
