// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Single-identity resource presentation. Immutable and thread independent; returns display metadata only,
 * never accesses capabilities, simulates transfers, or mutates authoritative state. */
public interface ScalarResourceVariant extends ResourceVariant {
    Component displayName();

    ResourceLocation iconItem();

    String unit();

    /** ARGB tint for the optional sprite; white preserves native colors. */
    default int tint() {
        return -1;
    }

    /** Optional block-atlas liquid-style sprite, already colored; null uses the display item. */
    default @org.jetbrains.annotations.Nullable ResourceLocation texture() {
        return null;
    }
}
