// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.RegisteredResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Lossless registered chemical identity; quantities and runtime attributes never become a second inventory. */
public final class ChemicalVariant implements RegisteredResourceVariant {
    public static final ResourceLocation TYPE = io.github.loongin.omniresonance.transfer.ResourceTypes.CHEMICAL;
    private final ResourceVariantKey key;
    private final Holder<Chemical> chemical;
    private final ResourceLocation id;

    private ChemicalVariant(Holder<Chemical> chemical, ResourceLocation id, ResourceVariantKey key) {
        this.chemical = chemical;
        this.id = id;
        this.key = key;
    }

    public static ChemicalVariant from(ChemicalStack stack) {
        Objects.requireNonNull(stack);
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty chemical identity");
        var holder = stack.getChemicalHolder();
        var id = holder.unwrapKey().orElseThrow().location();
        if (!MekanismAPI.CHEMICAL_REGISTRY.containsKey(id) || MekanismAPI.CHEMICAL_REGISTRY.get(id) != holder.value())
            throw new IllegalArgumentException("Unregistered chemical identity");
        var tag = new CompoundTag();
        tag.putString("id", id.toString());
        return new ChemicalVariant(holder, id, new ResourceVariantKey(TYPE, CanonicalResourceNbt.encode(tag)));
    }

    public static ChemicalVariant restore(ResourceVariantKey key) {
        if (!TYPE.equals(key.typeId())) throw new IllegalArgumentException("Wrong chemical type");
        var decoded = CanonicalResourceNbt.decode(key.canonicalBytes());
        if (!(decoded instanceof CompoundTag tag)
                || !tag.getAllKeys().equals(Set.of("id"))
                || !tag.contains("id", Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid chemical identity");
        var id = ResourceLocation.parse(tag.getString("id"));
        if (!id.toString().equals(tag.getString("id")) || !MekanismAPI.CHEMICAL_REGISTRY.containsKey(id))
            throw new IllegalArgumentException("Missing chemical identity");
        var chemical = MekanismAPI.CHEMICAL_REGISTRY.getHolder(id).orElseThrow();
        var result = from(new ChemicalStack(chemical, 1));
        if (!result.key.equals(key)) throw new IllegalArgumentException("Chemical identity cannot round trip");
        return result;
    }

    public ChemicalStack stack(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Chemical amount must be positive");
        return new ChemicalStack(chemical, amount);
    }

    public Object recipeIngredient() {
        return stack(1000);
    }

    public ResourceVariantKey key() {
        return key;
    }

    public ResourceLocation resourceId() {
        return id;
    }

    public Component displayName() {
        return chemical.value().getTextComponent();
    }

    public ResourceLocation texture() {
        return chemical.value().getIcon();
    }

    public int tint() {
        return chemical.value().getTint();
    }

    @Override
    public List<Component> tooltipLines(net.minecraft.world.item.Item.TooltipContext context) {
        var lines = new java.util.ArrayList<Component>();
        stack(1).appendHoverText(context, lines, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
        return lines.stream()
                .limit(4)
                .map(Component::copy)
                .map(component -> (Component) component)
                .toList();
    }

    public List<ResourceLocation> tags() {
        return chemical.tags().map(t -> t.location()).toList();
    }
}
