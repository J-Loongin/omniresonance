// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.exchange.ExchangeAgreement;
import io.github.loongin.omniresonance.exchange.ExchangeChannel;
import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeTunnel;
import io.github.loongin.omniresonance.network.ManagedName;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Detached pairing metadata within the existing exchange shard; never writes files or grants channel consent. */
record ExchangePairingCatalog(
        Map<UUID, ExchangeTunnel> tunnels, Map<UUID, ExchangeChannel> channels, Map<UUID, Long> ended) {
    static final ExchangePairingCatalog EMPTY = new ExchangePairingCatalog(Map.of(), Map.of());

    ExchangePairingCatalog(Map<UUID, ExchangeTunnel> tunnels, Map<UUID, ExchangeChannel> channels) {
        this(tunnels, channels, Map.of());
    }

    ExchangePairingCatalog atRevision(ExchangePairingCatalog old, long revision) {
        var orders = new HashMap<UUID, Long>();
        for (var t : tunnels.values())
            if (t.consent().revoked()) orders.put(t.id(), old.ended.getOrDefault(t.id(), revision));
        return new ExchangePairingCatalog(tunnels, channels, orders);
    }

    ExchangePairingCatalog {
        ended = Map.copyOf(ended);
        tunnels = Map.copyOf(tunnels);
        channels = Map.copyOf(channels);
        if (tunnels.size() > 262144 || channels.size() > 262144)
            throw new IllegalArgumentException("Pairing catalog too large");
    }

    ExchangePairingCatalog retain(Map<UUID, ExchangeAgreement> agreements) {
        if (agreements.keySet().containsAll(channels.keySet())) return this;
        var kept = new HashMap<>(channels);
        kept.keySet().retainAll(agreements.keySet());
        return new ExchangePairingCatalog(tunnels, kept, ended);
    }

    void validate(Map<UUID, ExchangeAgreement> agreements) {
        if (!tunnels.keySet().containsAll(ended.keySet())) throw new IllegalArgumentException("Orphan pairing history");
        for (var t : tunnels.values())
            if (t.consent().revoked() != ended.containsKey(t.id()) || ended.getOrDefault(t.id(), 0L) < 0)
                throw new IllegalArgumentException("Invalid pairing history");
        var names = new HashSet<String>();
        var livePairs = new HashSet<String>();
        for (var tunnel : tunnels.values())
            if (!tunnel.consent().revoked() && !livePairs.add(pairKey(tunnel.consent())))
                throw new IllegalArgumentException("Duplicate active pairing");
        for (var link : channels.values()) {
            var a = agreements.get(link.id());
            var t = tunnels.get(link.tunnel());
            if (a == null || t == null || !pairKey(a.consent()).equals(pairKey(t.consent())))
                throw new IllegalArgumentException("Channel parent mismatch");
            if (a.consent().approvedByBoth() && !t.consent().approvedByBoth())
                throw new IllegalArgumentException("Approved channel lacks pairing consent");
            if (t.consent().revoked() && !a.consent().revoked())
                throw new IllegalArgumentException("Live channel in closed tunnel");
            if (!a.consent().revoked()
                    && !names.add(link.tunnel() + ":" + link.name().uniquenessKey()))
                throw new IllegalArgumentException("Duplicate channel name");
        }
    }

    static String pairKey(ExchangeConsent c) {
        String a = c.sourceNetwork() + ":" + c.sourceOwner(), b = c.targetNetwork() + ":" + c.targetOwner();
        return a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a;
    }

    static ExchangePairingCatalog migrate(Map<UUID, ExchangeAgreement> agreements, Map<UUID, UUID> accepted) {
        Map<String, List<ExchangeAgreement>> groups = new HashMap<>();
        for (var a : agreements.values())
            groups.computeIfAbsent(pairKey(a.consent()), ignored -> new ArrayList<>())
                    .add(a);
        var tunnels = new HashMap<UUID, ExchangeTunnel>();
        var channels = new HashMap<UUID, ExchangeChannel>();
        for (var group : groups.entrySet()) {
            group.getValue().sort(Comparator.comparing(ExchangeAgreement::id));
            var rows = group.getValue();
            var representative = rows.getFirst();
            boolean approved = false, live = false;
            for (var a : rows) {
                if (!a.consent().revoked()) {
                    live = true;
                    if (!approved) representative = a;
                }
                if (a.id().equals(accepted.get(a.consent().invitationId()))) {
                    representative = a;
                    approved = true;
                }
            }
            var c = representative.consent();
            UUID id = UUID.nameUUIDFromBytes(
                    ("omniresonance:exchange_pair/" + group.getKey()).getBytes(StandardCharsets.UTF_8));
            var pair = new ExchangeConsent(
                    c.invitationId(),
                    c.sourceNetwork(),
                    c.targetNetwork(),
                    c.sourceOwner(),
                    c.targetOwner(),
                    0,
                    0,
                    new ExchangeConsent.Approval(true, false),
                    new ExchangeConsent.Approval(approved, false),
                    !live);
            tunnels.put(id, new ExchangeTunnel(id, pair));
            if (approved) accepted.put(c.invitationId(), id);
            int index = 0;
            for (var a : rows)
                channels.put(a.id(), new ExchangeChannel(a.id(), id, new ManagedName(Integer.toString(++index))));
        }
        return new ExchangePairingCatalog(tunnels, channels).atRevision(EMPTY, 0);
    }

    CompoundTag encode() {
        var tag = new CompoundTag();
        var pairs = new ListTag();
        var links = new ListTag();
        tunnels.values().stream()
                .sorted(Comparator.comparing(ExchangeTunnel::id))
                .forEach(t -> {
                    var row = new CompoundTag();
                    row.putUUID("id", t.id());
                    row.put("consent", ExchangeStateNbt.encodeConsent(t.consent()));
                    pairs.add(row);
                });
        channels.values().stream()
                .sorted(Comparator.comparing(ExchangeChannel::id))
                .forEach(c -> {
                    var row = new CompoundTag();
                    row.putUUID("id", c.id());
                    row.putUUID("tunnel", c.tunnel());
                    row.putString("name", c.name().value());
                    links.add(row);
                });
        tag.put("tunnels", pairs);
        tag.put("channels", links);
        var history = new ListTag();
        ended.keySet().stream().sorted().forEach(id -> {
            var row = new CompoundTag();
            row.putUUID("id", id);
            row.putLong("revision", ended.get(id));
            history.add(row);
        });
        tag.put("terminated_revisions", history);
        return tag;
    }

    static ExchangePairingCatalog decode(CompoundTag tag) {
        if (!tag.getAllKeys().equals(Set.of("tunnels", "channels", "terminated_revisions")))
            throw new IllegalArgumentException("Invalid pairing fields");
        var pairs = new HashMap<UUID, ExchangeTunnel>();
        var links = new HashMap<UUID, ExchangeChannel>();
        for (Tag value : ExchangeStateNbt.list(tag, "tunnels", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) value;
            if (!row.getAllKeys().equals(Set.of("id", "consent")))
                throw new IllegalArgumentException("Invalid tunnel fields");
            var t = new ExchangeTunnel(
                    ManagedDataNbt.readUuid(row, "id"),
                    ExchangeStateNbt.decodeConsent(ExchangeStateNbt.compound(row, "consent")));
            if (pairs.putIfAbsent(t.id(), t) != null) throw new IllegalArgumentException("Duplicate tunnel");
        }
        for (Tag value : ExchangeStateNbt.list(tag, "channels", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) value;
            if (!row.getAllKeys().equals(Set.of("id", "tunnel", "name")))
                throw new IllegalArgumentException("Invalid channel fields");
            ManagedDataNbt.requireType(row, "name", Tag.TAG_STRING);
            var name = new ManagedName(row.getString("name"));
            if (!name.value().equals(row.getString("name")))
                throw new IllegalArgumentException("Noncanonical channel name");
            var c = new ExchangeChannel(
                    ManagedDataNbt.readUuid(row, "id"), ManagedDataNbt.readUuid(row, "tunnel"), name);
            if (links.putIfAbsent(c.id(), c) != null) throw new IllegalArgumentException("Duplicate channel");
        }
        var history = new HashMap<UUID, Long>();
        for (Tag value : ExchangeStateNbt.list(tag, "terminated_revisions", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) value;
            if (!row.getAllKeys().equals(Set.of("id", "revision")))
                throw new IllegalArgumentException("Invalid pairing history row");
            ManagedDataNbt.requireType(row, "revision", Tag.TAG_LONG);
            if (history.putIfAbsent(ManagedDataNbt.readUuid(row, "id"), row.getLong("revision")) != null)
                throw new IllegalArgumentException("Duplicate pairing history");
        }
        return new ExchangePairingCatalog(pairs, links, history);
    }
}
