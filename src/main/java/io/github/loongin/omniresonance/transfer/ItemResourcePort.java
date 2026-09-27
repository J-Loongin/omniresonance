// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Server-thread-owned item boundary. Each view is a real slot; see ResourcePort for bound refresh ownership. */
public final class ItemResourcePort implements ResourcePort {
    private final IItemHandler handler;
    private final HolderLookup.Provider provider;
    private int sourceViews = -1;
    private int targetViews = -1;

    /** Borrows the handler/provider for this port lifetime, without accessing or modifying native state. */
    public ItemResourcePort(IItemHandler handler, HolderLookup.Provider provider) {
        this.handler = Objects.requireNonNull(handler);
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public ResourceLocation typeId() {
        return ResourceTypes.ITEM;
    }

    @Override
    public ExtractionScope extractionScope() {
        return ExtractionScope.VIEW;
    }

    @Override
    public int sourceViews(TransferWorkBudget budget) {
        sourceViews = ItemHandlerCalls.slots(handler, budget);
        return sourceViews;
    }

    @Override
    public int targetViews(TransferWorkBudget budget) {
        targetViews = ItemHandlerCalls.slots(handler, budget);
        return targetViews;
    }

    @Override
    public Optional<ResourceAmount> peek(int sourceView, TransferWorkBudget budget) {
        validateView(sourceView, sourceViews);
        ItemStack stack = ItemHandlerCalls.peek(handler, sourceView, budget);
        return stack.isEmpty()
                ? Optional.empty()
                : Optional.of(new ResourceAmount(ItemVariant.from(stack, provider), stack.getCount()));
    }

    @Override
    public long extract(
            int sourceView, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        int amount = ResourcePort.intRequest(maximum);
        validateView(sourceView, sourceViews);
        ItemStack identity = request(variant, amount);
        ItemStack extracted = ItemHandlerCalls.extract(handler, sourceView, amount, simulate, budget);
        return validateResult(extracted, identity, amount);
    }

    @Override
    public long insert(
            int targetView, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        int amount = ResourcePort.intRequest(maximum);
        validateView(targetView, targetViews);
        ItemStack identity = request(variant, amount);
        ItemStack remainder = ItemHandlerCalls.insert(handler, targetView, identity, simulate, budget);
        return amount - validateResult(remainder, identity, amount);
    }

    private static ItemStack request(ResourceVariant variant, int amount) {
        if (!(variant instanceof ItemVariant item)) throw new IllegalArgumentException("Expected item variant");
        return item.stack(amount);
    }

    private static int validateResult(ItemStack result, ItemStack identity, int amount) {
        if (!ItemHandlerCalls.validAmount(result, identity, amount))
            throw new IllegalArgumentException("Invalid native item result");
        return result.getCount();
    }

    private static void validateView(int view, int bound) {
        if (view < 0 || view >= bound) throw new IllegalArgumentException("Item view outside prepared bounds");
    }
}
