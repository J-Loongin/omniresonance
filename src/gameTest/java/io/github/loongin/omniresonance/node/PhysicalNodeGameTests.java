// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.registry.ModItems;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registry, BlockItem placement, BE persistence and drop contracts for B1 physical forms. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicalNodeGameTests {
    private PhysicalNodeGameTests() {}

    /** Verifies stable registry identities and one BE type valid for exactly both physical forms. */
    @GameTest(template = "bootstrap")
    public static void physicalFormsUseStableRegistriesAndSharedEntityType(GameTestHelper helper) {
        ResourceLocation nodeId = id("resonance_transfer_node");
        ResourceLocation panelId = id("resonance_transfer_panel");
        helper.assertTrue(
                BuiltInRegistries.BLOCK.getOptional(nodeId).orElse(null) == ModBlocks.RESONANCE_TRANSFER_NODE.get(),
                "Node block registry identity changed");
        helper.assertTrue(
                BuiltInRegistries.BLOCK.getOptional(panelId).orElse(null) == ModBlocks.RESONANCE_TRANSFER_PANEL.get(),
                "Panel block registry identity changed");
        helper.assertTrue(
                BuiltInRegistries.ITEM.getOptional(nodeId).orElse(null) == ModItems.RESONANCE_TRANSFER_NODE.get(),
                "Node item registry identity changed");
        helper.assertTrue(
                BuiltInRegistries.ITEM.getOptional(panelId).orElse(null) == ModItems.RESONANCE_TRANSFER_PANEL.get(),
                "Panel item registry identity changed");
        helper.assertTrue(
                ModBlockEntities.RESONANCE_TRANSFER_NODE
                        .get()
                        .getValidBlocks()
                        .equals(Set.of(
                                ModBlocks.RESONANCE_TRANSFER_NODE.get(), ModBlocks.RESONANCE_TRANSFER_PANEL.get())),
                "Shared block-entity valid-block set changed");
        helper.succeed();
    }

    /** Verifies actual BlockItem placement derives all six node-to-target directions for both forms. */
    @GameTest(template = "bootstrap")
    public static void blockItemsPlaceBothFormsAgainstEveryClickedFace(GameTestHelper helper) {
        FakePlayer player = player(helper, 1);
        int index = 0;
        for (BlockItem item : List.of((BlockItem) ModItems.RESONANCE_TRANSFER_NODE.get(), (BlockItem)
                ModItems.RESONANCE_TRANSFER_PANEL.get())) {
            for (Direction clickedFace : Direction.values()) {
                BlockPos target = helper.absolutePos(new BlockPos(3 + index * 3, 3, 3));
                helper.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(target.relative(clickedFace), Blocks.AIR.defaultBlockState());
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
                InteractionResult result = item.place(new BlockPlaceContext(new UseOnContext(
                        player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(target), clickedFace, target, false))));
                BlockPos placed = target.relative(clickedFace);
                helper.assertTrue(result.consumesAction(), "BlockItem placement failed for " + clickedFace);
                BlockState state = helper.getLevel().getBlockState(placed);
                helper.assertTrue(state.is(item.getBlock()), "Wrong block placed for " + clickedFace);
                helper.assertTrue(
                        state.getValue(AbstractResonanceNodeBlock.FACING) == clickedFace.getOpposite(),
                        "Placed facing changed for " + clickedFace);
                AbstractResonanceNodeBlock block = (AbstractResonanceNodeBlock) state.getBlock();
                helper.assertTrue(block.targetPosition(placed, state).equals(target), "Target position changed");
                helper.assertTrue(block.targetSide(state) == clickedFace, "Target side changed");
                ResonanceNodeBlockEntity entity = entity(helper, placed);
                helper.assertTrue(
                        entity.state().orElseThrow().linkState() == NodeLinkState.BLANK, "Placed node is not blank");
                helper.getLevel().setBlockAndUpdate(placed, Blocks.AIR.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                index++;
            }
        }
        helper.succeed();
    }

    /** Verifies independently placed forms receive distinct nonnull server UUIDs and survive target removal. */
    @GameTest(template = "bootstrap")
    public static void freshPlacementsHaveUniqueIdsAndDoNotDependOnTargetSurvival(GameTestHelper helper) {
        BlockPos firstTarget = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos secondTarget = helper.absolutePos(new BlockPos(6, 3, 2));
        FakePlayer player = player(helper, 2);
        BlockPos first = place(player, firstTarget, Direction.UP, (BlockItem) ModItems.RESONANCE_TRANSFER_NODE.get());
        BlockPos second =
                place(player, secondTarget, Direction.EAST, (BlockItem) ModItems.RESONANCE_TRANSFER_PANEL.get());
        UUID firstId = entity(helper, first).state().orElseThrow().nodeId();
        UUID secondId = entity(helper, second).state().orElseThrow().nodeId();
        helper.assertTrue(!firstId.equals(secondId), "Fresh placements reused a UUID");

        helper.getLevel().setBlockAndUpdate(firstTarget, Blocks.AIR.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(secondTarget, Blocks.AIR.defaultBlockState());
        helper.assertTrue(
                helper.getLevel().getBlockState(first).is(ModBlocks.RESONANCE_TRANSFER_NODE.get()),
                "Node detached with target");
        helper.assertTrue(
                helper.getLevel().getBlockState(second).is(ModBlocks.RESONANCE_TRANSFER_PANEL.get()),
                "Panel detached with target");
        helper.succeed();
    }

    /** Verifies legal blank/linked persistence and missing data never gains a replacement UUID. */
    @GameTest(template = "bootstrap")
    public static void nodeStateRoundTripsAndMissingDataCannotReinitialize(GameTestHelper helper) {
        BlockPos worldPos = helper.absolutePos(new BlockPos(2, 3, 2));
        helper.getLevel()
                .setBlockAndUpdate(
                        worldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
        ResonanceNodeBlockEntity placed = entity(helper, worldPos);
        NodePersistentState.Valid blank = placed.state().orElseThrow();
        CompoundTag blankTag = placed.saveCustomOnly(helper.getLevel().registryAccess());
        ResonanceNodeBlockEntity loadedBlank = new ResonanceNodeBlockEntity(
                worldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
        loadedBlank.loadCustomOnly(blankTag.copy(), helper.getLevel().registryAccess());
        helper.assertTrue(loadedBlank.state().orElse(null).equals(blank), "Blank state did not round-trip");

        UUID linkedId = new UUID(5, 6);
        CompoundTag linkedTag = new CompoundTag();
        new NodePersistentState.Valid(linkedId, NodeLinkState.LINKED).writeOwnedFields(linkedTag);
        ResonanceNodeBlockEntity loadedLinked = new ResonanceNodeBlockEntity(
                worldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
        loadedLinked.loadCustomOnly(linkedTag, helper.getLevel().registryAccess());
        helper.assertTrue(
                loadedLinked.state().orElse(null).equals(new NodePersistentState.Valid(linkedId, NodeLinkState.LINKED)),
                "Linked state did not round-trip");

        ResonanceNodeBlockEntity missing = new ResonanceNodeBlockEntity(
                worldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
        missing.loadCustomOnly(new CompoundTag(), helper.getLevel().registryAccess());
        boolean rejected = false;
        try {
            missing.initializeBlank(UUID.randomUUID());
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && missing.isUnavailable(), "Missing state was reinitialized");
        helper.assertTrue(
                missing.saveCustomOnly(helper.getLevel().registryAccess()).isEmpty(),
                "Missing state invented defaults");
        helper.succeed();
    }

    /** Verifies ordinary drops and clone stacks contain no block-entity identity data. */
    @GameTest(template = "bootstrap")
    public static void dropsAndCloneStacksAreAlwaysBlankFormItems(GameTestHelper helper) {
        FakePlayer player = player(helper, 3);
        for (Block block : List.of(ModBlocks.RESONANCE_TRANSFER_NODE.get(), ModBlocks.RESONANCE_TRANSFER_PANEL.get())) {
            BlockPos worldPos =
                    helper.absolutePos(new BlockPos(block == ModBlocks.RESONANCE_TRANSFER_NODE.get() ? 2 : 6, 3, 2));
            helper.getLevel().setBlockAndUpdate(worldPos, block.defaultBlockState());
            ResonanceNodeBlockEntity blockEntity = entity(helper, worldPos);
            helper.assertTrue(blockEntity.onlyOpCanSetNbt(), "Node accepts ordinary player BlockEntityData");
            List<ItemStack> drops = Block.getDrops(
                    helper.getLevel().getBlockState(worldPos),
                    helper.getLevel(),
                    worldPos,
                    blockEntity,
                    player,
                    ItemStack.EMPTY);
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()), "Wrong blank drop");
            assertNoIdentity(helper, drops.getFirst(), "drop");
            BlockState state = helper.getLevel().getBlockState(worldPos);
            ItemStack clone = block.getCloneItemStack(
                    state,
                    new BlockHitResult(Vec3.atCenterOf(worldPos), Direction.UP, worldPos, false),
                    helper.getLevel(),
                    worldPos,
                    player);
            helper.assertTrue(clone.is(block.asItem()), "Clone stack has wrong form");
            assertNoIdentity(helper, clone, "clone");
        }
        helper.succeed();
    }

    private static BlockPos place(FakePlayer player, BlockPos target, Direction face, BlockItem item) {
        player.level().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        player.level().setBlockAndUpdate(target.relative(face), Blocks.AIR.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        InteractionResult result = item.place(new BlockPlaceContext(new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(target), face, target, false))));
        if (!result.consumesAction()) {
            throw new IllegalStateException("Failed to place node form");
        }
        return target.relative(face);
    }

    private static ResonanceNodeBlockEntity entity(GameTestHelper helper, BlockPos worldPos) {
        var blockEntity = helper.getLevel().getBlockEntity(worldPos);
        helper.assertTrue(blockEntity instanceof ResonanceNodeBlockEntity, "Node block entity missing");
        return (ResonanceNodeBlockEntity) blockEntity;
    }

    private static FakePlayer player(GameTestHelper helper, long id) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(new UUID(0, id), "PhysicalNode" + id));
        player.setGameMode(GameType.CREATIVE);
        return player;
    }

    private static void assertNoIdentity(GameTestHelper helper, ItemStack stack, String source) {
        helper.assertTrue(stack.get(DataComponents.BLOCK_ENTITY_DATA) == null, source + " copied BlockEntityData");
        helper.assertTrue(stack.getComponentsPatch().isEmpty(), source + " copied unexpected components");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, path);
    }
}
