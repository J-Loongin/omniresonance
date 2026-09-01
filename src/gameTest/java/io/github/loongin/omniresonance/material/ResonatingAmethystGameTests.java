// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.registry.ModItems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real block, block-entity, NBT and scheduled-settlement contracts. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResonatingAmethystGameTests {
    private static final BlockPos TEST_POS = new BlockPos(1, 2, 1);

    private ResonatingAmethystGameTests() {}

    /** Verifies the internal world state cannot leak as an item or active ticker. */
    @GameTest(template = "bootstrap")
    public static void internalBlockHasStableIdentityAndNoObtainableForm(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, "resonating_amethyst");
        helper.assertTrue(
                BuiltInRegistries.BLOCK.getOptional(id).orElse(null) == ModBlocks.RESONATING_AMETHYST.get(),
                "Internal block registry identity changed");
        helper.assertTrue(BuiltInRegistries.ITEM.getOptional(id).isEmpty(), "Internal block gained a BlockItem");
        var state = ModBlocks.RESONATING_AMETHYST.get().defaultBlockState();
        helper.assertTrue(state.getRenderShape() == RenderShape.MODEL, "Internal block is not model-rendered");
        helper.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK, "Internal block can be pushed");
        helper.assertTrue(
                state.getBlock().getLootTable().equals(BuiltInLootTables.EMPTY), "Internal block gained a loot table");
        helper.assertTrue(
                ModBlocks.RESONATING_AMETHYST
                                .get()
                                .getTicker(helper.getLevel(), state, ModBlockEntities.RESONATING_AMETHYST.get())
                        == null,
                "Internal block gained a per-gt block-entity ticker");
        helper.succeed();
    }

    /** Verifies exact schema fields survive a real registered block-entity round trip. */
    @GameTest(template = "bootstrap")
    public static void progressRoundTripsExactVersionedNbt(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        long deadline = helper.getLevel().getGameTime() + 100;
        ResonanceProgress expected = new ResonanceProgress(17, deadline);
        entity.initialize(expected);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = entity.saveCustomOnly(registries);
        helper.assertTrue(saved.getInt("schema_version") == 1, "Schema version missing");
        helper.assertTrue(saved.getInt("pending_count") == 17, "Pending count changed");
        helper.assertTrue(saved.getLong("settle_at_game_tick") == deadline, "Deadline changed");

        ResonatingAmethystBlockEntity loaded = new ResonatingAmethystBlockEntity(
                entity.getBlockPos(), ModBlocks.RESONATING_AMETHYST.get().defaultBlockState());
        loaded.loadCustomOnly(saved.copy(), registries);
        helper.assertTrue(expected.equals(loaded.progress().orElse(null)), "Progress did not round-trip");
        helper.succeed();
    }

    /** Verifies malformed owned fields stay unavailable and are not rewritten to defaults. */
    @GameTest(template = "bootstrap")
    public static void malformedProgressIsPreservedWithoutBecomingUsable(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("schema_version", 2);
        malformed.putString("pending_count", "seventeen");
        malformed.putLong("settle_at_game_tick", -1);
        entity.loadCustomOnly(malformed.copy(), helper.getLevel().registryAccess());

        helper.assertTrue(!entity.isUsable(), "Malformed progress became usable");
        helper.assertTrue(entity.progress().isEmpty(), "Malformed progress became a batch");
        CompoundTag preserved = entity.saveCustomOnly(helper.getLevel().registryAccess());
        for (String key : List.of("schema_version", "pending_count", "settle_at_game_tick")) {
            var preservedValue = preserved.get(key);
            helper.assertTrue(
                    preservedValue != null && preservedValue.equals(malformed.get(key)),
                    "Malformed field was rewritten: " + key);
        }
        entity.ensureScheduled(helper.getLevel());
        helper.assertTrue(
                !helper.getLevel()
                        .getBlockTicks()
                        .hasScheduledTick(entity.getBlockPos(), ModBlocks.RESONATING_AMETHYST.get()),
                "Malformed progress scheduled settlement");
        helper.succeed();
    }

    /** Verifies a decoded record with every required field missing cannot masquerade as fresh placement. */
    @GameTest(template = "bootstrap")
    public static void missingProgressFieldsCannotBeReinitialized(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        entity.loadCustomOnly(new CompoundTag(), helper.getLevel().registryAccess());

        boolean rejected = false;
        try {
            entity.initialize(new ResonanceProgress(1, helper.getLevel().getGameTime() + 100));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Decoded empty state was treated as fresh placement");
        helper.assertTrue(!entity.isUsable(), "Decoded empty state became usable");
        helper.assertTrue(
                entity.saveCustomOnly(helper.getLevel().registryAccess()).isEmpty(),
                "Decoded empty state invented persistent fields");
        helper.succeed();
    }

    /** Verifies a loaded due record schedules an immediate settlement without a block-entity ticker. */
    @GameTest(template = "bootstrap", timeoutTicks = 10)
    public static void loadedDueProgressSchedulesImmediateSettlement(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        CompoundTag saved = new CompoundTag();
        saved.putInt("schema_version", 1);
        saved.putInt("pending_count", 3);
        saved.putLong("settle_at_game_tick", helper.getLevel().getGameTime());
        entity.loadCustomOnly(saved, helper.getLevel().registryAccess());
        entity.onLoad();

        helper.assertTrue(
                helper.getLevel()
                        .getBlockTicks()
                        .hasScheduledTick(entity.getBlockPos(), ModBlocks.RESONATING_AMETHYST.get()),
                "Loaded due progress did not schedule settlement");
        BlockPos worldPos = entity.getBlockPos();
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(worldPos).isAir(), "Loaded due progress did not settle");
            helper.succeed();
        });
    }

    /** Verifies the original scheduled tick honors a later reset deadline and installs one successor. */
    @GameTest(template = "bootstrap", timeoutTicks = 15)
    public static void resetDeadlineReschedulesWhenOriginalTickArrives(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        entity.initialize(new ResonanceProgress(2, now + 2));
        entity.ensureScheduled(level);
        entity.update(new ResonanceProgress(3, now + 6));
        BlockPos worldPos = entity.getBlockPos();

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(!level.getBlockState(worldPos).isAir(), "Old tick ignored the reset deadline");
            helper.assertTrue(
                    level.getBlockTicks().hasScheduledTick(worldPos, ModBlocks.RESONATING_AMETHYST.get()),
                    "Old tick did not schedule the remaining delay");
        });
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(level.getBlockState(worldPos).isAir(), "Reset deadline never settled");
            helper.succeed();
        });
    }

    /** Verifies repeated deadline changes keep one queued operation and settle one bounded stack. */
    @GameTest(template = "bootstrap", timeoutTicks = 20)
    public static void oneScheduledTickSettlesOneBoundedDustStack(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        entity.initialize(new ResonanceProgress(1, now + 4));
        entity.ensureScheduled(level);
        int scheduled = level.getBlockTicks().count();
        for (int count = 2; count <= 64; count++) {
            entity.update(new ResonanceProgress(count, now + 4));
            entity.ensureScheduled(level);
        }
        helper.assertTrue(level.getBlockTicks().count() == scheduled, "Repeated updates queued additional ticks");

        BlockPos worldPos = entity.getBlockPos();
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(level.getBlockState(worldPos).isAir(), "Due ritual block was not removed");
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(worldPos).inflate(1));
            helper.assertTrue(drops.size() == 1, "Settlement did not emit exactly one item entity: " + drops.size());
            helper.assertTrue(drops.getFirst().getItem().is(ModItems.OMNI_DUST.get()), "Settlement emitted wrong item");
            helper.assertTrue(drops.getFirst().getItem().getCount() == 64, "Settlement count changed");
            helper.succeed();
        });
    }

    /** Verifies early removal cannot recover amethyst or create dust. */
    @GameTest(template = "bootstrap")
    public static void earlyRemovalDropsNothing(GameTestHelper helper) {
        ResonatingAmethystBlockEntity entity = placeEntity(helper);
        entity.initialize(new ResonanceProgress(1, helper.getLevel().getGameTime() + 100));
        entity.ensureScheduled(helper.getLevel());
        BlockPos worldPos = entity.getBlockPos();
        helper.getLevel().destroyBlock(worldPos, true);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(worldPos).inflate(1));
        helper.assertTrue(drops.isEmpty(), "Early removal produced an item");
        helper.succeed();
    }

    private static ResonatingAmethystBlockEntity placeEntity(GameTestHelper helper) {
        BlockPos worldPos = helper.absolutePos(TEST_POS);
        helper.getLevel()
                .setBlockAndUpdate(worldPos, ModBlocks.RESONATING_AMETHYST.get().defaultBlockState());
        var blockEntity = helper.getLevel().getBlockEntity(worldPos);
        helper.assertTrue(blockEntity instanceof ResonatingAmethystBlockEntity, "Registered block entity missing");
        return (ResonatingAmethystBlockEntity) blockEntity;
    }
}
