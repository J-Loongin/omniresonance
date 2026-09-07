// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real End-world, player-event and dispenser contracts for dragon-breath infusion. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DragonBreathInfusionGameTests {
    private static final AtomicInteger END_FIXTURE_SEQUENCE = new AtomicInteger();

    private DragonBreathInfusionGameTests() {}

    /** Verifies initial conversion and repeat infusion share one scheduled operation. */
    @GameTest(template = "bootstrap")
    public static void initialAndRepeatInfusionMutateExactProgress(GameTestHelper helper) {
        try (EndFixture fixture = endFixture(helper, 0)) {
            int ticksBefore = fixture.level().getBlockTicks().count();
            DragonBreathInfusionService.Result initial =
                    DragonBreathInfusionService.inject(fixture.level(), fixture.target());
            DragonBreathInfusionService.Result repeat =
                    DragonBreathInfusionService.inject(fixture.level(), fixture.target());

            helper.assertTrue(initial == DragonBreathInfusionService.Result.SUCCESS_INITIAL, "Initial infusion failed");
            helper.assertTrue(repeat == DragonBreathInfusionService.Result.SUCCESS_EXISTING, "Repeat infusion failed");
            ResonatingAmethystBlockEntity entity = entity(fixture);
            helper.assertTrue(entity.progress().orElseThrow().pendingCount() == 2, "Infusion count changed");
            helper.assertTrue(
                    fixture.level().getBlockTicks().count() == ticksBefore + 1,
                    "Repeat infusion queued another scheduled tick");
            helper.succeed();
        }
    }

    /** Verifies dimension/support/full failures leave authoritative state unchanged. */
    @GameTest(template = "bootstrap")
    public static void invalidEnvironmentAndFullBatchDoNotMutate(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        BlockPos overworldPos = helper.absolutePos(new BlockPos(1, 2, 1));
        overworld.setBlockAndUpdate(overworldPos.below(), Blocks.END_STONE.defaultBlockState());
        overworld.setBlockAndUpdate(overworldPos, Blocks.AMETHYST_BLOCK.defaultBlockState());
        helper.assertTrue(
                DragonBreathInfusionService.inject(overworld, overworldPos)
                        == DragonBreathInfusionService.Result.WRONG_DIMENSION,
                "Overworld infusion was accepted");
        helper.assertTrue(overworld.getBlockState(overworldPos).is(Blocks.AMETHYST_BLOCK), "Overworld target changed");

        try (EndFixture missingSupport = endFixture(helper, 4)) {
            missingSupport.level().setBlockAndUpdate(missingSupport.target().below(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(
                    DragonBreathInfusionService.inject(missingSupport.level(), missingSupport.target())
                            == DragonBreathInfusionService.Result.MISSING_END_STONE,
                    "Missing support was accepted");
            helper.assertTrue(
                    missingSupport
                            .level()
                            .getBlockState(missingSupport.target())
                            .is(Blocks.AMETHYST_BLOCK),
                    "Rejected target changed");
        }

        try (EndFixture full = endFixture(helper, 8)) {
            full.level()
                    .setBlockAndUpdate(
                            full.target(), ModBlocks.RESONATING_AMETHYST.get().defaultBlockState());
            ResonatingAmethystBlockEntity entity = entity(full);
            ResonanceProgress progress = new ResonanceProgress(64, full.level().getGameTime() + 100);
            entity.initialize(progress);
            entity.ensureScheduled(full.level());
            helper.assertTrue(
                    DragonBreathInfusionService.inject(full.level(), full.target())
                            == DragonBreathInfusionService.Result.FULL,
                    "Full batch was not reported");
            helper.assertTrue(entity.progress().orElseThrow().equals(progress), "Full batch changed");
        }
        helper.succeed();
    }

    /** Verifies native player events preserve survival and creative container-item semantics. */
    @GameTest(template = "bootstrap")
    public static void playerAndFakePlayerUseAuthoritativeInventorySemantics(GameTestHelper helper) {
        try (EndFixture survivalFixture = endFixture(helper, 12);
                EndFixture creativeFixture = endFixture(helper, 16)) {
            FakePlayer survival = player(survivalFixture.level(), 1, GameType.SURVIVAL);
            survival.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DRAGON_BREATH));
            InteractionResult survivalResult =
                    postRightClick(survival, survivalFixture.target()).getCancellationResult();
            helper.assertTrue(survivalResult.consumesAction(), "Survival infusion was not handled");
            helper.assertTrue(
                    survival.getItemInHand(InteractionHand.MAIN_HAND).is(Items.GLASS_BOTTLE),
                    "Survival bottle remainder missing");
            helper.assertTrue(
                    entity(survivalFixture).progress().orElseThrow().pendingCount() == 1, "Survival count changed");

            FakePlayer creative = player(creativeFixture.level(), 2, GameType.CREATIVE);
            creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DRAGON_BREATH));
            InteractionResult creativeResult =
                    postRightClick(creative, creativeFixture.target()).getCancellationResult();
            helper.assertTrue(creativeResult.consumesAction(), "Creative infusion was not handled");
            helper.assertTrue(
                    creative.getItemInHand(InteractionHand.MAIN_HAND).is(Items.DRAGON_BREATH),
                    "Creative input was consumed");
            helper.assertTrue(creative.getInventory().countItem(Items.GLASS_BOTTLE) == 0, "Creative received a bottle");
            helper.assertTrue(
                    entity(creativeFixture).progress().orElseThrow().pendingCount() == 1, "Creative count changed");
            helper.succeed();
        }
    }

    /** Verifies an already canceled protection event cannot start or consume a ritual. */
    @GameTest(template = "bootstrap")
    public static void canceledPlayerEventCannotMutate(GameTestHelper helper) {
        try (EndFixture fixture = endFixture(helper, 20)) {
            FakePlayer player = player(fixture.level(), 3, GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DRAGON_BREATH));
            PlayerInteractEvent.RightClickBlock event = rightClick(player, fixture.target());
            event.setCanceled(true);
            NeoForge.EVENT_BUS.post(event);

            helper.assertTrue(
                    fixture.level().getBlockState(fixture.target()).is(Blocks.AMETHYST_BLOCK),
                    "Canceled event changed block");
            helper.assertTrue(
                    player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.DRAGON_BREATH),
                    "Canceled event consumed input");
            helper.succeed();
        }
    }

    /** Verifies unavailable persisted state consumes nothing and cannot fall through to dispenser behavior. */
    @GameTest(template = "bootstrap")
    public static void unavailableStateIsHandledWithoutConsumptionOrFallback(GameTestHelper helper) {
        try (EndFixture fixture = endFixture(helper, 22)) {
            fixture.level()
                    .setBlockAndUpdate(
                            fixture.target(),
                            ModBlocks.RESONATING_AMETHYST.get().defaultBlockState());
            ResonatingAmethystBlockEntity entity = entity(fixture);
            entity.loadCustomOnly(
                    new net.minecraft.nbt.CompoundTag(), fixture.level().registryAccess());

            FakePlayer player = player(fixture.level(), 4, GameType.SURVIVAL);
            ItemStack held = new ItemStack(Items.DRAGON_BREATH);
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            PlayerInteractEvent.RightClickBlock playerEvent = postRightClick(player, fixture.target());
            helper.assertTrue(
                    playerEvent.getCancellationResult().consumesAction(), "Unavailable player target fell through");
            helper.assertTrue(
                    player.getItemInHand(InteractionHand.MAIN_HAND) == held && held.getCount() == 1,
                    "Unavailable player target consumed input");

            AtomicInteger fallbackCalls = new AtomicInteger();
            DragonBreathDispenseBehavior wrapper = new DragonBreathDispenseBehavior((source, stack) -> {
                fallbackCalls.incrementAndGet();
                return ItemStack.EMPTY;
            });
            BlockSource source =
                    dispenserSource(fixture.level(), fixture.target().west(), Direction.EAST);
            ItemStack dispenserInput = new ItemStack(Items.DRAGON_BREATH);
            ItemStack result = wrapper.dispense(source, dispenserInput);
            helper.assertTrue(
                    result == dispenserInput && result.getCount() == 1, "Unavailable dispenser consumed input");
            helper.assertTrue(fallbackCalls.get() == 0, "Unavailable dispenser delegated to fallback");
            helper.succeed();
        }
    }

    /** Verifies the service thread guard fires before reading or changing the supplied world. */
    @GameTest(template = "bootstrap")
    public static void infusionRejectsOffThreadWorldAccess(GameTestHelper helper) throws Exception {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        try (var executor = Executors.newSingleThreadExecutor()) {
            ExecutionException failure;
            try {
                executor.submit(() -> DragonBreathInfusionService.inject(helper.getLevel(), pos))
                        .get();
                throw new AssertionError("Off-thread infusion unexpectedly succeeded");
            } catch (ExecutionException expected) {
                failure = expected;
            }
            helper.assertTrue(failure.getCause() instanceof IllegalStateException, "Wrong off-thread failure");
        }
        helper.succeed();
    }

    /** Verifies the registered dispenser behavior consumes once and returns the bottle in its selected slot. */
    @GameTest(template = "bootstrap")
    public static void dispenserInfusionReturnsBottleAndDelegatesIneligibleTargets(GameTestHelper helper) {
        try (EndFixture fixture = endFixture(helper, 24)) {
            BlockSource source =
                    dispenserSource(fixture.level(), fixture.target().west(), Direction.EAST);
            DispenseItemBehavior behavior = DispenserBlock.DISPENSER_REGISTRY.get(Items.DRAGON_BREATH);
            ItemStack result = behavior.dispense(source, new ItemStack(Items.DRAGON_BREATH));
            helper.assertTrue(result.is(Items.GLASS_BOTTLE), "Dispenser bottle remainder missing");
            helper.assertTrue(entity(fixture).progress().orElseThrow().pendingCount() == 1, "Dispenser count changed");
        }

        AtomicInteger fallbackCalls = new AtomicInteger();
        ItemStack fallbackResult = new ItemStack(Items.DIAMOND);
        DragonBreathDispenseBehavior wrapper = new DragonBreathDispenseBehavior((source, stack) -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });
        BlockPos dispenserPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockSource overworldSource = dispenserSource(helper.getLevel(), dispenserPos, Direction.EAST);
        ItemStack actual = wrapper.dispense(overworldSource, new ItemStack(Items.DRAGON_BREATH));
        helper.assertTrue(
                actual == fallbackResult && fallbackCalls.get() == 1, "Ineligible path did not delegate once");
        helper.succeed();
    }

    /** Verifies a direct wrong-item invocation cannot mutate ritual state before delegating. */
    @GameTest(template = "bootstrap")
    public static void dispenserWrapperRejectsWrongItemBeforeWorldMutation(GameTestHelper helper) {
        try (EndFixture fixture = endFixture(helper, 26)) {
            AtomicInteger fallbackCalls = new AtomicInteger();
            ItemStack fallbackResult = new ItemStack(Items.DIAMOND);
            DragonBreathDispenseBehavior wrapper = new DragonBreathDispenseBehavior((source, stack) -> {
                fallbackCalls.incrementAndGet();
                return fallbackResult;
            });
            BlockSource source =
                    dispenserSource(fixture.level(), fixture.target().west(), Direction.EAST);
            ItemStack actual = wrapper.dispense(source, new ItemStack(Items.APPLE));

            helper.assertTrue(actual == fallbackResult && fallbackCalls.get() == 1, "Wrong item did not delegate once");
            helper.assertTrue(
                    fixture.level().getBlockState(fixture.target()).is(Blocks.AMETHYST_BLOCK),
                    "Wrong item started resonance");
            helper.succeed();
        }
    }

    /** Verifies full rituals never delegate and overflow glass is ejected once from a full dispenser. */
    @GameTest(template = "bootstrap")
    public static void dispenserFullAndOverflowRemaindersAreConserved(GameTestHelper helper) {
        EndFixture full = endFixture(helper, 28);
        EndFixture overflow = endFixture(helper, 64);
        try {
            helper.assertTrue(
                    !new ChunkPos(full.target()).equals(new ChunkPos(overflow.target())),
                    "Dispenser remainder fixtures must not share a forced chunk");
            full.level()
                    .setBlockAndUpdate(
                            full.target(), ModBlocks.RESONATING_AMETHYST.get().defaultBlockState());
            ResonatingAmethystBlockEntity fullEntity = entity(full);
            fullEntity.initialize(new ResonanceProgress(64, full.level().getGameTime() + 100));
            fullEntity.ensureScheduled(full.level());
            AtomicInteger fallbackCalls = new AtomicInteger();
            DragonBreathDispenseBehavior wrapper = new DragonBreathDispenseBehavior((source, stack) -> {
                fallbackCalls.incrementAndGet();
                return ItemStack.EMPTY;
            });
            BlockSource fullSource = dispenserSource(full.level(), full.target().west(), Direction.EAST);
            ItemStack unchanged = new ItemStack(Items.DRAGON_BREATH);
            ItemStack fullResult = wrapper.dispense(fullSource, unchanged);
            helper.assertTrue(fullResult == unchanged && unchanged.getCount() == 1, "Full ritual consumed input");
            helper.assertTrue(fallbackCalls.get() == 0, "Full ritual delegated to fallback");
            full.close();

            BlockSource overflowSource =
                    dispenserSource(overflow.level(), overflow.target().west(), Direction.EAST);
            DispenserBlockEntity dispenser = overflowSource.blockEntity();
            for (int slot = 0; slot < dispenser.getContainerSize(); slot++) {
                dispenser.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            overflow.level().areEntitiesLoaded(ChunkPos.asLong(overflow.target())),
                            "Waiting for forced End chunk to become entity-ticking"))
                    .thenExecute(() -> {
                        try {
                            ItemStack twoBreaths = new ItemStack(Items.DRAGON_BREATH, 2);
                            ItemStack remaining = wrapper.dispense(overflowSource, twoBreaths);
                            helper.assertTrue(
                                    remaining.is(Items.DRAGON_BREATH) && remaining.getCount() == 1,
                                    "Input remainder changed");
                            List<ItemEntity> drops = overflow.level()
                                    .getEntitiesOfClass(ItemEntity.class, new AABB(overflowSource.pos()).inflate(2));
                            long glassDrops = drops.stream()
                                    .filter(drop -> drop.getItem().is(Items.GLASS_BOTTLE))
                                    .count();
                            helper.assertTrue(
                                    glassDrops == 1,
                                    "Overflow glass bottle count was " + glassDrops + " among " + drops);
                        } finally {
                            overflow.close();
                        }
                    })
                    .thenSucceed();
        } catch (RuntimeException | Error failure) {
            overflow.close();
            full.close();
            throw failure;
        }
    }

    private static PlayerInteractEvent.RightClickBlock postRightClick(FakePlayer player, BlockPos pos) {
        return NeoForge.EVENT_BUS.post(rightClick(player, pos));
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(FakePlayer player, BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(
                player,
                InteractionHand.MAIN_HAND,
                pos,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static FakePlayer player(ServerLevel level, long id, GameType gameType) {
        FakePlayer player = new FakePlayer(level, new GameProfile(new UUID(0, id), "InfusionTest" + id));
        player.setGameMode(gameType);
        return player;
    }

    private static BlockSource dispenserSource(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlockAndUpdate(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        var blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof DispenserBlockEntity dispenser)) {
            throw new IllegalStateException("Dispenser block entity missing at " + pos);
        }
        return new BlockSource(level, pos, level.getBlockState(pos), dispenser);
    }

    private static ResonatingAmethystBlockEntity entity(EndFixture fixture) {
        var blockEntity = fixture.level().getBlockEntity(fixture.target());
        if (!(blockEntity instanceof ResonatingAmethystBlockEntity resonating)) {
            throw new IllegalStateException("Resonating block entity missing at " + fixture.target());
        }
        return resonating;
    }

    private static EndFixture endFixture(GameTestHelper helper, int xOffset) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        if (end == null) {
            throw new IllegalStateException("End level is unavailable");
        }
        int fixtureSequence = END_FIXTURE_SEQUENCE.getAndIncrement();
        BlockPos target = new BlockPos(1024 + fixtureSequence * 256 + xOffset, 80, 1024);
        end.setChunkForced(target.getX() >> 4, target.getZ() >> 4, true);
        end.getChunkAt(target);
        end.setBlockAndUpdate(target.below(), Blocks.END_STONE.defaultBlockState());
        end.setBlockAndUpdate(target, Blocks.AMETHYST_BLOCK.defaultBlockState());
        return new EndFixture(end, target);
    }

    private record EndFixture(ServerLevel level, BlockPos target) implements AutoCloseable {
        @Override
        public void close() {
            level.getBlockTicks().clearArea(BoundingBox.fromCorners(target, target));
            level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(target.below(), Blocks.AIR.defaultBlockState());
            level.setChunkForced(target.getX() >> 4, target.getZ() >> 4, false);
        }
    }
}
