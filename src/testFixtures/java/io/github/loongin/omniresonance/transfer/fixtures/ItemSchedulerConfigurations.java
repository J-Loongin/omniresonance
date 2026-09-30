// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceDirectScheduler;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import java.util.UUID;

/** Pure fixture construction for historical item scenarios on the current multi-resource scheduler.
 * Preserves real legacy-policy conversion without retaining a legacy execution algorithm. */
public final class ItemSchedulerConfigurations {
    private ItemSchedulerConfigurations() {}

    public static ResourceDirectScheduler.Configuration configuration(
            UUID network, UUID node, UUID channel, long revision, ItemTransferPolicy policy) {
        return configuration(
                network,
                node,
                channel,
                revision,
                ResourceTransferPolicy.legacy(policy),
                io.github.loongin.omniresonance.network.WorkingFaces.attachedFace());
    }

    public static ResourceDirectScheduler.Configuration configuration(
            UUID network, UUID node, UUID channel, long revision, ResourceTransferPolicy policy) {
        return configuration(
                network,
                node,
                channel,
                revision,
                policy,
                io.github.loongin.omniresonance.network.WorkingFaces.attachedFace());
    }

    public static ResourceDirectScheduler.Configuration configuration(
            UUID network,
            UUID node,
            UUID channel,
            long revision,
            ItemTransferPolicy policy,
            io.github.loongin.omniresonance.network.WorkingFaces faces) {
        return configuration(network, node, channel, revision, ResourceTransferPolicy.legacy(policy), faces);
    }

    public static ResourceDirectScheduler.Configuration configuration(
            UUID network,
            UUID node,
            UUID channel,
            long revision,
            ResourceTransferPolicy policy,
            io.github.loongin.omniresonance.network.WorkingFaces faces) {
        return new ResourceDirectScheduler.Configuration(network, node, channel, revision, policy, faces);
    }
}
