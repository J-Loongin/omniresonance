// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.NetworkTopologyService;
import io.github.loongin.omniresonance.networking.ManagementTransferMessage;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Server-thread save accepted from an authorized menu. Holds one exact connection and immutable edit for
 * at most 200 ticks; never retains the Menu or a block entity. Window closure does not cancel ownership. */
final class SubmittedNodePolicySave {
    final ServerPlayer player;
    final UUID session;
    final int container;
    final NodeMenuRequest.BeginPolicyUpload request;
    final NetworkTopologyService.Edit edit;
    final io.github.loongin.omniresonance.networking.NodeMenuNodeSummary node;
    final @Nullable UUID channel;
    final @Nullable UUID tunnel;
    final long deadlineTicks;

    SubmittedNodePolicySave(
            ServerPlayer player,
            UUID session,
            int container,
            NodeMenuRequest.BeginPolicyUpload request,
            NetworkTopologyService.Edit edit,
            NodeMenuState.ResourceEdit metadata,
            long nowTicks) {
        this.player = player;
        this.session = session;
        this.container = container;
        this.request = request;
        this.edit = edit;
        this.node = metadata.node();
        channel = metadata instanceof NodeMenuState.DirectBindingEdit direct
                ? direct.channel().channelId()
                : null;
        tunnel = metadata instanceof NodeMenuState.DirectBindingEdit direct
                ? direct.tunnel().tunnelId()
                : null;
        deadlineTicks = Math.addExact(nowTicks, 200);
    }

    boolean matches(ServerPlayer sender, ManagementTransferMessage message) {
        return sender == player
                && session.equals(message.session())
                && request.transfer().equals(message.transfer());
    }

    void validate(NodeMenuService service, long nowTicks) {
        if (nowTicks >= deadlineTicks) throw new IllegalStateException("Submitted save expired");
        if (channel == null) service.topology().validateDomainEdit(player, edit);
        else service.topology().validateBindingEdit(player, edit, channel);
        service.validateSubmittedPhysical(node);
    }

    @Nullable
    NodeMenuResponse accept(NodeMenuService service, ManagementTransferMessage message) {
        boolean[] committing = {false};
        try {
            validate(service, service.currentTick());
            if (message instanceof ManagementTransferMessage.Abort)
                throw new IllegalStateException("Submitted save transport aborted");
            if (message instanceof ManagementTransferMessage.Chunk chunk) {
                service.transfers()
                        .upload(
                                player.getUUID(),
                                session,
                                request.transfer(),
                                chunk.offset(),
                                chunk.data(),
                                service.currentTick());
                return null;
            }
            if (!(message instanceof ManagementTransferMessage.Finish))
                throw new IllegalArgumentException("Unapproved save frame");
            ResourcePolicyEdit[] decoded = new ResourcePolicyEdit[1];
            service.transfers()
                    .finishUpload(
                            player.getUUID(),
                            session,
                            request.transfer(),
                            service.currentTick(),
                            view -> {
                                decoded[0] = ResourcePolicyEditCodec.decode(view);
                                if (channel == null)
                                    service.topology().validateDomainPolicyIntent(player, edit, decoded[0]);
                                else service.topology().validatePolicyIntent(player, edit, channel, decoded[0]);
                            },
                            () -> {
                                validate(service, service.currentTick());
                                return true;
                            },
                            view -> {
                                committing[0] = true;
                                if (channel == null)
                                    service.topology()
                                            .saveDomainConfiguration(
                                                    player,
                                                    edit,
                                                    decoded[0],
                                                    request.faces(),
                                                    request.confirmedReset());
                                else
                                    service.topology()
                                            .saveDirectBinding(
                                                    player,
                                                    edit,
                                                    channel,
                                                    decoded[0],
                                                    request.faces(),
                                                    request.confirmedReset());
                            });
            NodeMenuState result = channel == null
                    ? service.domainRoot(player, edit.networkId(), edit.nodeId())
                    : service.channelRoot(player, edit.networkId(), edit.nodeId(), tunnel, channel);
            return new NodeMenuResponse.State(container, session, request.sequence(), result);
        } catch (RuntimeException failure) {
            if (committing[0])
                org.slf4j.LoggerFactory.getLogger(SubmittedNodePolicySave.class)
                        .error(
                                "Submitted node save failed during commit; result must not be inferred or retried",
                                failure);
            cancel(service);
            return failure(
                    committing[0] ? NodeMenuResponse.Reason.INTERNAL_ERROR : NodeMenuResponse.Reason.INVALID_REQUEST);
        }
    }

    NodeMenuResponse.Failure failure(NodeMenuResponse.Reason reason) {
        return new NodeMenuResponse.Failure(container, session, request.sequence(), reason, null);
    }

    void cancel(NodeMenuService service) {
        service.transfers().abort(player.getUUID(), session, request.transfer());
        try {
            service.topology().cancel(player, edit);
        } catch (RuntimeException expired) {
            /* Exact lease may already be released or expired. */
        }
    }
}
