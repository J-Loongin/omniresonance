// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Server-thread carrier boundary. Every real capability operation settles its returned container into the still
 * owned player slot before its quantity is returned to the shared engine. Getter/settlement failures therefore
 * retain UNKNOWN_MUTATION semantics. Simulations never query a replacement container or write the slot.
 */
public final class TerminalCarrierPort implements ResourcePort, ResourceTransferEngine.Handle {
    /** Borrowed server-thread player slot. Reads are pure; writes replace only the currently owned stack.
     * current() validates inventory/menu lifetime, independently of permission to begin new transfers. */
    public interface Slot {
        ItemStack stack();

        void stack(ItemStack value);

        boolean current();
    }

    /** One logical carrier, possibly separated from a held stack. Settlement tracks the resulting player-owned
     * inventory/world location so known returns can modify only that carrier, never neighboring items. */
    public interface ContainerOwner {
        ItemStack stack();

        void settle(ItemStack value, TransferWorkBudget budget);

        boolean current();

        default int maximumSettlementCalls() {
            return 0;
        }

        default Object identity() {
            return this;
        }
    }

    private static ContainerOwner adapt(Slot slot) {
        return new ContainerOwner() {
            public ItemStack stack() {
                return slot.stack();
            }

            public void settle(ItemStack value, TransferWorkBudget budget) {
                slot.stack(value);
            }

            public boolean current() {
                return slot.current();
            }

            public Object identity() {
                return slot;
            }
        };
    }

    private final Thread owner = Thread.currentThread();
    private final ContainerOwner slot;
    private final ResourcePort delegate;
    private final Supplier<ItemStack> container;
    private final BooleanSupplier authorized;
    private final boolean externalContainer;
    private ItemStack expected;
    private final ResourceTransferEngine.Handle remainder;

    private TerminalCarrierPort(
            ContainerOwner slot,
            ItemStack expected,
            ResourcePort delegate,
            Supplier<ItemStack> container,
            boolean externalContainer,
            BooleanSupplier authorized) {
        this.slot = Objects.requireNonNull(slot);
        this.expected = Objects.requireNonNull(expected);
        this.delegate = Objects.requireNonNull(delegate);
        this.container = Objects.requireNonNull(container);
        this.externalContainer = externalContainer;
        this.authorized = Objects.requireNonNull(authorized);
        if (!owned()) throw new IllegalArgumentException("Carrier changed during discovery");
        remainder = new ResourceTransferEngine.Handle() {
            public ResourcePort port() {
                return TerminalCarrierPort.this;
            }

            public Object physicalIdentity() {
                return slot.identity();
            }

            public boolean valid() {
                return owned();
            }
        };
    }

    /** Borrows a fluid capability discovered from expected; callers count discovery before constructing this port. */
    public static TerminalCarrierPort fluid(
            Slot slot,
            ItemStack expected,
            IFluidHandlerItem handler,
            HolderLookup.Provider provider,
            BooleanSupplier authorized) {
        return fluid(adapt(slot), expected, handler, provider, authorized);
    }

    /** Uses the same fluid semantics with an explicitly tracked logical carrier owner. */
    public static TerminalCarrierPort fluid(
            ContainerOwner owner,
            ItemStack expected,
            IFluidHandlerItem handler,
            HolderLookup.Provider provider,
            BooleanSupplier authorized) {
        Objects.requireNonNull(handler);
        return new TerminalCarrierPort(
                owner, expected, new FluidResourcePort(handler, provider), handler::getContainer, true, authorized);
    }

    /** Borrows stack-backed FE storage and uses the original stack as its state carrier. */
    public static TerminalCarrierPort energy(
            Slot slot, ItemStack expected, IEnergyStorage handler, BooleanSupplier authorized) {
        return energy(adapt(slot), expected, handler, authorized);
    }

    /** Uses the same FE semantics with an explicitly tracked logical carrier owner. */
    public static TerminalCarrierPort energy(
            ContainerOwner owner, ItemStack expected, IEnergyStorage handler, BooleanSupplier authorized) {
        return new TerminalCarrierPort(
                owner, expected, new EnergyResourcePort(handler), () -> expected, false, authorized);
    }

    private boolean owned() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Carrier accessed off server thread");
        return slot.current() && slot.stack() == expected;
    }

    public ResourcePort port() {
        return this;
    }

    public Object physicalIdentity() {
        return slot.identity();
    }

    public boolean valid() {
        return owned() && authorized.getAsBoolean();
    }

    public ResourceTransferEngine.Handle remainderTarget() {
        return remainder;
    }

    public ResourceLocation typeId() {
        return delegate.typeId();
    }

    public ExtractionScope extractionScope() {
        return delegate.extractionScope();
    }

    public int maximumMutationCalls() {
        int settlement = slot.maximumSettlementCalls();
        if (settlement < 0) throw new IllegalArgumentException("Negative carrier settlement bound");
        return Math.addExact(externalContainer ? 2 : 1, settlement);
    }

    public int sourceViews(TransferWorkBudget budget) {
        return delegate.sourceViews(budget);
    }

    public int targetViews(TransferWorkBudget budget) {
        return delegate.targetViews(budget);
    }

    public Optional<ResourceAmount> peek(int view, TransferWorkBudget budget) {
        return delegate.peek(view, budget);
    }

    public int extract(int view, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        int extracted = delegate.extract(view, variant, amount, simulate, budget);
        if (!simulate) settle(budget);
        return extracted;
    }

    public int insert(int view, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        int inserted = delegate.insert(view, variant, amount, simulate, budget);
        if (!simulate) settle(budget);
        return inserted;
    }

    private void settle(TransferWorkBudget budget) {
        ItemStack replacement;
        if (externalContainer) {
            budget.beforeCall();
            try {
                replacement = Objects.requireNonNull(container.get(), "Missing carrier result")
                        .copy();
            } finally {
                budget.afterCall();
            }
        } else
            replacement = Objects.requireNonNull(container.get(), "Missing carrier result")
                    .copy();
        if (!owned()) throw new IllegalStateException("Carrier ownership changed during mutation");
        if (!replacement.isEmpty() && replacement.getCount() > replacement.getMaxStackSize())
            throw new IllegalStateException("Oversized carrier result");
        slot.settle(replacement, budget);
        expected = replacement;
    }
}
