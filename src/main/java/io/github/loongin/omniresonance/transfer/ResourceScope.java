// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable thread-safe resource selection that owns its detached custom set. It performs no simulation, world
 * access, registration, or mutation; malformed or over-limit pure values are rejected here, while adapter admission
 * is validated by the authority boundary.
 */
public final class ResourceScope {
    public static final int MAXIMUM_RESOURCE_TYPE_IDS = 262144;
    public static final int MAXIMUM_RESOURCE_TYPE_ID_BYTES = 128;

    /** Stable pure scope discriminator; enum values own no external state and perform no resource operations. */
    public enum Kind {
        ALL,
        CUSTOM_SET
    }

    private static final ResourceScope ALL = new ResourceScope(Kind.ALL, Set.of());

    private final Kind kind;
    private final Set<ResourceLocation> resourceTypeIds;

    private ResourceScope(Kind kind, Set<ResourceLocation> resourceTypeIds) {
        this.kind = kind;
        this.resourceTypeIds = resourceTypeIds;
    }

    /** Returns the shared immutable ALL value without accessing or discovering registered adapters. */
    public static ResourceScope all() {
        return ALL;
    }

    /**
     * Returns an immutable detached custom selection. This pure factory is thread-safe and performs no simulation or
     * authority mutation; it rejects null, empty, over-limit, null-entry, or overlong-ID input.
     */
    public static ResourceScope customSet(Collection<ResourceLocation> resourceTypeIds) {
        Objects.requireNonNull(resourceTypeIds, "resourceTypeIds");
        if (resourceTypeIds.isEmpty() || resourceTypeIds.size() > MAXIMUM_RESOURCE_TYPE_IDS) {
            throw new IllegalArgumentException("Custom resource scope must have a bounded non-empty selection");
        }
        for (ResourceLocation resourceTypeId : resourceTypeIds) validateResourceTypeId(resourceTypeId);
        Set<ResourceLocation> detached = Set.copyOf(resourceTypeIds);
        if (detached.isEmpty() || detached.size() > MAXIMUM_RESOURCE_TYPE_IDS) {
            throw new IllegalArgumentException("Custom resource scope must have a bounded non-empty selection");
        }
        return new ResourceScope(Kind.CUSTOM_SET, detached);
    }

    static void validateResourceTypeId(ResourceLocation resourceTypeId) {
        Objects.requireNonNull(resourceTypeId, "resourceTypeId");
        if (resourceTypeId.toString().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RESOURCE_TYPE_ID_BYTES) {
            throw new IllegalArgumentException("Resource type ID exceeds its UTF-8 boundary");
        }
    }

    public Kind kind() {
        return kind;
    }

    public Set<ResourceLocation> resourceTypeIds() {
        return resourceTypeIds;
    }

    public boolean includes(ResourceLocation resourceTypeId) {
        Objects.requireNonNull(resourceTypeId, "resourceTypeId");
        return kind == Kind.ALL || resourceTypeIds.contains(resourceTypeId);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ResourceScope scope
                        && kind == scope.kind
                        && resourceTypeIds.equals(scope.resourceTypeIds);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + resourceTypeIds.hashCode();
    }

    @Override
    public String toString() {
        return kind == Kind.ALL ? "ResourceScope[ALL]" : "ResourceScope[CUSTOM_SET=" + resourceTypeIds + "]";
    }
}
