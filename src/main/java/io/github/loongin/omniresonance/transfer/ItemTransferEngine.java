// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * One synchronous greedy commit, never a container scan. A started commit always disposes known remainder before
 * returning. At most ten external calls: two initial slot counts, two simulations, source count/extract,
 * target count/insert, source count/return. Handle validity reads authority and invalidation epochs, not capabilities.
 * Extended handlers may exceed native stack size; valid count/identity responses determine actual progress.
 */
public final class ItemTransferEngine {
    public static final int MAXIMUM_COMMIT_CALLS = 10;
    /** Server-thread-owned handler plus a nonmutating, non-discovering current-endpoint check. */
    public interface Handle {
        IItemHandler handler();

        boolean valid();

        default Object physicalIdentity() {
            return handler();
        }
    }

    public enum Failure {
        NONE,
        REFUSED,
        INVALID_ENDPOINT,
        RECOVERY_FULL,
        INCONSISTENT,
        EXCEPTION,
        UNKNOWN_MUTATION
    }
    /** Known evidence only. An unknown modification never fabricates a remainder or retries that transaction. */
    public record Result(
            long extracted,
            long moved,
            long returned,
            long buffered,
            Failure failure,
            @Nullable RuntimeException cause) {
        public Result(long extracted, long moved, long returned, long buffered, Failure failure) {
            this(extracted, moved, returned, buffered, failure, null);
        }

        public long removed() {
            return extracted - returned;
        }
    }

    /** Caller admits one bounded unit on the server thread; simulation never reserves, and mutation failures stop it. */
    public Result commit(
            Handle source,
            int sourceSlot,
            Handle target,
            int targetSlot,
            ItemVariant variant,
            long limit,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        if (limit <= 0) return result(Failure.REFUSED);
        if (!source.valid()
                || !target.valid()
                || (source.handler() == target.handler()
                        || source.physicalIdentity().equals(target.physicalIdentity())))
            return result(Failure.INVALID_ENDPOINT);
        ItemStack identity = variant.stack(1);
        int requested = (int) Math.min(Integer.MAX_VALUE, limit);
        try {
            if (!slotValid(source, sourceSlot, budget) || !slotValid(target, targetSlot, budget))
                return result(Failure.INVALID_ENDPOINT);
            if (!source.valid()) return result(Failure.INVALID_ENDPOINT);
            ItemStack simulated = ItemHandlerCalls.extract(source.handler(), sourceSlot, requested, true, budget);
            if (!ItemHandlerCalls.validAmount(simulated, identity, requested)) return result(Failure.INCONSISTENT);
            if (simulated.isEmpty()) return result(Failure.REFUSED);
            if (!target.valid()) return result(Failure.INVALID_ENDPOINT);
            ItemStack remainder = ItemHandlerCalls.insert(target.handler(), targetSlot, simulated, true, budget);
            if (!ItemHandlerCalls.validAmount(remainder, identity, simulated.getCount()))
                return result(Failure.INCONSISTENT);
            requested = simulated.getCount() - remainder.getCount();
            if (requested == 0) return result(Failure.REFUSED);
        } catch (RuntimeException failure) {
            return new Result(0, 0, 0, 0, Failure.EXCEPTION, failure);
        }
        RecoveryBuffer.Reservation reservation = recovery.reserve(
                        variant.key(), requested, limits.maxVariantsPerNetwork(), limits.maxEncodedBytesPerNetwork())
                .orElse(null);
        if (reservation == null) return result(Failure.RECOVERY_FULL);
        long extracted = 0, moved = 0, returned = 0;
        boolean modifying = false;
        try (reservation) {
            if (!target.valid() || !slotValid(source, sourceSlot, budget) || !target.valid())
                return result(Failure.INVALID_ENDPOINT);
            modifying = true;
            ItemStack actual = ItemHandlerCalls.extract(source.handler(), sourceSlot, requested, false, budget);
            if (!ItemHandlerCalls.validAmount(actual, identity, requested))
                return unknown(extracted, moved, returned, null);
            modifying = false;
            extracted = actual.getCount();
            if (extracted == 0) return result(Failure.INCONSISTENT);
            Failure evidence = extracted == requested ? Failure.NONE : Failure.INCONSISTENT;
            ItemStack remainder = actual;
            boolean targetValid;
            try {
                targetValid = slotValid(target, targetSlot, budget);
            } catch (RuntimeException failure) {
                targetValid = false;
            }
            if (targetValid) {
                modifying = true;
                remainder = ItemHandlerCalls.insert(target.handler(), targetSlot, actual, false, budget);
                if (!ItemHandlerCalls.validAmount(remainder, identity, actual.getCount()))
                    return unknown(extracted, moved, returned, null);
                modifying = false;
                moved = extracted - remainder.getCount();
                if (!remainder.isEmpty()) evidence = Failure.INCONSISTENT;
            } else evidence = Failure.INVALID_ENDPOINT;
            if (!remainder.isEmpty()) {
                boolean sourceValid;
                try {
                    sourceValid = slotValid(source, sourceSlot, budget);
                } catch (RuntimeException failure) {
                    sourceValid = false;
                }
                if (sourceValid) {
                    modifying = true;
                    ItemStack unreturned =
                            ItemHandlerCalls.insert(source.handler(), sourceSlot, remainder, false, budget);
                    if (!ItemHandlerCalls.validAmount(unreturned, identity, remainder.getCount()))
                        return unknown(extracted, moved, returned, null);
                    modifying = false;
                    returned = remainder.getCount() - unreturned.getCount();
                    remainder = unreturned;
                }
            }
            reservation.commit(remainder.getCount());
            return new Result(extracted, moved, returned, remainder.getCount(), evidence);
        } catch (RuntimeException failure) {
            if (modifying) return unknown(extracted, moved, returned, failure);
            throw failure;
        }
    }

    private static boolean slotValid(Handle handle, int slot, TransferWorkBudget budget) {
        if (!handle.valid() || slot < 0) return false;
        int slots = ItemHandlerCalls.slots(handle.handler(), budget);
        return slot < slots && handle.valid();
    }

    private static Result result(Failure failure) {
        return new Result(0, 0, 0, 0, failure);
    }

    private static Result unknown(long extracted, long moved, long returned, @Nullable RuntimeException failure) {
        RuntimeException evidence = failure == null
                ? new IllegalStateException(
                        "Modifying item capability returned invalid identity or amount; outcome is unknown")
                : failure;
        return new Result(extracted, moved, returned, 0, Failure.UNKNOWN_MUTATION, evidence);
    }
}
