// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Strict pure caller-thread codec for detached stored policies, using an externally supplied direction and adapter
 * set. No world access, simulation, mutation, registration discovery, or client admission occurs. Malformed and
 * oversized values fail before publication; returned tags own their mutable state.
 */
public final class ResourcePolicyNbt {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private ResourcePolicyNbt() {}

    /** Pure encoding into a fresh owned compound; rejects full unnamed binary NBT exceeding 16 MiB. */
    public static CompoundTag encode(StoredResourcePolicy stored) {
        Objects.requireNonNull(stored, "stored");
        ResourceTransferPolicy policy = stored.effectivePolicy();
        CompoundTag tag = commonTag(policy);
        CompoundTag scope = new CompoundTag();
        scope.putString("kind", policy.scope().kind() == ResourceScope.Kind.ALL ? "all" : "custom_set");
        if (policy.scope().kind() == ResourceScope.Kind.CUSTOM_SET) {
            ListTag types = new ListTag();
            policy.scope().resourceTypeIds().stream()
                    .sorted()
                    .forEach(id -> types.add(StringTag.valueOf(id.toString())));
            scope.put("types", types);
        }
        tag.put("resource_scope", scope);
        Map<ResourceLocation, StoredResourcePolicy.RawOverride> rows = new HashMap<>(stored.missingTypeOverrides());
        policy.resourcePolicyOverrides().forEach((id, override) -> {
            Long rate = override.rate() == ResourceTransferPolicy.DEFAULT_RATE ? null : override.rate();
            if (override instanceof ResourceTransferPolicy.InputOverride input
                    && input.batchMode() == ResourceTransferPolicy.BatchMode.EXACT) {
                rows.put(id, new StoredResourcePolicy.RawOverride(rate, input.batchMode(), input.batchSize()));
            } else rows.put(id, new StoredResourcePolicy.RawOverride(rate, null, null));
        });
        ListTag overrides = new ListTag();
        rows.keySet().stream().sorted().forEach(id -> {
            StoredResourcePolicy.RawOverride raw = rows.get(id);
            CompoundTag row = new CompoundTag();
            row.putString("type_id", id.toString());
            if (raw.rate() != null) {
                if (raw.rate() <= Integer.MAX_VALUE) row.putInt("rate", Math.toIntExact(raw.rate()));
                else row.putLong("rate", raw.rate());
            }
            if (raw.batchMode() != null)
                row.putString(
                        "batch_mode", raw.batchMode() == ResourceTransferPolicy.BatchMode.EXACT ? "exact" : "greedy");
            if (raw.batchSize() != null) row.putLong("batch_size", raw.batchSize());
            overrides.add(row);
        });
        tag.put("resource_policy_overrides", overrides);
        ManagedObjectNbtSize.validate(tag);
        return tag;
    }

    /**
     * Pure strict decoding of an already native-decoded tag, never retaining or modifying its NBT. The caller owns
     * and must stabilize the tag and registered set during this call. Invalid fields/counts/bytes throw; this is not
     * an allocation-safe decoder for untrusted wire bytes or authorization for unknown client overrides.
     */
    public static StoredResourcePolicy decode(
            CompoundTag tag, TransferDirection direction, Set<ResourceLocation> registered) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(registered, "registered");
        Set<String> fields = new HashSet<>(Set.of(
                "interval_ticks",
                "redstone_condition",
                "filter_mode",
                "resource_scope",
                "resource_policy_overrides",
                direction == TransferDirection.INPUT ? "keep_count" : "priority"));
        if (tag.contains("filter_preset_id")) fields.add("filter_preset_id");
        requireFields(tag, fields);
        CompoundTag common = new CompoundTag();
        for (String field : fields) {
            if (!field.equals("resource_scope") && !field.equals("resource_policy_overrides"))
                common.put(field, tag.get(field));
        }
        CompoundTag binding = new CompoundTag();
        binding.put("item_policy", common);
        var legacyCommon = ItemPolicyNbt.decode(binding, direction);
        ManagedDataNbt.requireType(tag, "resource_scope", Tag.TAG_COMPOUND);
        ResourceScope scope = readScope(tag.getCompound("resource_scope"));
        ListTag rows = readList(tag, "resource_policy_overrides", Tag.TAG_COMPOUND);
        Map<ResourceLocation, StoredResourcePolicy.RawOverride> missing = new HashMap<>();
        Map<ResourceLocation, ResourceTransferPolicy.InputOverride> input = new HashMap<>();
        Map<ResourceLocation, ResourceTransferPolicy.OutputOverride> output = new HashMap<>();
        Set<ResourceLocation> seen = new HashSet<>();
        for (Tag entry : rows) {
            CompoundTag row = (CompoundTag) entry;
            ManagedDataNbt.requireType(row, "type_id", Tag.TAG_STRING);
            ResourceLocation id = readId(row.getString("type_id"));
            if (!scope.includes(id) || !seen.add(id))
                throw new IllegalArgumentException("Duplicate or out-of-scope override");
            StoredResourcePolicy.RawOverride raw = readOverride(row, direction);
            if (!registered.contains(id)) missing.put(id, raw);
            else {
                long rate = raw.rate() == null ? ResourceTransferPolicy.DEFAULT_RATE : raw.rate();
                if (direction == TransferDirection.INPUT)
                    input.put(
                            id,
                            new ResourceTransferPolicy.InputOverride(
                                    rate,
                                    raw.batchMode() == null ? ResourceTransferPolicy.BatchMode.GREEDY : raw.batchMode(),
                                    raw.batchSize() == null
                                            ? ResourceTypes.defaultExactBatchSize(id)
                                            : raw.batchSize()));
                else output.put(id, new ResourceTransferPolicy.OutputOverride(rate));
            }
        }
        ManagedObjectNbtSize.validate(tag);
        ResourceTransferPolicy policy = direction == TransferDirection.INPUT
                ? new ResourceTransferPolicy.Input(
                        legacyCommon.intervalTicks(),
                        scope,
                        legacyCommon.redstoneCondition(),
                        legacyCommon.filterPresetId(),
                        legacyCommon.filterMode(),
                        input,
                        tag.getLong("keep_count"))
                : new ResourceTransferPolicy.Output(
                        legacyCommon.intervalTicks(),
                        scope,
                        legacyCommon.redstoneCondition(),
                        legacyCommon.filterPresetId(),
                        legacyCommon.filterMode(),
                        output,
                        tag.getInt("priority"));
        return new StoredResourcePolicy(policy, missing);
    }

    /** Pure strict M2 compound conversion preserving all old parameters and ITEM-only scope without source mutation. */
    public static StoredResourcePolicy decodeLegacyItemPolicy(CompoundTag tag, TransferDirection direction) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(direction, "direction");
        CompoundTag binding = new CompoundTag();
        binding.put("item_policy", tag);
        return new StoredResourcePolicy(
                ResourceTransferPolicy.legacy(ItemPolicyNbt.decode(binding, direction)), Map.of());
    }

    private static CompoundTag commonTag(ResourceTransferPolicy policy) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("interval_ticks", policy.intervalTicks());
        tag.putString(
                "redstone_condition",
                switch (policy.redstoneCondition()) {
                    case IGNORE -> "ignore";
                    case SIGNAL -> "signal";
                    case NO_SIGNAL -> "no_signal";
                });
        tag.putString(
                "filter_mode",
                policy.filterMode() == io.github.loongin.omniresonance.filter.FilterMode.WHITELIST
                        ? "whitelist"
                        : "blacklist");
        if (policy.filterPresetId() != null) tag.putUUID("filter_preset_id", policy.filterPresetId());
        if (policy instanceof ResourceTransferPolicy.Input input) tag.putLong("keep_count", input.keepCount());
        else tag.putInt("priority", ((ResourceTransferPolicy.Output) policy).priority());
        return tag;
    }

    private static ResourceScope readScope(CompoundTag tag) {
        ManagedDataNbt.requireType(tag, "kind", Tag.TAG_STRING);
        if (tag.getString("kind").equals("all")) {
            requireFields(tag, Set.of("kind"));
            return ResourceScope.all();
        }
        if (!tag.getString("kind").equals("custom_set"))
            throw new IllegalArgumentException("Invalid resource scope kind");
        requireFields(tag, Set.of("kind", "types"));
        ListTag list = readList(tag, "types", Tag.TAG_STRING);
        Set<ResourceLocation> types = new HashSet<>();
        for (Tag entry : list)
            if (!types.add(readId(entry.getAsString()))) throw new IllegalArgumentException("Duplicate scope type");
        return ResourceScope.customSet(types);
    }

    private static StoredResourcePolicy.RawOverride readOverride(CompoundTag row, TransferDirection direction) {
        Set<String> fields = new HashSet<>(Set.of("type_id"));
        Long rate = null;
        ResourceTransferPolicy.BatchMode mode = null;
        Long batch = null;
        if (row.contains("rate")) {
            fields.add("rate");
            if (!row.contains("rate", Tag.TAG_INT) && !row.contains("rate", Tag.TAG_LONG))
                throw new IllegalArgumentException("Invalid resource rate type");
            rate = row.getLong("rate");
        }
        if (direction == TransferDirection.INPUT) {
            if (row.contains("batch_mode")) {
                fields.add("batch_mode");
                ManagedDataNbt.requireType(row, "batch_mode", Tag.TAG_STRING);
                mode = switch (row.getString("batch_mode")) {
                    case "greedy" -> ResourceTransferPolicy.BatchMode.GREEDY;
                    case "exact" -> ResourceTransferPolicy.BatchMode.EXACT;
                    default -> throw new IllegalArgumentException("Invalid batch mode");
                };
            }
            if (row.contains("batch_size")) {
                fields.add("batch_size");
                ManagedDataNbt.requireType(row, "batch_size", Tag.TAG_LONG);
                batch = row.getLong("batch_size");
            }
        }
        requireFields(row, fields);
        return new StoredResourcePolicy.RawOverride(rate, mode, batch);
    }

    private static ResourceLocation readId(String value) {
        if (value.length() > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES)
            throw new IllegalArgumentException("Overlong type ID");
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical type ID");
        return id;
    }

    private static ListTag readList(CompoundTag tag, String field, int elementType) {
        ManagedDataNbt.requireType(tag, field, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(field);
        if (list.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || list.getElementType() != elementType && !(list.isEmpty() && list.getElementType() == Tag.TAG_END))
            throw new IllegalArgumentException("Invalid resource policy list");
        return list;
    }

    private static void requireFields(CompoundTag tag, Set<String> fields) {
        if (!tag.getAllKeys().equals(fields))
            throw new IllegalArgumentException("Unexpected or missing resource policy fields");
    }
}
