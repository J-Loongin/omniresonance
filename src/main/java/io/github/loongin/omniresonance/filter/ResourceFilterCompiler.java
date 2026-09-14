// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Pure internal compiler. Snapshots are caller-prepared off the hot path; cursors are single-caller owned.
 * No authority, simulation, persistence or cache mutation occurs. Invalid graphs fail closed. */
public final class ResourceFilterCompiler {
    private ResourceFilterCompiler() {}

    public record OwnerSnapshot(UUID ownerId, Map<UUID, ResourceFilterPreset> presets) {
        public OwnerSnapshot {
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(presets);
            if (presets.size() > ResourceFilterPreset.MAX_ENTRIES)
                throw new IllegalArgumentException("Too many presets");
            for (var entry : presets.entrySet())
                if (!entry.getKey().equals(entry.getValue().id()))
                    throw new IllegalArgumentException("Preset ID mismatch");
            presets = Map.copyOf(presets);
        }
    }

    public record TagKey(ResourceLocation typeId, ResourceLocation tagId) {}
    /** Immutable tag membership prepared before compilation; missing differs from present and empty. */
    public static final class TagSnapshot {
        private final boolean exists;
        private final long revision;
        private final List<ResourceLocation> members;

        public TagSnapshot(boolean exists, long revision, List<ResourceLocation> members) {
            Objects.requireNonNull(members);
            if (revision < 0 || members.size() > ResourceFilterPreset.MAX_ENTRIES || !exists && !members.isEmpty())
                throw new IllegalArgumentException("Invalid tag snapshot");
            for (ResourceLocation id : members) validateMember(id);
            this.members = List.copyOf(members);
            this.exists = exists;
            this.revision = revision;
        }

        private TagSnapshot(long revision, List<ResourceLocation> owned) {
            this.exists = true;
            this.revision = revision;
            this.members = Collections.unmodifiableList(owned);
        }

        private static void validateMember(ResourceLocation id) {
            Objects.requireNonNull(id);
            if ((long) id.getNamespace().length() + 1 + id.getPath().length() > 65535)
                throw new IllegalArgumentException("Tag member ID too long");
        }

        /** Single-caller owned incremental builder; freeze transfers ownership in constant work and retires writes. */
        public static final class Builder {
            private final long revision;
            private @Nullable List<ResourceLocation> owned = new ArrayList<>();

            public Builder(long revision) {
                if (revision < 0) throw new IllegalArgumentException("Negative revision");
                this.revision = revision;
            }

            public void add(ResourceLocation member) {
                if (owned == null) throw new IllegalStateException("Frozen tag builder");
                validateMember(member);
                if (owned.size() == ResourceFilterPreset.MAX_ENTRIES)
                    throw new IllegalArgumentException("Too many tag members");
                owned.add(member);
            }

            public TagSnapshot freeze() {
                if (owned == null) throw new IllegalStateException("Frozen tag builder");
                TagSnapshot result = new TagSnapshot(revision, owned);
                owned = null;
                return result;
            }
        }

        public boolean exists() {
            return exists;
        }

        public long revision() {
            return revision;
        }

        public List<ResourceLocation> members() {
            return members;
        }

        public static TagSnapshot missing(long revision) {
            return new TagSnapshot(false, revision, List.of());
        }
    }
    /** Must return an already immutable snapshot in bounded lookup work; exceptions invalidate the root. */
    public interface Tags {
        TagSnapshot resolve(ResourceLocation typeId, ResourceLocation tagId);
    }

    /** Explicit unavailable authority result; does not reinterpret a failed library read as an empty library. */
    public static Compiled unavailable(UUID ownerId) {
        return new Compiled(false, false, ownerId, Map.of(), Map.of(), Map.of(), 0);
    }

    public static Preparation prepare(@Nullable UUID selected, OwnerSnapshot owner, Tags tags) {
        return new Preparation(selected, owner, tags);
    }
    /** Non-hot-path convenience. Runtime integration must retain and drive the preparation cursor. */
    public static Compiled compile(@Nullable UUID selected, OwnerSnapshot owner, Tags tags) {
        Preparation p = prepare(selected, owner, tags);
        while (!p.done()) p.step(4096);
        return p.result();
    }

    private record CompiledRule(
            ResourceFilterRule.Match match, @Nullable TagSnapshot tag) {}

    private static final class RuleGroup {
        private final List<CompiledRule> rules = new ArrayList<>();
        private boolean needsComponents;
    }

    public static final class Preparation {
        private final @Nullable UUID selected;
        private final OwnerSnapshot owner;
        private final Tags tags;
        private final ArrayDeque<Frame> stack = new ArrayDeque<>();
        private final Map<UUID, Integer> heights = new HashMap<>();
        private final Map<UUID, Long> dependencies = new LinkedHashMap<>();
        private final Map<TagKey, Long> tagDependencies = new HashMap<>();
        private final Map<TagKey, TagSnapshot> resolvedTags = new HashMap<>();
        private final Map<ResourceLocation, RuleGroup> groups = new HashMap<>();
        private int ruleCount;
        private boolean started;
        private @Nullable Compiled result;

        private Preparation(@Nullable UUID selected, OwnerSnapshot owner, Tags tags) {
            this.selected = selected;
            this.owner = Objects.requireNonNull(owner);
            this.tags = Objects.requireNonNull(tags);
        }

        public boolean done() {
            return result != null;
        }

        public Compiled result() {
            if (result == null) throw new IllegalStateException("Incomplete compilation");
            return result;
        }
        /** One unit per graph/rule transition; each tag resolver call must obey the snapshot lookup contract. */
        public int step(int budget) {
            if (budget < 0) throw new IllegalArgumentException("Negative budget");
            int used = 0;
            while (used < budget && !done()) {
                used++;
                try {
                    advance();
                } catch (RuntimeException ex) {
                    finish(false);
                }
            }
            return used;
        }

        private void advance() {
            if (!started) {
                started = true;
                if (selected == null) {
                    finish(true);
                    return;
                }
                push(selected);
                return;
            }
            if (stack.isEmpty()) {
                finish(true);
                return;
            }
            Frame frame = stack.peek();
            if (frame.pending != null) {
                Integer height = heights.get(frame.pending);
                if (height == null) {
                    finish(false);
                    return;
                }
                frame.height = Math.max(frame.height, height + 1);
                frame.pending = null;
                if (frame.height > 8) finish(false);
                return;
            }
            if (frame.index == frame.preset.rules().size()) {
                heights.put(frame.preset.id(), frame.height);
                stack.pop();
                return;
            }
            ResourceFilterRule rule = frame.preset.rules().get(frame.index++);
            if (rule instanceof ResourceFilterRule.Reference reference) {
                Integer height = heights.get(reference.presetId());
                if (height != null) {
                    frame.height = Math.max(frame.height, height + 1);
                    if (frame.height > 8) finish(false);
                } else {
                    frame.pending = reference.presetId();
                    push(reference.presetId());
                }
            } else if (rule instanceof ResourceFilterRule.Match match) {
                TagSnapshot tag = null;
                if (match.selector() instanceof ResourceFilterRule.TagSelector selector) {
                    TagKey key = new TagKey(match.resourceTypeId(), selector.tagId());
                    tag = resolvedTags.get(key);
                    if (tag == null) {
                        // Preserve the attempted dependency even when lookup cannot provide a revision.
                        tagDependencies.put(key, -1L);
                        tag = Objects.requireNonNull(tags.resolve(key.typeId(), key.tagId()));
                        resolvedTags.put(key, tag);
                        tagDependencies.put(key, tag.revision());
                    }
                    if (!tag.exists()) {
                        finish(false);
                        return;
                    }
                }
                RuleGroup group = groups.get(match.resourceTypeId());
                if (group == null) {
                    group = new RuleGroup();
                    groups.put(match.resourceTypeId(), group);
                }
                group.needsComponents |= !match.components().isIdOnly();
                group.rules.add(new CompiledRule(match, tag));
                ruleCount++;
            }
        }

        private void push(UUID id) {
            if (dependencies.containsKey(id) || stack.size() > 8) {
                finish(false);
                return;
            }
            ResourceFilterPreset preset = owner.presets().get(id);
            if (preset == null) {
                dependencies.put(id, -1L);
                finish(false);
                return;
            }
            dependencies.put(id, preset.revision());
            stack.push(new Frame(preset));
        }

        private void finish(boolean valid) {
            result = new Compiled(
                    selected == null, valid, owner.ownerId(), dependencies, tagDependencies, groups, ruleCount);
        }

        private static final class Frame {
            private final ResourceFilterPreset preset;
            private int index;
            private int height;
            private @Nullable UUID pending;

            private Frame(ResourceFilterPreset preset) {
                this.preset = preset;
            }
        }
    }
    /** Immutable compiled reachable graph evidence, owned by one preparation. No global cache is retained. */
    public static final class Compiled {
        private final boolean unselected;
        private final boolean valid;
        private final UUID ownerId;
        private final Map<UUID, Long> dependencies;
        private final Map<TagKey, Long> tagDependencies;
        private final Map<ResourceLocation, RuleGroup> groups;
        private final int ruleCount;

        private Compiled(
                boolean unselected,
                boolean valid,
                UUID ownerId,
                Map<UUID, Long> dependencies,
                Map<TagKey, Long> tagDependencies,
                Map<ResourceLocation, RuleGroup> groups,
                int ruleCount) {
            this.ruleCount = ruleCount;
            this.unselected = unselected;
            this.valid = valid;
            this.ownerId = ownerId;
            this.dependencies = Collections.unmodifiableMap(dependencies);
            this.tagDependencies = Collections.unmodifiableMap(tagDependencies);
            this.groups = Collections.unmodifiableMap(groups);
        }

        public boolean valid() {
            return valid;
        }

        public UUID ownerId() {
            return ownerId;
        }

        public Map<UUID, Long> dependencies() {
            return dependencies;
        }

        /** Tag revision evidence; -1 means resolution failed before a revision was available.
         * Any event for that key must invalidate/retry this root, including recovery from failure. */
        public Map<TagKey, Long> tagDependencies() {
            return tagDependencies;
        }

        public int ruleCount() {
            return ruleCount;
        }

        /** Constant-time definitive decision without preparing a candidate. Null requires resumable evaluation.
         * This immutable pure lookup performs no simulation, decoding, cache or authority mutation. */
        public @Nullable Boolean fastDecision(ResourceLocation typeId, FilterMode mode) {
            Objects.requireNonNull(typeId);
            Objects.requireNonNull(mode);
            if (!valid || unselected) return valid;
            if (!groups.containsKey(typeId)) return mode == FilterMode.BLACKLIST;
            return null;
        }

        public boolean needsComponents(ResourceLocation typeId) {
            RuleGroup group = groups.get(Objects.requireNonNull(typeId));
            return valid && group != null && group.needsComponents;
        }
        /** Runtime admission evidence. Admit this bounded preparation under the soft CPU budget,
         * then recheck time; do not wait forever for a fixed smaller slice. */
        public FilterResourceSample.PreparationCost preparationCost(
                io.github.loongin.omniresonance.transfer.ResourceVariant candidate) {
            return FilterResourceSample.preparationCost(
                    candidate, needsComponents(candidate.key().typeId()));
        }
        /**
         * Calling server-thread preparation after admitting preparationCost. ID-only roots skip NBT for native
         * concrete variants; generic ResourceVariant keys still require bounded decoding.
         */
        public FilterResourceSample prepareCandidate(
                io.github.loongin.omniresonance.transfer.ResourceVariant candidate) {
            return needsComponents(candidate.key().typeId())
                    ? FilterResourceSample.capture(candidate)
                    : FilterResourceSample.idOnly(candidate);
        }

        public Evaluation evaluate(FilterResourceSample sample, FilterMode mode) {
            return new Evaluation(this, Objects.requireNonNull(sample), Objects.requireNonNull(mode));
        }
        /** Non-hot-path convenience; production hot paths must retain evaluate().step(budget). */
        public boolean allows(FilterResourceSample sample, FilterMode mode) {
            Evaluation e = evaluate(sample, mode);
            while (!e.done()) e.step(4096);
            return e.allowed();
        }
    }
    /** Resumable OR over unique reachable rules, with AND inside each rule. One char/byte per comparison unit. */
    public static final class Evaluation {
        private final List<CompiledRule> rules;
        private final FilterResourceSample sample;
        private final FilterMode mode;
        private int ruleIndex;
        private @Nullable CompiledRule rule;
        private @Nullable ResourceIdGlob.Evaluation glob;
        private @Nullable ComponentCondition.Comparison components;
        private int member;
        private int character;
        private boolean selectorMatched;
        private boolean done;
        private boolean allowed;

        private Evaluation(Compiled compiled, FilterResourceSample sample, FilterMode mode) {
            RuleGroup group = compiled.groups.get(sample.typeId());
            this.rules = group == null ? List.of() : group.rules;
            this.sample = sample;
            this.mode = mode;
            Boolean decision = compiled.fastDecision(sample.typeId(), mode);
            if (decision != null) {
                done = true;
                allowed = decision;
            }
        }

        public boolean done() {
            return done;
        }

        public boolean allowed() {
            if (!done) throw new IllegalStateException("Incomplete evaluation");
            return allowed;
        }

        public int step(int budget) {
            if (budget < 0) throw new IllegalArgumentException("Negative budget");
            int used = 0;
            while (used < budget && !done) {
                used++;
                advance();
            }
            return used;
        }

        private void advance() {
            if (rule == null) {
                if (ruleIndex == rules.size()) {
                    done = true;
                    allowed = mode == FilterMode.BLACKLIST;
                    return;
                }
                CompiledRule next = rules.get(ruleIndex++);
                rule = next;
                member = 0;
                character = 0;
                selectorMatched = false;
                if (rule.match().selector() instanceof ResourceFilterRule.Glob selector)
                    glob = selector.glob().evaluate(sample.resourceId());
                return;
            }
            if (!selectorMatched) {
                if (rule.match().selector() instanceof ResourceFilterRule.WholeType) selectorMatched = true;
                else if (glob != null) {
                    glob.step(1);
                    if (glob.done()) {
                        if (glob.matches()) selectorMatched = true;
                        else nextRule();
                    }
                } else {
                    ResourceLocation expectedId = null;
                    String expected = null;
                    if (rule.tag() != null) {
                        if (member == rule.tag().members().size()) {
                            nextRule();
                            return;
                        }
                        expectedId = rule.tag().members().get(member);
                    } else expected = ((ResourceFilterRule.Exact) rule.match().selector()).text();
                    String actual = sample.resourceId();
                    int length = expectedId == null
                            ? expected.length()
                            : expectedId.getNamespace().length()
                                    + 1
                                    + expectedId.getPath().length();
                    if (length != actual.length()
                            || character < length
                                    && (expectedId == null
                                                    ? expected.charAt(character)
                                                    : idCharacter(expectedId, character))
                                            != actual.charAt(character)) {
                        if (rule.tag() == null) nextRule();
                        else {
                            member++;
                            character = 0;
                        }
                    } else if (character++ == length) selectorMatched = true;
                }
                return;
            }
            if (components == null) {
                components = rule.match().components().compare(sample);
                return;
            }
            components.step();
            if (components.done()) {
                if (components.matches()) {
                    done = true;
                    allowed = mode == FilterMode.WHITELIST;
                } else nextRule();
            }
        }

        private static char idCharacter(ResourceLocation id, int index) {
            int namespace = id.getNamespace().length();
            if (index < namespace) return id.getNamespace().charAt(index);
            return index == namespace ? ':' : id.getPath().charAt(index - namespace - 1);
        }

        private void nextRule() {
            rule = null;
            glob = null;
            components = null;
        }
    }
}
