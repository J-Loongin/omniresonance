// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded network-settings view; administrator instances never carry owner-only default state. */
public record NetworkSettingsSummary(
        NetworkSummary network,
        String ownerName,
        long managementRevision,
        long topologyRevision,
        boolean ownerActions,
        boolean defaultNetwork) {
    public NetworkSettingsSummary {
        Objects.requireNonNull(network, "network");
        if (!new ManagedName(ownerName).value().equals(ownerName)
                || managementRevision < 0
                || topologyRevision < 0
                || (!ownerActions && defaultNetwork)) {
            throw new IllegalArgumentException("Invalid network settings summary");
        }
    }

    static NetworkSettingsSummary read(FriendlyByteBuf buffer) {
        return new NetworkSettingsSummary(
                NetworkSummary.read(buffer),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readLong(),
                buffer.readBoolean(),
                buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        network.write(buffer);
        NetworkSummary.writeName(buffer, ownerName);
        buffer.writeLong(managementRevision);
        buffer.writeLong(topologyRevision);
        buffer.writeBoolean(ownerActions);
        buffer.writeBoolean(defaultNetwork);
    }
}
