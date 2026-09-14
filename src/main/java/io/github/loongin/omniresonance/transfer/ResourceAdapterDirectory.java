// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * Initialization-owned bounded directory, explicitly registered then frozen before publication.
 * Frozen metadata is immutable and thread-safe; factories belong exclusively to the server thread.
 * No registration or metadata lookup accesses a world, simulates, or transfers resources.
 */
public final class ResourceAdapterDirectory {
    /** Pure immutable metadata; rejects IDs over 128 UTF-8 bytes and nonpositive batch sizes. */
    public record Descriptor(ResourceLocation typeId, String unit, long defaultBatchSize) {
        public Descriptor {
            Objects.requireNonNull(typeId);
            Objects.requireNonNull(unit);
            if (typeId.toString().getBytes(StandardCharsets.UTF_8).length > 128
                    || unit.isBlank()
                    || unit.length() > 32
                    || defaultBatchSize <= 0) throw new IllegalArgumentException("Invalid resource descriptor");
        }
    }

    private final int capacity;
    private final Map<ResourceLocation, Descriptor> descriptors = new LinkedHashMap<>();
    private final Map<ResourceLocation, Binding<?>> bindings = new LinkedHashMap<>();
    private @Nullable List<ResourceLocation> types;

    public ResourceAdapterDirectory(int capacity) {
        if (capacity <= 0 || capacity > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Invalid directory capacity");
        this.capacity = capacity;
    }

    /** Registers metadata during initialization; duplicate IDs, capacity overflow, and frozen writes fail. */
    private void register(Descriptor descriptor) {
        if (types != null) throw new IllegalStateException("Directory is frozen");
        Objects.requireNonNull(descriptor);
        if (descriptors.containsKey(descriptor.typeId()) || descriptors.size() >= capacity)
            throw new IllegalArgumentException("Duplicate resource type or directory capacity exceeded");
        descriptors.put(descriptor.typeId(), descriptor);
    }

    /** Registers a native binding without invoking its factory; factory must only wrap the borrowed handler. */
    public <T> void register(
            Descriptor descriptor,
            BlockCapability<T, Direction> capability,
            BiFunction<T, HolderLookup.Provider, ResourcePort> factory) {
        Objects.requireNonNull(capability);
        Objects.requireNonNull(factory);
        register(descriptor);
        bindings.put(descriptor.typeId(), new Binding<>(capability, factory));
    }

    /** Freezes insertion order; repeated freeze is harmless and does not invoke factories. */
    public void freeze() {
        if (types == null) types = List.copyOf(descriptors.keySet());
    }

    /** Immutable ALL traversal for this initialization lifecycle; never persisted as a type snapshot. */
    public List<ResourceLocation> types() {
        if (types == null) throw new IllegalStateException("Directory must be frozen");
        return types;
    }

    /** Pure frozen metadata lookup; unknown types remain absent. */
    public Optional<Descriptor> find(ResourceLocation typeId) {
        types();
        return Optional.ofNullable(descriptors.get(Objects.requireNonNull(typeId)));
    }

    /** Explicitly creates and freezes the three built-in native bindings; performs no world access. */
    public static ResourceAdapterDirectory nativeDefaults() {
        var directory = new ResourceAdapterDirectory(3);
        registerNative(directory);
        directory.freeze();
        return directory;
    }

    /** Adds built-ins to a caller-owned initialization directory, allowing subsequent explicit additions. */
    public static void registerNative(ResourceAdapterDirectory directory) {
        directory.register(
                new Descriptor(ResourceTypes.ITEM, "item", ResourceTypes.defaultExactBatchSize(ResourceTypes.ITEM)),
                Capabilities.ItemHandler.BLOCK,
                ItemResourcePort::new);
        directory.register(
                new Descriptor(ResourceTypes.FLUID, "mB", ResourceTypes.defaultExactBatchSize(ResourceTypes.FLUID)),
                Capabilities.FluidHandler.BLOCK,
                FluidResourcePort::new);
        directory.register(
                new Descriptor(ResourceTypes.ENERGY, "FE", ResourceTypes.defaultExactBatchSize(ResourceTypes.ENERGY)),
                Capabilities.EnergyStorage.BLOCK,
                (handler, provider) -> new EnergyResourcePort(handler));
    }

    @Nullable
    Binding<?> binding(ResourceLocation typeId) {
        types();
        return bindings.get(typeId);
    }

    record Binding<T>(
            BlockCapability<T, Direction> capability, BiFunction<T, HolderLookup.Provider, ResourcePort> factory) {
        Supplier<@Nullable ResourcePort> create(
                ServerLevel level, BlockPos target, Direction side, BooleanSupplier alive, Runnable invalidated) {
            if (!level.getServer().isSameThread())
                throw new IllegalStateException("Native factory accessed off server thread");
            var cache = BlockCapabilityCache.create(capability, level, target, side, alive, invalidated);
            return () -> {
                if (!level.getServer().isSameThread())
                    throw new IllegalStateException("Native factory accessed off server thread");
                T handler = cache.getCapability();
                return handler == null ? null : Objects.requireNonNull(factory.apply(handler, level.registryAccess()));
            };
        }
    }
}
