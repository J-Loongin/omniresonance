// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.transfer.ResourceDirectScheduler;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Server-thread owned cache bounded by current owner/root references and their reachable rules/tags.
 * Last reference retires its root, tag references and owner snapshot. No LRU evicts pending work.
 * Library loading occurs only at first reference or an explicit edit, never during matching or token lookup. */
public final class ResourceFilterCache implements AutoCloseable {
    /** Opens immutable-generation native membership on the server thread. Null means absent, empty means present.
     * Opening and each iterator operation must be bounded; no world scan or eager member collection is allowed. */
    public interface TagSource {
        @Nullable
        Iterator<ResourceLocation> open(ResourceFilterCompiler.TagKey key);
    }

    public record Key(UUID ownerId, UUID presetId) {}

    private final Function<UUID, ResourceFilterCompiler.@Nullable OwnerSnapshot> libraries;
    private final TagSource source;
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final Map<Key, Root> roots = new HashMap<>();
    private final Map<ResourceFilterCompiler.TagKey, Tag> tags = new HashMap<>();
    private long generation;
    private boolean closed;

    public ResourceFilterCache(
            Function<UUID, ResourceFilterCompiler.@Nullable OwnerSnapshot> libraries, TagSource source) {
        this.libraries = Objects.requireNonNull(libraries);
        this.source = Objects.requireNonNull(source);
    }

    /** Adds one current published reference; failure is retained as unavailable until an explicit edit. */
    public Key acquire(UUID ownerId, UUID presetId) {
        if (closed) throw new IllegalStateException("Closed filter cache");
        Key key = new Key(ownerId, presetId);
        Root root = roots.get(key);
        if (root == null) {
            var builtin = BuiltInPresets.find(presetId);
            Owner owner = builtin == null
                    ? owners.computeIfAbsent(ownerId, id -> new Owner(load(id)))
                    : new Owner(new ResourceFilterCompiler.OwnerSnapshot(ownerId, Map.of(presetId, builtin.preset())));
            root = new Root(key, owner);
            roots.put(key, root);
            owner.keys.add(key);
        }
        root.references++;
        return key;
    }

    public void release(Key key) {
        Root root = roots.get(key);
        if (root == null) return;
        if (--root.references != 0) return;
        roots.remove(key);
        releaseTags(root);
        root.owner.keys.remove(key);
        if (root.owner.keys.isEmpty()) owners.remove(key.ownerId(), root.owner);
    }

    public ResourceDirectScheduler.FilterView view(Key key) {
        Root root = required(key);
        return new ResourceDirectScheduler.FilterView(root.token, root.compiled);
    }

    public Object token(Key key) {
        return required(key).token;
    }

    /** Invalidates every previous permit before reading the changed library, then resets only its roots. */
    public void ownerChanged(UUID ownerId) {
        Owner owner = owners.get(ownerId);
        if (owner == null) return;
        for (Key key : owner.keys) required(key).token = new Object();
        owner.snapshot = load(ownerId);
        for (Key key : owner.keys) reset(required(key));
    }

    /** Server tick entry, after the off-thread event only signals a generation. No stale compiled permit survives. */
    public void tagsChanged() {
        generation++;
        for (Root root : roots.values()) root.token = new Object();
        for (Root root : roots.values()) reset(root);
    }

    /** Advances metadata, unique tag members and finally compilation; at most 256 measured units per invocation. */
    public int advance(Key key, int workUnits) {
        if (workUnits < 0 || workUnits > 256) throw new IllegalArgumentException("Invalid filter slice");
        Root root = required(key);
        int used = 0;
        while (used < workUnits && root.compiled == null) {
            used++;
            try {
                step(root);
            } catch (RuntimeException failure) {
                root.compiled = ResourceFilterCompiler.unavailable(key.ownerId());
            }
        }
        return used;
    }

    public int rootCount() {
        return roots.size();
    }

    public int ownerCount() {
        return owners.size();
    }

    public int tagCount() {
        return tags.size();
    }

    @Override
    public void close() {
        closed = true;
        roots.clear();
        owners.clear();
        tags.clear();
    }

    private void step(Root root) {
        if (root.owner.snapshot == null) {
            root.compiled = ResourceFilterCompiler.unavailable(root.key.ownerId());
            return;
        }
        if (!root.discovered) {
            if (root.rules != null && root.rules.hasNext()) {
                ResourceFilterRule rule = root.rules.next();
                if (rule instanceof ResourceFilterRule.Reference reference) {
                    if (root.visited.add(reference.presetId())) root.pending.add(reference.presetId());
                } else if (rule instanceof ResourceFilterRule.Match match
                        && match.selector() instanceof ResourceFilterRule.TagSelector selector) {
                    var key = new ResourceFilterCompiler.TagKey(match.resourceTypeId(), selector.tagId());
                    if (root.tagKeys.add(key)) {
                        Tag tag = tags.computeIfAbsent(key, ignored -> new Tag());
                        tag.references++;
                        root.pendingTags.add(key);
                    }
                }
                return;
            }
            UUID next = root.pending.poll();
            if (next != null) {
                ResourceFilterPreset preset = root.owner.snapshot.presets().get(next);
                root.rules = preset == null ? null : preset.rules().iterator();
                return;
            }
            root.rules = null;
            root.discovered = true;
            return;
        }
        var key = root.pendingTags.peek();
        if (key != null) {
            Tag tag = tags.get(key);
            if (tag.failed) {
                root.compiled = ResourceFilterCompiler.unavailable(root.key.ownerId());
                return;
            }
            try {
                if (tag.snapshot != null) root.pendingTags.remove();
                else if (!tag.opened) {
                    tag.opened = true;
                    tag.members = source.open(key);
                    if (tag.members == null) tag.snapshot = ResourceFilterCompiler.TagSnapshot.missing(generation);
                    else tag.builder = new ResourceFilterCompiler.TagSnapshot.Builder(generation);
                } else if (tag.members.hasNext()) tag.builder.add(tag.members.next());
                else {
                    tag.snapshot = tag.builder.freeze();
                    tag.builder = null;
                    tag.members = null;
                }
            } catch (RuntimeException failure) {
                // Native iteration can consume a member before validation fails. Poison the shared generation,
                // not just this root: no other reference may freeze or resume a truncated member list.
                tag.failed = true;
                tag.snapshot = null;
                tag.builder = null;
                tag.members = null;
                throw failure;
            }
            return;
        }
        if (root.preparation == null)
            root.preparation = ResourceFilterCompiler.prepare(
                    root.key.presetId(),
                    root.owner.snapshot,
                    (type, id) ->
                            Objects.requireNonNull(tags.get(new ResourceFilterCompiler.TagKey(type, id)).snapshot));
        else {
            root.preparation.step(1);
            if (root.preparation.done()) root.compiled = root.preparation.result();
        }
    }

    private @Nullable ResourceFilterCompiler.OwnerSnapshot load(UUID id) {
        try {
            var snapshot = libraries.apply(id);
            if (snapshot != null && !snapshot.ownerId().equals(id)) return null;
            return snapshot;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Root required(Key key) {
        return Objects.requireNonNull(roots.get(key), "Retired filter reference");
    }

    private void releaseTags(Root root) {
        for (var key : root.tagKeys) {
            Tag tag = tags.get(key);
            if (--tag.references == 0) tags.remove(key);
        }
        root.tagKeys.clear();
    }

    private void reset(Root root) {
        releaseTags(root);
        root.compiled = null;
        root.preparation = null;
        root.rules = null;
        root.discovered = false;
        root.visited.clear();
        root.pending.clear();
        root.pendingTags.clear();
        root.visited.add(root.key.presetId());
        root.pending.add(root.key.presetId());
    }

    private static final class Owner {
        private @Nullable ResourceFilterCompiler.OwnerSnapshot snapshot;
        private final Set<Key> keys = new HashSet<>();

        private Owner(@Nullable ResourceFilterCompiler.OwnerSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class Root {
        private final Key key;
        private final Owner owner;
        private final Set<UUID> visited = new HashSet<>();
        private final ArrayDeque<UUID> pending = new ArrayDeque<>();
        private final Set<ResourceFilterCompiler.TagKey> tagKeys = new HashSet<>();
        private final ArrayDeque<ResourceFilterCompiler.TagKey> pendingTags = new ArrayDeque<>();
        private @Nullable Iterator<ResourceFilterRule> rules;
        private @Nullable ResourceFilterCompiler.Preparation preparation;
        private @Nullable ResourceFilterCompiler.Compiled compiled;
        private Object token = new Object();
        private int references;
        private boolean discovered;

        private Root(Key key, Owner owner) {
            this.key = key;
            this.owner = owner;
            visited.add(key.presetId());
            pending.add(key.presetId());
        }
    }

    private static final class Tag {
        private int references;
        private boolean opened;
        private boolean failed;
        private @Nullable Iterator<ResourceLocation> members;
        private @Nullable ResourceFilterCompiler.TagSnapshot.Builder builder;
        private @Nullable ResourceFilterCompiler.TagSnapshot snapshot;
    }
}
