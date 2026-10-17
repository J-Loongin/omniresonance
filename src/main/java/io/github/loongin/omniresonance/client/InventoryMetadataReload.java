// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Client-owned resource reload bridge. Invalidation waits for the loading overlay to finish so language/font
 * listeners have all applied before the existing budgeted inventory queue regenerates display metadata. */
final class InventoryMetadataReload implements ResourceManagerReloadListener {
    private final Runnable invalidate;
    private boolean pending;

    InventoryMetadataReload(Runnable invalidate) {
        this.invalidate = invalidate;
    }

    void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(this);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resources) {
        pending = true;
    }

    void tick(boolean overlayPresent) {
        if (pending && !overlayPresent) {
            pending = false;
            invalidate.run();
        }
    }
}
