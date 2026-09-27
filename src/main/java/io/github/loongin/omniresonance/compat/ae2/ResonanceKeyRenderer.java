// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.client.AEKeyRenderHandler;
import appeng.api.client.AEKeyRendering;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.loongin.omniresonance.bootstrap.ResourceAdapters;
import io.github.loongin.omniresonance.transfer.RegisteredResourceVariant;
import io.github.loongin.omniresonance.transfer.ScalarResourceVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;

/** Client-only native atlas presentation. No world scanning or mutable storage reference is retained. */
public final class ResonanceKeyRenderer<T extends ResonanceKey> implements AEKeyRenderHandler<T> {
    private static final io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory ADAPTERS =
            ResourceAdapters.create();

    // Client-owned, at most 256 immutable key appearances. No sprites/native objects are cached.
    // World changes and a 100-tick TTL invalidate metadata; a weak owner never retains a closed level.
    private static final java.util.Map<ResonanceKey, Appearance> APPEARANCES = new java.util.LinkedHashMap<>();
    private static java.lang.ref.WeakReference<Level> appearanceLevel = new java.lang.ref.WeakReference<>(null);
    private static long refreshTick;

    private record Appearance(ResourceLocation texture, int color) {}

    public static void register() {
        AEKeyRendering.register(ResonanceKeys.ENERGY_TYPE, ResonanceKeys.Energy.class, new ResonanceKeyRenderer<>());
        AEKeyRendering.register(ResonanceKeys.SOURCE_TYPE, ResonanceKeys.Source.class, new ResonanceKeyRenderer<>());
        AEKeyRendering.register(ResonanceKeys.SOUL_TYPE, ResonanceKeys.Soul.class, new ResonanceKeyRenderer<>());
        AEKeyRendering.register(
                ResonanceKeys.CHEMICAL_TYPE, ResonanceKeys.Chemical.class, new ResonanceKeyRenderer<>());
    }

    private static Appearance appearance(ResonanceKey key, Level level) {
        long tick = level.getGameTime();
        if (appearanceLevel.get() != level || tick < refreshTick || tick - refreshTick >= 100) {
            APPEARANCES.clear();
            appearanceLevel = new java.lang.ref.WeakReference<>(level);
            refreshTick = tick;
        }
        var cached = APPEARANCES.get(key);
        if (cached != null) return cached;
        var value = resolveAppearance(key, level);
        if (APPEARANCES.size() >= 256)
            APPEARANCES.remove(APPEARANCES.keySet().iterator().next());
        APPEARANCES.put(key, value);
        return value;
    }

    private static Appearance resolveAppearance(ResonanceKey key, Level level) {
        if (key instanceof ResonanceKeys.Energy)
            return new Appearance(ResourceLocation.parse("minecraft:block/water_still"), 0xFF50E060);
        var variant = ADAPTERS.decode(key.resourceKey(), level.registryAccess()).orElse(null);
        if (variant instanceof RegisteredResourceVariant registered)
            return new Appearance(registered.texture(), registered.tint());
        if (variant instanceof ScalarResourceVariant scalar && scalar.texture() != null)
            return new Appearance(scalar.texture(), scalar.tint());
        return new Appearance(ResourceLocation.withDefaultNamespace("missingno"), -1);
    }

    @Override
    public void drawInGui(Minecraft minecraft, GuiGraphics graphics, int x, int y, T key) {
        if (minecraft.level == null) return;
        var value = appearance(key, minecraft.level);
        var sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(value.texture());
        graphics.blit(
                x,
                y,
                0,
                16,
                16,
                sprite,
                ((value.color() >>> 16) & 255) / 255f,
                ((value.color() >>> 8) & 255) / 255f,
                (value.color() & 255) / 255f,
                1f);
    }

    @Override
    public void drawOnBlockFace(PoseStack pose, MultiBufferSource buffers, T key, float scale, int light, Level level) {
        var value = appearance(key, level);
        var sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(value.texture());
        var vertices = buffers.getBuffer(RenderType.solid());
        float half = Math.max(0, scale) / 2;
        for (int corner = 0; corner < 4; corner++) {
            boolean right = corner == 1 || corner == 2;
            boolean top = corner >= 2;
            vertices.addVertex(pose.last().pose(), right ? half : -half, top ? half : -half, 0.01f)
                    .setColor(value.color() | 0xFF000000)
                    .setUv(right ? sprite.getU1() : sprite.getU0(), top ? sprite.getV0() : sprite.getV1())
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(0, 0, 1);
        }
    }

    @Override
    public Component getDisplayName(T key) {
        return key.getDisplayName();
    }

    @Override
    public java.util.List<Component> getTooltip(T key) {
        var lines = new java.util.ArrayList<Component>();
        lines.add(key.getDisplayName());
        var level = Minecraft.getInstance().level;
        if (level != null
                && ADAPTERS.decode(key.resourceKey(), level.registryAccess()).orElse(null)
                        instanceof RegisteredResourceVariant variant)
            lines.addAll(variant.tooltipLines(net.minecraft.world.item.Item.TooltipContext.of(level)));
        return java.util.List.copyOf(lines);
    }
}
