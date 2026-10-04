// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.persistence.ExchangeStateNbt;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread draft resolution, before committing through ExchangeManagementService. Actor UUID must come from
 * the authenticated terminal sender, never a client field. Reads only current directory/persistence authority;
 * no approval, invitation consumption, audit, simulation or dirty marking occurs. Preparation may load an owner's
 * standard shard and copy bounded library metadata, so ingress must admit its management cost separately.
 */
public final class ExchangeDraftResolver {
    private final Thread owner = Thread.currentThread();
    private final SavedNetworkRepository repository;
    private final NetworkDirectory directory;
    private final Set<ResourceLocation> registered;

    public ExchangeDraftResolver(SavedNetworkRepository repository, NetworkDirectory directory) {
        this.repository = Objects.requireNonNull(repository);
        this.directory = Objects.requireNonNull(directory);
        registered = Set.copyOf(repository.resourceAdapters().types());
    }

    /**
     * Resolves a new proposal (null baseline, revision -1) or exact current agreement edit. Only a participating
     * current owner may author terms. Missing or stale authority rejects the whole candidate; returned terms are
     * immutable but do not themselves grant execution or replace final commit checks.
     */
    public ExchangeTerms resolve(
            UUID actor, UUID network, ExchangeTermsDraft draft, @Nullable UUID agreementId, long expectedRevision) {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Exchange draft accessed off server thread");
        Objects.requireNonNull(actor);
        Objects.requireNonNull(draft);
        var metadata = directory.find(network).orElseThrow(() -> new IllegalStateException("Network unavailable"));
        if (!actor.equals(metadata.ownerId()))
            throw new SecurityException("Only network owners may author exchange terms");
        var authority = repository
                .findLoadedNetwork(network)
                .orElseThrow(() -> new IllegalStateException("Network unavailable"));
        if (!metadata.equals(authority.metadata())) throw new IllegalStateException("Network metadata changed");
        ExchangeAgreement baseline = null;
        if (agreementId == null) {
            if (expectedRevision != -1) throw new IllegalStateException("New exchange draft has an existing revision");
        } else {
            baseline = repository
                    .exchangeRepository()
                    .find()
                    .orElseThrow()
                    .agreement(agreementId)
                    .orElseThrow(() -> new IllegalStateException("Agreement unavailable"));
            ExchangeConsent consent = baseline.consent();
            boolean source = network.equals(consent.sourceNetwork());
            if (!source && !network.equals(consent.targetNetwork())
                    || !actor.equals(source ? consent.sourceOwner() : consent.targetOwner()))
                throw new SecurityException("Agreement does not belong to this owner and network");
            if (consent.revoked() || consent.revision() != expectedRevision)
                throw new IllegalStateException("Closed or stale exchange draft");
        }
        validateTypes(draft, baseline);
        ExchangeFilterSnapshot filter = null;
        if (draft.filter() instanceof ExchangeTermsDraft.KeepApproved) {
            if (baseline == null) throw new IllegalStateException("No approved filter snapshot to retain");
            filter = baseline.terms().filter();
        } else if (draft.filter() instanceof ExchangeTermsDraft.OwnerPreset selected) {
            Map<UUID, ResourceFilterPreset> presets = new HashMap<>();
            if (io.github.loongin.omniresonance.filter.BuiltInPresets.find(selected.presetId()) != null)
                throw new IllegalStateException("Retired built-in presets cannot be newly selected");
            var library = repository
                    .findOwner(actor)
                    .orElseThrow(() -> new IllegalStateException("Preset library unavailable"));
            if (library.presetLibraryRevision() != selected.libraryRevision())
                throw new IllegalStateException("Preset library changed during editing");
            if (library.findPreset(selected.presetId()).isEmpty())
                throw new IllegalStateException("Owned preset unavailable");
            for (ResourceFilterPreset preset : library.presets()) presets.put(preset.id(), preset);
            filter = ExchangeFilterSnapshot.capture(
                    selected.presetId(), presets, ResourceFilterPreset.MAX_ENTRIES, ResourceFilterPreset.MAX_ENTRIES);
            ExchangeStateNbt.encodeFilter(filter);
        }
        return new ExchangeTerms(
                draft.scope(),
                draft.filterMode(),
                filter,
                draft.defaultRate(),
                Map.of(),
                draft.intervalTicks(),
                draft.resourceParameters());
    }

    private void validateTypes(ExchangeTermsDraft draft, @Nullable ExchangeAgreement baseline) {
        for (ResourceLocation type : draft.scope().resourceTypeIds()) {
            if (registered.contains(type)) continue;
            if (baseline == null
                    || baseline.terms().scope().kind() != ResourceScope.Kind.CUSTOM_SET
                    || !baseline.terms().scope().resourceTypeIds().contains(type))
                throw new IllegalArgumentException("Cannot introduce an unregistered exchange resource type");
        }
        for (var entry : draft.resourceParameters().entrySet()) {
            if (registered.contains(entry.getKey())) continue;
            if (baseline == null
                    || !entry.getValue()
                            .equals(baseline.terms().resourceParameters().get(entry.getKey())))
                throw new IllegalArgumentException("Unknown resource overrides may only be retained unchanged");
        }
    }
}
