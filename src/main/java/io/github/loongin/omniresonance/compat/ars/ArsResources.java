// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ars;

import com.hollingsworth.arsnouveau.api.source.ISourceCap;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Optional public capability boundary. No player mana, direct field edits or guessed item carriers. */
public final class ArsResources {
    public static final BlockCapability<ISourceCap, Direction> BLOCK =
            BlockCapability.createSided(SourceVariant.TYPE, ISourceCap.class);

    private ArsResources() {}

    public static void register(ResourceAdapterDirectory directory) {
        directory.register(
                new ResourceAdapterDirectory.Descriptor(SourceVariant.TYPE, "Source", 1),
                BLOCK,
                (handler, provider) -> new SourceResourcePort(handler),
                (key, provider) -> SourceVariant.restore(key));
    }
}
