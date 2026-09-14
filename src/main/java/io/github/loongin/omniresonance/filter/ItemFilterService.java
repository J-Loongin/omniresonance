// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.networking.FilterImpactSummary;
import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.FilterRulePage;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.OwnerSavedData;
import io.github.loongin.omniresonance.persistence.ResourceFilterPresetNbt;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread owner-library management using the shared object leases. Returned pages and edit identities are
 * immutable; SavedData stays repository-owned. Reads never create data, simulation is not supported, and failed
 * validation precedes mutation. Management requests derive references from every persisted binding, independently
 * of the active transport/node index. No authority cache, world scan, external call or synchronous save is used.
 */
public final class ItemFilterService {
    public enum Reason {
        NO_ACCESS,
        UNAVAILABLE,
        LOCKED,
        LOCK_EXPIRED,
        STALE_REVISION,
        INVALID_REQUEST,
        NAME_CONFLICT,
        QUOTA_REACHED
    }

    public static final class Rejected extends IllegalStateException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    public record Reference(UUID networkId, UUID nodeId, UUID channelId) {}

    public record Edit(
            EditLockTable.Token token,
            UUID networkId,
            UUID ownerId,
            @Nullable UUID presetId,
            PresetEditOperation operation,
            long revision,
            Set<Reference> references,
            FilterImpactSummary impact,
            String originalRule) {
        public Edit {
            references = Set.copyOf(references);
        }
    }

    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory networks;
    private final EditLockTable locks;
    private final Supplier<ServerSettings> settings;
    private final Consumer<UUID> ownerLibraryChanged;
    private final Supplier<UUID> ids;
    private long currentTick;

    public ItemFilterService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            EditLockTable locks,
            Supplier<ServerSettings> settings,
            Consumer<UUID> ownerLibraryChanged,
            Supplier<UUID> ids) {
        this.server = Objects.requireNonNull(server, "server");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.networks = Objects.requireNonNull(networks, "networks");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ownerLibraryChanged = Objects.requireNonNull(ownerLibraryChanged, "ownerLibraryChanged");
        this.ids = Objects.requireNonNull(ids, "ids");
        requireThread();
    }

    public FilterPresetPage page(ServerPlayer actor, UUID networkId, int offset) {
        return page(actor, networkId, offset, "", -1);
    }

    /** Server-thread, read-only name query over the authorized owner's entire library, returning at most 128 summaries.
     * A changed library revision or deleted page restarts at zero; no authoritative state is modified.
     */
    public FilterPresetPage page(ServerPlayer actor, UUID networkId, int offset, String query, long libraryRevision) {
        if (offset < 0 || offset > 262144 || query == null || query.length() > 256 || libraryRevision < -1)
            throw rejected(Reason.STALE_REVISION);
        NetworkMetadata network = requireNetwork(actor, networkId);
        OwnerSavedData owner = owner(network.ownerId());
        if (owner == null) {
            return new FilterPresetPage(List.of(), 0, 0, 0);
        }
        String folded = query.toLowerCase(java.util.Locale.ROOT);
        List<ResourceFilterPreset> presets = new ArrayList<>();
        for (ResourceFilterPreset preset : owner.presets()) {
            if (preset.name().value().toLowerCase(java.util.Locale.ROOT).contains(folded)) presets.add(preset);
        }
        presets.sort(Comparator.comparing(preset -> preset.id()));
        if ((libraryRevision >= 0 && libraryRevision != owner.presetLibraryRevision()) || offset >= presets.size())
            offset = 0;
        List<FilterPresetSummary> result = new ArrayList<>();
        for (int i = offset; i < Math.min(presets.size(), offset + 128); i++) {
            ResourceFilterPreset preset = presets.get(i);
            result.add(summary(preset, canEdit(actor, network.ownerId(), references(network.ownerId(), preset.id()))));
        }
        return new FilterPresetPage(result, offset, presets.size(), owner.presetLibraryRevision());
    }

    public @Nullable FilterPresetSummary summary(ServerPlayer actor, UUID networkId, UUID presetId) {
        NetworkMetadata network = requireNetwork(actor, networkId);
        OwnerSavedData owner = owner(network.ownerId());
        ResourceFilterPreset preset =
                owner == null ? null : owner.findPreset(presetId).orElse(null);
        return preset == null
                ? null
                : summary(preset, canEdit(actor, network.ownerId(), references(network.ownerId(), presetId)));
    }

    public FilterRulePage rules(ServerPlayer actor, UUID networkId, UUID presetId, long revision, int offset) {
        NetworkMetadata network = requireNetwork(actor, networkId);
        ResourceFilterPreset preset = preset(network.ownerId(), presetId);
        if (preset.revision() != revision) throw rejected(Reason.STALE_REVISION);
        List<ResourceFilterRule> sorted = new ArrayList<>(preset.rules());
        sorted.sort(Comparator.comparing(ItemFilterService::ruleLabel).thenComparing(ResourceFilterRule::id));
        if (offset < 0 || offset > sorted.size()) throw rejected(Reason.STALE_REVISION);
        List<String> result = new ArrayList<>();
        List<UUID> identities = new ArrayList<>();
        int bytes = 0;
        for (int i = offset; i < sorted.size() && result.size() < FilterRulePage.MAXIMUM_ENTRIES; i++) {
            String label = ruleLabel(sorted.get(i));
            if (bytes + label.length() + 19 > FilterRulePage.MAXIMUM_ENCODED_RULE_BYTES) break;
            result.add(label);
            identities.add(sorted.get(i).id());
            bytes += label.length() + 19;
        }
        int previousOffset = offset;
        int previousBytes = 0;
        while (previousOffset > 0 && offset - previousOffset < FilterRulePage.MAXIMUM_ENTRIES) {
            int size = ruleLabel(sorted.get(previousOffset - 1)).length() + 19;
            if (previousBytes + size > FilterRulePage.MAXIMUM_ENCODED_RULE_BYTES) break;
            previousBytes += size;
            previousOffset--;
        }
        return new FilterRulePage(result, offset, sorted.size(), previousOffset, identities);
    }

    public Edit begin(ServerPlayer actor, UUID networkId, PresetEditOperation operation, @Nullable UUID presetId) {
        return begin(actor, networkId, operation, presetId, "");
    }

    /** Pins the exact existing rule before acquiring a server-owned edit lease; no mutation occurs here. */
    public Edit begin(
            ServerPlayer actor,
            UUID networkId,
            PresetEditOperation operation,
            @Nullable UUID presetId,
            String originalRule) {
        NetworkMetadata network = requireNetwork(actor, networkId);
        if ((operation == PresetEditOperation.CREATE) != (presetId == null)) throw rejected(Reason.INVALID_REQUEST);
        OwnerSavedData owner = owner(network.ownerId());
        ResourceFilterPreset preset = presetId == null ? null : preset(network.ownerId(), presetId);
        if ((operation == PresetEditOperation.ADD_RULE
                        || operation == PresetEditOperation.EDIT_RULE
                        || operation == PresetEditOperation.REMOVE_RULE)
                && !ResourceFilterPresetNbt.isCompact(Objects.requireNonNull(preset)))
            throw rejected(Reason.INVALID_REQUEST);
        if (operation == PresetEditOperation.EDIT_RULE) {
            if (originalRule == null || originalRule.length() > ItemFilterPreset.MAXIMUM_ITEM_ID_BYTES)
                throw rejected(Reason.INVALID_REQUEST);
            ResourceLocation source = ResourceLocation.tryParse(originalRule);
            if (source == null
                    || !source.toString().equals(originalRule)
                    || !containsExact(Objects.requireNonNull(preset), source)) throw rejected(Reason.INVALID_REQUEST);
        } else if (!"".equals(originalRule)) throw rejected(Reason.INVALID_REQUEST);
        Set<Reference> references = presetId == null || operation == PresetEditOperation.COPY
                ? Set.of()
                : references(network.ownerId(), presetId);
        if (operation != PresetEditOperation.CREATE
                && operation != PresetEditOperation.COPY
                && !canEdit(actor, network.ownerId(), references)) throw rejected(Reason.NO_ACCESS);
        if ((operation == PresetEditOperation.CREATE || operation == PresetEditOperation.COPY))
            requirePresetQuota(owner);
        EditLockTable.Token token = locks.tryAcquire(lockId(network.ownerId(), presetId), actor.getUUID(), currentTick)
                .orElseThrow(() -> rejected(Reason.LOCKED));
        return new Edit(
                token,
                networkId,
                network.ownerId(),
                presetId,
                operation,
                preset == null ? -1 : preset.revision(),
                references,
                impact(references),
                originalRule);
    }

    /** Commits one explicit intent after late permission, reference-impact, object revision and exact-lease checks. */
    public @Nullable UUID save(ServerPlayer actor, Edit edit, String value) {
        requireEdit(actor, edit);
        OwnerSavedData owner = owner(edit.ownerId());
        ResourceFilterPreset previous = edit.presetId() == null ? null : preset(edit.ownerId(), edit.presetId());
        if (edit.operation() == PresetEditOperation.DELETE) {
            if (!value.isEmpty()) throw rejected(Reason.INVALID_REQUEST);
            Objects.requireNonNull(owner)
                    .removePreset(Objects.requireNonNull(edit.presetId()), owner.presetLibraryRevision());
            committed(actor, edit);
            return null;
        }
        boolean creates =
                edit.operation() == PresetEditOperation.CREATE || edit.operation() == PresetEditOperation.COPY;
        UUID id = creates ? Objects.requireNonNull(ids.get(), "preset id") : Objects.requireNonNull(edit.presetId());
        ManagedName name;
        try {
            name = switch (edit.operation()) {
                case CREATE, COPY, RENAME -> new ManagedName(value);
                case ADD_RULE, REMOVE_RULE, EDIT_RULE ->
                    Objects.requireNonNull(previous).name();
                case DELETE -> throw rejected(Reason.INVALID_REQUEST);
            };
        } catch (IllegalArgumentException failure) {
            throw rejected(Reason.INVALID_REQUEST);
        }
        List<ResourceFilterRule> rules = previous == null ? new ArrayList<>() : new ArrayList<>(previous.rules());
        if (edit.operation() == PresetEditOperation.ADD_RULE
                || edit.operation() == PresetEditOperation.REMOVE_RULE
                || edit.operation() == PresetEditOperation.EDIT_RULE) {
            ResourceLocation itemId = ResourceLocation.tryParse(value);
            if (itemId == null
                    || !itemId.toString().equals(value)
                    || value.length() > ItemFilterPreset.MAXIMUM_ITEM_ID_BYTES) throw rejected(Reason.INVALID_REQUEST);
            if (edit.operation() == PresetEditOperation.EDIT_RULE) {
                ResourceLocation source = ResourceLocation.parse(edit.originalRule());
                if (!rules.removeIf(rule -> exactItem(rule, source))) throw rejected(Reason.INVALID_REQUEST);
            }
            if (edit.operation() == PresetEditOperation.REMOVE_RULE) rules.removeIf(rule -> exactItem(rule, itemId));
            else if (rules.stream().noneMatch(rule -> exactItem(rule, itemId)))
                rules.add(new ResourceFilterRule.Match(
                        ResourceFilterPresetNbt.compactRuleId(id, itemId),
                        ResourceTypes.ITEM,
                        ResourceFilterRule.Selector.exact(itemId),
                        ComponentCondition.idOnly()));
        }
        ResourceFilterPreset next = edit.operation() == PresetEditOperation.COPY
                ? ResourceFilterPresetNbt.copy(Objects.requireNonNull(previous), id, name)
                : new ResourceFilterPreset(id, name, creates ? 0 : Math.incrementExact(edit.revision()), rules);
        if (owner != null
                && owner.findPreset(name)
                        .filter(existing -> !existing.id().equals(id))
                        .isPresent()) throw rejected(Reason.NAME_CONFLICT);
        if (creates) {
            requirePresetQuota(owner);
            if (owner != null && owner.findPreset(id).isPresent()) throw rejected(Reason.UNAVAILABLE);
        }
        ServerSettings.FilterLimits limits = settings.get().filterLimits();
        if (limits.rulesPerFilterPreset() >= 0
                && rules.size() > limits.rulesPerFilterPreset()
                && (creates
                        || rules.size()
                                > Objects.requireNonNull(previous).rules().size()))
            throw rejected(Reason.QUOTA_REACHED);
        ResourceFilterPresetNbt.encode(next);
        if (owner == null) owner = repository.createOwner(edit.ownerId(), null);
        owner.putPreset(
                next, owner.presetLibraryRevision(), limits.filterPresetsPerOwner(), limits.rulesPerFilterPreset());
        committed(actor, edit);
        return id;
    }

    /** Acquires an exact full-rule edit, retaining its server identity and all component data behind the lease. */
    public Edit beginRule(ServerPlayer actor, UUID networkId, UUID presetId, @Nullable UUID ruleId, boolean remove) {
        NetworkMetadata network = requireNetwork(actor, networkId);
        ResourceFilterPreset preset = preset(network.ownerId(), presetId);
        if (ruleId != null
                && preset.rules().stream().noneMatch(rule -> rule.id().equals(ruleId)))
            throw rejected(Reason.INVALID_REQUEST);
        if (remove && ruleId == null) throw rejected(Reason.INVALID_REQUEST);
        Edit edit = begin(actor, networkId, PresetEditOperation.RENAME, presetId);
        return new Edit(
                edit.token(),
                edit.networkId(),
                edit.ownerId(),
                edit.presetId(),
                remove
                        ? PresetEditOperation.REMOVE_RULE
                        : ruleId == null ? PresetEditOperation.ADD_RULE : PresetEditOperation.EDIT_RULE,
                edit.revision(),
                edit.references(),
                edit.impact(),
                ruleId == null ? "" : ruleId.toString());
    }

    /** Reads the exact authorized rule at the pinned revision, without mutation or client component authority. */
    public ResourceFilterRule rule(ServerPlayer actor, UUID networkId, UUID presetId, long revision, UUID ruleId) {
        NetworkMetadata network = requireNetwork(actor, networkId);
        ResourceFilterPreset preset = preset(network.ownerId(), presetId);
        if (preset.revision() != revision) throw rejected(Reason.STALE_REVISION);
        return preset.rules().stream()
                .filter(rule -> rule.id().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
    }

    /** Commits one typed intent; trusted old values stay server-owned and every late permission check is repeated. */
    public UUID saveRule(ServerPlayer actor, Edit edit, @Nullable ResourceRuleIntent intent) {
        requireEdit(actor, edit);
        if (edit.operation() != PresetEditOperation.ADD_RULE
                && edit.operation() != PresetEditOperation.EDIT_RULE
                && edit.operation() != PresetEditOperation.REMOVE_RULE) throw rejected(Reason.INVALID_REQUEST);
        ResourceFilterPreset previous = preset(edit.ownerId(), Objects.requireNonNull(edit.presetId()));
        UUID oldId = edit.originalRule().isEmpty() ? null : UUID.fromString(edit.originalRule());
        ResourceFilterRule old = oldId == null
                ? null
                : previous.rules().stream()
                        .filter(rule -> rule.id().equals(oldId))
                        .findFirst()
                        .orElseThrow(() -> rejected(Reason.STALE_REVISION));
        List<ResourceFilterRule> rules = new ArrayList<>(previous.rules());
        if (old != null) rules.remove(old);
        if (edit.operation() == PresetEditOperation.REMOVE_RULE) {
            if (old == null || intent != null) throw rejected(Reason.INVALID_REQUEST);
        } else {
            Objects.requireNonNull(intent);
            UUID ruleId = oldId == null ? ids.get() : oldId;
            ResourceFilterRule next;
            if (intent instanceof ResourceRuleIntent.Reference reference) {
                if (!(old instanceof ResourceFilterRule.Reference saved
                                && saved.presetId().equals(reference.presetId()))
                        && owner(edit.ownerId())
                                .findPreset(reference.presetId())
                                .isEmpty()) throw rejected(Reason.INVALID_REQUEST);
                next = new ResourceFilterRule.Reference(ruleId, reference.presetId());
            } else {
                ResourceRuleIntent.Match match = (ResourceRuleIntent.Match) intent;
                if (!ResourceTypes.ITEM.equals(match.typeId())
                        && !ResourceTypes.FLUID.equals(match.typeId())
                        && !ResourceTypes.ENERGY.equals(match.typeId())) throw rejected(Reason.INVALID_REQUEST);
                ComponentCondition trusted = old instanceof ResourceFilterRule.Match saved
                                && saved.resourceTypeId().equals(match.typeId())
                        ? saved.components()
                        : ComponentCondition.idOnly();
                if (match.sampleToken() != null)
                    trusted = sampledCondition(actor, edit, match.sampleToken(), match.typeId());
                ComponentCondition condition = trusted.selectTrusted(match.mode(), match.selectedKeys());
                if ((old == null || ResourceFilterPresetNbt.isCompact(previous))
                        && match.typeId().equals(ResourceTypes.ITEM)
                        && match.selector() instanceof ResourceFilterRule.Exact exact
                        && condition.isIdOnly()) {
                    ruleId = ResourceFilterPresetNbt.compactRuleId(previous.id(), exact.resourceId());
                    for (ResourceFilterRule existing : rules)
                        if (exactItem(existing, exact.resourceId())) {
                            ruleId = existing.id();
                            break;
                        }
                    rules.removeIf(rule -> exactItem(rule, exact.resourceId()));
                }
                next = new ResourceFilterRule.Match(ruleId, match.typeId(), match.selector(), condition);
            }
            rules.add(next);
        }
        OwnerSavedData owner = Objects.requireNonNull(owner(edit.ownerId()));
        ResourceFilterPreset next = new ResourceFilterPreset(
                previous.id(), previous.name(), Math.incrementExact(previous.revision()), rules);
        ResourceFilterGraph.validate(owner.presets(), next);
        ResourceFilterPresetNbt.encode(next);
        ServerSettings.FilterLimits limits = settings.get().filterLimits();
        owner.putPreset(
                next, owner.presetLibraryRevision(), limits.filterPresetsPerOwner(), limits.rulesPerFilterPreset());
        committed(actor, edit);
        return next.id();
    }

    public record SampleResult(
            UUID token,
            ResourceLocation typeId,
            @Nullable ResourceLocation resourceId,
            @Nullable ComponentCondition components,
            int tankCount,
            int tank,
            String failure) {}

    private final java.util.Map<UUID, SampleTask> sampleTasks = new java.util.HashMap<>();
    private final java.util.ArrayDeque<UUID> sampleQueue = new java.util.ArrayDeque<>();

    private static final class SampleTask {
        private final ServerPlayer actor;
        private final Edit edit;
        private final UUID token;
        private final ResourceLocation type;
        private final int slot, tank;
        private final net.minecraft.world.item.ItemStack original, copy;
        private final java.util.function.BooleanSupplier sessionValid, requestValid;
        private final Consumer<SampleResult> completed;
        private @Nullable net.neoforged.neoforge.fluids.capability.IFluidHandlerItem fluid;
        private int stage, tankCount;
        private @Nullable SampleResult result;

        private SampleTask(
                ServerPlayer actor,
                Edit edit,
                UUID token,
                ResourceLocation type,
                int slot,
                int tank,
                java.util.function.BooleanSupplier sessionValid,
                java.util.function.BooleanSupplier requestValid,
                Consumer<SampleResult> completed) {
            this.actor = actor;
            this.edit = edit;
            this.token = token;
            this.type = type;
            this.slot = slot;
            this.tank = tank;
            this.sessionValid = sessionValid;
            this.requestValid = requestValid;
            this.completed = completed;
            original = actor.getInventory().getItem(slot).copy();
            copy = original.copy();
        }
    }

    /** Queues one non-consuming inventory sample, replacing only this actor's sample at the exact authorized lease. */
    public UUID requestSample(
            ServerPlayer actor,
            Edit edit,
            ResourceLocation type,
            int inventorySlot,
            int tank,
            java.util.function.BooleanSupplier sessionValid,
            Consumer<SampleResult> completed) {
        return requestSample(actor, edit, type, inventorySlot, tank, sessionValid, () -> true, completed);
    }

    /**
     * Queues server-thread sample work with a separate exact-request lifetime. Both predicates are read-only,
     * server-owned callbacks; the request predicate governs only unfinished native work and publication. A successful
     * snapshot remains authorized by its edit/session after its logical request completes. Rejection changes no task.
     */
    public UUID requestSample(
            ServerPlayer actor,
            Edit edit,
            ResourceLocation type,
            int inventorySlot,
            int tank,
            java.util.function.BooleanSupplier sessionValid,
            java.util.function.BooleanSupplier requestValid,
            Consumer<SampleResult> completed) {
        requireEdit(actor, edit);
        if (!ResourceTypes.ITEM.equals(type) && !ResourceTypes.FLUID.equals(type)
                || inventorySlot < 0
                || inventorySlot >= actor.getInventory().getContainerSize()
                || tank < 0
                || !sessionValid.getAsBoolean()
                || !requestValid.getAsBoolean()) throw rejected(Reason.INVALID_REQUEST);
        if (!sampleTasks.containsKey(actor.getUUID()) && sampleTasks.size() >= Math.max(1, server.getMaxPlayers()))
            throw rejected(Reason.QUOTA_REACHED);
        UUID token = Objects.requireNonNull(ids.get());
        SampleTask task =
                new SampleTask(actor, edit, token, type, inventorySlot, tank, sessionValid, requestValid, completed);
        releaseSample(actor.getUUID());
        sampleTasks.put(actor.getUUID(), task);
        sampleQueue.addLast(actor.getUUID());
        return token;
    }

    /** One fair, resumable native query step, using the exact logistics tick budget; never calls drain/fill/extract. */
    public void sampleStep(io.github.loongin.omniresonance.transfer.TransferWorkBudget budget) {
        requireThread();
        if (!budget.canStart() || sampleQueue.isEmpty()) return;
        UUID actorId = sampleQueue.removeFirst();
        SampleTask task = sampleTasks.get(actorId);
        if (task == null) return;
        if (!validSample(task)) {
            failChangedSample(task);
            return;
        }
        try {
            if (task.original.isEmpty()) finishSample(task, null, null, "empty");
            else if (ResourceTypes.ITEM.equals(task.type)) {
                var variant =
                        io.github.loongin.omniresonance.transfer.ItemVariant.from(task.copy, server.registryAccess());
                finishSample(task, variant.itemId(), ComponentCondition.full(variant), "");
            } else if (task.stage == 0) {
                budget.beforeCall();
                try {
                    task.fluid =
                            task.copy.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM);
                } finally {
                    budget.afterCall();
                }
                if (task.fluid == null) finishSample(task, null, null, "no_capability");
                else task.stage++;
            } else if (task.stage == 1) {
                budget.beforeCall();
                try {
                    task.tankCount = Objects.requireNonNull(task.fluid).getTanks();
                } finally {
                    budget.afterCall();
                }
                if (task.tankCount < 0 || task.tank >= task.tankCount) finishSample(task, null, null, "invalid_tank");
                else task.stage++;
            } else {
                net.neoforged.neoforge.fluids.FluidStack stack;
                budget.beforeCall();
                try {
                    stack = Objects.requireNonNull(task.fluid).getFluidInTank(task.tank);
                } finally {
                    budget.afterCall();
                }
                if (stack == null || stack.isEmpty()) finishSample(task, null, null, "empty");
                else {
                    var variant = io.github.loongin.omniresonance.transfer.FluidVariant.from(
                            stack.copy(), server.registryAccess());
                    finishSample(task, variant.fluidId(), ComponentCondition.full(variant), "");
                }
            }
        } catch (RuntimeException failure) {
            finishSample(task, null, null, "invalid_sample");
        }
        if (sampleTasks.get(actorId) == task && task.result == null) sampleQueue.addLast(actorId);
    }

    private void finishSample(
            SampleTask task,
            @Nullable ResourceLocation resource,
            @Nullable ComponentCondition components,
            String failure) {
        if (task.result != null || sampleTasks.get(task.actor.getUUID()) != task) return;
        if (!validSample(task)) {
            failChangedSample(task);
            return;
        }
        task.result = new SampleResult(task.token, task.type, resource, components, task.tankCount, task.tank, failure);
        task.fluid = null;
        task.completed.accept(task.result);
    }

    private void failChangedSample(SampleTask task) {
        boolean notify = validSampleSession(task);
        if (sampleTasks.get(task.actor.getUUID()) != task) return;
        releaseSample(task.actor.getUUID());
        if (notify)
            task.completed.accept(new SampleResult(
                    task.token, task.type, null, null, Math.max(0, task.tankCount), task.tank, "inventory_changed"));
    }

    private boolean validSample(SampleTask task) {
        return validSampleSession(task)
                && net.minecraft.world.item.ItemStack.matches(
                        task.original, task.actor.getInventory().getItem(task.slot));
    }

    private boolean validSampleSession(SampleTask task) {
        if (sampleTasks.get(task.actor.getUUID()) != task
                || !task.sessionValid.getAsBoolean()
                || task.result == null && !task.requestValid.getAsBoolean()) return false;
        try {
            requireEdit(task.actor, task.edit);
            return true;
        } catch (Rejected failure) {
            return false;
        }
    }

    private ComponentCondition sampledCondition(ServerPlayer actor, Edit edit, UUID token, ResourceLocation type) {
        SampleTask task = sampleTasks.get(actor.getUUID());
        if (task == null
                || !task.edit.token().equals(edit.token())
                || !task.token.equals(token)
                || task.result == null
                || task.result.components() == null
                || !task.type.equals(type)
                || !task.sessionValid.getAsBoolean()) throw rejected(Reason.INVALID_REQUEST);
        return task.result.components();
    }

    /**
     * Cancels exactly one server-owned sample identity on the server thread, including an expired request.
     * Does not release the edit lease, mutate authority or cancel another request sharing the same actor/edit.
     * Returns false for an already released or replaced task; no simulation or external capability call occurs.
     */
    public boolean cancelSample(ServerPlayer actor, Edit edit, UUID token) {
        requireActor(actor);
        SampleTask task = sampleTasks.get(actor.getUUID());
        if (task == null || !task.edit.token().equals(edit.token()) || !task.token.equals(token)) return false;
        releaseSample(actor.getUUID());
        return true;
    }

    private void releaseSampleForEdit(UUID actorId, Edit edit) {
        SampleTask task = sampleTasks.get(actorId);
        if (task != null && task.edit.token().equals(edit.token())) releaseSample(actorId);
    }

    private void releaseSample(UUID actorId) {
        SampleTask removed = sampleTasks.remove(actorId);
        if (removed != null) removed.fluid = null;
        sampleQueue.remove(actorId);
    }

    /** Read-only current actor/lease/revision/impact validation; does not renew an edit or mutate authority. */
    public void validateEdit(ServerPlayer actor, Edit edit) {
        requireEdit(actor, edit);
    }

    public void heartbeat(ServerPlayer actor, Edit edit) {
        requireEdit(actor, edit);
        if (!locks.renew(edit.token(), actor.getUUID(), currentTick)) throw rejected(Reason.LOCK_EXPIRED);
    }

    public void cancel(ServerPlayer actor, Edit edit) {
        requireActor(actor);
        releaseSampleForEdit(actor.getUUID(), edit);
        locks.release(edit.token(), actor.getUUID());
    }

    public void tick() {
        requireThread();
        currentTick = Math.incrementExact(currentTick);
        for (UUID actorId : new ArrayList<>(sampleTasks.keySet())) {
            SampleTask task = sampleTasks.get(actorId);
            if (task != null
                    && (!task.sessionValid.getAsBoolean() || !locks.isHeld(task.edit.token(), actorId, currentTick)))
                releaseSample(actorId);
        }
    }

    private void requireEdit(ServerPlayer actor, Edit edit) {
        requireActor(actor);
        if (!locks.isHeld(edit.token(), actor.getUUID(), currentTick)
                || !edit.token().objectId().equals(lockId(edit.ownerId(), edit.presetId())))
            throw rejected(Reason.LOCK_EXPIRED);
        try {
            NetworkMetadata network = requireNetwork(actor, edit.networkId());
            if (!network.ownerId().equals(edit.ownerId())) throw rejected(Reason.NO_ACCESS);
            if (edit.presetId() != null
                    && preset(edit.ownerId(), edit.presetId()).revision() != edit.revision())
                throw rejected(Reason.STALE_REVISION);
            if (edit.operation() != PresetEditOperation.CREATE && edit.operation() != PresetEditOperation.COPY) {
                Set<Reference> current = references(edit.ownerId(), Objects.requireNonNull(edit.presetId()));
                if (!canEdit(actor, edit.ownerId(), current)) throw rejected(Reason.NO_ACCESS);
                if (!current.equals(edit.references()) || !impact(current).equals(edit.impact()))
                    throw rejected(Reason.STALE_REVISION);
            }
        } catch (Rejected failure) {
            releaseSampleForEdit(actor.getUUID(), edit);
            locks.release(edit.token(), actor.getUUID());
            throw failure;
        }
    }

    private void committed(ServerPlayer actor, Edit edit) {
        releaseSampleForEdit(actor.getUUID(), edit);
        locks.release(edit.token(), actor.getUUID());
        ownerLibraryChanged.accept(edit.ownerId());
    }

    private void requirePresetQuota(@Nullable OwnerSavedData owner) {
        int limit = settings.get().filterLimits().filterPresetsPerOwner();
        if (limit >= 0 && (owner == null ? 0 : owner.presets().size()) >= limit) throw rejected(Reason.QUOTA_REACHED);
    }

    private Set<Reference> references(UUID ownerId, UUID presetId) {
        OwnerSavedData owner = owner(ownerId);
        Set<UUID> affected =
                owner == null ? Set.of(presetId) : ResourceFilterGraph.ancestors(owner.presets(), presetId);
        java.util.Map<UUID, Set<Reference>> direct = referenceGroups(ownerId);
        Set<Reference> result = new HashSet<>();
        for (UUID id : affected) result.addAll(direct.getOrDefault(id, Set.of()));
        return Set.copyOf(result);
    }

    private java.util.Map<UUID, Set<Reference>> referenceGroups(UUID ownerId) {
        java.util.Map<UUID, Set<Reference>> result = new java.util.HashMap<>();
        for (UUID networkId : repository.loadedNetworkIds()) {
            NetworkSavedData network = repository.findLoadedNetwork(networkId).orElseThrow();
            if (!network.metadata().ownerId().equals(ownerId)) continue;
            for (NetworkNodeRecord node : network.nodes()) {
                for (DirectNodeBinding binding : network.directBindings(node.nodeId())) {
                    UUID presetId = binding.policy().filterPresetId();
                    if (presetId != null)
                        result.computeIfAbsent(presetId, ignored -> new HashSet<>())
                                .add(new Reference(networkId, node.nodeId(), binding.channelId()));
                }
            }
        }
        return result;
    }

    private boolean canEdit(ServerPlayer actor, UUID ownerId, Set<Reference> references) {
        if (ownerId.equals(actor.getUUID())) return true;
        if (repository.hasUnreadableNetworkShards()) return false;
        for (Reference reference : references) {
            NetworkSavedData network =
                    repository.findLoadedNetwork(reference.networkId()).orElseThrow();
            if (!network.metadata().equals(networks.find(reference.networkId()).orElse(null))
                    || !NetworkPermissions.canManage(
                            actor.getUUID(), ownerId, network.metadata().administrators())) return false;
        }
        return true;
    }

    private FilterImpactSummary impact(Set<Reference> references) {
        Set<UUID> networks = new HashSet<>();
        Set<String> nodes = new HashSet<>();
        for (Reference reference : references) {
            networks.add(reference.networkId());
            nodes.add(reference.networkId() + "/" + reference.nodeId());
        }
        return new FilterImpactSummary(
                networks.size(), nodes.size(), references.size(), !repository.hasUnreadableNetworkShards());
    }

    private static boolean containsExact(ResourceFilterPreset preset, ResourceLocation id) {
        return preset.rules().stream().anyMatch(rule -> exactItem(rule, id));
    }

    private static boolean exactItem(ResourceFilterRule rule, ResourceLocation id) {
        return rule instanceof ResourceFilterRule.Match match
                && match.resourceTypeId().equals(ResourceTypes.ITEM)
                && match.components().isIdOnly()
                && match.selector() instanceof ResourceFilterRule.Exact exact
                && exact.resourceId().equals(id);
    }

    public static String ruleLabel(ResourceFilterRule rule) {
        if (rule instanceof ResourceFilterRule.Reference reference) return "@" + reference.presetId();
        ResourceFilterRule.Match match = (ResourceFilterRule.Match) rule;
        if (match.selector() instanceof ResourceFilterRule.Exact exact)
            return exact.resourceId().toString();
        if (match.selector() instanceof ResourceFilterRule.TagSelector tag) {
            String id = tag.tagId().toString();
            return id.length() < 65535 ? "#" + id : id;
        }
        if (match.selector() instanceof ResourceFilterRule.Glob glob)
            return glob.glob().pattern();
        return match.resourceTypeId().toString();
    }

    private static FilterPresetSummary summary(ResourceFilterPreset preset, boolean editable) {
        return new FilterPresetSummary(
                preset.id(),
                preset.name().value(),
                preset.revision(),
                preset.rules().size(),
                editable);
    }

    private @Nullable OwnerSavedData owner(UUID ownerId) {
        try {
            return repository.findOwner(ownerId).orElse(null);
        } catch (IllegalStateException failure) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    private ResourceFilterPreset preset(UUID ownerId, UUID presetId) {
        OwnerSavedData owner = owner(ownerId);
        if (owner == null) throw rejected(Reason.UNAVAILABLE);
        return owner.findPreset(presetId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
    }

    private NetworkMetadata requireNetwork(ServerPlayer actor, UUID networkId) {
        requireActor(actor);
        NetworkMetadata metadata = repository
                .findLoadedNetwork(networkId)
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE))
                .metadata();
        if (!metadata.equals(networks.find(networkId).orElse(null))) throw rejected(Reason.UNAVAILABLE);
        if (!NetworkPermissions.canManage(actor.getUUID(), metadata.ownerId(), metadata.administrators()))
            throw rejected(Reason.NO_ACCESS);
        return metadata;
    }

    private static UUID lockId(UUID ownerId, @Nullable UUID presetId) {
        return UUID.nameUUIDFromBytes(
                ("omniresonance:filter/" + ownerId + "/" + (presetId == null ? "create" : presetId))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void requireActor(ServerPlayer actor) {
        requireThread();
        if (actor.server != server) throw rejected(Reason.NO_ACCESS);
    }

    private void requireThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Filter management accessed outside server thread");
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }
}
