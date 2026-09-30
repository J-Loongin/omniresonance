// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable, thread-independent, nonpersistent face/type advertisement. Owns no inventory or native capability;
 * all operations are pure and perform no simulation, discovery or world access. Packing is private and may change
 * without a saved-data migration. This catalogue names supported connection APIs, not all possible resource types. */
public final class PipeConnections {
    /** Named native capability categories with stable resource identities and no optional API dependencies. */
    public enum Type {
        ITEM(ResourceTypes.ITEM),
        FLUID(ResourceTypes.FLUID),
        ENERGY(ResourceTypes.ENERGY),
        CHEMICAL(ResourceTypes.CHEMICAL),
        SOURCE(ResourceTypes.SOURCE),
        SOUL(ResourceTypes.SOUL);

        private final ResourceLocation resourceType;

        Type(ResourceLocation resourceType) {
            this.resourceType = resourceType;
        }

        public ResourceLocation resourceType() {
            return resourceType;
        }
    }

    private static final int FACE_COUNT = Direction.values().length;
    private static final int ALL_FACES = (1 << FACE_COUNT) - 1;
    private static final Type[] TYPES = Type.values();

    static {
        if (TYPES.length > Long.SIZE / FACE_COUNT)
            throw new IllegalStateException("Connection catalogue exceeds internal packing capacity");
    }

    public static final PipeConnections NONE = new PipeConnections(0);
    private final long bits;

    private PipeConnections(long bits) {
        this.bits = bits;
    }

    /** Selects only supported connection types included by the scope. Rejects faces outside the six native sides. */
    public static PipeConnections forScope(ResourceScope scope, int faces) {
        Objects.requireNonNull(scope);
        validateFaces(faces);
        long bits = 0;
        for (Type type : TYPES) if (scope.includes(type.resourceType)) bits |= pack(type, faces);
        return ofBits(bits);
    }

    /** One typed side, useful to compose advertisements without exposing their binary representation. */
    public static PipeConnections only(Type type, Direction face) {
        return ofBits(pack(
                Objects.requireNonNull(type), 1 << Objects.requireNonNull(face).get3DDataValue()));
    }

    /** Immutable union for multiple channel configurations; does not select a channel or grant storage access. */
    public PipeConnections union(PipeConnections other) {
        long combined = bits | Objects.requireNonNull(other).bits;
        return combined == bits ? this : ofBits(combined);
    }

    /** Pure membership query; an unsided capability request never grants a working face. */
    public boolean allows(Type type, @Nullable Direction face) {
        Objects.requireNonNull(type);
        return face != null && (bits & pack(type, 1 << face.get3DDataValue())) != 0;
    }

    public boolean isEmpty() {
        return bits == 0;
    }

    private static long pack(Type type, int faces) {
        return (long) faces << (type.ordinal() * FACE_COUNT);
    }

    private static PipeConnections ofBits(long bits) {
        return bits == 0 ? NONE : new PipeConnections(bits);
    }

    private static void validateFaces(int faces) {
        if (faces < 0 || (faces & ~ALL_FACES) != 0) throw new IllegalArgumentException("Invalid working faces");
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PipeConnections value && bits == value.bits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bits);
    }
}
