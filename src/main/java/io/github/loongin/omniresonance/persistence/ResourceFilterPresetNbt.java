// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ItemFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Strict pure caller-thread codec for a single full preset. Never reads worlds, resolves references/tags, mutates
 * authority or admits client intents. Callers stabilize native inputs during decoding; outputs own mutable state.
 * Invalid shapes, field limits and actual unnamed NBT over 16 MiB reject the entire operation before publication.
 */
public final class ResourceFilterPresetNbt {
    private ResourceFilterPresetNbt() {}

    /** Encodes into a fresh owned compound, without mutation or simulation; invalid/oversized data throws. */
    public static CompoundTag encode(ResourceFilterPreset preset) {
        Objects.requireNonNull(preset);
        CompoundTag tag = new CompoundTag();
        tag.putUUID("preset_id", preset.id());
        tag.putString("name", preset.name().value());
        tag.putLong("revision", preset.revision());
        ListTag compact = compactItems(preset);
        if (compact != null) {
            tag.put("items", compact);
            ManagedObjectNbtSize.validate(tag);
            return tag;
        }
        ListTag rules = new ListTag();
        for (ResourceFilterRule rule : preset.rules()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("rule_id", rule.id());
            if (rule instanceof ResourceFilterRule.Reference reference) {
                row.putString("kind", "reference");
                row.putUUID("preset_id", reference.presetId());
            } else {
                ResourceFilterRule.Match match = (ResourceFilterRule.Match) rule;
                row.putString("kind", "match");
                row.putString("resource_type_id", match.resourceTypeId().toString());
                row.put("selector", selector(match.selector()));
                row.put("components", components(match.components()));
            }
            rules.add(row);
        }
        tag.put("rules", rules);
        ManagedObjectNbtSize.validate(tag);
        return tag;
    }

    /**
     * Restores detached server persistence, never authorizing client component bytes. Native decoding is the caller's
     * responsibility; this object gate is not a bounded wire decoder or a cross-library graph/admission validator.
     */
    public static ResourceFilterPreset decode(CompoundTag tag) {
        Objects.requireNonNull(tag);
        boolean compact = tag.contains("items");
        fields(tag, "preset_id", "name", "revision", compact ? "items" : "rules");
        UUID id = ManagedDataNbt.readUuid(tag, "preset_id");
        var name = ManagedDataNbt.readName(tag);
        ManagedDataNbt.requireType(tag, "revision", Tag.TAG_LONG);
        ListTag rows = list(tag, compact ? "items" : "rules", compact ? Tag.TAG_STRING : Tag.TAG_COMPOUND);
        // Enforce the whole object budget before constructing detached domain rules and component copies.
        ManagedObjectNbtSize.validate(tag);
        if (compact) {
            Set<ResourceLocation> ids = new HashSet<>();
            for (Tag value : rows)
                if (!ids.add(id(value.getAsString(), 65535)))
                    throw new IllegalArgumentException("Duplicate compact item ID");
            return migrateIds(id, name, tag.getLong("revision"), ids);
        }
        List<ResourceFilterRule> rules = new ArrayList<>();
        for (Tag value : rows) {
            CompoundTag row = (CompoundTag) value;
            UUID ruleId = ManagedDataNbt.readUuid(row, "rule_id");
            String kind = string(row, "kind");
            if (kind.equals("reference")) {
                fields(row, "rule_id", "kind", "preset_id");
                rules.add(new ResourceFilterRule.Reference(ruleId, ManagedDataNbt.readUuid(row, "preset_id")));
            } else if (kind.equals("match")) {
                fields(row, "rule_id", "kind", "resource_type_id", "selector", "components");
                ResourceLocation type = id(string(row, "resource_type_id"), 128);
                rules.add(new ResourceFilterRule.Match(
                        ruleId,
                        type,
                        readSelector(compound(row, "selector")),
                        readComponents(compound(row, "components"), type)));
            } else throw new IllegalArgumentException("Unknown rule kind");
        }
        return new ResourceFilterPreset(id, name, tag.getLong("revision"), rules);
    }

    /**
     * Pure M2 migration preserving parent identity/name/revision. Rule IDs derive from the parent UUID and complete
     * resource ID, remaining stable under reordering or deletion. Hash collisions reject before publication.
     * Compact persistence preserves every valid M2 object's byte budget; no registry discovery or mutation occurs.
     */
    public static ResourceFilterPreset migrate(ItemFilterPreset legacy) {
        Objects.requireNonNull(legacy);
        return migrateIds(legacy.id(), legacy.name(), legacy.revision(), legacy.itemIds());
    }

    private static ResourceFilterPreset migrateIds(
            UUID parent, ManagedName name, long revision, Set<ResourceLocation> source) {
        List<ResourceLocation> ids = new ArrayList<>(source);
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceFilterRule> rules = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        MessageDigest digest = digest();
        for (ResourceLocation resourceId : ids) {
            UUID ruleId = compactRuleId(digest, parent, resourceId);
            if (!seen.add(ruleId)) throw new IllegalArgumentException("Compact rule UUID collision");
            rules.add(new ResourceFilterRule.Match(
                    ruleId,
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(resourceId),
                    ComponentCondition.idOnly()));
        }
        return new ResourceFilterPreset(parent, name, revision, rules);
    }

    private static @Nullable ListTag compactItems(ResourceFilterPreset preset) {
        List<ResourceLocation> ids = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        MessageDigest digest = digest();
        for (ResourceFilterRule rule : preset.rules()) {
            if (!(rule instanceof ResourceFilterRule.Match match)
                    || !match.resourceTypeId().equals(ResourceTypes.ITEM)
                    || !(match.selector() instanceof ResourceFilterRule.Exact exact)
                    || !match.components().isIdOnly()
                    || !rule.id().equals(compactRuleId(digest, preset.id(), exact.resourceId()))
                    || !seen.add(exact.resourceId())) return null;
            ids.add(exact.resourceId());
        }
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        ListTag items = new ListTag();
        for (ResourceLocation id : ids) items.add(StringTag.valueOf(id.toString()));
        return items;
    }

    /** Pure identity derivation shared by trusted migration and compact rule editing. */
    public static UUID compactRuleId(UUID parent, ResourceLocation resourceId) {
        return compactRuleId(digest(), parent, resourceId);
    }

    /** Reads compact eligibility without rewriting arbitrary rule identities or component values. */
    public static boolean isCompact(ResourceFilterPreset preset) {
        return compactItems(preset) != null;
    }

    /** Trusted copy preserves general rule identities, but derives compact leaves under the new parent. */
    public static ResourceFilterPreset copy(ResourceFilterPreset source, UUID id, ManagedName name) {
        List<ResourceFilterRule> rules = source.rules();
        if (isCompact(source)) {
            List<ResourceFilterRule> copied = new ArrayList<>();
            for (ResourceFilterRule rule : rules) {
                ResourceFilterRule.Match match = (ResourceFilterRule.Match) rule;
                ResourceLocation resource = ((ResourceFilterRule.Exact) match.selector()).resourceId();
                copied.add(new ResourceFilterRule.Match(
                        compactRuleId(id, resource), match.resourceTypeId(), match.selector(), match.components()));
            }
            rules = copied;
        }
        return new ResourceFilterPreset(id, name, 0, rules);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required JDK SHA-256 unavailable", exception);
        }
    }

    private static UUID compactRuleId(MessageDigest digest, UUID parent, ResourceLocation resourceId) {
        digest.update("omniresonance:compact_item_rule\0".getBytes(StandardCharsets.UTF_8));
        digest.update(ByteBuffer.allocate(16)
                .putLong(parent.getMostSignificantBits())
                .putLong(parent.getLeastSignificantBits())
                .array());
        byte[] bytes = digest.digest(resourceId.toString().getBytes(StandardCharsets.UTF_8));
        ByteBuffer uuid = ByteBuffer.wrap(bytes);
        return new UUID(uuid.getLong(), uuid.getLong());
    }

    /** Pure strict conversion of one legacy compound; no owner schema change, publication or mutation. */
    public static ResourceFilterPreset decodeLegacy(CompoundTag legacy) {
        ListTag entries = new ListTag();
        entries.add(legacy);
        CompoundTag root = new CompoundTag();
        root.put("filter_presets", entries);
        ItemFilterPreset preset =
                OwnerPresetNbt.decode(root).values().iterator().next();
        return migrate(preset);
    }

    private static CompoundTag selector(ResourceFilterRule.Selector selector) {
        CompoundTag tag = new CompoundTag();
        if (selector instanceof ResourceFilterRule.WholeType) tag.putString("kind", "whole_type");
        else if (selector instanceof ResourceFilterRule.Exact exact) {
            tag.putString("kind", "exact");
            tag.putString("resource_id", exact.resourceId().toString());
        } else if (selector instanceof ResourceFilterRule.TagSelector selected) {
            tag.putString("kind", "tag");
            tag.putString("tag_id", selected.tagId().toString());
        } else {
            tag.putString("kind", "glob");
            tag.putString("pattern", ((ResourceFilterRule.Glob) selector).glob().pattern());
        }
        return tag;
    }

    private static ResourceFilterRule.Selector readSelector(CompoundTag tag) {
        return switch (string(tag, "kind")) {
            case "whole_type" -> {
                fields(tag, "kind");
                yield ResourceFilterRule.Selector.wholeType();
            }
            case "exact" -> {
                fields(tag, "kind", "resource_id");
                yield ResourceFilterRule.Selector.exact(id(string(tag, "resource_id"), 65535));
            }
            case "tag" -> {
                fields(tag, "kind", "tag_id");
                yield ResourceFilterRule.Selector.tag(id(string(tag, "tag_id"), 65535));
            }
            case "glob" -> {
                fields(tag, "kind", "pattern");
                yield ResourceFilterRule.Selector.glob(string(tag, "pattern"));
            }
            default -> throw new IllegalArgumentException("Unknown selector kind");
        };
    }

    private static CompoundTag components(ComponentCondition condition) {
        var snapshot = condition.persistenceSnapshot();
        CompoundTag tag = new CompoundTag();
        tag.putString(
                "mode",
                switch (snapshot.mode()) {
                    case ID_ONLY -> "id_only";
                    case FULL -> "full";
                    case SELECTED -> "selected";
                });
        if (snapshot.mode() == ComponentCondition.Mode.FULL) tag.putByteArray("canonical", snapshot.fullBytes());
        else if (snapshot.mode() == ComponentCondition.Mode.SELECTED) {
            ListTag values = new ListTag();
            for (var component : snapshot.selected()) {
                CompoundTag value = new CompoundTag();
                value.putString("component_id", component.key().toString());
                value.putByteArray("canonical", component.canonicalBytes());
                values.add(value);
            }
            tag.put("selected", values);
        }
        return tag;
    }

    private static ComponentCondition readComponents(CompoundTag tag, ResourceLocation type) {
        switch (string(tag, "mode")) {
            case "id_only":
                fields(tag, "mode");
                return ComponentCondition.idOnly();
            case "full":
                fields(tag, "mode", "canonical");
                ManagedDataNbt.requireType(tag, "canonical", Tag.TAG_BYTE_ARRAY);
                return ComponentCondition.fromPersistenceSnapshot(new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.FULL, type, tag.getByteArray("canonical"), List.of()));
            case "selected":
                fields(tag, "mode", "selected");
                List<ComponentCondition.SelectedComponent> values = new ArrayList<>();
                for (Tag entry : list(tag, "selected")) {
                    CompoundTag value = (CompoundTag) entry;
                    fields(value, "component_id", "canonical");
                    ManagedDataNbt.requireType(value, "canonical", Tag.TAG_BYTE_ARRAY);
                    values.add(new ComponentCondition.SelectedComponent(
                            id(string(value, "component_id"), 65535), value.getByteArray("canonical")));
                }
                return ComponentCondition.fromPersistenceSnapshot(new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.SELECTED, type, null, values));
            default:
                throw new IllegalArgumentException("Unknown component mode");
        }
    }

    private static ResourceLocation id(String text, int limit) {
        if (text.length() > limit) throw new IllegalArgumentException("ID too long");
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical ID");
        return id;
    }

    private static void fields(CompoundTag tag, String... names) {
        if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Unexpected preset fields");
    }

    private static String string(CompoundTag tag, String name) {
        ManagedDataNbt.requireType(tag, name, Tag.TAG_STRING);
        return tag.getString(name);
    }

    private static CompoundTag compound(CompoundTag tag, String name) {
        ManagedDataNbt.requireType(tag, name, Tag.TAG_COMPOUND);
        return tag.getCompound(name);
    }

    private static ListTag list(CompoundTag tag, String name) {
        return list(tag, name, Tag.TAG_COMPOUND);
    }

    private static ListTag list(CompoundTag tag, String name, int type) {
        ManagedDataNbt.requireType(tag, name, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(name);
        if (list.size() > ResourceFilterPreset.MAX_ENTRIES
                || list.getElementType() != type && !(list.isEmpty() && list.getElementType() == Tag.TAG_END))
            throw new IllegalArgumentException("Invalid preset list");
        return list;
    }
}
