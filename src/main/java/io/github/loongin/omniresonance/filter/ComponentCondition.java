// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable sampled conditions; no raw NBT editing entry point or mutable public output. */
public final class ComponentCondition {
    private final @Nullable ResourceLocation typeId;
    private final @Nullable FilterComponentBytes full;
    private final List<Entry> selected;

    private ComponentCondition(
            @Nullable ResourceLocation typeId, @Nullable FilterComponentBytes full, List<Entry> selected) {
        this.typeId = typeId;
        this.full = full;
        this.selected = List.copyOf(selected);
    }

    public static ComponentCondition idOnly() {
        return new ComponentCondition(null, null, List.of());
    }
    /** Pure non-hot-path capture from an already captured native identity; rejects FE and unsupported types. */
    public static ComponentCondition full(ResourceVariant sample) {
        FilterResourceSample value = sample(sample);
        return new ComponentCondition(value.typeId(), value.fullBytes(), List.of());
    }
    /** Structured component keys must exist on the captured sample. Values are detached, never client-authored. */
    public static ComponentCondition selected(ResourceVariant sample, Set<ResourceLocation> keys) {
        Objects.requireNonNull(keys);
        if (keys.size() > ResourceFilterPreset.MAX_ENTRIES) throw new IllegalArgumentException("Too many keys");
        FilterResourceSample value = sample(sample);
        List<Entry> entries = new ArrayList<>();
        for (ResourceLocation key : keys) {
            String name = key.toString();
            FilterComponentBytes bytes = value.componentBytes(name);
            if (bytes == null) throw new IllegalArgumentException("Missing sample component");
            entries.add(new Entry(name, bytes));
        }
        return new ComponentCondition(value.typeId(), null, entries);
    }

    /**
     * Pure caller-thread selection from this trusted server condition. Only existing values may be retained;
     * selecting absent keys or promoting an ID-only/selected condition to full fails without mutation.
     */
    public ComponentCondition selectTrusted(Mode mode, Set<ResourceLocation> keys) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(keys);
        if (keys.size() > ResourceFilterPreset.MAX_ENTRIES || mode != Mode.SELECTED && !keys.isEmpty())
            throw new IllegalArgumentException("Invalid selected keys");
        if (mode == Mode.ID_ONLY) return idOnly();
        if (isIdOnly()) throw new IllegalArgumentException("Native sample required");
        if (mode == Mode.FULL) {
            if (full == null) throw new IllegalArgumentException("Full native sample required");
            return this;
        }
        List<Entry> entries = new ArrayList<>();
        if (full != null) {
            CompoundTag tag = (CompoundTag) CanonicalResourceNbt.decode(full.copy());
            for (ResourceLocation key : keys) {
                Tag value = tag.get(key.toString());
                if (value == null) throw new IllegalArgumentException("Missing trusted component");
                entries.add(new Entry(key.toString(), new FilterComponentBytes(CanonicalResourceNbt.encode(value))));
            }
        } else {
            java.util.Map<String, FilterComponentBytes> available = new java.util.HashMap<>();
            for (Entry entry : selected) available.put(entry.key(), entry.bytes());
            for (ResourceLocation key : keys) {
                FilterComponentBytes value = available.get(key.toString());
                if (value == null) throw new IllegalArgumentException("Missing trusted component");
                entries.add(new Entry(key.toString(), value));
            }
        }
        return new ComponentCondition(typeId, null, entries);
    }

    private static FilterResourceSample sample(ResourceVariant sample) {
        FilterResourceSample value = FilterResourceSample.capture(sample);
        if (!value.typeId().equals(ResourceTypes.ITEM) && !value.typeId().equals(ResourceTypes.FLUID))
            throw new IllegalArgumentException("Unsupported component sample");
        return value;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ComponentCondition other
                && persistenceSnapshot().equals(other.persistenceSnapshot());
    }

    @Override
    public int hashCode() {
        return persistenceSnapshot().hashCode();
    }

    public boolean isIdOnly() {
        return typeId == null;
    }

    public @Nullable ResourceLocation typeId() {
        return typeId;
    }

    public enum Mode {
        ID_ONLY,
        FULL,
        SELECTED
    }

    /** Pure detached persistence snapshot; never authorizes client-supplied component data. */
    public PersistenceSnapshot persistenceSnapshot() {
        List<SelectedComponent> values = new ArrayList<>();
        for (Entry entry : selected)
            values.add(new SelectedComponent(
                    ResourceLocation.parse(entry.key()), entry.bytes().copy()));
        return new PersistenceSnapshot(
                isIdOnly() ? Mode.ID_ONLY : full != null ? Mode.FULL : Mode.SELECTED,
                typeId,
                full == null ? null : full.copy(),
                values);
    }

    /**
     * Pure caller-thread restoration from trusted server persistence only, with no authority mutation or simulation.
     * Copies validated bounded canonical data; rejects invalid modes/types/values. Client intents must instead use
     * server-authorized native samples and selected keys; successful restoration is never client authorization.
     */
    public static ComponentCondition fromPersistenceSnapshot(PersistenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        List<Entry> entries = new ArrayList<>();
        for (SelectedComponent component : snapshot.selected())
            entries.add(new Entry(component.key().toString(), new FilterComponentBytes(component.canonicalBytes())));
        return new ComponentCondition(
                snapshot.typeId(),
                snapshot.fullBytes() == null ? null : new FilterComponentBytes(snapshot.fullBytes()),
                entries);
    }

    /** Immutable owned canonical selected value. Construction rejects invalid keys, END and malformed NBT. */
    public record SelectedComponent(ResourceLocation key, byte[] canonicalBytes) {
        public SelectedComponent {
            Objects.requireNonNull(key);
            if (key.toString().length() > 65535) throw new IllegalArgumentException("Component key too long");
            canonicalBytes = canonical(canonicalBytes, false);
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SelectedComponent value
                    && key.equals(value.key)
                    && java.util.Arrays.equals(canonicalBytes, value.canonicalBytes);
        }

        @Override
        public int hashCode() {
            return 31 * key.hashCode() + java.util.Arrays.hashCode(canonicalBytes);
        }
    }

    /** Immutable bounded persistence value; all byte access is defensive and the selected collection is immutable. */
    public record PersistenceSnapshot(
            Mode mode,
            @Nullable ResourceLocation typeId,
            byte @Nullable [] fullBytes,
            List<SelectedComponent> selected) {
        public PersistenceSnapshot {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(selected);
            if (selected.size() > ResourceFilterPreset.MAX_ENTRIES)
                throw new IllegalArgumentException("Too many selected components");
            if (mode == Mode.ID_ONLY) {
                if (typeId != null || fullBytes != null || !selected.isEmpty())
                    throw new IllegalArgumentException("Mixed ID-only condition");
            } else {
                if (!ResourceTypes.ITEM.equals(typeId) && !ResourceTypes.FLUID.equals(typeId))
                    throw new IllegalArgumentException("Unsupported component type");
                if (mode == Mode.FULL) {
                    if (fullBytes == null || !selected.isEmpty())
                        throw new IllegalArgumentException("Mixed full condition");
                    fullBytes = canonical(fullBytes, true);
                } else if (fullBytes != null) throw new IllegalArgumentException("Mixed selected condition");
            }
            Set<ResourceLocation> keys = new HashSet<>();
            for (SelectedComponent value : selected)
                if (!keys.add(value.key())) throw new IllegalArgumentException("Duplicate selected key");
            List<SelectedComponent> sorted = new ArrayList<>(selected);
            sorted.sort(java.util.Comparator.comparing(value -> value.key().toString()));
            selected = List.copyOf(sorted);
        }

        @Override
        public byte @Nullable [] fullBytes() {
            return fullBytes == null ? null : fullBytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PersistenceSnapshot value
                    && mode == value.mode
                    && Objects.equals(typeId, value.typeId)
                    && java.util.Arrays.equals(fullBytes, value.fullBytes)
                    && selected.equals(value.selected);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(mode, typeId, selected) + java.util.Arrays.hashCode(fullBytes);
        }
    }

    private static byte[] canonical(byte[] bytes, boolean compound) {
        Tag tag = CanonicalResourceNbt.decode(Objects.requireNonNull(bytes));
        if (tag.getId() == Tag.TAG_END || compound && !(tag instanceof CompoundTag))
            throw new IllegalArgumentException("Invalid component value type");
        if (compound) {
            for (String key : ((CompoundTag) tag).getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (key.length() > 65535 || id == null || !id.toString().equals(key))
                    throw new IllegalArgumentException("Invalid full component key");
            }
        }
        return CanonicalResourceNbt.encode(tag);
    }

    Comparison compare(FilterResourceSample candidate) {
        return new Comparison(candidate);
    }

    private record Entry(String key, FilterComponentBytes bytes) {}

    final class Comparison {
        private final FilterResourceSample candidate;
        private int entry;
        private int offset;
        private @Nullable FilterComponentBytes expected;
        private @Nullable FilterComponentBytes actual;
        private boolean done;
        private boolean matches = true;

        private Comparison(FilterResourceSample candidate) {
            this.candidate = candidate;
            if (isIdOnly()) done = true;
            else if (!typeId.equals(candidate.typeId())) {
                done = true;
                matches = false;
            }
        }

        boolean done() {
            return done;
        }

        boolean matches() {
            return matches;
        }

        void step() {
            if (done) return;
            if (expected == null) {
                if (full != null) {
                    if (entry > 0) {
                        done = true;
                        return;
                    }
                    expected = full;
                    actual = candidate.fullBytes();
                } else {
                    if (entry == selected.size()) {
                        done = true;
                        return;
                    }
                    Entry selectedEntry = selected.get(entry);
                    expected = selectedEntry.bytes();
                    actual = candidate.componentBytes(selectedEntry.key());
                }
                if (actual == null || expected.size() != actual.size()) {
                    matches = false;
                    done = true;
                    return;
                }
            }
            if (offset < expected.size() && expected.at(offset) != actual.at(offset)) {
                matches = false;
                done = true;
                return;
            }
            if (++offset >= expected.size()) {
                entry++;
                offset = 0;
                expected = null;
                actual = null;
            }
        }
    }
}
