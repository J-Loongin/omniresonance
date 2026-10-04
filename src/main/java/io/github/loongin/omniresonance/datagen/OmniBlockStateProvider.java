// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NodeGeometry;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder.FaceRotation;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Generates completed block states and block models. */
public final class OmniBlockStateProvider extends BlockStateProvider {
    public OmniBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, OmniResonanceMod.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlock(
                ModBlocks.RESONATING_AMETHYST.get(),
                models().cubeAll("resonating_amethyst", modLoc("block/resonating_amethyst")));

        ResourceLocation frontTexture = modLoc("block/resonance_transfer_node_front");
        ResourceLocation topTexture = modLoc("block/resonance_transfer_node_top");
        ResourceLocation leftTexture = modLoc("block/resonance_transfer_node_left");
        ResourceLocation rightTexture = modLoc("block/resonance_transfer_node_right");
        ResourceLocation bottomTexture = modLoc("block/resonance_transfer_node_bottom");
        ResourceLocation backTexture = modLoc("block/resonance_transfer_node_back");
        ResourceLocation panelEdgeTexture = modLoc("block/resonance_transfer_panel_edge");
        ModelFile blockParent = models().getExistingFile(ResourceLocation.withDefaultNamespace("block/block"));

        ModelFile nodeModel = models().getBuilder("resonance_transfer_node")
                .parent(blockParent)
                .texture("particle", frontTexture)
                .texture("front", frontTexture)
                .texture("top", topTexture)
                .texture("left", leftTexture)
                .texture("right", rightTexture)
                .texture("bottom", bottomTexture)
                .texture("back", backTexture)
                .element()
                .face(Direction.NORTH)
                .texture("#front")
                .cullface(Direction.NORTH)
                .end()
                .face(Direction.SOUTH)
                .texture("#back")
                .cullface(Direction.SOUTH)
                .end()
                .face(Direction.UP)
                .texture("#top")
                .cullface(Direction.UP)
                .end()
                .face(Direction.DOWN)
                .texture("#bottom")
                .cullface(Direction.DOWN)
                .end()
                .face(Direction.EAST)
                .texture("#left")
                .cullface(Direction.EAST)
                .end()
                .face(Direction.WEST)
                .texture("#right")
                .cullface(Direction.WEST)
                .end()
                .end();
        getVariantBuilder(ModBlocks.RESONANCE_TRANSFER_NODE.get()).forAllStates(state -> {
            Direction facing = state.getValue(AbstractResonanceNodeBlock.FACING);
            return ConfiguredModel.builder()
                    .modelFile(nodeModel)
                    .rotationX(nodeRotationX(facing))
                    .rotationY(nodeRotationY(facing))
                    .build();
        });

        Map<Direction, ModelFile> panelModels = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            AABB bounds = NodeGeometry.panelShape(direction).bounds();
            BlockModelBuilder model = models().getBuilder("resonance_transfer_panel_" + direction.getSerializedName())
                    .parent(blockParent)
                    .texture("particle", panelEdgeTexture)
                    .texture("front", frontTexture)
                    .texture("back", backTexture)
                    .texture("edge", panelEdgeTexture);
            ModelBuilder<BlockModelBuilder>.ElementBuilder element = model.element()
                    .from((float) (bounds.minX * 16), (float) (bounds.minY * 16), (float) (bounds.minZ * 16))
                    .to((float) (bounds.maxX * 16), (float) (bounds.maxY * 16), (float) (bounds.maxZ * 16));
            for (Direction face : Direction.values()) {
                if (face == direction) {
                    element.face(face).texture("#back").uvs(0, 0, 16, 16);
                } else if (face == direction.getOpposite()) {
                    ModelBuilder<BlockModelBuilder>.ElementBuilder.FaceBuilder frontFace =
                            element.face(face).texture("#front").uvs(0, 0, 16, 16);
                    if (direction.getAxis().isVertical()) {
                        frontFace.rotation(FaceRotation.UPSIDE_DOWN);
                    }
                } else {
                    element.face(face).texture("#edge");
                }
            }
            element.end();
            panelModels.put(direction, model);
        }
        getVariantBuilder(ModBlocks.RESONANCE_TRANSFER_PANEL.get())
                .forAllStates(state -> ConfiguredModel.builder()
                        .modelFile(panelModels.get(state.getValue(AbstractResonanceNodeBlock.FACING)))
                        .build());
    }

    private static int nodeRotationX(Direction facing) {
        return switch (facing) {
            case DOWN -> 270;
            case UP -> 90;
            default -> 0;
        };
    }

    private static int nodeRotationY(Direction facing) {
        return switch (facing) {
            case NORTH -> 180;
            case WEST -> 90;
            case EAST -> 270;
            default -> 0;
        };
    }
}
