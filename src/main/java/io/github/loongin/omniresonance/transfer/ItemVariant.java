// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Internal immutable item identity. Registry use and reconstruction remain on the calling server thread. */
public final class ItemVariant implements ResourceVariant {
    private static final ResourceLocation TYPE_ID = ResourceLocation.withDefaultNamespace("item");
    private final ResourceVariantKey key;
    private final HolderLookup.Provider provider;
    private final Item item;

    private ItemVariant(ResourceVariantKey key, HolderLookup.Provider provider, Item item) {
        this.key = key;
        this.provider = provider;
        this.item = item;
    }

    /** Restores one trusted stored key on the server thread, without mutation or native calls. Unknown IDs,
     * malformed structure, or any lossy component decoding reject; callers must retain the opaque original key. */
    public static ItemVariant restore(ResourceVariantKey key, HolderLookup.Provider provider) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(provider);
        if (!key.typeId().equals(TYPE_ID)) throw new IllegalArgumentException("Wrong resource type");
        var decoded = CanonicalResourceNbt.decode(key.canonicalBytes());
        if (!(decoded instanceof CompoundTag identity)
                || !identity.getAllKeys().equals(java.util.Set.of("id", "components"))
                || !identity.contains("id", net.minecraft.nbt.Tag.TAG_STRING)
                || !identity.contains("components", net.minecraft.nbt.Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid stored item identity");
        ResourceLocation id = ResourceLocation.parse(identity.getString("id"));
        if (!id.toString().equals(identity.getString("id")) || !BuiltInRegistries.ITEM.containsKey(id))
            throw new IllegalArgumentException("Missing stored item");
        ItemVariant restored = new ItemVariant(key, provider, BuiltInRegistries.ITEM.get(id));
        ItemVariant roundTrip = from(restored.stack(1), provider);
        if (!roundTrip.key().equals(key)) throw new IllegalArgumentException("Stored item identity cannot round trip");
        return restored;
    }

    /** Captures full effective components without count or patch history; rejects lossy codecs before mutation. */
    public static ItemVariant from(ItemStack stack, HolderLookup.Provider provider) {
        Objects.requireNonNull(stack);
        Objects.requireNonNull(provider);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Empty item variant");
        }
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) != item) {
            throw new IllegalArgumentException("Unregistered item variant");
        }
        for (TypedDataComponent<?> component : stack.getComponents()) {
            if (component.type().isTransient()) {
                throw new IllegalArgumentException("Transient item component");
            }
        }
        try {
            CompoundTag identity = new CompoundTag();
            identity.putString("id", itemId.toString());
            identity.put(
                    "components",
                    DataComponentMap.CODEC
                            .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), stack.getComponents())
                            .getOrThrow());
            ItemVariant result = new ItemVariant(
                    new ResourceVariantKey(TYPE_ID, CanonicalResourceNbt.encode(identity)), provider, item);
            if (!ItemStack.isSameItemSameComponents(stack, result.stack(1))) {
                throw new IllegalArgumentException("Item component codec loses identity");
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Item variant cannot be persisted", ex);
        }
    }

    /** Returns the registered immutable item ID on the calling server thread without component decoding or mutation. */
    public ResourceLocation itemId() {
        return Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(item));
    }

    @Override
    public ResourceVariantKey key() {
        return key;
    }

    /** Decodes fresh owned components on each call. Positive int quantities are independent of the key. */
    public ItemStack stack(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Item amount must be positive");
        }
        CompoundTag identity = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
        DataComponentMap components = DataComponentMap.CODEC
                .parse(
                        provider.createSerializationContext(NbtOps.INSTANCE),
                        Objects.requireNonNull(identity.get("components")))
                .getOrThrow();
        DataComponentPatch.Builder patch = DataComponentPatch.builder();
        for (DataComponentType<?> type : item.components().keySet()) {
            if (!components.has(type)) {
                patch.remove(type);
            }
        }
        for (TypedDataComponent<?> component : components) {
            patch.set(component);
        }
        return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), amount, patch.build());
    }
}
