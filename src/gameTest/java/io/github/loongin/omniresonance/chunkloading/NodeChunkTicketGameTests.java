// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeChunkTicketGameTests {
    private NodeChunkTicketGameTests() {}

    @GameTest(template = "bootstrap")
    public static void oneTicketOwnerCannotReleaseAnotherOwnersNativeTicket(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(net.minecraft.core.BlockPos.ZERO));
        var pos = new ChunkPos(origin.x + 128, origin.z + 128);
        var key = new ChunkLoadingAllocator.Chunk(level.dimension().location(), pos.x, pos.z);
        var allocator = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 1, 1));
        allocator.put(new ChunkLoadingAllocator.Request(
                new UUID(999, 3), new UUID(999, 4), key, ChunkLoadingAllocator.Eligibility.READY));
        allocator.advance(1);
        try (var first = new NodeChunkTickets(level.getServer(), allocator);
                var second = new NodeChunkTickets(level.getServer(), allocator)) {
            first.acquire(key);
            second.acquire(key);
            first.close();
            helper.assertTrue(
                    level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(pos.toLong()),
                    "One ticket owner released another owner's ticket");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void quotaGrantedNativeTicketsForceTickWithoutPersistingAndCloseReleasesThem(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(net.minecraft.core.BlockPos.ZERO));
        var pos = new ChunkPos(origin.x + 64, origin.z + 64);
        var key = new ChunkLoadingAllocator.Chunk(level.dimension().location(), pos.x, pos.z);
        var allocator = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 1, 1));
        allocator.put(new ChunkLoadingAllocator.Request(
                new UUID(999, 1), new UUID(999, 2), key, ChunkLoadingAllocator.Eligibility.READY));
        allocator.advance(1);
        boolean saved = level.getForcedChunks().contains(pos.toLong());
        try (var tickets = new NodeChunkTickets(level.getServer(), allocator)) {
            helper.assertTrue(
                    tickets.acquire(key) && tickets.acquire(key) && tickets.size() == 1,
                    "Native ticket was duplicated");
            helper.assertTrue(
                    level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(pos.toLong()),
                    "Granted chunk was not forced to tick");
            helper.assertTrue(
                    level.getForcedChunks().contains(pos.toLong()) == saved,
                    "Native transient ticket changed persisted forced chunks");
        }
        helper.assertTrue(
                !level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(pos.toLong()),
                "Closing leaked a node ticking ticket");
        helper.succeed();
    }
}
