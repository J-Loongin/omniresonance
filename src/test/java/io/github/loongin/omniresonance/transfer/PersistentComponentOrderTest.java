// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.fixtures.LegacyEffectKeys;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

class PersistentComponentOrderTest {
    private static final HolderLookup.Provider PROVIDER =
            net.minecraft.data.registries.VanillaRegistries.createLookup();

    private static ItemStack customFood() {
        ItemStack food = new ItemStack(Items.APPLE);
        food.set(
                DataComponents.FOOD,
                new FoodProperties.Builder()
                        .nutrition(7)
                        .saturationModifier(0.8f)
                        .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 321, 2), 0.7f)
                        .build());
        return food;
    }

    private static ItemStack potion() {
        ItemStack potion = new ItemStack(Items.POTION);
        potion.set(
                DataComponents.POTION_CONTENTS,
                new PotionContents(
                        Optional.empty(),
                        Optional.of(0x123456),
                        List.of(new MobEffectInstance(
                                MobEffects.REGENERATION,
                                310,
                                1,
                                true,
                                false,
                                true,
                                new MobEffectInstance(MobEffects.ABSORPTION, 222, 2)))));
        return potion;
    }

    @Test
    void nestedTransientComponentsMustNotDisappearThroughTheKnownFoodFallback() {
        var transientType = net.minecraft.core.component.DataComponentType.<String>builder()
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
                .build();
        var nested = customFood();
        nested.set(transientType, "must survive");
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(nested)));
        assertThrows(IllegalArgumentException.class, () -> ItemVariant.from(box, PROVIDER));
    }

    @Test
    void equalNativePotionObjectsCannotHideChangedCureMembership() {
        var first = potion();
        var second = potion();
        var old = second.get(DataComponents.POTION_CONTENTS);
        var changed = old.customEffects().getFirst();
        changed.getCures().clear();
        second.set(
                DataComponents.POTION_CONTENTS, new PotionContents(old.potion(), old.customColor(), List.of(changed)));
        assertTrue(ItemStack.isSameItemSameComponents(first, second), "Native equality omits cure membership");
        org.junit.jupiter.api.Assertions.assertFalse(ItemVariant.sameIdentity(first, second, PROVIDER));
    }

    @Test
    void repeatedPeeksReuseSharedImmutableFoodIdentityWithoutReencodingEffects() {
        var effectReads = new java.util.concurrent.atomic.AtomicInteger();
        var stack = new ItemStack(Items.APPLE, 64);
        stack.set(
                DataComponents.FOOD,
                new FoodProperties.Builder()
                        .nutrition(4)
                        .effect(
                                () -> {
                                    effectReads.incrementAndGet();
                                    return new MobEffectInstance(MobEffects.REGENERATION, 400);
                                },
                                1)
                        .build());
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(1);
        handler.setStackInSlot(0, stack);
        var port = new ItemResourcePort(handler, PROVIDER);
        var budget = new TransferWorkBudget(5000, 1000000, 1000000, () -> 0);
        port.sourceViews(budget);
        var captured = port.peek(0, budget).orElseThrow().variant();
        int reads = effectReads.get();
        for (int i = 0; i < 4096; i++)
            assertSame(captured, port.peek(0, budget).orElseThrow().variant());
        assertEquals(reads, effectReads.get(), "Unchanged slots must not reencode effect components on every peek");
    }

    @Test
    void nativeItemBoundaryAcceptsEquivalentCustomFoodSuppliersWithoutGuessingQuantity() {
        var stack = customFood().copyWithCount(64);
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(1);
        handler.setStackInSlot(0, stack);
        var port = new ItemResourcePort(handler, PROVIDER);
        var budget = new TransferWorkBudget(20, 1000000, 1000000, () -> 0);
        port.sourceViews(budget);
        var variant = port.peek(0, budget).orElseThrow().variant();
        assertEquals(64, port.extract(0, variant, 64, true, budget));
        assertEquals(64, handler.getStackInSlot(0).getCount());
        assertEquals(64, port.extract(0, variant, 64, false, budget));
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void foodPotionAndNestedStacksRestoreBothHistoricalSetOrders() {
        ItemStack food = customFood(), potion = potion();
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(food, ItemStack.EMPTY, potion)));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(food, potion)));
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(potion, food)));
        ItemStack converts = new ItemStack(Items.BREAD);
        converts.set(
                DataComponents.FOOD,
                new FoodProperties.Builder()
                        .nutrition(3)
                        .usingConvertsTo(Items.APPLE)
                        .build());
        var base = converts.get(DataComponents.FOOD);
        converts.set(
                DataComponents.FOOD,
                new FoodProperties(
                        base.nutrition(),
                        base.saturation(),
                        base.canAlwaysEat(),
                        base.eatSeconds(),
                        Optional.of(food),
                        base.effects()));
        for (ItemStack stack : List.of(food, potion, box, bundle, crossbow, converts)) {
            var key = ItemVariant.from(stack, PROVIDER).key();
            var legacy = LegacyEffectKeys.reverse(key);
            assertNotEquals(key, legacy);
            byte[] untouched = legacy.canonicalBytes();
            var recovered = ItemVariant.restore(legacy, PROVIDER);
            assertEquals(legacy, recovered.key());
            assertEquals(key, ItemVariant.from(recovered.stack(1), PROVIDER).key());
            assertEquals(key, legacy.normalized());
            assertSame(legacy.normalized(), legacy.normalized(), "Normalization is cached per immutable key");
            assertArrayEquals(untouched, legacy.canonicalBytes());
        }
    }

    @Test
    void fluidDataComponentsUseTheSameKnownSetRules() {
        var stack = new FluidStack(Fluids.WATER, 1000);
        stack.set(DataComponents.POTION_CONTENTS, potion().get(DataComponents.POTION_CONTENTS));
        var key = FluidVariant.from(stack, PROVIDER).key();
        var legacy = LegacyEffectKeys.reverse(key);
        var restored = FluidVariant.restore(legacy, PROVIDER);
        assertEquals(legacy, restored.key());
        assertEquals(key, FluidVariant.from(restored.stack(1000), PROVIDER).key());
    }

    @Test
    void actualLegacyAppleFixtureRecoversWithAllEffectsAndExactQuantity() throws Exception {
        String saved;
        try (var stream = getClass().getResourceAsStream("legacy-enchanted-golden-apple.snbt")) {
            saved = new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var key = new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(TagParser.parseTag(saved)));
        var originalBytes = key.canonicalBytes();
        ItemStack stack = ItemVariant.restore(key, PROVIDER).stack(64);
        assertTrue(stack.is(Items.ENCHANTED_GOLDEN_APPLE));
        assertEquals(64, stack.getCount());
        assertEquals(4, stack.get(DataComponents.FOOD).effects().size());
        assertEquals(
                ItemVariant.from(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), PROVIDER)
                        .key(),
                ItemVariant.from(stack, PROVIDER).key());
        assertArrayEquals(originalBytes, key.canonicalBytes());
    }

    @Test
    void duplicateCuresAndLossyUnknownFieldsAreStillRejected() {
        var key = ItemVariant.from(customFood(), PROVIDER).key();
        for (boolean duplicate : new boolean[] {true, false}) {
            CompoundTag root = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
            var effect = root.getCompound("components")
                    .getCompound("minecraft:food")
                    .getList("effects", Tag.TAG_COMPOUND)
                    .getCompound(0)
                    .getCompound("effect");
            if (duplicate) {
                var cures = effect.getList("neoforge:cures", Tag.TAG_STRING);
                cures.add(cures.get(0).copy());
            } else effect.putString("unknown_effect_field", "must not disappear");
            var malformed = new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(root));
            assertThrows(IllegalArgumentException.class, () -> ItemVariant.restore(malformed, PROVIDER));
        }
    }

    @Test
    void customDataAndOrderedListsKeepTheirExactOrder() throws Exception {
        var root = TagParser.parseTag(
                "{id:'minecraft:apple',components:{'minecraft:custom_data':{'neoforge:cures':['z','a']},'minecraft:lore':['first','second'],'minecraft:attribute_modifiers':{modifiers:[{id:'x:first'},{id:'x:second'}]},'minecraft:written_book_content':{pages:['page1','page2']}}}");
        var key = new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(root));
        assertSame(key, key.normalized());
        var reversed = LegacyEffectKeys.reverse(key);
        assertNotEquals(key.normalized(), reversed.normalized());
        ItemStack first = potion(), second = potion();
        second.set(
                DataComponents.POTION_CONTENTS,
                new PotionContents(
                        Optional.empty(),
                        Optional.of(0x123456),
                        List.of(new MobEffectInstance(MobEffects.REGENERATION, 311, 1))));
        assertNotEquals(
                ItemVariant.from(first, PROVIDER).key(),
                ItemVariant.from(second, PROVIDER).key());
    }

    @Test
    void compoundMapInsertionOrderAlreadyHasStableCanonicalEncoding() throws Exception {
        var a = TagParser.parseTag("{levels:{'minecraft:sharpness':2,'minecraft:unbreaking':3}}");
        var b = TagParser.parseTag("{levels:{'minecraft:unbreaking':3,'minecraft:sharpness':2}}");
        assertArrayEquals(CanonicalResourceNbt.encode(a), CanonicalResourceNbt.encode(b));
    }
}
