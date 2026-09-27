// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/** Loaded optional AE node; every mounted facade captures a generation and rechecks all live gates. */
final class Ae2InterfaceHost implements IInWorldGridNodeHost, IStorageProvider, IGridNodeListener<Ae2InterfaceHost> {
    final Ae2InterfaceRuntime runtime;
    final ResonanceNodeBlockEntity entity;
    final IManagedGridNode node;
    @Nullable
    UUID member, domain;

    @Nullable
    IGrid grid;

    @Nullable
    Ae2DomainStorage storage;

    private long generation, lastRevision = -1;
    private @Nullable DomainLedger previousLedger;
    boolean stopped, failed;

    Ae2InterfaceHost(Ae2InterfaceRuntime runtime, ResonanceNodeBlockEntity entity) {
        this.runtime = runtime;
        this.entity = entity;
        node = GridHelper.createManagedNode(this, this)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setIdlePowerUsage(1)
                .setVisualRepresentation(Ae2InterfaceContent.ITEM.get())
                .addService(IStorageProvider.class, this)
                .setTagName("ae2");
    }

    void start() {
        node.loadFromNBT(entity.interfaceData());
        if (entity.interfaceOwner() != null) {
            var player = runtime.server.getPlayerList().getPlayer(entity.interfaceOwner());
            if (player != null) node.setOwningPlayer(player);
        }
        node.create(entity.getLevel(), entity.getBlockPos());
        refreshMembership();
    }

    void refreshMembership() {
        if (stopped) return;
        var binding = runtime.binding(entity);
        UUID nextId = entity.state().map(s -> s.nodeId()).orElse(null);
        UUID nextDomain = binding == null ? null : binding.networkId();
        IGrid nextGrid = node.getGrid();
        if (java.util.Objects.equals(nextId, member)
                && java.util.Objects.equals(nextDomain, domain)
                && nextGrid == grid) return;
        if (member != null) {
            runtime.changed(runtime.mounts.remove(member));
            runtime.byMember.remove(member, this);
        }
        generation++;
        member = nextId;
        domain = nextDomain;
        grid = nextGrid;
        storage = null;
        previousLedger = null;
        lastRevision = -1;
        if (member != null && domain != null && grid != null) {
            runtime.byMember.put(member, this);
            runtime.changed(runtime.mounts.bind(member, grid, domain));
        }
        runtime.pending.add(this);
    }

    @Nullable
    DomainLedger current(long expected) {
        if (stopped
                || failed
                || generation != expected
                || entity.isRemoved()
                || domain == null
                || grid == null
                || member == null
                || !node.isActive()
                || node.getGrid() != grid
                || !runtime.mounts.permits(member, grid, domain)) return null;
        var binding = runtime.binding(entity);
        if (binding == null
                || !binding.networkId().equals(domain)
                || !binding.record().enabled()
                || !binding.record().nodeId().equals(member)) return null;
        var existing = runtime.repository.inspectDomain(domain);
        return existing == null
                ? null
                : existing.activatedLedger().filter(DomainLedger::isAvailable).orElse(null);
    }

    String status() {
        if (failed || entity.isUnavailable()) return "unavailable";
        if (domain == null)
            return entity.state()
                            .map(s -> s.linkState() == io.github.loongin.omniresonance.node.NodeLinkState.LINKED)
                            .orElse(false)
                    ? "unavailable"
                    : "unbound";
        var existing = runtime.repository.inspectDomain(domain);
        if (existing != null
                && existing.state() == io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE)
            return "unavailable";
        if (grid != null && runtime.mounts.count(grid, domain) > 1) return "conflict";
        if (!node.isActive()) return "offline";
        return current(generation) == null ? "preparing" : "ready";
    }

    void prepare() {
        if (failed || stopped) return;
        refreshMembership();
        if (domain != null
                && member != null
                && grid != null
                && node.isActive()
                && runtime.mounts.permits(member, grid, domain))
            runtime.repository.domainStorage(domain).activate();
        var ledger = current(generation);
        boolean conflict = domain != null && grid != null && runtime.mounts.count(grid, domain) > 1;
        if (entity.getBlockState().getBlock() instanceof Ae2InterfaceBlock
                && entity.getBlockState().getValue(Ae2InterfaceBlock.CONFLICT) != conflict)
            entity.getLevel()
                    .setBlock(
                            entity.getBlockPos(),
                            entity.getBlockState().setValue(Ae2InterfaceBlock.CONFLICT, conflict),
                            2);
        if (ledger != previousLedger) {
            previousLedger = ledger;
            lastRevision = -1;
            runtime.pending.add(this);
        }
        if (ledger != null && ledger.revision() != lastRevision) {
            lastRevision = ledger.revision();
            node.ifPresent(g -> g.getStorageService().invalidateCache());
        }
    }

    void refreshMount() {
        if (!stopped) {
            storage = null;
            IStorageProvider.requestUpdate(node);
            node.ifPresent(g -> g.getStorageService().invalidateCache());
        }
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        if (current(generation) == null) return;
        if (storage == null) {
            long expected = generation;
            storage = new Ae2DomainStorage(
                    new Ae2DomainAccess(
                            () -> current(expected),
                            () -> runtime.settings.get().storageVariantLimitPerNetwork()),
                    () -> entity.getLevel().registryAccess());
        }
        mounts.mount(storage);
    }

    @Override
    public @Nullable IGridNode getGridNode(Direction side) {
        return stopped ? null : node.getNode();
    }

    @Override
    public void onSaveChanges(Ae2InterfaceHost owner, IGridNode gridNode) {
        if (!stopped) {
            var tag = entity.interfaceData();
            node.saveToNBT(tag);
            entity.interfaceData(tag);
        }
    }

    @Override
    public void onGridChanged(Ae2InterfaceHost owner, IGridNode gridNode) {
        refreshMembership();
    }

    @Override
    public void onStateChanged(Ae2InterfaceHost owner, IGridNode gridNode, IGridNodeListener.State state) {
        runtime.pending.add(this);
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        generation++;
        if (member != null) runtime.changed(runtime.mounts.remove(member));
        if (member != null) runtime.byMember.remove(member, this);
        node.destroy();
        storage = null;
        grid = null;
        domain = null;
    }
}
