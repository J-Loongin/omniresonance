// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.exchange.ExchangeAgreement;
import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeFilterSnapshot;
import io.github.loongin.omniresonance.exchange.ExchangeInvitation;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Strict, pure codecs for detached exchange persistence values. Caller-thread operations never access worlds,
 * authorize requests, simulate transfers or retain mutable tags. Malformed or oversized values reject the whole
 * candidate. These codecs are not client intent decoders or a substitute for authoritative service validation.
 */
public final class ExchangeStateNbt {
    private ExchangeStateNbt() {}

    /** Returns fresh caller-owned NBT, without changing consent or authority. */
    public static CompoundTag encodeConsent(ExchangeConsent value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("invitation_id", value.invitationId());
        tag.putUUID("source_network", value.sourceNetwork());
        tag.putUUID("target_network", value.targetNetwork());
        tag.putUUID("source_owner", value.sourceOwner());
        tag.putUUID("target_owner", value.targetOwner());
        tag.putLong("revision", value.revision());
        tag.putLong("terms_revision", value.termsRevision());
        tag.putBoolean("source_approved", value.source().approved());
        tag.putBoolean("source_paused", value.source().paused());
        tag.putBoolean("target_approved", value.target().approved());
        tag.putBoolean("target_paused", value.target().paused());
        tag.putBoolean("revoked", value.revoked());
        return tag;
    }

    /** Pure restoration; unknown fields, wrong types and invalid consent invariants fail without mutation. */
    public static ExchangeConsent decodeConsent(CompoundTag tag) {
        fields(
                tag,
                "invitation_id",
                "source_network",
                "target_network",
                "source_owner",
                "target_owner",
                "revision",
                "terms_revision",
                "source_approved",
                "source_paused",
                "target_approved",
                "target_paused",
                "revoked");
        return new ExchangeConsent(
                uuid(tag, "invitation_id"),
                uuid(tag, "source_network"),
                uuid(tag, "target_network"),
                uuid(tag, "source_owner"),
                uuid(tag, "target_owner"),
                number(tag, "revision"),
                number(tag, "terms_revision"),
                new ExchangeConsent.Approval(bool(tag, "source_approved"), bool(tag, "source_paused")),
                new ExchangeConsent.Approval(bool(tag, "target_approved"), bool(tag, "target_paused")),
                bool(tag, "revoked"));
    }

    /** Returns fresh caller-owned invitation NBT; no time advancement, consumption or I/O occurs. */
    public static CompoundTag encodeInvitation(ExchangeInvitation value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", value.id());
        tag.putUUID("target_network", value.targetNetwork());
        tag.putUUID("target_owner", value.targetOwner());
        tag.putLong("issued_tick", value.issuedTick());
        tag.putLong("expires_tick", value.expiresTick());
        tag.putLong("revision", value.revision());
        tag.putString(
                "state",
                switch (value.state()) {
                    case OPEN -> "open";
                    case CONSUMED -> "consumed";
                    case REVOKED -> "revoked";
                });
        return tag;
    }

    /** Pure restoration of exact lifecycle and expiry; unknown state or invalid lifetime rejects the candidate. */
    public static ExchangeInvitation decodeInvitation(CompoundTag tag) {
        fields(tag, "id", "target_network", "target_owner", "issued_tick", "expires_tick", "revision", "state");
        ManagedDataNbt.requireType(tag, "state", Tag.TAG_STRING);
        ExchangeInvitation.State state =
                switch (tag.getString("state")) {
                    case "open" -> ExchangeInvitation.State.OPEN;
                    case "consumed" -> ExchangeInvitation.State.CONSUMED;
                    case "revoked" -> ExchangeInvitation.State.REVOKED;
                    default -> throw new IllegalArgumentException("Unknown exchange invitation state");
                };
        return new ExchangeInvitation(
                uuid(tag, "id"),
                uuid(tag, "target_network"),
                uuid(tag, "target_owner"),
                number(tag, "issued_tick"),
                number(tag, "expires_tick"),
                number(tag, "revision"),
                state);
    }

    /** Encodes the unique closure in deterministic identity order; applies the existing 16 MiB object boundary. */
    public static CompoundTag encodeFilter(ExchangeFilterSnapshot value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("root", value.root());
        ListTag rows = new ListTag();
        value.presets().values().stream()
                .sorted(Comparator.comparing(ResourceFilterPreset::id))
                .forEach(preset -> rows.add(ResourceFilterPresetNbt.encode(preset)));
        tag.put("presets", rows);
        ManagedObjectNbtSize.validate(tag);
        return tag;
    }

    /** Restores only complete reachable graphs; duplicate or extraneous presets are never silently discarded. */
    public static ExchangeFilterSnapshot decodeFilter(CompoundTag tag) {
        fields(tag, "root", "presets");
        ManagedObjectNbtSize.validate(tag);
        UUID root = uuid(tag, "root");
        ManagedDataNbt.requireType(tag, "presets", Tag.TAG_LIST);
        ListTag rows = (ListTag) tag.get("presets");
        if (rows.size() > ResourceFilterPreset.MAX_ENTRIES || rows.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid exchange filter collection");
        Map<UUID, ResourceFilterPreset> presets = new HashMap<>();
        int count = 0;
        for (Tag row : rows) {
            ResourceFilterPreset preset = ResourceFilterPresetNbt.decode((CompoundTag) row);
            if (presets.putIfAbsent(preset.id(), preset) != null)
                throw new IllegalArgumentException("Duplicate exchange preset");
            if (preset.rules().size() > ResourceFilterPreset.MAX_ENTRIES - count)
                throw new IllegalArgumentException("Too many exchange filter rules");
            count += preset.rules().size();
        }
        ExchangeFilterSnapshot result = ExchangeFilterSnapshot.capture(
                root, presets, ResourceFilterPreset.MAX_ENTRIES, ResourceFilterPreset.MAX_ENTRIES);
        if (result.presets().size() != presets.size())
            throw new IllegalArgumentException("Unreachable exchange preset");
        return result;
    }

    /** Encodes one complete agreement as fresh NBT, enforcing the existing managed-object byte ceiling. */
    public static CompoundTag encodeAgreement(ExchangeAgreement value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", value.id());
        tag.put("consent", encodeConsent(value.consent()));
        ExchangeTerms terms = value.terms();
        CompoundTag body = new CompoundTag();
        body.putString("scope", terms.scope().kind() == ResourceScope.Kind.ALL ? "all" : "custom_set");
        ListTag types = new ListTag();
        terms.scope().resourceTypeIds().stream()
                .sorted()
                .forEach(type -> types.add(StringTag.valueOf(type.toString())));
        body.put("types", types);
        body.putString("filter_mode", terms.filterMode() == FilterMode.WHITELIST ? "whitelist" : "blacklist");
        if (terms.filter() != null) body.put("filter", encodeFilter(terms.filter()));
        body.putLong("default_rate", terms.defaultRate());
        body.putInt("interval_ticks", terms.intervalTicks());
        ListTag rates = new ListTag();
        terms.rates().keySet().stream().sorted().forEach(type -> {
            CompoundTag row = new CompoundTag();
            row.putString("type", type.toString());
            row.putLong("rate", terms.rate(type));
            var parameter = terms.resourceParameters().get(type);
            row.putString(
                    "batch_mode",
                    parameter.batchMode() == ResourceTransferPolicy.BatchMode.GREEDY ? "greedy" : "exact");
            row.putLong("batch_size", parameter.batchSize());
            rates.add(row);
        });
        body.put("rates", rates);
        tag.put("terms", body);
        ManagedObjectNbtSize.validate(tag);
        return tag;
    }

    /** Pure strict restoration of complete terms, including unknown registered-type identities without dropping them. */
    public static ExchangeAgreement decodeAgreement(CompoundTag tag) {
        return decodeAgreement(tag, false);
    }

    static ExchangeAgreement decodeLegacyAgreement(CompoundTag tag) {
        return decodeAgreement(tag, true);
    }

    private static ExchangeAgreement decodeAgreement(CompoundTag tag, boolean legacy) {
        fields(tag, "id", "consent", "terms");
        ManagedObjectNbtSize.validate(tag);
        CompoundTag body = compound(tag, "terms");
        Set<String> expected =
                new HashSet<>(Set.of("scope", "types", "filter_mode", "default_rate", "interval_ticks", "rates"));
        if (body.contains("filter")) expected.add("filter");
        if (!body.getAllKeys().equals(expected)) throw new IllegalArgumentException("Unexpected exchange terms fields");
        Set<ResourceLocation> types = new HashSet<>();
        for (Tag row : list(body, "types", Tag.TAG_STRING))
            if (!types.add(typeId(row.getAsString()))) throw new IllegalArgumentException("Duplicate exchange type");
        ManagedDataNbt.requireType(body, "scope", Tag.TAG_STRING);
        ResourceScope scope =
                switch (body.getString("scope")) {
                    case "all" -> {
                        if (!types.isEmpty()) throw new IllegalArgumentException("ALL scope has custom types");
                        yield ResourceScope.all();
                    }
                    case "custom_set" -> ResourceScope.customSet(types);
                    default -> throw new IllegalArgumentException("Unknown exchange scope");
                };
        ManagedDataNbt.requireType(body, "filter_mode", Tag.TAG_STRING);
        FilterMode mode =
                switch (body.getString("filter_mode")) {
                    case "whitelist" -> FilterMode.WHITELIST;
                    case "blacklist" -> FilterMode.BLACKLIST;
                    default -> throw new IllegalArgumentException("Unknown exchange filter mode");
                };
        Map<ResourceLocation, ResourceTransferPolicy.InputOverride> parameters = new HashMap<>();
        for (Tag value : list(body, "rates", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            ManagedDataNbt.requireType(row, "type", Tag.TAG_STRING);
            var type = typeId(row.getString("type"));
            ResourceTransferPolicy.BatchMode batchMode;
            long batchSize;
            if (legacy && row.getAllKeys().equals(Set.of("type", "rate"))) {
                batchMode = ResourceTransferPolicy.BatchMode.GREEDY;
                batchSize = ResourceTypes.defaultExactBatchSize(type);
            } else {
                fields(row, "type", "rate", "batch_mode", "batch_size");
                ManagedDataNbt.requireType(row, "batch_mode", Tag.TAG_STRING);
                batchMode = switch (row.getString("batch_mode")) {
                    case "greedy" -> ResourceTransferPolicy.BatchMode.GREEDY;
                    case "exact" -> ResourceTransferPolicy.BatchMode.EXACT;
                    default -> throw new IllegalArgumentException("Unknown exchange batch mode");
                };
                batchSize = number(row, "batch_size");
            }
            if (parameters.putIfAbsent(
                            type, new ResourceTransferPolicy.InputOverride(number(row, "rate"), batchMode, batchSize))
                    != null) throw new IllegalArgumentException("Duplicate exchange rate");
        }
        ManagedDataNbt.requireType(body, "interval_ticks", Tag.TAG_INT);
        ExchangeTerms terms = new ExchangeTerms(
                scope,
                mode,
                body.contains("filter") ? decodeFilter(compound(body, "filter")) : null,
                number(body, "default_rate"),
                Map.of(),
                body.getInt("interval_ticks"),
                parameters);
        return new ExchangeAgreement(uuid(tag, "id"), decodeConsent(compound(tag, "consent")), terms);
    }

    static CompoundTag compound(CompoundTag tag, String field) {
        ManagedDataNbt.requireType(tag, field, Tag.TAG_COMPOUND);
        return tag.getCompound(field);
    }

    static ListTag list(CompoundTag tag, String field, int type) {
        ManagedDataNbt.requireType(tag, field, Tag.TAG_LIST);
        ListTag rows = (ListTag) tag.get(field);
        if (rows.size() > ResourceFilterPreset.MAX_ENTRIES
                || rows.getElementType() != type && !(rows.isEmpty() && rows.getElementType() == Tag.TAG_END))
            throw new IllegalArgumentException("Invalid exchange list");
        return rows;
    }

    private static ResourceLocation typeId(String value) {
        if (value.length() > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES)
            throw new IllegalArgumentException("Exchange type ID too long");
        return ResourceLocation.parse(value);
    }

    private static UUID uuid(CompoundTag tag, String field) {
        return ManagedDataNbt.readUuid(tag, field);
    }

    private static long number(CompoundTag tag, String field) {
        ManagedDataNbt.requireType(tag, field, Tag.TAG_LONG);
        return tag.getLong(field);
    }

    private static boolean bool(CompoundTag tag, String field) {
        ManagedDataNbt.requireType(tag, field, Tag.TAG_BYTE);
        byte value = tag.getByte(field);
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid exchange boolean");
        return value == 1;
    }

    private static void fields(CompoundTag tag, String... fields) {
        if (!tag.getAllKeys().equals(Set.of(fields))) throw new IllegalArgumentException("Unexpected exchange fields");
    }
}
