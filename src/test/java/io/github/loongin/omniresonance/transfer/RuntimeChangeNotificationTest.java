// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RuntimeChangeNotificationTest {
    @Test
    void authorityNotifiesButRecoveryDirtyDoesNotRebuildRoutes() {
        NetworkSavedData data = NetworkSavedData.create(
                new NetworkMetadata(new UUID(1, 1), new UUID(2, 1), new ManagedName("Runtime"), 0, Set.of()));
        AtomicInteger events = new AtomicInteger();
        data.onRuntimeChanged(events::incrementAndGet);
        data.createTunnel(new UUID(3, 1), new ManagedName("Main"), new UUID(4, 1), new ManagedName("Items"), -1);
        assertEquals(1, events.get());
        var key = new ResourceVariantKey(ResourceLocation.parse("example:raw"), new byte[] {1});
        try (var reservation = data.recovery().reserve(key, 4, 64, 1048576).orElseThrow()) {
            reservation.commit(4);
        }
        assertEquals(1, events.get());
        assertEquals(4, data.recovery().amount(key));
        data.onRuntimeChanged(null);
        data.setDirty();
        assertEquals(1, events.get());
    }
}
