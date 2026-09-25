// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.exchange.ExchangeTunnel;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** Bounded client-safe pairing projection; never contains invitation credentials or supplies authorization. */
public record ExchangeTunnelView(
        UUID id,
        long revision,
        UUID first,
        UUID second,
        @Nullable String firstName,
        @Nullable String secondName,
        boolean firstApproved,
        boolean secondApproved,
        boolean closed,
        boolean available) {
    public ExchangeTunnelView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (first.equals(second) || revision < 0) throw new IllegalArgumentException("Invalid pair view");
        if (firstName != null
                        && !new io.github.loongin.omniresonance.network.ManagedName(firstName)
                                .value()
                                .equals(firstName)
                || secondName != null
                        && !new io.github.loongin.omniresonance.network.ManagedName(secondName)
                                .value()
                                .equals(secondName)
                || available && (firstName == null || secondName == null))
            throw new IllegalArgumentException("Invalid pair names");
    }

    public static ExchangeTunnelView from(ExchangeTunnel t, @Nullable NetworkMetadata a, @Nullable NetworkMetadata b) {
        var c = t.consent();
        if (a != null && !a.id().equals(c.sourceNetwork()) || b != null && !b.id().equals(c.targetNetwork()))
            throw new IllegalArgumentException("Pair endpoint mismatch");
        return new ExchangeTunnelView(
                t.id(),
                c.revision(),
                c.sourceNetwork(),
                c.targetNetwork(),
                a == null ? null : a.name().value(),
                b == null ? null : b.name().value(),
                c.source().approved(),
                c.target().approved(),
                c.revoked(),
                a != null
                        && b != null
                        && a.ownerId().equals(c.sourceOwner())
                        && b.ownerId().equals(c.targetOwner()));
    }

    public UUID peer(UUID network) {
        if (first.equals(network)) return second;
        if (second.equals(network)) return first;
        throw new IllegalArgumentException("Pair not in this network");
    }

    public boolean approved() {
        return firstApproved && secondApproved && !closed;
    }

    public boolean ownApproved(UUID network) {
        peer(network);
        return first.equals(network) ? firstApproved : secondApproved;
    }

    public @Nullable String peerName(UUID network) {
        peer(network);
        return first.equals(network) ? secondName : firstName;
    }

    public void write(FriendlyByteBuf b) {
        b.writeUUID(id).writeLong(revision).writeUUID(first).writeUUID(second);
        b.writeBoolean(firstName != null);
        if (firstName != null) NetworkSummary.writeName(b, firstName);
        b.writeBoolean(secondName != null);
        if (secondName != null) NetworkSummary.writeName(b, secondName);
        b.writeByte((firstApproved ? 1 : 0) | (secondApproved ? 2 : 0) | (closed ? 4 : 0) | (available ? 8 : 0));
    }

    public static ExchangeTunnelView read(FriendlyByteBuf b) {
        UUID id = b.readUUID();
        long rev = b.readLong();
        UUID first = b.readUUID(), second = b.readUUID();
        String a = bool(b) ? NetworkSummary.readName(b) : null, c = bool(b) ? NetworkSummary.readName(b) : null;
        int flags = b.readUnsignedByte();
        if (flags > 15) throw new IllegalArgumentException("Invalid pairing flags");
        return new ExchangeTunnelView(
                id, rev, first, second, a, c, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0);
    }

    private static boolean bool(FriendlyByteBuf b) {
        int v = b.readUnsignedByte();
        if (v > 1) throw new IllegalArgumentException("Invalid flag");
        return v == 1;
    }
}
