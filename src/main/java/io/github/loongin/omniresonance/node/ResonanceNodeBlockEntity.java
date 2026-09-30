// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.registry.ModBlockEntities;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-authoritative local identity for one physical node or panel.
 *
 * <p>A fresh world placement may initialize exactly once. Calling any NBT load path, including an empty tag,
 * permanently distinguishes decoded data from fresh placement so missing/corrupt data cannot gain a replacement
 * UUID. Values are immutable and no network record, capability, menu, simulation or asynchronous work is owned.
 */
public final class ResonanceNodeBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResonanceNodeBlockEntity.class);
    private @Nullable NodePersistentState persistentState;
    private CompoundTag interfaceData = new CompoundTag();
    private @Nullable UUID interfaceOwner;
    /** Immutable cached authorization identity; no NBT copying or world access. */
    public @Nullable UUID interfaceOwner() {
        requireServerThreadIfAttached();
        return interfaceOwner;
    }
    /** Server-thread detached optional integration data; contains no domain inventory. */
    public CompoundTag interfaceData() {
        requireServerThreadIfAttached();
        return interfaceData.copy();
    }

    public void interfaceData(CompoundTag value) {
        requireServerThreadIfAttached();
        interfaceData = value.copy();
        interfaceOwner = interfaceData.hasUUID("domain_owner") ? interfaceData.getUUID("domain_owner") : null;
        setChanged();
    }

    /** Server-owned, nonpersistent connection metadata: six faces each for items, fluids, FE and chemicals. */
    private long pipeConnectionMask;

    private Object externalIdentity = new Object();

    private @Nullable io.github.loongin.omniresonance.transfer.ExternalDomainInput externalInput;

    /** Publishes derived face/type availability on the server thread, never inventory or saved authority.
     * Changed availability invalidates native capability caches; zero disconnects all marker interfaces. */
    public void publishPipeConnections(long mask) {
        requireServerThreadIfAttached();
        if (mask < 0 || mask > 0xFFFFFFFFFL) throw new IllegalArgumentException("Invalid connection mask");
        if (pipeConnectionMask == mask) return;
        pipeConnectionMask = mask;
        if (level instanceof ServerLevel serverLevel) serverLevel.invalidateCapabilities(worldPosition);
    }

    /** Server-thread pure availability lookup for native/optional registration. Indices 0..5 denote item,
     * fluid, FE, chemical, Source and soul; owns no resources and performs no simulation, mutation or discovery. */
    public boolean pipeConnection(@Nullable net.minecraft.core.Direction side, int type) {
        requireServerThreadIfAttached();
        if (type < 0 || type > 5) throw new IllegalArgumentException("Unknown connection type");
        return level instanceof ServerLevel
                && !isRemoved()
                && side != null
                && state().map(s -> s.linkState() == NodeLinkState.LINKED).orElse(false)
                && (pipeConnectionMask & (1L << (type * 6 + side.get3DDataValue()))) != 0;
    }

    /** Opaque lifecycle token for delivery leases; pure server-thread read, no inventory ownership. */
    public Object externalIdentity() {
        requireServerThreadIfAttached();
        return externalIdentity;
    }

    /** Server-thread lease publication; revokes cached native handlers through normal invalidation. No storage access. */
    public void publishExternalInput(@Nullable io.github.loongin.omniresonance.transfer.ExternalDomainInput input) {
        requireServerThreadIfAttached();
        if (externalInput == input) return;
        externalInput = input;
        if (level instanceof ServerLevel serverLevel) serverLevel.invalidateCapabilities(worldPosition);
    }

    /** Borrowed server-thread lease; stale calls must still pass the lease's authority checks. */
    public @Nullable io.github.loongin.omniresonance.transfer.ExternalDomainInput externalInput() {
        requireServerThreadIfAttached();
        return !isRemoved() ? externalInput : null;
    }

    private boolean decoded;
    private boolean unavailableLogged;

    public ResonanceNodeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESONANCE_TRANSFER_NODE.get(), pos, state);
    }

    /** Returns validated immutable local identity without creating or repairing state. */
    public Optional<NodePersistentState.Valid> state() {
        requireServerThreadIfAttached();
        return persistentState == null ? Optional.empty() : persistentState.valid();
    }

    /** Reports decoded unavailable state; a fresh uninitialized placement is not decoded corruption. */
    public boolean isUnavailable() {
        requireServerThreadIfAttached();
        return decoded && (persistentState == null || persistentState.isUnavailable());
    }

    /** Initializes one fresh instance as BLANK; decoded or already initialized state rejects before mutation. */
    void initializeBlank(UUID nodeId) {
        requireServerThreadIfAttached();
        if (decoded || persistentState != null) {
            throw new IllegalStateException("Node identity is decoded, unavailable or already initialized");
        }
        persistentState = NodePersistentState.fresh(Objects.requireNonNull(nodeId, "nodeId"));
        setChanged();
    }

    void initializeFreshIfNeeded(UUID nodeId) {
        requireServerThreadIfAttached();
        if (!decoded && persistentState == null) {
            initializeBlank(nodeId);
        }
    }

    void linkFromAuthority(UUID expectedId) {
        requireServerThreadIfAttached();
        Objects.requireNonNull(expectedId, "expectedId");
        NodePersistentState.Valid current =
                state().orElseThrow(() -> new IllegalStateException("Unavailable node identity cannot be linked"));
        if (!current.nodeId().equals(expectedId)) {
            throw new IllegalStateException("Node identity changed before authority link");
        }
        if (current.linkState() == NodeLinkState.BLANK) {
            persistentState = NodePersistentState.linked(expectedId);
            setChanged();
        }
    }

    void replaceWithFreshBlank(UUID expectedId, UUID replacementId) {
        requireServerThreadIfAttached();
        Objects.requireNonNull(expectedId, "expectedId");
        Objects.requireNonNull(replacementId, "replacementId");
        if (expectedId.equals(replacementId)) {
            throw new IllegalArgumentException("Replacement node identity must be new");
        }
        NodePersistentState.Valid current =
                state().orElseThrow(() -> new IllegalStateException("Unavailable node identity cannot be replaced"));
        if (!current.nodeId().equals(expectedId)) {
            throw new IllegalStateException("Node identity changed before authority replacement");
        }
        externalIdentity = new Object();
        publishExternalInput(null);
        publishPipeConnections(0);
        persistentState = NodePersistentState.fresh(replacementId);
        setChanged();
    }

    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        externalIdentity = new Object();
        publishExternalInput(null);
        publishPipeConnections(0);
        decoded = true;
        unavailableLogged = false;
        persistentState = NodePersistentState.decode(tag);
        interfaceData = tag.getCompound("interface_data").copy();
        interfaceOwner = interfaceData.hasUUID("domain_owner") ? interfaceData.getUUID("domain_owner") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!interfaceData.isEmpty()) tag.put("interface_data", interfaceData.copy());
        if (persistentState != null) {
            persistentState.writeOwnedFields(tag);
        }
    }

    @Override
    public void setRemoved() {
        externalIdentity = new Object();
        publishExternalInput(null);
        publishPipeConnections(0);
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel && isUnavailable() && !unavailableLogged) {
            LOGGER.warn("Rejected resonance-node identity at {}; preserving unavailable fields", worldPosition);
            unavailableLogged = true;
        }
        if (level instanceof ServerLevel serverLevel) {
            NeoForge.EVENT_BUS.post(new NodeLifecycleEvent.Loaded(serverLevel, this));
        }
    }

    private void requireServerThreadIfAttached() {
        if (level instanceof ServerLevel serverLevel && !serverLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Resonance node accessed outside the server thread");
        }
    }
}
