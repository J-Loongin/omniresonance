// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.transfer.ResourceAmount;
import io.github.loongin.omniresonance.transfer.ResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Objects;
import java.util.Optional;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.resources.ResourceLocation;

/** Borrowed server-thread chemical capability; each operation makes one counted native call per tank.
 * Positive long requests are never split by amount. Simulation owns no quantities; native failures propagate. */
public final class ChemicalResourcePort implements ResourcePort {
    private final IChemicalHandler handler;
    private int count = -1;
    private final boolean wholeHandler;

    public ChemicalResourcePort(IChemicalHandler handler) {
        this(handler, false);
    }

    public ChemicalResourcePort(IChemicalHandler handler, boolean wholeHandler) {
        this.handler = Objects.requireNonNull(handler);
        this.wholeHandler = wholeHandler;
    }

    public ResourceLocation typeId() {
        return ChemicalVariant.TYPE;
    }

    public ExtractionScope extractionScope() {
        return wholeHandler ? ExtractionScope.HANDLER : ExtractionScope.VIEW;
    }

    public int sourceViews(TransferWorkBudget budget) {
        return views(budget);
    }

    public int targetViews(TransferWorkBudget budget) {
        return wholeHandler ? 1 : views(budget);
    }

    private int views(TransferWorkBudget budget) {
        budget.beforeCall();
        try {
            int value = handler.getChemicalTanks();
            if (value < 0) throw new IllegalArgumentException("Negative chemical tank count");
            count = value;
            return value;
        } finally {
            budget.afterCall();
        }
    }

    public Optional<ResourceAmount> peek(int view, TransferWorkBudget budget) {
        validate(view);
        ChemicalStack stack;
        budget.beforeCall();
        try {
            stack = handler.getChemicalInTank(view).copy();
        } finally {
            budget.afterCall();
        }
        return stack.isEmpty()
                ? Optional.empty()
                : Optional.of(new ResourceAmount(ChemicalVariant.from(stack), stack.getAmount()));
    }

    public long extract(int view, ResourceVariant variant, long amount, boolean simulate, TransferWorkBudget budget) {
        validate(view);
        var expected = request(variant, amount);
        ChemicalStack moved;
        budget.beforeCall();
        try {
            var action = simulate ? Action.SIMULATE : Action.EXECUTE;
            moved = (wholeHandler
                            ? handler.extractChemical(expected, action)
                            : handler.extractChemical(view, amount, action))
                    .copy();
        } finally {
            budget.afterCall();
        }
        return checked(moved, expected, amount);
    }

    public long insert(int view, ResourceVariant variant, long amount, boolean simulate, TransferWorkBudget budget) {
        if (wholeHandler) {
            if (view != 0) throw new IllegalArgumentException("Aggregate chemical target must be zero");
        } else validate(view);
        var expected = request(variant, amount);
        ChemicalStack remainder;
        budget.beforeCall();
        try {
            var action = simulate ? Action.SIMULATE : Action.EXECUTE;
            remainder = (wholeHandler
                            ? handler.insertChemical(expected.copy(), action)
                            : handler.insertChemical(view, expected.copy(), action))
                    .copy();
        } finally {
            budget.afterCall();
        }
        return amount - checked(remainder, expected, amount);
    }

    private static long checked(ChemicalStack stack, ChemicalStack expected, long maximum) {
        if (stack.isEmpty()) return 0;
        if (stack.getAmount() < 0 || stack.getAmount() > maximum || !ChemicalStack.isSameChemical(stack, expected))
            throw new IllegalArgumentException("Invalid native chemical result");
        return stack.getAmount();
    }

    private static ChemicalStack request(ResourceVariant variant, long amount) {
        if (!(variant instanceof ChemicalVariant chemical))
            throw new IllegalArgumentException("Expected chemical variant");
        return chemical.stack(amount);
    }

    private void validate(int view) {
        if (view < 0 || view >= count) throw new IllegalArgumentException("Chemical tank outside prepared bounds");
    }
}
