// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Development-only native handler fixture, owned by a real block entity, never included in the release JAR. */
@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "omniresonance")
public final class ResourceEndpointGameTests {
    private static Block testBlock;
    private static BlockEntityType<EndpointEntity> testType;

    private ResourceEndpointGameTests() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            testBlock = new EndpointBlock();
            helper.register(ResourceLocation.parse("omniresonance:test_resource_endpoint"), testBlock);
        });
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            testType =
                    BlockEntityType.Builder.of(EndpointEntity::new, testBlock).build(null);
            helper.register(ResourceLocation.parse("omniresonance:test_resource_endpoint"), testType);
        });
    }

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, testType, (entity, face) -> {
            entity.itemCalls++;
            if (entity.throwItem) throw new IllegalStateException("Fixture discovery failure");
            return entity.available && face == Direction.UP ? entity.items : null;
        });
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, testType, (entity, face) -> {
            entity.fluidCalls++;
            return entity.available && face == Direction.UP ? entity.fluid : null;
        });
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, testType, (entity, face) -> {
            entity.energyCalls++;
            return entity.available && face == Direction.UP ? entity.energy : null;
        });
    }

    @GameTest(template = "bootstrap")
    public static void selectedNativeTypesReuseAndInvalidateExactlyOnce(GameTestHelper helper) {
        var node = node(helper, NodeForm.BLOCK);
        var entity = (EndpointEntity)
                helper.getLevel().getBlockEntity(node.position().pos().below());
        var budget = budget();
        try (var cache = new ResourceEndpointCache(
                helper.getLevel().getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            var item = cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget);
            helper.assertTrue(item != null && item.valid(), "Item capability missing");
            helper.assertTrue(
                    entity.itemCalls == 1 && entity.fluidCalls == 0 && entity.energyCalls == 0,
                    "Unselected type queried");
            var fluid = cache.resolve(node, Direction.DOWN, ResourceTypes.FLUID, budget);
            var energy = cache.resolve(node, Direction.DOWN, ResourceTypes.ENERGY, budget);
            helper.assertTrue(
                    fluid != null && energy != null && budget.calls() == 3, "Three native types not discovered once");
            helper.assertTrue(
                    fluid.port().typeId().equals(ResourceTypes.FLUID)
                            && energy.port().typeId().equals(ResourceTypes.ENERGY),
                    "Wrong port type");
            helper.assertTrue(
                    item.physicalIdentity().equals(fluid.physicalIdentity()),
                    "Physical identity differs by resource type");
            helper.assertTrue(
                    cache.resolve(node.withConfigurationChanged(), Direction.DOWN, ResourceTypes.ITEM, budget) == item
                            && item.port() == item.port()
                            && budget.calls() == 3,
                    "Reuse rediscovered endpoint");
            helper.getLevel().invalidateCapabilities(node.position().pos().below());
            helper.assertTrue(
                    !item.valid() && !fluid.valid() && !energy.valid() && budget.calls() == 3,
                    "Old handle remained valid or validity queried native");
            helper.assertTrue(
                    cache.drainInvalidatedNodes().equals(java.util.List.of(node.nodeId())),
                    "Callbacks not deduplicated");
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget) != item
                            && budget.calls() == 4
                            && entity.itemCalls == 2,
                    "Invalidation did not query exactly once");
            helper.assertTrue(
                    entity.fluidCalls == 1 && entity.energyCalls == 1, "Invalidation eagerly queried unselected types");
            cache.remove(node.nodeId());
            helper.assertTrue(cache.size() == 0, "Remove retained entries");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void nullAndThrowingDiscoveryRespectBudget(GameTestHelper helper) {
        var node = node(helper, NodeForm.BLOCK);
        var entity = (EndpointEntity)
                helper.getLevel().getBlockEntity(node.position().pos().below());
        var budget = budget();
        try (var cache = new ResourceEndpointCache(
                helper.getLevel().getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            entity.available = false;
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.FLUID, budget) == null,
                    "Absent capability returned");
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.FLUID, budget) == null
                            && budget.calls() == 1
                            && entity.fluidCalls == 1,
                    "Null capability not cached");
            entity.available = true;
            helper.getLevel().invalidateCapabilities(node.position().pos().below());
            var fluid = cache.resolve(node, Direction.DOWN, ResourceTypes.FLUID, budget);
            helper.assertTrue(fluid != null && budget.calls() == 2, "Null invalidation not refreshed");
            entity.throwItem = true;
            boolean threw = false;
            try {
                cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            helper.assertTrue(threw && budget.calls() == 3 && entity.itemCalls == 1, "Throwing discovery not counted");
            cache.unload(
                    helper.getLevel().dimension(),
                    new ChunkPos(node.position().pos().below()));
            helper.assertTrue(!fluid.valid() && cache.size() == 0, "Unload retained target entries");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void physicalAndSelectedFaceGatesNeverDiscover(GameTestHelper helper) {
        var node = node(helper, NodeForm.PANEL);
        var budget = budget();
        try (var cache = new ResourceEndpointCache(
                helper.getLevel().getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            helper.assertTrue(
                    cache.resolve(node, Direction.UP, ResourceTypes.ITEM, budget) == null, "Panel accepted wrong face");
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceLocation.parse("example:missing"), budget) == null,
                    "Unknown type resolved");
            for (var wrong : java.util.List.of(
                    NetworkNodeRecord.fresh(
                            UUID.randomUUID(),
                            1,
                            new ManagedName("Wrong UUID"),
                            node.position(),
                            NodeForm.PANEL,
                            Direction.DOWN),
                    NetworkNodeRecord.fresh(
                            node.nodeId(),
                            1,
                            new ManagedName("Wrong form"),
                            node.position(),
                            NodeForm.BLOCK,
                            Direction.DOWN),
                    NetworkNodeRecord.fresh(
                            node.nodeId(),
                            1,
                            new ManagedName("Wrong facing"),
                            node.position(),
                            NodeForm.PANEL,
                            Direction.NORTH))) {
                helper.assertTrue(
                        cache.resolve(wrong, wrong.facing(), ResourceTypes.ITEM, budget) == null,
                        "Wrong physical identity resolved");
            }
            helper.assertTrue(budget.calls() == 0, "Rejected endpoint discovered capability");
            var handle = cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget);
            helper.assertTrue(handle != null && handle.valid(), "Correct panel face rejected");
            cache.close();
            helper.assertTrue(
                    !handle.valid()
                            && cache.size() == 0
                            && cache.drainInvalidatedNodes().isEmpty(),
                    "Close retained entries");
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget) == null && budget.calls() == 1,
                    "Closed cache discovered");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void unloadedNodeDoesNotLoadChunks(GameTestHelper helper) {
        BlockPos far = new BlockPos(29999000, 80, 29999000);
        var node = NetworkNodeRecord.fresh(
                UUID.randomUUID(),
                1,
                new ManagedName("Unloaded"),
                GlobalPos.of(helper.getLevel().dimension(), far),
                NodeForm.BLOCK,
                Direction.DOWN);
        var budget = budget();
        try (var cache = new ResourceEndpointCache(
                helper.getLevel().getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            helper.assertTrue(!helper.getLevel().isLoaded(far) && !cache.physical(node), "Far fixture loaded");
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget) == null
                            && budget.calls() == 0
                            && cache.size() == 0,
                    "Unloaded node queried");
            helper.assertTrue(!helper.getLevel().isLoaded(far), "Lookup forced chunk loading");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void capacityAndPhysicalChangesRetireActualEntries(GameTestHelper helper) {
        var node = node(helper, NodeForm.BLOCK);
        var budget = budget();
        try (var cache = new ResourceEndpointCache(
                helper.getLevel().getServer(), ResourceAdapterDirectory.nativeDefaults(), 1)) {
            var original = cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget);
            helper.assertTrue(
                    cache.resolve(node, Direction.DOWN, ResourceTypes.FLUID, budget) == null && budget.calls() == 1,
                    "Full cache discovered a new endpoint");
            helper.getLevel()
                    .setBlock(
                            node.position().pos(),
                            helper.getLevel()
                                    .getBlockState(node.position().pos())
                                    .setValue(AbstractResonanceNodeBlock.FACING, Direction.WEST),
                            3);
            var moved = NetworkNodeRecord.fresh(
                    node.nodeId(), 1, new ManagedName("Changed"), node.position(), NodeForm.BLOCK, Direction.WEST);
            helper.assertTrue(!original.valid(), "Changed facing retained old handle");
            var refreshed = cache.resolve(moved, Direction.DOWN, ResourceTypes.ITEM, budget);
            helper.assertTrue(
                    refreshed != null && refreshed != original && cache.size() == 1 && budget.calls() == 2,
                    "Physical snapshot change did not retire actual entries");
            helper.getLevel().destroyBlock(node.position().pos(), false);
            helper.assertTrue(
                    !refreshed.valid()
                            && cache.resolve(moved, Direction.DOWN, ResourceTypes.ITEM, budget) == null
                            && cache.size() == 0,
                    "Removed physical node retained endpoint");
            helper.getLevel().invalidateCapabilities(node.position().pos().below());
            helper.assertTrue(cache.drainInvalidatedNodes().isEmpty(), "Retired listener queued a wakeup");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void targetChunkGateAndDistinctChunkIndices(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        BlockPos edge = null;
        Direction side = null;
        for (int radius = 0; radius <= 64 && edge == null; radius++) {
            for (Direction direction :
                    java.util.List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
                int x = origin.x + direction.getStepX() * radius;
                int z = origin.z + direction.getStepZ() * radius;
                BlockPos candidate = new BlockPos(
                        x * 16 + (direction == Direction.EAST ? 15 : 0),
                        level.getMinBuildHeight() + 8,
                        z * 16 + (direction == Direction.SOUTH ? 15 : 0));
                if (level.isLoaded(candidate) && !level.isLoaded(candidate.relative(direction))) {
                    edge = candidate;
                    side = direction;
                    break;
                }
            }
        }
        helper.assertTrue(edge != null, "No loaded boundary found within bounded fixture search");
        var node = nodeAt(helper, NodeForm.BLOCK, edge, side, false);
        helper.assertTrue(!level.isLoaded(edge.relative(side)), "Fixture placement loaded target before cache access");
        var budget = budget();
        try (var cache = new ResourceEndpointCache(level.getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            helper.assertTrue(cache.physical(node), "Loaded physical node rejected because target unloaded");
            helper.assertTrue(
                    cache.resolve(node, side, ResourceTypes.ITEM, budget) == null
                            && budget.calls() == 0
                            && cache.size() == 0,
                    "Unloaded target queried");
            helper.assertTrue(!level.isLoaded(edge.relative(side)), "Target lookup loaded chunk");
        }
        level.destroyBlock(edge, false);
        BlockPos base = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos cross = new BlockPos((base.getX() & ~15) + 15, base.getY(), base.getZ());
        helper.assertTrue(level.isLoaded(cross) && level.isLoaded(cross.east()), "Cross-chunk fixture unavailable");
        var crossNode = nodeAt(helper, NodeForm.BLOCK, cross, Direction.EAST, false);
        var target = cross.east();
        level.setBlock(target, testBlock.defaultBlockState(), 3);
        try (var cache = new ResourceEndpointCache(level.getServer(), ResourceAdapterDirectory.nativeDefaults(), 32)) {
            cache.resolve(crossNode, Direction.EAST, ResourceTypes.ITEM, budget);
            helper.assertTrue(cache.size() == 1, "Wrong-face null endpoint missing");
            cache.unload(level.dimension(), new ChunkPos(target));
            helper.assertTrue(cache.size() == 0, "Distinct target chunk did not clear endpoint");
            cache.resolve(crossNode, Direction.EAST, ResourceTypes.ITEM, budget);
            cache.unload(level.dimension(), new ChunkPos(cross));
            helper.assertTrue(cache.size() == 0, "Distinct node chunk did not clear endpoint");
        }
        level.destroyBlock(cross, false);
        level.destroyBlock(target, false);
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void mismatchedNativeFactoryTypeNeverPublishesPort(GameTestHelper helper) {
        var node = node(helper, NodeForm.BLOCK);
        var entity = (EndpointEntity)
                helper.getLevel().getBlockEntity(node.position().pos().below());
        var directory = new ResourceAdapterDirectory(1);
        directory.register(
                new ResourceAdapterDirectory.Descriptor(ResourceTypes.ITEM, "item", 64),
                Capabilities.ItemHandler.BLOCK,
                (handler, registries) -> new EnergyResourcePort(new EnergyStorage(1000)));
        directory.freeze();
        var budget = budget();
        try (var cache = new ResourceEndpointCache(helper.getLevel().getServer(), directory, 4)) {
            boolean rejected = false;
            try {
                cache.resolve(node, Direction.DOWN, ResourceTypes.ITEM, budget);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Mismatched factory type published a port");
            helper.assertTrue(
                    budget.calls() == 1
                            && entity.itemCalls == 1
                            && entity.items.getStackInSlot(0).isEmpty(),
                    "Rejected factory lost discovery accounting or modified native storage");
        }
        helper.succeed();
    }

    static EndpointEntity place(GameTestHelper helper, BlockPos position) {
        helper.getLevel().setBlock(position, testBlock.defaultBlockState(), 3);
        return (EndpointEntity) helper.getLevel().getBlockEntity(position);
    }

    private static NetworkNodeRecord node(GameTestHelper helper, NodeForm form) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        return nodeAt(helper, form, pos, Direction.DOWN, true);
    }

    private static NetworkNodeRecord nodeAt(
            GameTestHelper helper, NodeForm form, BlockPos pos, Direction facing, boolean placeTarget) {
        if (placeTarget) helper.getLevel().setBlock(pos.relative(facing), testBlock.defaultBlockState(), 3);
        Block block = form == NodeForm.PANEL
                ? ModBlocks.RESONANCE_TRANSFER_PANEL.get()
                : ModBlocks.RESONANCE_TRANSFER_NODE.get();
        helper.getLevel()
                .setBlock(pos, block.defaultBlockState().setValue(AbstractResonanceNodeBlock.FACING, facing), 18);
        var entity = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(pos);
        UUID id = entity.state().orElseThrow().nodeId();
        var tag = new CompoundTag();
        NodePersistentState.linked(id).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
        return NetworkNodeRecord.fresh(
                id,
                1,
                new ManagedName("Endpoint"),
                GlobalPos.of(helper.getLevel().dimension(), pos),
                form,
                facing);
    }

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(100, 1000, 1000, () -> 0);
    }

    private static final class EndpointBlock extends Block implements EntityBlock {
        EndpointBlock() {
            super(Properties.of().strength(1));
        }

        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new EndpointEntity(pos, state);
        }
    }

    static final class EndpointEntity extends BlockEntity {
        net.neoforged.neoforge.items.IItemHandler items = new ItemStackHandler(1);
        final FluidTank fluid = new FluidTank(4000);
        final EnergyStorage energy = new EnergyStorage(100000);
        int itemCalls, fluidCalls, energyCalls;
        boolean available = true, throwItem;

        EndpointEntity(BlockPos pos, BlockState state) {
            super(testType, pos, state);
        }
    }
}
