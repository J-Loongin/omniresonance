// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Stable AE types, independent of installed resource addons. Registration is explicit on the mod registry thread. */
public final class ResonanceKeys {
    public static final Energy ENERGY = new Energy();
    public static final Source SOURCE = new Source();
    public static final Soul SOUL = new Soul();
    public static final ResourceLocation CHEMICAL_RESOURCE = ResourceLocation.parse("mekanism:chemical");
    public static final Type<Energy> ENERGY_TYPE =
            new Type<>("energy", Energy.class, MapCodec.unit(ENERGY), b -> ENERGY, "FE", 1, 10000);
    public static final Type<Source> SOURCE_TYPE =
            new Type<>("source", Source.class, MapCodec.unit(SOURCE), b -> SOURCE, "Source", 1, 1000);
    public static final Type<Soul> SOUL_TYPE =
            new Type<>("soul", Soul.class, MapCodec.unit(SOUL), b -> SOUL, "Soul", 1, 1);
    private static final Codec<Chemical> CHEMICAL_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(new Chemical(id(value)));
                } catch (IllegalArgumentException invalid) {
                    return DataResult.error(() -> "Invalid chemical identity");
                }
            },
            key -> key.getId().toString());
    public static final Type<Chemical> CHEMICAL_TYPE = new Type<>(
            "chemical",
            Chemical.class,
            CHEMICAL_CODEC.fieldOf("id"),
            b -> new Chemical(id(b.readUtf(256))),
            "B",
            1000,
            1000);

    private ResonanceKeys() {}

    private static ResourceLocation id(String value) {
        if (value.length() > 256) throw new IllegalArgumentException("Chemical ID exceeds bound");
        var id = ResourceLocation.parse(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical chemical ID");
        return id;
    }

    public static void register(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.ITEM)) {
            AEKeyTypes.register(ENERGY_TYPE);
            AEKeyTypes.register(SOURCE_TYPE);
            AEKeyTypes.register(SOUL_TYPE);
            AEKeyTypes.register(CHEMICAL_TYPE);
        }
    }

    public static ResonanceKey from(ResourceVariantKey key) {
        if (key.typeId().equals(ResourceTypes.ENERGY) && key.canonicalBytes().length == 0) return ENERGY;
        if (key.typeId().equals(ResourceTypes.SOURCE) && key.canonicalBytes().length == 0) return SOURCE;
        if (key.typeId().equals(ResourceTypes.SOUL) && key.canonicalBytes().length == 0) return SOUL;
        if (key.typeId().equals(CHEMICAL_RESOURCE)) {
            var tag = CanonicalResourceNbt.decode(key.canonicalBytes());
            if (tag instanceof CompoundTag compound && compound.getAllKeys().equals(java.util.Set.of("id"))) {
                var result = new Chemical(id(compound.getString("id")));
                if (result.resourceKey().equals(key)) return result;
            }
        }
        throw new IllegalArgumentException("Unsupported independent AE resource identity");
    }

    public static final class Energy extends ResonanceKey {
        private static final ResourceVariantKey RESOURCE = new ResourceVariantKey(ResourceTypes.ENERGY, new byte[0]);

        private Energy() {}

        public ResourceVariantKey resourceKey() {
            return RESOURCE;
        }

        public AEKeyType getType() {
            return ENERGY_TYPE;
        }

        public ResourceLocation getId() {
            return ResourceTypes.ENERGY;
        }

        public CompoundTag toTag(HolderLookup.Provider registries) {
            return new CompoundTag();
        }

        public void writeToPacket(RegistryFriendlyByteBuf buffer) {
            /* Singleton has no payload. */
        }
    }

    public static final class Source extends ResonanceKey {
        private static final ResourceVariantKey RESOURCE = new ResourceVariantKey(ResourceTypes.SOURCE, new byte[0]);

        private Source() {}

        public ResourceVariantKey resourceKey() {
            return RESOURCE;
        }

        public AEKeyType getType() {
            return SOURCE_TYPE;
        }

        public ResourceLocation getId() {
            return ResourceTypes.SOURCE;
        }

        public CompoundTag toTag(HolderLookup.Provider registries) {
            return new CompoundTag();
        }

        public void writeToPacket(RegistryFriendlyByteBuf buffer) {
            /* Singleton has no payload. */
        }
    }

    public static final class Soul extends ResonanceKey {
        private static final ResourceVariantKey RESOURCE = new ResourceVariantKey(ResourceTypes.SOUL, new byte[0]);

        private Soul() {}

        public ResourceVariantKey resourceKey() {
            return RESOURCE;
        }

        public AEKeyType getType() {
            return SOUL_TYPE;
        }

        public ResourceLocation getId() {
            return ResourceTypes.SOUL;
        }

        public CompoundTag toTag(HolderLookup.Provider registries) {
            return new CompoundTag();
        }

        public void writeToPacket(RegistryFriendlyByteBuf buffer) {
            /* Singleton has no payload. */
        }
    }

    public static final class Chemical extends ResonanceKey {
        private final ResourceLocation id;
        private final ResourceVariantKey resource;
        private final Object primary;

        public Chemical(ResourceLocation id) {
            this.id = ResonanceKeys.id(id.toString());
            primary = primaryIdentity(this.id);
            var tag = new CompoundTag();
            tag.putString("id", id.toString());
            resource = new ResourceVariantKey(CHEMICAL_RESOURCE, CanonicalResourceNbt.encode(tag));
        }

        private static Object primaryIdentity(ResourceLocation id) {
            if (net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
                var identity = io.github.loongin.omniresonance.compat.mekanism.MekanismResources.primaryIdentity(id);
                if (identity != null) return identity;
            }
            // Minecraft canonicalizes ResourceKeys through weak values. Keep the returned token alive with this
            // key so equal missing IDs share a reference without merging different IDs or retaining a private cache.
            return net.minecraft.resources.ResourceKey.create(
                    net.minecraft.resources.ResourceKey.createRegistryKey(CHEMICAL_RESOURCE), id);
        }

        @Override
        public Object getPrimaryKey() {
            return primary;
        }

        public ResourceVariantKey resourceKey() {
            return resource;
        }

        public AEKeyType getType() {
            return CHEMICAL_TYPE;
        }

        public ResourceLocation getId() {
            return id;
        }

        public CompoundTag toTag(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", id.toString());
            return tag;
        }

        public void writeToPacket(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(id.toString(), 256);
        }
    }

    public static final class Type<T extends ResonanceKey> extends AEKeyType {
        private final MapCodec<T> codec;
        private final java.util.function.Function<RegistryFriendlyByteBuf, T> reader;
        private final String unit;
        private final int divisor, operation;

        private Type(
                String name,
                Class<T> keyClass,
                MapCodec<T> codec,
                java.util.function.Function<RegistryFriendlyByteBuf, T> reader,
                String unit,
                int divisor,
                int operation) {
            super(
                    ResourceLocation.fromNamespaceAndPath("omniresonance", name),
                    keyClass,
                    Component.translatable("omniresonance.ae_resource." + name));
            this.codec = codec;
            this.reader = reader;
            this.unit = unit;
            this.divisor = divisor;
            this.operation = operation;
        }

        public MapCodec<T> codec() {
            return codec;
        }

        public T readFromPacket(RegistryFriendlyByteBuf buffer) {
            return reader.apply(buffer);
        }

        public String getUnitSymbol() {
            return unit;
        }

        public int getAmountPerUnit() {
            return divisor;
        }

        public int getAmountPerOperation() {
            return operation;
        }
    }
}
