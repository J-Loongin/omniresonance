// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.NodeHighlightFrame;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-thread, nonpersistent navigation owner. At most one highlight/task per player; tasks obey the global
 * admission cap. Rate/cooldown entries live until logout. Every tick revalidates access and policy; no world
 * access is asynchronous and no lookup synchronously loads a chunk. Own temporary tickets are always released
 * on completion/cancellation/close; native movement failures are not retried.
 */
public final class NodeNavigationService implements AutoCloseable {
    private final MinecraftServer server;
    private final NodeManagementService management;
    private final NodeAuthorityService authority;
    private final BiConsumer<ServerPlayer, NodeHighlightFrame> sender;
    private final TicketType<UUID> ticket = TicketType.create("omniresonance:node_travel", UUID::compareTo);
    private final Map<UUID, Task> tasks = new HashMap<>();
    private final Map<UUID, Highlight> highlights = new HashMap<>();
    private final Map<UUID, Long> requests = new HashMap<>(), cooldowns = new HashMap<>();
    private ServerSettings.Navigation settings;

    private record Highlight(ServerPlayer player, UUID network, UUID node, GlobalPos position, long expiresTick) {}

    private static final class Task {
        final ServerPlayer player;
        final UUID network, node;
        final GlobalPos position;
        final ServerLevel level, origin;
        final long expiresTick;
        boolean ticketed;
        boolean completed;

        Task(ServerPlayer player, UUID network, NetworkNodeRecord node, ServerLevel level, long expiresTick) {
            this.player = player;
            this.network = network;
            this.node = node.nodeId();
            position = node.position();
            this.level = level;
            origin = player.serverLevel();
            this.expiresTick = expiresTick;
        }
    }

    public NodeNavigationService(
            MinecraftServer server,
            NodeManagementService management,
            NodeAuthorityService authority,
            ServerSettings.Navigation settings,
            BiConsumer<ServerPlayer, NodeHighlightFrame> sender) {
        this.server = server;
        this.management = management;
        this.authority = authority;
        this.settings = settings;
        this.sender = sender;
        check();
    }

    private void check() {
        if (!server.isSameThread()) throw new IllegalStateException("Navigation off server thread");
    }

    private long now() {
        return server.overworld().getGameTime();
    }

    private static boolean eligible(ServerPlayer p) {
        return p.isAlive() && !p.isRemoved() && !p.isSleeping() && !p.isPassenger();
    }

    private NetworkNodeRecord node(ServerPlayer player, UUID network, UUID id) {
        if (player.server != server) throw new IllegalArgumentException("Foreign player");
        return management.inspectLinked(player, network, id);
    }

    public void highlight(ServerPlayer player, UUID network, UUID id) {
        check();
        var node = node(player, network, id);
        if (!settings.highlightEnabled()) throw new IllegalStateException("Highlight disabled");
        var old = highlights.get(player.getUUID());
        if (old != null && old.node.equals(id)) {
            clearHighlight(player);
            return;
        }
        show(player, network, node);
    }

    private void show(ServerPlayer player, UUID network, NetworkNodeRecord node) {
        if (!settings.highlightEnabled()) return;
        highlights.put(
                player.getUUID(),
                new Highlight(
                        player, network, node.nodeId(), node.position(), now() + settings.highlightDurationTicks()));
        sender.accept(
                player,
                new NodeHighlightFrame(
                        node.nodeId(), node.position(), node.name().value(), settings.highlightDurationTicks()));
    }

    private void clearHighlight(ServerPlayer player) {
        if (highlights.remove(player.getUUID()) != null)
            sender.accept(player, new NodeHighlightFrame(null, null, "", 0));
    }

    public void teleport(ServerPlayer player, UUID network, UUID id) {
        check();
        if (!player.hasPermissions(2)) throw new SecurityException("Node travel requires operator permission");
        long tick = now();
        UUID actor = player.getUUID();
        if (tick < requests.getOrDefault(actor, Long.MIN_VALUE)) throw new IllegalStateException("Request interval");
        requests.put(actor, tick + 10);
        var node = node(player, network, id);
        if (!settings.teleportEnabled()
                || !eligible(player)
                || tasks.containsKey(actor)
                || tasks.size() >= settings.maximumPending()
                || tick < cooldowns.getOrDefault(actor, Long.MIN_VALUE))
            throw new IllegalStateException("Travel unavailable");
        var level = server.getLevel(node.position().dimension());
        if (level == null
                || !level.isInWorldBounds(node.position().pos())
                || !settings.crossDimension() && level != player.serverLevel())
            throw new IllegalStateException("Destination unavailable");
        var task = new Task(player, network, node, level, tick + settings.ticketTtlTicks());
        var pos = new ChunkPos(task.position.pos());
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null && !settings.temporaryLoading())
            throw new IllegalStateException("Temporary loading disabled");
        tasks.put(actor, task);
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
            task.ticketed = true;
            try {
                level.getChunkSource().addRegionTicket(ticket, pos, 2, actor);
            } catch (RuntimeException failure) {
                release(task);
                tasks.remove(actor);
                throw failure;
            }
        }
    }

    public void tick(ServerSettings.Navigation next) {
        check();
        settings = next;
        long tick = now();
        var hi = highlights.entrySet().iterator();
        while (hi.hasNext()) {
            var h = hi.next().getValue();
            boolean valid = settings.highlightEnabled() && tick < h.expiresTick && !h.player.isRemoved();
            if (valid)
                try {
                    valid = node(h.player, h.network, h.node).position().equals(h.position);
                } catch (RuntimeException denied) {
                    valid = false;
                }
            if (!valid) {
                hi.remove();
                sender.accept(h.player, new NodeHighlightFrame(null, null, "", 0));
            }
        }
        var it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            var task = it.next().getValue();
            if (task.completed) {
                release(task);
                it.remove();
                continue;
            }
            boolean finished = false;
            try {
                if (!settings.teleportEnabled()
                        || !task.player.hasPermissions(2)
                        || !eligible(task.player)
                        || tick >= task.expiresTick
                        || task.origin != task.player.serverLevel()
                        || !settings.crossDimension() && task.level != task.player.serverLevel()
                        || task.ticketed && !settings.temporaryLoading())
                    throw new IllegalStateException("Travel cancelled");
                var record = node(task.player, task.network, task.node);
                if (!record.position().equals(task.position)) throw new IllegalStateException("Node moved");
                var cp = new ChunkPos(task.position.pos());
                var chunk = task.level.getChunkSource().getChunkNow(cp.x, cp.z);
                if (chunk == null) continue;
                authority.verifyRecordedPosition(task.position);
                record = node(task.player, task.network, task.node);
                var be = chunk.getBlockEntity(task.position.pos());
                var state = be instanceof ResonanceNodeBlockEntity entity
                        ? entity.state().orElse(null)
                        : null;
                if (state == null || !state.nodeId().equals(task.node) || state.linkState() != NodeLinkState.LINKED)
                    throw new IllegalStateException("Node identity mismatch");
                BlockPos target = null;
                for (var candidate : NodeTravelCandidates.around(task.position.pos()))
                    if (safe(task.level, task.player, cp, candidate)) {
                        target = candidate;
                        break;
                    }
                if (target == null) throw new IllegalStateException("No safe destination");
                node(task.player, task.network, task.node);
                if (!safe(task.level, task.player, cp, target)) throw new IllegalStateException("Destination changed");
                finished = true;
                task.completed = true;
                task.player.teleportTo(
                        task.level,
                        target.getX() + 0.5,
                        target.getY(),
                        target.getZ() + 0.5,
                        task.player.getYRot(),
                        task.player.getXRot());
                if (task.player.serverLevel() != task.level
                        || task.player.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 0.001)
                    throw new IllegalStateException("Travel was cancelled or redirected by a native hook");
                task.player.setDeltaMovement(Vec3.ZERO);
                task.player.fallDistance = 0;
                cooldowns.put(task.player.getUUID(), tick + settings.cooldownTicks());
                show(task.player, task.network, record);
            } catch (RuntimeException failure) {
                if (task.completed)
                    org.slf4j.LoggerFactory.getLogger(NodeNavigationService.class)
                            .error("Native node travel did not complete as requested; no retry", failure);
                finished = true;
                task.player.displayClientMessage(Component.translatable("omniresonance.navigation.failed"), true);
            } finally {
                if (finished) {
                    task.completed = true;
                    release(task);
                    it.remove();
                }
            }
        }
    }

    static boolean safe(ServerLevel level, ServerPlayer player, ChunkPos chunk, BlockPos feet) {
        AABB box = player.getDimensions(player.getPose())
                .makeBoundingBox(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        if (!level.isInWorldBounds(feet.below())
                || !level.isInWorldBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))
                || box.minX < chunk.getMinBlockX()
                || box.maxX > chunk.getMaxBlockX() + 1
                || box.minZ < chunk.getMinBlockZ()
                || box.maxZ > chunk.getMaxBlockZ() + 1
                || !level.getWorldBorder().isWithinBounds(box)
                || level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return false;
        var collisionView = new LoadedCollisionView(level);
        if (!collisionView.getBlockState(feet.below()).isFaceSturdy(collisionView, feet.below(), Direction.UP)
                || collisionView.missing) return false;
        for (var p : BlockPos.betweenClosed(
                (int) Math.floor(box.minX),
                feet.getY() - 1,
                (int) Math.floor(box.minZ),
                (int) Math.ceil(box.maxX) - 1,
                (int) Math.ceil(box.maxY) - 1,
                (int) Math.ceil(box.maxZ) - 1)) {
            var state = level.getBlockState(p);
            if (!state.getFluidState().isEmpty()
                    || state.is(BlockTags.FIRE)
                    || state.is(Blocks.NETHER_PORTAL)
                    || state.is(Blocks.END_PORTAL)
                    || state.is(Blocks.END_GATEWAY)
                    || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS)
                    || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.WITHER_ROSE)
                    || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE)) return false;
        }
        boolean clear = collisionView.noCollision(player, box);
        return clear && !collisionView.missing;
    }

    /** Native collision shapes through nonblocking chunk reads; missing context invalidates the candidate. */
    private static final class LoadedCollisionView implements net.minecraft.world.level.CollisionGetter {
        private final ServerLevel level;
        private boolean missing;

        LoadedCollisionView(ServerLevel level) {
            this.level = level;
        }

        public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
            return level.getWorldBorder();
        }

        public @org.jetbrains.annotations.Nullable net.minecraft.world.level.BlockGetter getChunkForCollisions(
                int x, int z) {
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) missing = true;
            return chunk;
        }

        public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(
                @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity entity, AABB box) {
            return level.getEntityCollisions(entity, box);
        }

        public @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
                BlockPos pos) {
            var chunk = getChunkForCollisions(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos);
        }

        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            var chunk = getChunkForCollisions(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }

        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public int getHeight() {
            return level.getHeight();
        }

        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }

    int pendingCount() {
        check();
        return tasks.size();
    }

    int ticketCount() {
        check();
        int count = 0;
        for (var task : tasks.values()) if (task.ticketed) count++;
        return count;
    }

    private void release(Task task) {
        if (task.ticketed) {
            task.level
                    .getChunkSource()
                    .removeRegionTicket(ticket, new ChunkPos(task.position.pos()), 2, task.player.getUUID());
            task.ticketed = false;
        }
    }

    public void disconnect(ServerPlayer player) {
        check();
        var task = tasks.remove(player.getUUID());
        if (task != null) release(task);
        highlights.remove(player.getUUID());
        requests.remove(player.getUUID());
        cooldowns.remove(player.getUUID());
    }

    public void close() {
        check();
        for (var task : tasks.values()) release(task);
        tasks.clear();
        highlights.clear();
        requests.clear();
        cooldowns.clear();
    }
}
