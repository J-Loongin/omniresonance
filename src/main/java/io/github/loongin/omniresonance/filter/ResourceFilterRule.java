// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable internal rule values; pure construction rejects invalid combinations without mutation. */
public sealed interface ResourceFilterRule {
    UUID id();

    record Reference(UUID id, UUID presetId) implements ResourceFilterRule {
        public Reference {
            Objects.requireNonNull(id);
            Objects.requireNonNull(presetId);
        }
    }

    record Match(UUID id, ResourceLocation resourceTypeId, Selector selector, ComponentCondition components)
            implements ResourceFilterRule {
        public Match {
            Objects.requireNonNull(id);
            Objects.requireNonNull(resourceTypeId);
            Objects.requireNonNull(selector);
            Objects.requireNonNull(components);
            if (resourceTypeId.toString().length() > 128) throw new IllegalArgumentException("Type ID too long");
            if (ResourceTypes.scalar(resourceTypeId) && (!(selector instanceof WholeType) || !components.isIdOnly()))
                throw new IllegalArgumentException("Scalar resources support only whole type");
            if (!components.isIdOnly() && !resourceTypeId.equals(components.typeId()))
                throw new IllegalArgumentException("Component type mismatch");
        }
    }

    sealed interface Selector permits WholeType, Exact, TagSelector, Glob {
        static Selector wholeType() {
            return new WholeType();
        }

        static Selector exact(ResourceLocation id) {
            return new Exact(id);
        }

        static Selector tag(ResourceLocation id) {
            return new TagSelector(id);
        }

        static Selector glob(String pattern) {
            return new Glob(new ResourceIdGlob(pattern));
        }
    }

    record WholeType() implements Selector {}

    final class Exact implements Selector {
        private final ResourceLocation resourceId;
        private final String text;

        public Exact(ResourceLocation resourceId) {
            checkId(resourceId);
            this.resourceId = resourceId;
            this.text = resourceId.toString();
        }

        public ResourceLocation resourceId() {
            return resourceId;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Exact other && resourceId.equals(other.resourceId);
        }

        @Override
        public int hashCode() {
            return resourceId.hashCode();
        }

        String text() {
            return text;
        }
    }

    record TagSelector(ResourceLocation tagId) implements Selector {
        public TagSelector {
            checkId(tagId);
        }
    }

    record Glob(ResourceIdGlob glob) implements Selector {
        public Glob {
            Objects.requireNonNull(glob);
        }
    }

    private static void checkId(ResourceLocation id) {
        Objects.requireNonNull(id);
        if (id.toString().length() > 65535) throw new IllegalArgumentException("ID too long");
    }
}
