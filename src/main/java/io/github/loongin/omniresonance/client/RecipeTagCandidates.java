// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/** Bounded immutable hover metadata. Registry identity suffices for tags; component/resource identities are untouched. */
public final class RecipeTagCandidates {
    public static final int MAX_CANDIDATES = 256, MAX_TAGS = 1024;

    public record Candidate(String type, String id, List<String> tags) {
        public Candidate {
            if (!io.github.loongin.omniresonance.bootstrap.ResourceAdapters.registryType(ResourceLocation.parse(type)))
                throw new IllegalArgumentException("Unsupported tag type");
            identifier(id);
            if (tags.size() > MAX_TAGS) throw new IllegalArgumentException("Too many tags");
            for (var tag : tags) identifier(tag);
            tags = List.copyOf(new java.util.LinkedHashSet<>(tags));
        }
    }

    public record Common(String type, List<String> tags) {
        public Common {
            tags = List.copyOf(tags);
        }
    }

    private record Identity(String type, String id) {}

    private RecipeTagCandidates() {}

    public static Optional<Common> common(List<Candidate> candidates) {
        if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES * 2) return Optional.empty();
        var unique = new HashMap<Identity, HashSet<String>>();
        String type = candidates.getFirst().type();
        for (var candidate : candidates) {
            if (!type.equals(candidate.type())) return Optional.empty();
            var key = new Identity(type, candidate.id());
            var tags = new HashSet<>(candidate.tags());
            var existing = unique.get(key);
            if (existing == null) unique.put(key, tags);
            else existing.retainAll(tags);
            if (unique.size() > MAX_CANDIDATES) return Optional.empty();
        }
        HashSet<String> shared = null;
        for (var tags : unique.values()) {
            if (shared == null) shared = new HashSet<>(tags);
            else shared.retainAll(tags);
            if (shared.isEmpty()) return Optional.empty();
        }
        var sorted = new ArrayList<>(Objects.requireNonNull(shared));
        sorted.sort(String::compareTo);
        return Optional.of(new Common(type, sorted));
    }

    /** Reads client registry tags only, without capabilities or stack mutation; unsupported/oversized values return null. */
    public static @Nullable Candidate from(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty())
            return collect("minecraft:item", BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getTags());
        if (ingredient instanceof FluidStack stack && !stack.isEmpty())
            return collect("minecraft:fluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()), stack.getTags());
        var optional = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.recipeVariant(ingredient);
        if (optional == null) return null;
        var tags = optional.tags();
        if (tags.size() > MAX_TAGS) return null;
        return new Candidate(
                optional.key().typeId().toString(),
                optional.resourceId().toString(),
                tags.stream().map(Object::toString).toList());
    }

    private static @Nullable Candidate collect(
            String type, ResourceLocation id, java.util.stream.Stream<? extends TagKey<?>> stream) {
        try (stream) {
            if (id == null || id.toString().length() > 256) return null;
            var tags = new ArrayList<String>();
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                String tag = iterator.next().location().toString();
                if (tag.length() > 256 || tags.size() == MAX_TAGS) return null;
                tags.add(tag);
            }
            return new Candidate(type, id.toString(), tags);
        }
    }

    private static void identifier(String value) {
        if (value == null
                || value.length() > 256
                || !ResourceLocation.parse(value).toString().equals(value))
            throw new IllegalArgumentException("Invalid tag identity");
    }
}
