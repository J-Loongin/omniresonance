// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.filter.ItemFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned v3 default-network pointer and complete resource preset library for one owner.
 *
 * <p>Create and load must run on the owning server thread, which then owns all state access. UUID values are
 * immutable and tags are never retained. No operation is a simulation. Wrong-thread access fails before reading
 * or changing state, including inherited dirty marking and file saving through the guarded dirty methods.
 */
public final class OwnerSavedData extends SavedData {
    /** Returns a detached bounded snapshot on the owning server thread; no state changes or I/O occur. */
    public java.util.List<AuditEntry> auditEntries() {
        requireOwningThread();
        return audit.snapshot();
    }

    /** Appends server-authored metadata on the owning thread after a confirmed action; zero retains history.
     * Invalid input rejects before mutation. Marks only this shard dirty; performs no simulation or I/O.
     */
    public void appendAudit(AuditEntry entry, int capacity) {
        requireOwningThread();
        if (audit.append(entry, capacity)) super.setDirty(true);
    }

    private final AuditRing audit = new AuditRing();
    private final Thread owningThread = Thread.currentThread();
    private final UUID ownerId;
    private @Nullable UUID defaultNetworkId;
    private final Map<UUID, ResourceFilterPreset> presets = new HashMap<>();
    private final Map<String, UUID> presetsByName = new HashMap<>();
    private long presetLibraryRevision;

    private OwnerSavedData(UUID ownerId, @Nullable UUID defaultNetworkId) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.defaultNetworkId = defaultNetworkId;
    }

    /**
     * Creates a dirty shard on its owning server thread; a null default means no preferred network.
     *
     * <p>UUIDs remain immutable, and no caller state, simulation, or I/O is involved. A missing owner throws
     * {@link NullPointerException}; the caller must validate any default network's ownership and existence.
     */
    public static OwnerSavedData create(UUID ownerId, @Nullable UUID defaultNetworkId) {
        OwnerSavedData data = new OwnerSavedData(ownerId, defaultNetworkId);
        data.setDirty();
        return data;
    }

    /**
     * Decodes a clean shard on its owning server thread without retaining or modifying the caller's tag.
     *
     * <p>No simulation or I/O occurs. Missing inputs throw {@link NullPointerException}; unsupported, malformed, or
     * identity-mismatched data throws {@link IllegalArgumentException} without inventing replacement state.
     */
    public static OwnerSavedData load(UUID expectedOwnerId, CompoundTag tag) {
        int version = ManagedDataNbt.readSchemaVersion(tag);
        if (version != 1 && version != 2 && version != 3 && version != 4)
            throw new IllegalArgumentException("Unsupported owner schema");
        ManagedDataNbt.validateSchemaAndFields(
                tag,
                version,
                version == 1
                        ? ManagedDataNbt.OWNER_V1_FIELDS
                        : version <= 3 ? ManagedDataNbt.OWNER_V3_FIELDS : ManagedDataNbt.OWNER_FIELDS);
        UUID ownerId = ManagedDataNbt.readIdentity(tag, "owner_id", expectedOwnerId);
        UUID defaultNetworkId =
                tag.contains("default_network_id") ? ManagedDataNbt.readUuid(tag, "default_network_id") : null;
        OwnerSavedData data = new OwnerSavedData(ownerId, defaultNetworkId);
        if (version >= 2) {
            ManagedDataNbt.requireType(tag, "preset_library_revision", net.minecraft.nbt.Tag.TAG_LONG);
            long revision = tag.getLong("preset_library_revision");
            if (revision < 0) throw new IllegalArgumentException("Negative preset library revision");
            Map<UUID, ResourceFilterPreset> decoded = new HashMap<>();
            if (version == 2) {
                for (ItemFilterPreset old : OwnerPresetNbt.decode(tag).values()) {
                    ResourceFilterPreset preset = ResourceFilterPresetNbt.migrate(old);
                    decoded.put(preset.id(), preset);
                }
            } else {
                ManagedDataNbt.requireType(tag, "filter_presets", Tag.TAG_LIST);
                ListTag rows = (ListTag) tag.get("filter_presets");
                if (rows.size() > ResourceFilterPreset.MAX_ENTRIES
                        || rows.getElementType() != Tag.TAG_COMPOUND
                                && !(rows.isEmpty() && rows.getElementType() == Tag.TAG_END))
                    throw new IllegalArgumentException("Invalid preset collection");
                java.util.Set<String> names = new HashSet<>();
                for (Tag row : rows) {
                    ResourceFilterPreset preset = ResourceFilterPresetNbt.decode((CompoundTag) row);
                    if (decoded.putIfAbsent(preset.id(), preset) != null
                            || !names.add(preset.name().uniquenessKey()))
                        throw new IllegalArgumentException("Duplicate preset identity or name");
                }
            }
            for (ResourceFilterPreset preset : decoded.values()) {
                data.presets.put(preset.id(), preset);
                data.presetsByName.put(preset.name().uniquenessKey(), preset.id());
            }
            data.presetLibraryRevision = revision;
        }
        if (version >= 4) {
            ManagedDataNbt.requireType(tag, "audit_entries", net.minecraft.nbt.Tag.TAG_LIST);
            data.audit.restore(AuditNbt.decode((net.minecraft.nbt.ListTag) tag.get("audit_entries")));
        }
        return data;
    }

    /** Reads the owning-thread library revision without mutation or simulation. */
    public long presetLibraryRevision() {
        requireOwningThread();
        return presetLibraryRevision;
    }
    /** Returns an unmodifiable live view of immutable presets; iteration remains on the owning server thread. */
    public Collection<ResourceFilterPreset> presets() {
        requireOwningThread();
        return Collections.unmodifiableCollection(presets.values());
    }
    /** Reads an immutable preset without mutation; wrong-thread access fails before lookup. */
    public Optional<ResourceFilterPreset> findPreset(UUID id) {
        requireOwningThread();
        return Optional.ofNullable(presets.get(Objects.requireNonNull(id, "id")));
    }
    /** Reads the name index without mutation; callers authorize owner-library visibility separately. */
    public Optional<ResourceFilterPreset> findPreset(ManagedName name) {
        requireOwningThread();
        return Optional.ofNullable(presets.get(
                presetsByName.get(Objects.requireNonNull(name, "name").uniquenessKey())));
    }
    /**
     * Atomically creates revision-zero or replaces exactly next-revision immutable presets on the owning thread.
     * The caller authorizes all references. Validation, quotas and both revision increments precede mutation;
     * failure preserves all state. Lowered quotas allow replacement with equal or fewer rules, but reject rule-count growth.
     * No simulation or I/O occurs; success marks this shard dirty.
     */
    public void putPreset(ResourceFilterPreset preset, long expectedLibraryRevision, int presetLimit, int ruleLimit) {
        requireOwningThread();
        Objects.requireNonNull(preset, "preset");
        validatePresetQuota(presetLimit);
        validatePresetQuota(ruleLimit);
        long nextLibraryRevision = nextPresetLibraryRevision(expectedLibraryRevision);
        ResourceFilterPreset existing = presets.get(preset.id());
        UUID sameName = presetsByName.get(preset.name().uniquenessKey());
        if (sameName != null && !sameName.equals(preset.id()))
            throw new IllegalArgumentException("Preset name already exists");
        if (existing == null) {
            if (preset.revision() != 0) throw new IllegalStateException("New preset requires revision zero");
            if (presets.size() >= 262144 || (presetLimit != -1 && presets.size() >= presetLimit))
                throw new IllegalArgumentException("Preset quota reached");
        } else if (existing.revision() == Long.MAX_VALUE || preset.revision() != existing.revision() + 1)
            throw new IllegalStateException("Stale or exhausted preset revision");
        if (ruleLimit != -1
                && preset.rules().size() > ruleLimit
                && (existing == null || preset.rules().size() > existing.rules().size()))
            throw new IllegalArgumentException("Rule quota reached");
        ResourceFilterPresetNbt.encode(preset);
        if (existing != null) presetsByName.remove(existing.name().uniquenessKey());
        presets.put(preset.id(), preset);
        presetsByName.put(preset.name().uniquenessKey(), preset.id());
        presetLibraryRevision = nextLibraryRevision;
        setDirty();
    }
    /** Trusted legacy server adapter; migrates before the same atomic full-preset admission. No client authority. */
    public void putPreset(ItemFilterPreset legacy, long expectedLibraryRevision, int presetLimit, int ruleLimit) {
        requireOwningThread();
        putPreset(ResourceFilterPresetNbt.migrate(legacy), expectedLibraryRevision, presetLimit, ruleLimit);
    }

    /** Deletes one existing preset atomically at the exact library revision; dangling network UUIDs remain untouched. */
    public void removePreset(UUID id, long expectedLibraryRevision) {
        requireOwningThread();
        Objects.requireNonNull(id, "id");
        long next = nextPresetLibraryRevision(expectedLibraryRevision);
        ResourceFilterPreset existing = presets.get(id);
        if (existing == null) throw new IllegalStateException("Missing preset");
        presets.remove(id);
        presetsByName.remove(existing.name().uniquenessKey());
        presetLibraryRevision = next;
        setDirty();
    }

    private long nextPresetLibraryRevision(long expected) {
        if (expected < 0 || expected != presetLibraryRevision || presetLibraryRevision == Long.MAX_VALUE)
            throw new IllegalStateException("Stale or exhausted library revision");
        return presetLibraryRevision + 1;
    }

    private static void validatePresetQuota(int quota) {
        if (quota < -1 || quota > 65535) throw new IllegalArgumentException("Invalid preset quota");
    }

    /** Returns the immutable owner ID without modification or simulation; rejects wrong-thread access. */
    public UUID ownerId() {
        requireOwningThread();
        return ownerId;
    }

    /** Returns the immutable optional pointer without modification or simulation; rejects wrong-thread access. */
    public Optional<UUID> defaultNetworkId() {
        requireOwningThread();
        return Optional.ofNullable(defaultNetworkId);
    }

    /**
     * Replaces or clears the pointer on the owning server thread and marks dirty only when its value changes.
     *
     * <p>No simulation or I/O occurs, and the immutable input may be shared. Wrong-thread access throws
     * {@link IllegalStateException} even for a no-op; callers must first validate network ownership and existence.
     */
    public void setDefaultNetwork(@Nullable UUID networkId) {
        requireOwningThread();
        if (!Objects.equals(defaultNetworkId, networkId)) {
            defaultNetworkId = networkId;
            setDirty();
        }
    }

    /**
     * Writes v3 fields and fresh UUID tags into caller-owned output on the owning server thread.
     *
     * <p>The optional field is removed when no default exists. Output is not retained; dirty state is unchanged,
     * and no simulation or disk I/O occurs. Wrong-thread access is rejected before mutation; null output throws
     * {@link NullPointerException}.
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireOwningThread();
        Objects.requireNonNull(tag, "tag");
        tag.put("audit_entries", AuditNbt.encode(audit.snapshot()));
        tag.putInt("schema_version", ManagedDataNbt.OWNER_SCHEMA_VERSION);
        tag.putUUID("owner_id", ownerId);
        tag.putLong("preset_library_revision", presetLibraryRevision);
        ListTag entries = new ListTag();
        presets.values().stream()
                .sorted(java.util.Comparator.comparing(ResourceFilterPreset::id))
                .forEach(preset -> entries.add(ResourceFilterPresetNbt.encode(preset)));
        tag.put("filter_presets", entries);
        if (defaultNetworkId == null) {
            tag.remove("default_network_id");
        } else {
            tag.putUUID("default_network_id", defaultNetworkId);
        }
        return tag;
    }

    /** Sets owned dirty state without simulation; rejects wrong-thread access before mutation. */
    @Override
    public void setDirty(boolean dirty) {
        requireOwningThread();
        super.setDirty(dirty);
    }

    /** Reads owned dirty state without mutation or simulation; rejects wrong-thread access. */
    @Override
    public boolean isDirty() {
        requireOwningThread();
        return super.isDirty();
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Owner SavedData accessed outside its owning server thread");
        }
    }
}
