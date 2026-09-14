// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ResourceFilterCacheTest {
    private static final UUID OWNER = new UUID(0, 1), ROOT = new UUID(0, 2), OTHER = new UUID(0, 3);
    private static final ResourceLocation TAG = ResourceLocation.parse("test:members");

    private static ResourceFilterPreset preset(UUID id, List<ResourceFilterRule> rules) {
        return new ResourceFilterPreset(id, new ManagedName("Preset" + id.getLeastSignificantBits()), 0, rules);
    }

    private static ResourceFilterRule tag(long id, ResourceLocation tag) {
        return new ResourceFilterRule.Match(
                new UUID(1, id),
                ResourceTypes.ITEM,
                new ResourceFilterRule.TagSelector(tag),
                ComponentCondition.idOnly());
    }

    private static ResourceFilterCompiler.OwnerSnapshot owner(ResourceFilterPreset... presets) {
        Map<UUID, ResourceFilterPreset> map = new java.util.HashMap<>();
        for (var preset : presets) map.put(preset.id(), preset);
        return new ResourceFilterCompiler.OwnerSnapshot(OWNER, map);
    }

    private static int complete(ResourceFilterCache cache, ResourceFilterCache.Key key) {
        int units = 0;
        while (cache.view(key).compiled() == null) {
            int used = cache.advance(key, 7);
            assertTrue(used > 0 && used <= 7);
            units += used;
            assertTrue(units < 100000, "Compilation did not make bounded forward progress");
        }
        return units;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"oversized", "null_member", "long_member", "open", "has_next", "next"})
    void malformedSharedTagFailsEveryRootAndRecoversOnlyAfterGeneration(String failure) {
        AtomicInteger opens = new AtomicInteger(), consumed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean(true);
        var valid = ResourceLocation.parse("test:present");
        var omitted = ResourceLocation.parse("test:omitted");
        var snapshot = owner(preset(ROOT, List.of(tag(1, TAG))), preset(OTHER, List.of(tag(2, TAG))));
        try (var cache = new ResourceFilterCache(id -> snapshot, key -> {
            opens.incrementAndGet();
            if (!broken.get()) return List.of(valid, omitted).iterator();
            if (failure.equals("open")) throw new IllegalStateException("Fixture open failure");
            return new Iterator<>() {
                private int index;
                private boolean injected;
                private final int size = failure.equals("oversized") ? ResourceFilterPreset.MAX_ENTRIES + 1 : 2;

                public boolean hasNext() {
                    if (failure.equals("has_next") && index == 1 && !injected) {
                        injected = true;
                        index = size;
                        throw new IllegalStateException("Fixture hasNext failure");
                    }
                    return index < size;
                }

                public ResourceLocation next() {
                    index++;
                    consumed.incrementAndGet();
                    if (index < size) return valid;
                    return switch (failure) {
                        case "null_member" -> null;
                        case "long_member" -> ResourceLocation.fromNamespaceAndPath("test", "a".repeat(65535));
                        case "next" -> throw new IllegalStateException("Fixture next failure after consumption");
                        default -> omitted;
                    };
                }
            };
        })) {
            var first = cache.acquire(OWNER, ROOT);
            var second = cache.acquire(OWNER, OTHER);
            int units = 0;
            while (cache.view(first).compiled() == null) {
                int used = cache.advance(first, 256);
                assertTrue(used > 0 && used <= 256);
                units += used;
                assertTrue(units <= ResourceFilterPreset.MAX_ENTRIES + 32);
            }
            assertFalse(cache.view(first).compiled().valid());
            assertFalse(cache.view(first).compiled().allows(sample(omitted.toString()), FilterMode.BLACKLIST));
            int stoppedAt = consumed.get();
            complete(cache, second);
            assertFalse(cache.view(second).compiled().valid(), "Second root published a truncated shared tag");
            assertFalse(cache.view(second).compiled().allows(sample(omitted.toString()), FilterMode.BLACKLIST));
            assertEquals(stoppedAt, consumed.get(), "Poisoned shared iterator advanced again");
            assertEquals(1, opens.get(), "Poisoned shared tag reopened in the same generation");
            broken.set(false);
            assertEquals(0, cache.advance(first, 256));
            assertEquals(0, cache.advance(second, 256));
            assertFalse(cache.view(second).compiled().valid());
            Object firstToken = cache.token(first), secondToken = cache.token(second);
            cache.tagsChanged();
            assertNotSame(firstToken, cache.token(first));
            assertNotSame(secondToken, cache.token(second));
            complete(cache, first);
            complete(cache, second);
            assertEquals(2, opens.get());
            assertTrue(cache.view(first).compiled().valid());
            assertTrue(cache.view(second).compiled().valid());
            assertFalse(cache.view(second).compiled().allows(sample(omitted.toString()), FilterMode.BLACKLIST));
            assertTrue(cache.view(second).compiled().allows(sample("test:other"), FilterMode.BLACKLIST));
        }
    }

    @Test
    void sameRootAndOwnerShareOnePreparationAndRetireLastReference() {
        AtomicInteger loads = new AtomicInteger(), opens = new AtomicInteger(), members = new AtomicInteger();
        var snapshot = owner(preset(ROOT, List.of(tag(1, TAG))), preset(OTHER, List.of(tag(2, TAG))));
        try (var cache = new ResourceFilterCache(
                id -> {
                    loads.incrementAndGet();
                    return snapshot;
                },
                key -> {
                    opens.incrementAndGet();
                    return new Iterator<>() {
                        private int index;

                        public boolean hasNext() {
                            return index < 1000;
                        }

                        public ResourceLocation next() {
                            members.incrementAndGet();
                            return ResourceLocation.parse("test:member_" + index++);
                        }
                    };
                })) {
            var key = cache.acquire(OWNER, ROOT);
            for (int i = 0; i < 99; i++) assertEquals(key, cache.acquire(OWNER, ROOT));
            assertEquals(1, loads.get());
            assertEquals(1, cache.rootCount());
            assertNull(cache.view(key).compiled());
            assertEquals(0, cache.advance(key, 0));
            assertNull(cache.view(key).compiled());
            assertTrue(complete(cache, key) > 1000);
            var compiled = cache.view(key).compiled();
            assertEquals(1, opens.get());
            assertEquals(1000, members.get());
            var second = cache.acquire(OWNER, OTHER);
            complete(cache, second);
            assertEquals(1, loads.get());
            assertEquals(1, opens.get());
            assertEquals(1000, members.get());
            for (int i = 0; i < 99; i++) cache.release(key);
            assertSame(compiled, cache.view(key).compiled());
            cache.release(key);
            assertEquals(1, cache.rootCount());
            assertEquals(1, cache.tagCount());
            cache.release(second);
            assertEquals(0, cache.rootCount());
            assertEquals(0, cache.tagCount());
            assertEquals(0, cache.ownerCount());
            assertThrows(NullPointerException.class, () -> cache.token(key));
        }
    }

    @Test
    void largeReachableGraphAndTagsHaveLinearIncrementalWork() {
        for (int count : new int[] {10, 100, 1000}) {
            List<ResourceFilterRule> rules = new ArrayList<>();
            for (int i = 0; i < count; i++) rules.add(tag(i, ResourceLocation.parse("test:tag_" + i)));
            AtomicInteger opened = new AtomicInteger(), consumed = new AtomicInteger();
            var snapshot = owner(preset(ROOT, rules), preset(OTHER, List.of(tag(10001, TAG))));
            try (var cache = new ResourceFilterCache(id -> snapshot, key -> {
                opened.incrementAndGet();
                return new Iterator<>() {
                    private boolean more = true;

                    public boolean hasNext() {
                        return more;
                    }

                    public ResourceLocation next() {
                        more = false;
                        consumed.incrementAndGet();
                        return ResourceLocation.parse("test:member");
                    }
                };
            })) {
                var key = cache.acquire(OWNER, ROOT);
                int work = complete(cache, key);
                assertEquals(count, opened.get());
                assertEquals(count, consumed.get());
                assertEquals(count, cache.view(key).compiled().ruleCount());
                assertTrue(work <= 7 * count + 16, "Repeated root compilation exceeded linear work: " + work);
                assertThrows(IllegalArgumentException.class, () -> cache.advance(key, 257));
            }
        }
    }

    @Test
    void missingEmptyIndirectAndRecoveryInvalidateTokensBeforeReuse() {
        AtomicReference<List<ResourceLocation>> membership = new AtomicReference<>();
        var root = preset(ROOT, List.of(new ResourceFilterRule.Reference(new UUID(1, 1), OTHER)));
        var leaf = preset(OTHER, List.of(tag(2, TAG)));
        AtomicReference<ResourceFilterCompiler.OwnerSnapshot> snapshot = new AtomicReference<>(owner(root, leaf));
        try (var cache = new ResourceFilterCache(id -> snapshot.get(), key -> {
            var current = membership.get();
            return current == null ? null : current.iterator();
        })) {
            var key = cache.acquire(OWNER, ROOT);
            complete(cache, key);
            assertFalse(cache.view(key).compiled().valid());
            Object missingToken = cache.token(key);
            membership.set(List.of());
            cache.tagsChanged();
            assertNotSame(missingToken, cache.token(key));
            assertNull(cache.view(key).compiled());
            complete(cache, key);
            assertTrue(cache.view(key).compiled().valid());
            Object emptyToken = cache.token(key);
            snapshot.set(owner(root));
            cache.ownerChanged(OWNER);
            assertNotSame(emptyToken, cache.token(key));
            assertNull(cache.view(key).compiled());
            complete(cache, key);
            assertFalse(cache.view(key).compiled().valid());
            snapshot.set(owner(root, leaf));
            cache.ownerChanged(OWNER);
            complete(cache, key);
            assertTrue(cache.view(key).compiled().valid());
        }
    }

    @Test
    void failedLibraryIsNotEmptyOrReloadedUntilExplicitEventAndTokenChangesBeforeRead() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<ResourceFilterCache> reference = new AtomicReference<>();
        AtomicReference<ResourceFilterCache.Key> selected = new AtomicReference<>();
        AtomicReference<Object> oldToken = new AtomicReference<>();
        try (var cache = new ResourceFilterCache(
                id -> {
                    loads.incrementAndGet();
                    if (oldToken.get() != null)
                        assertNotSame(oldToken.get(), reference.get().token(selected.get()));
                    throw new IllegalStateException("Unavailable fixture");
                },
                key -> {
                    throw new AssertionError("Unavailable owner opened tags");
                })) {
            reference.set(cache);
            var key = cache.acquire(OWNER, ROOT);
            selected.set(key);
            complete(cache, key);
            for (int i = 0; i < 1000; i++) {
                assertFalse(cache.view(key).compiled().valid());
                assertFalse(cache.view(key).compiled().fastDecision(ResourceTypes.ITEM, FilterMode.BLACKLIST));
                assertEquals(0, cache.advance(key, 256));
            }
            assertEquals(1, loads.get());
            oldToken.set(cache.token(key));
            cache.ownerChanged(OWNER);
            assertEquals(2, loads.get());
            complete(cache, key);
            cache.close();
            assertEquals(0, cache.rootCount());
            assertEquals(0, cache.ownerCount());
            assertEquals(0, cache.tagCount());
            assertThrows(IllegalStateException.class, () -> cache.acquire(OWNER, ROOT));
        }
    }

    @Test
    void tagGenerationRetiresPartialSharedPreparationAndReopensOnlyCurrentMembers() {
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<List<ResourceLocation>> members =
                new AtomicReference<>(java.util.Collections.nCopies(1000, TAG));
        var snapshot = owner(preset(ROOT, List.of(tag(1, TAG))), preset(OTHER, List.of(tag(2, TAG))));
        try (var cache = new ResourceFilterCache(id -> snapshot, key -> {
            opened.incrementAndGet();
            return members.get().iterator();
        })) {
            var first = cache.acquire(OWNER, ROOT);
            var second = cache.acquire(OWNER, OTHER);
            cache.advance(first, 256);
            cache.advance(second, 256);
            assertNull(cache.view(first).compiled());
            assertEquals(1, opened.get());
            Object firstToken = cache.token(first), secondToken = cache.token(second);
            members.set(List.of(ResourceLocation.parse("test:replacement")));
            cache.tagsChanged();
            assertNotSame(firstToken, cache.token(first));
            assertNotSame(secondToken, cache.token(second));
            complete(cache, first);
            complete(cache, second);
            assertEquals(2, opened.get());
            assertTrue(cache.view(first).compiled().allows(sample("test:replacement"), FilterMode.WHITELIST));
            assertFalse(cache.view(first).compiled().allows(sample(TAG.toString()), FilterMode.WHITELIST));
        }
    }

    private static FilterResourceSample sample(String id) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", id);
        tag.put("components", new net.minecraft.nbt.CompoundTag());
        var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                ResourceTypes.ITEM, io.github.loongin.omniresonance.transfer.CanonicalResourceNbt.encode(tag));
        return FilterResourceSample.idOnly(() -> key);
    }

    @Test
    void freezeOwnsMemberListAndRetiresBuilderWithoutChangingMatching() {
        var builder = new ResourceFilterCompiler.TagSnapshot.Builder(3);
        var id = ResourceLocation.parse("test:some_member");
        builder.add(id);
        var snapshot = builder.freeze();
        assertSame(id, snapshot.members().getFirst());
        assertThrows(IllegalStateException.class, () -> builder.add(id));
        assertThrows(IllegalStateException.class, builder::freeze);
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.members().clear());
        var compiled = ResourceFilterCompiler.compile(
                ROOT, owner(preset(ROOT, List.of(tag(1, TAG)))), (type, tag) -> snapshot);
        assertTrue(compiled.valid());
        for (String candidate :
                new String[] {"test:some_member", "rest:some_member", "test:some_nember", "test:some_member_long"}) {
            var evaluation = compiled.evaluate(sample(candidate), FilterMode.WHITELIST);
            int work = 0;
            while (!evaluation.done()) {
                assertEquals(1, evaluation.step(1));
                work++;
                assertTrue(work < 100);
            }
            assertEquals(candidate.equals("test:some_member"), evaluation.allowed());
        }
        // Existing public constructor continues to detach caller mutation; no complete member ID strings retained.
        var mutable = new ArrayList<>(List.of(id));
        var legacy = new ResourceFilterCompiler.TagSnapshot(true, 3, mutable);
        mutable.clear();
        assertEquals(List.of(id), legacy.members());
    }
}
