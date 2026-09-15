// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Internal synchronous resource boundary, owned by one server thread for its endpoint lifetime.
 * All native calls count against the supplied budget, including failures; caller controls admission.
 * Simulations do not modify authoritative state or port metadata. Inputs and observed stacks are detached.
 * Invalid arguments/results throw; modifying failures may have unknown effects and must never be guessed or retried.
 * Dynamic counts are preparation: refresh the appropriate view count for each transaction before operations.
 * Implementations retain only the latest successfully observed bound per view kind (constant capacity,
 * port-owned, replaced on refresh, discarded with the port). It is not a validity promise across native calls.
 * Native adapters perform one native query where needed; their peek and simulations perform one native call after
 * local request validation/reconstruction; real mutations use maximumMutationCalls(), including required container settlement. Private ledger ports make zero native calls and must validate before any mutation;
 * a modifying RuntimeException without an advanced native marker therefore still proves no quantity moved. Every native invocation must increment the supplied budget via
 * beforeCall before entering native code, and no resource mutation may precede that marker. The budget is
 * owned exclusively by the calling server thread during an operation. Therefore an unchanged calls() count
 * across an exception proves no native invocation occurred and no offered resources left the caller.
 * Once the count advances, any modifying exception or invalid result remains conservatively unknown;
 * exception class alone is never evidence of whether the native call began.
 */
public interface ResourcePort {
    /** Pure commit-bound metadata. Native adapters perform at most one counted call per operation; only
     * private ledger ports return false and promise zero native access for every operation. */
    default boolean usesNativeCalls() {
        return true;
    }

    /** Pure upper bound per real extract/insert, including required carrier settlement queries. Ordinary native
     * ports use one call; private ledger ports use zero. The declared finite bound participates in exact admission. */
    default int maximumMutationCalls() {
        return usesNativeCalls() ? 1 : 0;
    }

    /** Internal execution admission after simulations and before extraction. Native ports require no extra state.
     * Ledger ports reserve exact destination capacity here; rejection/exception must not move any quantity or
     * invoke a native capability. This hook is never called by a simulation-only operation. */
    default boolean reserveInsertion(ResourceVariant variant, int amount) {
        return true;
    }

    /** Releases internal destination admission on the owner thread; must not throw, move resources or call native code. */
    default void releaseInsertion() {}

    /** Whether extraction promises from distinct views can represent independent quantities. */
    enum ExtractionScope {
        VIEW,
        HANDLER
    }
    /** Pure immutable type metadata; performs no native access. */
    ResourceLocation typeId();
    /** Pure locality metadata; HANDLER simulations for the same variant must not be added per view. */
    ExtractionScope extractionScope();
    /** Refreshes source bounds without authoritative mutation; rejects negative native counts. */
    int sourceViews(TransferWorkBudget budget);
    /** Refreshes target bounds without authoritative mutation; rejects negative native counts. */
    int targetViews(TransferWorkBudget budget);
    /** Returns a detached positive candidate or empty; caller must have refreshed dynamic source bounds. */
    Optional<ResourceAmount> peek(int sourceView, TransferWorkBudget budget);
    /** Extracts/simulates one positive int request and validates identity/amount before returning 0..amount. */
    int extract(int sourceView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget);
    /** Inserts/simulates one positive int request and validates results before returning accepted 0..amount. */
    int insert(int targetView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget);
}
