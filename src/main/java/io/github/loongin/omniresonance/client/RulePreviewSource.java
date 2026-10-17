// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.bootstrap.ResourceAdapters;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/** Registry-only client access. Optional types enter through the explicit bootstrap boundary, never native handlers. */
interface RulePreviewSource {
    @Nullable
    Iterator<ResourceLocation> open(FilterRulePreview preview);

    @Nullable
    RulePreviewImage image(ResourceLocation type, ResourceLocation id);

    static RulePreviewSource client() {
        return new RegistrySource(ResourceAdapters.create(), () -> {
            var client = Minecraft.getInstance();
            return client == null || client.level == null ? null : client.level.registryAccess();
        });
    }

    final class RegistrySource implements RulePreviewSource {
        private final ResourceAdapterDirectory adapters;
        private final java.util.function.Supplier<HolderLookup.Provider> registries;

        RegistrySource(
                ResourceAdapterDirectory adapters, java.util.function.Supplier<HolderLookup.Provider> registries) {
            this.adapters = adapters;
            this.registries = registries;
        }

        public @Nullable Iterator<ResourceLocation> open(FilterRulePreview preview) {
            var type = preview.type();
            if (type == null || adapters.find(type).isEmpty()) return null;
            if (preview.kind() == FilterRulePreview.Kind.EXACT)
                return List.of(ResourceLocation.parse(preview.selector())).iterator();
            if (preview.kind() == FilterRulePreview.Kind.WHOLE_TYPE && ResourceTypes.scalar(type))
                return List.of(type).iterator();
            var provider = registries.get();
            if (provider == null) return null;
            if (preview.kind() == FilterRulePreview.Kind.TAG) {
                var values = adapters.openTag(type, ResourceLocation.parse(preview.selector()), provider);
                return values == null ? Collections.emptyIterator() : values;
            }
            return adapters.registeredIds(type, provider);
        }

        public @Nullable RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
            if (type.equals(ResourceTypes.ITEM)) {
                if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
                var item = new ItemStack(BuiltInRegistries.ITEM.get(id));
                return item.isEmpty() ? null : RulePreviewImage.item(item);
            }
            if (type.equals(ResourceTypes.FLUID)) {
                if (!BuiltInRegistries.FLUID.containsKey(id)) return null;
                var fluid = new FluidStack(BuiltInRegistries.FLUID.get(id), 1000);
                return fluid.isEmpty() ? null : RulePreviewImage.fluid(fluid);
            }
            if (type.equals(ResourceTypes.ENERGY))
                return new RulePreviewImage(
                        ItemStack.EMPTY,
                        FluidStack.EMPTY,
                        EnergyDisplay.TEXTURE,
                        EnergyDisplay.TINT,
                        NodeResourcePolicyView.text("type.energy"));
            var optional = ResourceAdapters.preview(type, id);
            if (optional instanceof io.github.loongin.omniresonance.transfer.RegisteredResourceVariant registered)
                return RulePreviewImage.registered(registered);
            if (optional instanceof io.github.loongin.omniresonance.transfer.ScalarResourceVariant scalar)
                return RulePreviewImage.scalar(scalar);
            return null;
        }
    }
}
