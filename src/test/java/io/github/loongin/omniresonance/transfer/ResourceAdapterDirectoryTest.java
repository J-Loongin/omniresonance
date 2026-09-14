// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.junit.jupiter.api.Test;

class ResourceAdapterDirectoryTest {
    @Test
    void explicitRegistrationFreezesStableImmutableMetadata() {
        var directory = new ResourceAdapterDirectory(3);
        var item = new ResourceAdapterDirectory.Descriptor(ResourceTypes.ITEM, "item", 64);
        var fluid = new ResourceAdapterDirectory.Descriptor(ResourceTypes.FLUID, "mB", 1000);
        register(directory, item);
        register(directory, fluid);
        assertThrows(IllegalStateException.class, directory::types);
        assertThrows(IllegalArgumentException.class, () -> register(directory, item));
        directory.freeze();
        assertEquals(List.of(ResourceTypes.ITEM, ResourceTypes.FLUID), directory.types());
        assertSame(item, directory.find(ResourceTypes.ITEM).orElseThrow());
        assertTrue(directory.find(ResourceTypes.ENERGY).isEmpty());
        assertThrows(
                UnsupportedOperationException.class, () -> directory.types().clear());
        assertThrows(
                IllegalStateException.class,
                () -> register(directory, new ResourceAdapterDirectory.Descriptor(ResourceTypes.ENERGY, "FE", 10000)));
        assertEquals(64, directory.find(ResourceTypes.ITEM).orElseThrow().defaultBatchSize());
    }

    @Test
    void descriptorAndCapacityAreBoundedWithoutWorldOrNativeInitialization() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceAdapterDirectory(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceAdapterDirectory.Descriptor(
                        ResourceLocation.parse("example:" + "x".repeat(121)), "item", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceAdapterDirectory.Descriptor(ResourceTypes.ITEM, "item", 0));
        var directory = new ResourceAdapterDirectory(1);
        register(directory, new ResourceAdapterDirectory.Descriptor(ResourceTypes.ITEM, "item", 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> register(directory, new ResourceAdapterDirectory.Descriptor(ResourceTypes.FLUID, "mB", 1000)));
    }

    @Test
    void directoryCapacityUsesApprovedResourceTypeBoundary() {
        var directory = new ResourceAdapterDirectory(ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS);
        directory.freeze();
        assertTrue(directory.types().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceAdapterDirectory(ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS + 1));
    }

    private static void register(ResourceAdapterDirectory directory, ResourceAdapterDirectory.Descriptor descriptor) {
        directory.register(descriptor, Capabilities.ItemHandler.BLOCK, (handler, provider) -> {
            throw new AssertionError("Metadata operation invoked native factory");
        });
    }
}
