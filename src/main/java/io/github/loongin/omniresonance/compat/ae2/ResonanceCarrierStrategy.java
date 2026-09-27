// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.ContainerItemStrategy;
import appeng.api.config.Actionable;
import appeng.api.stacks.GenericStack;
import io.github.loongin.omniresonance.bootstrap.ResourceAdapters;
import io.github.loongin.omniresonance.transfer.EnergyResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/** AE-owned transaction boundary. Each call borrows one detached carrier; simulations never settle it.
 * Modification runs on the server thread, returns the native amount, and does not compensate AE inventory.
 * Native failures propagate without retry; no native handler or inventory snapshot survives a call. */
public final class ResonanceCarrierStrategy<T extends ResonanceKey>
        implements ContainerItemStrategy<T, ResonanceCarrierStrategy.Context> {
    private static final int MAXIMUM_PROBE_VIEWS = 64;
    private static final ResourceAdapterDirectory ADAPTERS = ResourceAdapters.create();
    private final ResourceLocation type;

    private ResonanceCarrierStrategy(ResourceLocation type) {
        this.type = type;
    }

    public static void register() {
        ContainerItemStrategies.register(
                ResonanceKeys.ENERGY_TYPE,
                ResonanceKeys.Energy.class,
                new ResonanceCarrierStrategy<>(ResourceTypes.ENERGY));
        if (!AeResourceKeys.nativeChemicals() && ADAPTERS.carrierTypes().contains(ResonanceKeys.CHEMICAL_RESOURCE))
            ContainerItemStrategies.register(
                    ResonanceKeys.CHEMICAL_TYPE,
                    ResonanceKeys.Chemical.class,
                    new ResonanceCarrierStrategy<>(ResonanceKeys.CHEMICAL_RESOURCE));
    }

    private static TransferWorkBudget budget() {
        // AE owns its interaction loop; this accounting object bounds our candidate scan, not the node scheduler.
        return new TransferWorkBudget(MAXIMUM_PROBE_VIEWS + 4, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    private @Nullable ResourcePort open(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return null;
        if (type.equals(ResourceTypes.ENERGY)) {
            var handler = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            return handler == null ? null : new EnergyResourcePort(handler);
        }
        return ADAPTERS.carrier(type, stack, registries);
    }

    @Override
    public @Nullable GenericStack getContainedStack(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var registries =
                HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(r -> r.asLookup()));
        var port = open(stack.copyWithCount(1), registries);
        if (port == null) return null;
        var budget = budget();
        int views = Math.min(MAXIMUM_PROBE_VIEWS, port.sourceViews(budget));
        for (int view = 0; view < views && budget.canStart(); view++) {
            var candidate = port.peek(view, budget).orElse(null);
            if (candidate != null)
                return new GenericStack(ResonanceKeys.from(candidate.variant().key()), candidate.quantity());
        }
        return null;
    }

    @Override
    public @Nullable Context findCarriedContext(Player player, AbstractContainerMenu menu) {
        if (player.containerMenu != menu || open(menu.getCarried().copyWithCount(1), player.registryAccess()) == null)
            return null;
        return new Context(player, menu, -1);
    }

    @Override
    public @Nullable Context findPlayerSlotContext(Player player, int slot) {
        if (slot < 0
                || slot >= player.getInventory().getContainerSize()
                || open(player.getInventory().getItem(slot).copyWithCount(1), player.registryAccess()) == null)
            return null;
        return new Context(player, player.containerMenu, slot);
    }

    @Override
    public long insert(Context context, T key, long amount, Actionable mode) {
        return transfer(context, key, amount, mode, true);
    }

    @Override
    public long extract(Context context, T key, long amount, Actionable mode) {
        return transfer(context, key, amount, mode, false);
    }

    private long transfer(Context context, T key, long amount, Actionable mode, boolean insert) {
        java.util.Objects.requireNonNull(mode);
        if (amount < 0) throw new IllegalArgumentException("Negative carrier transfer");
        if (amount == 0 || !key.resourceKey().typeId().equals(type) || !context.current()) return 0;
        if (!context.player.level().isClientSide && !context.player.getServer().isSameThread())
            throw new IllegalStateException("Carrier accessed off server thread");
        if (mode == Actionable.MODULATE && context.player.level().isClientSide)
            throw new IllegalStateException("Client attempted carrier mutation");
        var original = context.stack();
        if (original.isEmpty()) return 0;
        var snapshot = original.copy();
        var working = original.copyWithCount(1);
        var registries = context.player.registryAccess();
        var port = open(working, registries);
        var variant = ADAPTERS.decode(key.resourceKey(), registries).orElse(null);
        if (port == null || variant == null) return 0;
        var budget = budget();
        int views = insert ? port.targetViews(budget) : port.sourceViews(budget);
        if (views <= 0) return 0;
        long moved = insert
                ? port.insert(0, variant, amount, mode == Actionable.SIMULATE, budget)
                : port.extract(0, variant, amount, mode == Actionable.SIMULATE, budget);
        if (mode == Actionable.MODULATE && moved > 0) {
            if (!context.current() || context.stack() != original || !ItemStack.matches(original, snapshot))
                throw new IllegalStateException("Carrier slot changed during native operation");
            if (original.getCount() == 1) context.replace(working);
            else {
                context.replace(original.copyWithCount(original.getCount() - 1));
                context.player.getInventory().placeItemBackInInventory(working);
            }
            context.player.getInventory().setChanged();
        }
        return moved;
    }

    @Override
    public @Nullable GenericStack getExtractableContent(Context context) {
        return context.current() ? getContainedStack(context.stack()) : null;
    }

    @Override
    public void playFillSound(Player player, T key) {
        player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.2f, 1f);
    }

    @Override
    public void playEmptySound(Player player, T key) {
        playFillSound(player, key);
    }

    /** Short-lived AE interaction context. Owns no capability or detached stack; follows only the original menu/slot. */
    public record Context(Player player, AbstractContainerMenu menu, int slot) {
        boolean current() {
            return player.isAlive() && player.containerMenu == menu;
        }

        ItemStack stack() {
            return slot < 0 ? menu.getCarried() : player.getInventory().getItem(slot);
        }

        void replace(ItemStack value) {
            if (slot < 0) menu.setCarried(value);
            else player.getInventory().setItem(slot, value);
        }
    }
}
