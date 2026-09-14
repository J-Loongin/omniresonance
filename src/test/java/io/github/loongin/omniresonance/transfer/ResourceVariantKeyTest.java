// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ResourceVariantKeyTest {
    @Test
    void keyOwnsItsBytesAndUsesContentEquality() {
        byte[] input = {1, 2};
        ResourceLocation id = ResourceLocation.parse("omniresonance:item");
        ResourceVariantKey key = new ResourceVariantKey(id, input);
        input[0] = 9;
        byte[] output = key.canonicalBytes();
        output[0] = 9;
        ResourceVariantKey equal = new ResourceVariantKey(id, new byte[] {1, 2});
        assertEquals(equal, key);
        assertEquals(equal.hashCode(), key.hashCode());
        assertEquals(id, key.typeId());
        assertNotEquals(key, new ResourceVariantKey(ResourceLocation.parse("omniresonance:fluid"), new byte[] {1, 2}));
        assertEquals(id.toString().length() + 2, key.encodedSizeBytes());
        assertThrows(IllegalArgumentException.class, () -> new ResourceVariantKey(id, new byte[262145]));
    }

    @Test
    void typeIdentifierHasAnIndependent128ByteBoundary() {
        ResourceLocation accepted = ResourceLocation.parse("a:" + "b".repeat(126));
        ResourceVariantKey key = new ResourceVariantKey(accepted, new byte[] {1});
        assertEquals(129, key.encodedSizeBytes());
        ResourceLocation rejected = ResourceLocation.parse("a:" + "b".repeat(127));
        assertThrows(IllegalArgumentException.class, () -> new ResourceVariantKey(rejected, new byte[] {1}));
    }

    @Test
    void canonicalPayloadHasItsFullIndependent262144ByteBoundary() {
        ResourceLocation id = ResourceLocation.parse("minecraft:item");
        ResourceVariantKey key = new ResourceVariantKey(id, new byte[262144]);
        assertEquals(262144, key.canonicalBytes().length);
        assertEquals(262158, key.encodedSizeBytes());
        assertThrows(IllegalArgumentException.class, () -> new ResourceVariantKey(id, new byte[262145]));
    }

    @Test
    void opaqueEnvelopeAllowsEmptyPayloadWithoutAllowingEmptyNbt() {
        ResourceLocation energyId = ResourceLocation.parse("neoforge:energy");
        ResourceVariantKey energy = new ResourceVariantKey(energyId, new byte[0]);
        ResourceVariantKey unknown = new ResourceVariantKey(ResourceLocation.parse("unknown:resource"), new byte[0]);
        assertEquals(15, energy.encodedSizeBytes());
        assertEquals(0, energy.canonicalBytes().length);
        assertEquals(energy, new ResourceVariantKey(energyId, new byte[0]));
        assertNotEquals(energy, unknown);
        assertThrows(IllegalArgumentException.class, () -> CanonicalResourceNbt.decode(new byte[0]));
    }
}
