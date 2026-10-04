// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Generates material item models and block-model-backed node items. */
public final class OmniItemModelProvider extends ItemModelProvider {
    public OmniItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, OmniResonanceMod.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        materialModel("omni_dust");
        materialModel("resonance_substrate");
        materialModel("resonance_core");
        generatedBlockModel("resonance_transfer_node", "resonance_transfer_node");
        generatedBlockModel("resonance_transfer_panel", "resonance_transfer_panel_south");
    }

    private void materialModel(String item) {
        getBuilder(item)
                .parent(new ModelFile.UncheckedModelFile("minecraft:item/generated"))
                .texture("layer0", modLoc("item/" + item));
    }

    private void generatedBlockModel(String item, String blockModel) {
        getBuilder(item).parent(new ModelFile.UncheckedModelFile(modLoc("block/" + blockModel)));
    }
}
