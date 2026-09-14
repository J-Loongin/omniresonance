// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Development-only, player-operable endpoint used for M3 native-resource verification. */
@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "omniresonance")
public final class M3ManualEndpointGameTests {
    public static final ResourceLocation ID = ResourceLocation.parse("omniresonance:m3_test_endpoint");
    private static final int ITEM_SLOTS = 9;
    private static final int FLUID_CAPACITY = 16_000;
    private static final int ENERGY_CAPACITY = 100_000;

    private static Block endpointBlock;
    private static BlockEntityType<EndpointBlockEntity> endpointType;

    private M3ManualEndpointGameTests() {}

    /** Registers the fixture block, its obtainable item, and its block entity only in gameTest runs. */
    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            endpointBlock = new EndpointBlock();
            helper.register(ID, endpointBlock);
        });
        event.register(
                Registries.ITEM, helper -> helper.register(ID, new BlockItem(endpointBlock, new Item.Properties())));
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            endpointType = BlockEntityType.Builder.of(EndpointBlockEntity::new, endpointBlock)
                    .build(null);
            helper.register(ID, endpointType);
        });
    }

    /** Exposes all three native block capabilities on every face for manual node placement. */
    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, endpointType, (entity, side) -> entity.items);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, endpointType, (entity, side) -> entity.fluid);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, endpointType, (entity, side) -> entity.energy);
    }

    /** Verifies real registry entries, six-sided capabilities, exact native data, and save/load identity. */
    @GameTest(template = "bootstrap")
    public static void registeredEndpointRoundTripsNativeResources(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.get(ID) == endpointBlock, "Fixture block was not registered");
        helper.assertTrue(BuiltInRegistries.ITEM.get(ID) instanceof BlockItem, "Fixture BlockItem was not registered");
        helper.assertTrue(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ID) == endpointType, "Fixture type was not registered");

        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        helper.getLevel().setBlock(pos, endpointBlock.defaultBlockState(), 3);
        EndpointBlockEntity entity = (EndpointBlockEntity) helper.getLevel().getBlockEntity(pos);
        IItemHandler itemIdentity = entity.items;
        IFluidHandler fluidIdentity = entity.fluid;
        IEnergyStorage energyIdentity = entity.energy;
        for (Direction side : Direction.values()) {
            helper.assertTrue(
                    helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, side) == itemIdentity,
                    "Item capability missing or unstable on " + side);
            helper.assertTrue(
                    helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, side) == fluidIdentity,
                    "Fluid capability missing or unstable on " + side);
            helper.assertTrue(
                    helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) == energyIdentity,
                    "Energy capability missing or unstable on " + side);
        }

        helper.assertTrue(
                entity.items
                        .insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false)
                        .isEmpty(),
                "Could not insert 64 iron ingots through native capability");
        helper.assertTrue(
                entity.fluid.fill(new FluidStack(Fluids.WATER, 3_000), IFluidHandler.FluidAction.EXECUTE) == 3_000,
                "Could not insert 3000 mB water through native capability");
        helper.assertTrue(entity.energy.receiveEnergy(50_000, false) == 50_000, "Could not insert 50000 FE");

        CompoundTag saved = entity.saveCustomOnly(helper.getLevel().registryAccess());
        assertManualDataShape(helper, saved);
        EndpointBlockEntity loaded = new EndpointBlockEntity(pos, endpointBlock.defaultBlockState());
        IItemHandler loadedItemIdentity = loaded.items;
        IFluidHandler loadedFluidIdentity = loaded.fluid;
        IEnergyStorage loadedEnergyIdentity = loaded.energy;
        loaded.loadCustomOnly(saved, helper.getLevel().registryAccess());
        assertContents(helper, loaded, 64, 3_000, 50_000);
        helper.assertTrue(
                loaded.items == loadedItemIdentity
                        && loaded.fluid == loadedFluidIdentity
                        && loaded.energy == loadedEnergyIdentity,
                "Save/load replaced a native handler object");
        helper.succeed();
    }

    /** Verifies malformed or over-capacity native payloads never partially update the fixture. */
    @GameTest(template = "bootstrap")
    public static void invalidNativeDataIsRejectedAtomically(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        helper.getLevel().setBlock(pos, endpointBlock.defaultBlockState(), 3);
        EndpointBlockEntity entity = (EndpointBlockEntity) helper.getLevel().getBlockEntity(pos);
        entity.items.insertItem(0, new ItemStack(Items.IRON_INGOT, 8), false);
        entity.fluid.fill(new FluidStack(Fluids.WATER, 2_000), IFluidHandler.FluidAction.EXECUTE);
        entity.energy.receiveEnergy(4_000, false);
        CompoundTag validDifferent = nativeData(helper, 16, 3_000, 5_000);

        CompoundTag badSize = validDifferent.copy();
        badSize.getCompound("test_items").putInt("Size", 1_000_000);
        rejectWithoutChange(helper, entity, badSize, "arbitrary item Size");

        CompoundTag negativeItems = validDifferent.copy();
        negativeItems
                .getCompound("test_items")
                .getList("Items", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putInt("count", -1);
        rejectWithoutChange(helper, entity, negativeItems, "negative item count");

        CompoundTag excessiveItems = validDifferent.copy();
        excessiveItems
                .getCompound("test_items")
                .getList("Items", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putInt("count", 65);
        rejectWithoutChange(helper, entity, excessiveItems, "over-capacity item count");

        CompoundTag stringItems = validDifferent.copy();
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("not_an_item_entry"));
        stringItems.getCompound("test_items").put("Items", strings);
        rejectWithoutChange(helper, entity, stringItems, "string item list");

        CompoundTag integerItems = validDifferent.copy();
        ListTag integers = new ListTag();
        integers.add(IntTag.valueOf(1));
        integerItems.getCompound("test_items").put("Items", integers);
        rejectWithoutChange(helper, entity, integerItems, "integer item list");

        CompoundTag negativeFluid = validDifferent.copy();
        negativeFluid.getCompound("test_fluid").getCompound("Fluid").putInt("amount", -1);
        rejectWithoutChange(helper, entity, negativeFluid, "negative fluid amount");

        CompoundTag excessiveFluid = validDifferent.copy();
        excessiveFluid.getCompound("test_fluid").getCompound("Fluid").putInt("amount", FLUID_CAPACITY + 1);
        rejectWithoutChange(helper, entity, excessiveFluid, "over-capacity fluid amount");

        CompoundTag negativeEnergy = validDifferent.copy();
        negativeEnergy.putInt("test_energy", -1);
        rejectWithoutChange(helper, entity, negativeEnergy, "negative energy");

        CompoundTag excessiveEnergy = validDifferent.copy();
        excessiveEnergy.putInt("test_energy", ENERGY_CAPACITY + 1);
        rejectWithoutChange(helper, entity, excessiveEnergy, "over-capacity energy");
        helper.succeed();
    }

    private static CompoundTag nativeData(GameTestHelper helper, int items, int fluid, int energy) {
        EndpointBlockEntity entity = new EndpointBlockEntity(BlockPos.ZERO, endpointBlock.defaultBlockState());
        entity.items.insertItem(0, new ItemStack(Items.IRON_INGOT, items), false);
        entity.fluid.fill(new FluidStack(Fluids.WATER, fluid), IFluidHandler.FluidAction.EXECUTE);
        entity.energy.receiveEnergy(energy, false);
        return entity.saveCustomOnly(helper.getLevel().registryAccess());
    }

    private static void rejectWithoutChange(
            GameTestHelper helper, EndpointBlockEntity entity, CompoundTag candidate, String description) {
        entity.loadCustomOnly(candidate, helper.getLevel().registryAccess());
        assertContents(helper, entity, 8, 2_000, 4_000);
        helper.assertTrue(entity.items.getSlots() == ITEM_SLOTS, "Rejected " + description + " resized storage");
    }

    private static void assertContents(
            GameTestHelper helper, EndpointBlockEntity entity, int items, int fluid, int energy) {
        helper.assertTrue(
                entity.items.getStackInSlot(0).is(Items.IRON_INGOT)
                        && entity.items.getStackInSlot(0).getCount() == items,
                "Unexpected item contents");
        helper.assertTrue(
                entity.fluid.getFluid().is(Fluids.WATER) && entity.fluid.getFluidAmount() == fluid,
                "Unexpected fluid contents");
        helper.assertTrue(entity.energy.getEnergyStored() == energy, "Unexpected energy contents");
    }

    private static void assertManualDataShape(GameTestHelper helper, CompoundTag saved) {
        CompoundTag items = saved.getCompound("test_items");
        CompoundTag fluid = saved.getCompound("test_fluid").getCompound("Fluid");
        helper.assertTrue(
                items.getInt("Size") == ITEM_SLOTS
                        && items.getList("Items", Tag.TAG_COMPOUND)
                                        .getCompound(0)
                                        .getInt("Slot")
                                == 0,
                "ItemStackHandler did not use documented Size/Items/Slot data");
        helper.assertTrue(
                fluid.getString("id").equals("minecraft:water") && fluid.getInt("amount") == 3_000,
                "FluidTank did not use documented Fluid/id/amount data");
        helper.assertTrue(saved.getInt("test_energy") == 50_000, "EnergyStorage did not use an IntTag");
    }

    private static final class EndpointBlock extends Block implements EntityBlock {
        EndpointBlock() {
            super(Properties.ofFullCopy(Blocks.IRON_BLOCK));
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new EndpointBlockEntity(pos, state);
        }
    }

    static final class EndpointBlockEntity extends BlockEntity {
        final DirtyItemHandler items;
        final DirtyFluidTank fluid;
        final DirtyEnergyStorage energy;

        EndpointBlockEntity(BlockPos pos, BlockState state) {
            super(endpointType, pos, state);
            items = new DirtyItemHandler(this::setChanged);
            fluid = new DirtyFluidTank(this::setChanged);
            energy = new DirtyEnergyStorage(this::setChanged);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("test_items", items.serializeNBT(registries));
            tag.put("test_fluid", fluid.writeToNBT(registries, new CompoundTag()));
            tag.put("test_energy", energy.serializeNBT(registries));
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            LoadedData loaded = validate(tag, registries);
            if (loaded == null) return;
            items.replaceFrom(loaded.items);
            fluid.replaceFrom(loaded.fluid);
            energy.replaceFrom(loaded.energy);
        }

        private static LoadedData validate(CompoundTag root, HolderLookup.Provider registries) {
            if (!root.contains("test_items", Tag.TAG_COMPOUND)
                    || !root.contains("test_fluid", Tag.TAG_COMPOUND)
                    || !root.contains("test_energy", Tag.TAG_INT)) return null;
            CompoundTag itemTag = root.getCompound("test_items");
            if (!itemTag.contains("Size", Tag.TAG_INT)
                    || itemTag.getInt("Size") != ITEM_SLOTS
                    || !itemTag.contains("Items", Tag.TAG_LIST)) return null;
            Tag rawEntries = itemTag.get("Items");
            if (!(rawEntries instanceof ListTag entries)) return null;
            if ((entries.isEmpty() && entries.getElementType() != Tag.TAG_END)
                    || (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
                    || entries.size() > ITEM_SLOTS) return null;
            Set<Integer> occupied = new HashSet<>(ITEM_SLOTS);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (!entry.contains("Slot", Tag.TAG_INT)
                        || !entry.contains("count", Tag.TAG_INT)
                        || entry.sizeInBytes() > 262_144L) return null;
                int slot = entry.getInt("Slot");
                int count = entry.getInt("count");
                if (count <= 0 || count > 99) return null;
                ItemStack stack = ItemStack.parse(registries, entry).orElse(ItemStack.EMPTY);
                if (slot < 0
                        || slot >= ITEM_SLOTS
                        || !occupied.add(slot)
                        || stack.isEmpty()
                        || stack.getCount() < 0
                        || stack.getCount() > stack.getMaxStackSize()) return null;
            }
            DirtyItemHandler loadedItems = new DirtyItemHandler(() -> {});
            loadedItems.deserializeNBT(registries, itemTag);

            CompoundTag tankTag = root.getCompound("test_fluid");
            FluidStack loadedFluid = FluidStack.EMPTY;
            if (!tankTag.isEmpty()) {
                if (!tankTag.contains("Fluid", Tag.TAG_COMPOUND)) return null;
                CompoundTag fluidTag = tankTag.getCompound("Fluid");
                if (!fluidTag.contains("amount", Tag.TAG_INT) || fluidTag.sizeInBytes() > 262_144L) return null;
                int amount = fluidTag.getInt("amount");
                if (amount <= 0 || amount > FLUID_CAPACITY) return null;
                loadedFluid = FluidStack.parse(registries, fluidTag).orElse(FluidStack.EMPTY);
                if (loadedFluid.isEmpty()) return null;
            }
            int loadedEnergy = root.getInt("test_energy");
            if (loadedEnergy < 0 || loadedEnergy > ENERGY_CAPACITY) return null;
            return new LoadedData(loadedItems, loadedFluid, loadedEnergy);
        }

        private static final class DirtyItemHandler extends ItemStackHandler {
            private final Runnable changed;

            DirtyItemHandler(Runnable changed) {
                super(ITEM_SLOTS);
                this.changed = changed;
            }

            @Override
            protected void onContentsChanged(int slot) {
                changed.run();
            }

            void replaceFrom(DirtyItemHandler source) {
                for (int slot = 0; slot < ITEM_SLOTS; slot++) {
                    stacks.set(slot, source.getStackInSlot(slot).copy());
                }
            }
        }

        private static final class DirtyFluidTank extends FluidTank {
            private final Runnable changed;

            DirtyFluidTank(Runnable changed) {
                super(FLUID_CAPACITY);
                this.changed = changed;
            }

            @Override
            protected void onContentsChanged() {
                changed.run();
            }

            void replaceFrom(FluidStack source) {
                setFluid(source.copy());
            }
        }

        private static final class DirtyEnergyStorage extends EnergyStorage {
            private final Runnable changed;

            DirtyEnergyStorage(Runnable changed) {
                super(ENERGY_CAPACITY);
                this.changed = changed;
            }

            @Override
            public int receiveEnergy(int amount, boolean simulate) {
                int received = super.receiveEnergy(amount, simulate);
                if (!simulate && received > 0) changed.run();
                return received;
            }

            @Override
            public int extractEnergy(int amount, boolean simulate) {
                int extracted = super.extractEnergy(amount, simulate);
                if (!simulate && extracted > 0) changed.run();
                return extracted;
            }

            void replaceFrom(int value) {
                energy = value;
            }
        }

        private record LoadedData(DirtyItemHandler items, FluidStack fluid, int energy) {}
    }
}
