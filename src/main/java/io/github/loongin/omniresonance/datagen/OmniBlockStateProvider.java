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
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Generates completed block states and documented temporary vanilla-texture models. */
public final class OmniBlockStateProvider extends BlockStateProvider {
    public OmniBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, OmniResonanceMod.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlock(
                ModBlocks.RESONATING_AMETHYST.get(),
                models().cubeAll("resonating_amethyst", ResourceLocation.withDefaultNamespace("block/amethyst_block")));

        ModelFile nodeModel = models().cubeColumn(
                        "resonance_transfer_node",
                        ResourceLocation.withDefaultNamespace("block/quartz_block_side"),
                        ResourceLocation.withDefaultNamespace("block/quartz_block_top"));
        simpleBlock(ModBlocks.RESONANCE_TRANSFER_NODE.get(), nodeModel);

        Map<Direction, ModelFile> panelModels = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            AABB bounds = NodeGeometry.panelShape(direction).bounds();
            ModelFile model = models().getBuilder("resonance_transfer_panel_" + direction.getSerializedName())
                    .texture("all", ResourceLocation.withDefaultNamespace("block/quartz_block_side"))
                    .element()
                    .from((float) (bounds.minX * 16), (float) (bounds.minY * 16), (float) (bounds.minZ * 16))
                    .to((float) (bounds.maxX * 16), (float) (bounds.maxY * 16), (float) (bounds.maxZ * 16))
                    .textureAll("#all")
                    .end();
            panelModels.put(direction, model);
        }
        getVariantBuilder(ModBlocks.RESONANCE_TRANSFER_PANEL.get())
                .forAllStates(state -> ConfiguredModel.builder()
                        .modelFile(panelModels.get(state.getValue(AbstractResonanceNodeBlock.FACING)))
                        .build());
    }
}
