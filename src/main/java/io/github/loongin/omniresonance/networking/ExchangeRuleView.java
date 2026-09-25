// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.exchange.ExchangeAgreement;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable client-safe rule summary: no invitation identity, code, component payload or mutable owner library.
 * Projection and codec are pure caller-thread operations, not authorization. The outer terminal flow must verify
 * its actor/session/network before projecting and sending, and enforce page and whole-frame bounds separately.
 */
public record ExchangeRuleView(
        UUID id,
        long revision,
        long termsRevision,
        Endpoint source,
        Endpoint target,
        boolean sourceApproved,
        boolean targetApproved,
        boolean sourcePaused,
        boolean targetPaused,
        boolean terminated,
        boolean allTypes,
        int typeCount,
        FilterMode filterMode,
        @Nullable String filterName,
        int filterEntries,
        long defaultRate,
        int intervalTicks,
        int overrideCount) {
    public static final int MAXIMUM_BYTES = 1024;

    public enum State {
        PENDING,
        APPROVED,
        PAUSED,
        TERMINATED,
        UNAVAILABLE
    }

    /** Declared signing owner remains available for history even when current network metadata cannot be used. */
    public record Endpoint(
            UUID network, UUID owner, @Nullable String name, boolean available) {
        public Endpoint {
            Objects.requireNonNull(network);
            Objects.requireNonNull(owner);
            if (name != null) ExchangeRuleView.name(name);
            if (available && name == null) throw new IllegalArgumentException("Available network has no name");
        }
    }

    public ExchangeRuleView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(filterMode);
        if (source.network().equals(target.network())
                || termsRevision < 0
                || revision < termsRevision
                || defaultRate < 1
                || intervalTicks < 1
                || allTypes && typeCount != 0
                || !allTypes && typeCount == 0) throw new IllegalArgumentException("Invalid exchange rule summary");
        count(typeCount);
        count(filterEntries);
        count(overrideCount);
        if (filterName == null && filterEntries != 0) throw new IllegalArgumentException("Missing filter summary name");
        if (filterName != null) name(filterName);
    }

    /** Projects only display facts from a previously authorized agreement; names must match their endpoint IDs. */
    public static ExchangeRuleView from(
            ExchangeAgreement agreement, @Nullable NetworkMetadata source, @Nullable NetworkMetadata target) {
        var c = agreement.consent();
        var terms = agreement.terms();
        var filter = terms.filter();
        return new ExchangeRuleView(
                agreement.id(),
                c.revision(),
                c.termsRevision(),
                endpoint(c.sourceNetwork(), c.sourceOwner(), source),
                endpoint(c.targetNetwork(), c.targetOwner(), target),
                c.source().approved(),
                c.target().approved(),
                c.source().paused(),
                c.target().paused(),
                c.revoked(),
                terms.scope().kind() == ResourceScope.Kind.ALL,
                terms.scope().resourceTypeIds().size(),
                terms.filterMode(),
                filter == null
                        ? null
                        : filter.presets().get(filter.root()).name().value(),
                filter == null ? 0 : filter.ruleCount(),
                terms.defaultRate(),
                terms.intervalTicks(),
                terms.rates().size());
    }

    /** Approval status only; APPROVED does not claim that resources were actually transferred. */
    public State state() {
        if (terminated) return State.TERMINATED;
        if (!source.available() || !target.available()) return State.UNAVAILABLE;
        if (!sourceApproved || !targetApproved) return State.PENDING;
        return sourcePaused || targetPaused ? State.PAUSED : State.APPROVED;
    }

    public void write(FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeUUID(id).writeLong(revision).writeLong(termsRevision);
        int flags = (sourceApproved ? 1 : 0)
                | (targetApproved ? 2 : 0)
                | (sourcePaused ? 4 : 0)
                | (targetPaused ? 8 : 0)
                | (terminated ? 16 : 0);
        buffer.writeByte(flags);
        writeEndpoint(buffer, source);
        writeEndpoint(buffer, target);
        buffer.writeBoolean(allTypes).writeVarInt(typeCount).writeByte(filterMode == FilterMode.WHITELIST ? 0 : 1);
        buffer.writeBoolean(filterName != null);
        if (filterName != null) NetworkSummary.writeName(buffer, filterName);
        buffer.writeVarInt(filterEntries)
                .writeLong(defaultRate)
                .writeInt(intervalTicks)
                .writeVarInt(overrideCount);
        if (buffer.writerIndex() - start > MAXIMUM_BYTES)
            throw new IllegalArgumentException("Exchange summary exceeds row bound");
    }

    /** Reads exactly one bounded row; the enclosing frame must reject any unexpected trailing rows or bytes. */
    public static ExchangeRuleView read(FriendlyByteBuf buffer) {
        int start = buffer.readerIndex();
        UUID id = buffer.readUUID();
        long revision = buffer.readLong(), terms = buffer.readLong();
        int flags = buffer.readUnsignedByte();
        if (flags > 31) throw new IllegalArgumentException("Unknown exchange flags");
        Endpoint source = readEndpoint(buffer), target = readEndpoint(buffer);
        boolean all = bool(buffer);
        int types = buffer.readVarInt();
        FilterMode mode =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> FilterMode.WHITELIST;
                    case 1 -> FilterMode.BLACKLIST;
                    default -> throw new IllegalArgumentException("Unknown exchange filter mode");
                };
        String filter = bool(buffer) ? NetworkSummary.readName(buffer) : null;
        ExchangeRuleView value = new ExchangeRuleView(
                id,
                revision,
                terms,
                source,
                target,
                (flags & 1) != 0,
                (flags & 2) != 0,
                (flags & 4) != 0,
                (flags & 8) != 0,
                (flags & 16) != 0,
                all,
                types,
                mode,
                filter,
                buffer.readVarInt(),
                buffer.readLong(),
                buffer.readInt(),
                buffer.readVarInt());
        if (buffer.readerIndex() - start > MAXIMUM_BYTES)
            throw new IllegalArgumentException("Exchange summary exceeds row bound");
        return value;
    }

    private static Endpoint endpoint(UUID network, UUID owner, @Nullable NetworkMetadata metadata) {
        if (metadata != null && !metadata.id().equals(network))
            throw new IllegalArgumentException("Exchange endpoint mismatch");
        return new Endpoint(
                network,
                owner,
                metadata == null ? null : metadata.name().value(),
                metadata != null && metadata.ownerId().equals(owner));
    }

    private static void writeEndpoint(FriendlyByteBuf buffer, Endpoint endpoint) {
        buffer.writeUUID(endpoint.network()).writeUUID(endpoint.owner()).writeBoolean(endpoint.name() != null);
        if (endpoint.name() != null) NetworkSummary.writeName(buffer, endpoint.name());
        buffer.writeBoolean(endpoint.available());
    }

    private static Endpoint readEndpoint(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID(), owner = buffer.readUUID();
        String name = bool(buffer) ? NetworkSummary.readName(buffer) : null;
        return new Endpoint(id, owner, name, bool(buffer));
    }

    private static boolean bool(FriendlyByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid exchange view flag");
        return value == 1;
    }

    private static void count(int value) {
        if (value < 0 || value > 262144) throw new IllegalArgumentException("Invalid exchange summary count");
    }

    private static void name(String value) {
        if (!new ManagedName(value).value().equals(value))
            throw new IllegalArgumentException("Noncanonical exchange name");
    }
}
