// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** Bounded authoritative non-directory views within the existing resonance-terminal session. */
public sealed interface NetworkTerminalState {
    record NetworkSettings(NetworkSettingsSummary settings) implements NetworkTerminalState {
        public NetworkSettings {
            Objects.requireNonNull(settings, "settings");
        }
    }

    record NetworkRename(NetworkSettingsSummary settings) implements NetworkTerminalState {
        public NetworkRename {
            Objects.requireNonNull(settings, "settings");
        }
    }

    record NetworkDelete(NetworkSettingsSummary settings, NetworkDeletionSummary deletion)
            implements NetworkTerminalState {
        public NetworkDelete {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(deletion, "deletion");
            if (!settings.ownerActions()
                    || !settings.network().id().equals(deletion.networkId())
                    || !settings.network().name().equals(deletion.name())) {
                throw new IllegalArgumentException("Deletion summary does not match owner settings");
            }
        }
    }

    record Members(NetworkSummary network, NetworkMemberPage page, int administratorLimit, UUID selectedMemberId)
            implements NetworkTerminalState {
        public Members {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(selectedMemberId, "selectedMemberId");
            if (administratorLimit < -1
                    || administratorLimit > 1024
                    || page.entries().stream()
                            .noneMatch(member -> member.playerId().equals(selectedMemberId))) {
                throw new IllegalArgumentException("Invalid members view");
            }
            for (NetworkMemberSummary member : page.entries()) {
                if ((member.role() == NetworkMemberSummary.Role.OWNER)
                        != member.playerId().equals(network.ownerId())) {
                    throw new IllegalArgumentException("Member role does not match owner identity");
                }
            }
        }
    }

    record AdministratorCandidates(NetworkSummary network, OnlinePlayerPage page) implements NetworkTerminalState {
        public AdministratorCandidates {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(page, "page");
        }
    }

    record RemoveAdministrator(NetworkSummary network, NetworkMemberSummary target) implements NetworkTerminalState {
        public RemoveAdministrator {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(target, "target");
            if (target.role() != NetworkMemberSummary.Role.ADMINISTRATOR
                    || target.playerId().equals(network.ownerId())) {
                throw new IllegalArgumentException("Cannot remove the owner");
            }
        }
    }

    record NetworkRoot(NetworkSummary network) implements NetworkTerminalState {
        public NetworkRoot {
            Objects.requireNonNull(network, "network");
        }
    }

    record TunnelList(NetworkSummary network, TunnelPage page) implements NetworkTerminalState {
        public TunnelList {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(page, "page");
        }
    }

    record TunnelEdit(
            NetworkSummary network,
            @Nullable TunnelSummary existing,
            @Nullable String suggestedName) implements NetworkTerminalState {
        public TunnelEdit {
            Objects.requireNonNull(network, "network");
            if ((existing == null) != (suggestedName != null)) {
                throw new IllegalArgumentException("Invalid tunnel edit state");
            }
            if (suggestedName != null
                    && !new io.github.loongin.omniresonance.network.ManagedName(suggestedName)
                            .value()
                            .equals(suggestedName)) {
                throw new IllegalArgumentException("Tunnel suggestion must be canonical");
            }
        }
    }

    record ChannelList(NetworkSummary network, TunnelSummary tunnel, ChannelPage page) implements NetworkTerminalState {
        public ChannelList {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(page, "page");
        }
    }

    record TunnelSettings(NetworkSummary network, TunnelSummary tunnel) implements NetworkTerminalState {
        public TunnelSettings {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(tunnel, "tunnel");
        }
    }

    record DeleteConfirmation(NetworkSummary network, TopologyDeletionSummary summary) implements NetworkTerminalState {
        public DeleteConfirmation {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(summary, "summary");
        }
    }

    static NetworkTerminalState read(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new NetworkRoot(NetworkSummary.read(buffer));
            case 1 -> new TunnelList(NetworkSummary.read(buffer), TunnelPage.read(buffer));
            case 2 ->
                new TunnelEdit(
                        NetworkSummary.read(buffer),
                        buffer.readBoolean() ? TunnelSummary.read(buffer) : null,
                        buffer.readBoolean() ? NetworkSummary.readName(buffer) : null);
            case 3 ->
                new ChannelList(NetworkSummary.read(buffer), TunnelSummary.read(buffer), ChannelPage.read(buffer));
            case 5 -> new DeleteConfirmation(NetworkSummary.read(buffer), TopologyDeletionSummary.read(buffer));
            case 6 -> new TunnelSettings(NetworkSummary.read(buffer), TunnelSummary.read(buffer));
            case 7 ->
                new Members(
                        NetworkSummary.read(buffer),
                        NetworkMemberPage.read(buffer),
                        buffer.readInt(),
                        buffer.readUUID());
            case 8 -> new AdministratorCandidates(NetworkSummary.read(buffer), OnlinePlayerPage.read(buffer));
            case 9 -> new RemoveAdministrator(NetworkSummary.read(buffer), NetworkMemberSummary.read(buffer));
            case 10 -> new NetworkSettings(NetworkSettingsSummary.read(buffer));
            case 11 -> new NetworkRename(NetworkSettingsSummary.read(buffer));
            case 12 -> new NetworkDelete(NetworkSettingsSummary.read(buffer), NetworkDeletionSummary.read(buffer));
            default -> throw new DecoderException("Unknown terminal state");
        };
    }

    static void write(FriendlyByteBuf buffer, NetworkTerminalState state) {
        switch (state) {
            case NetworkSettings settings -> {
                buffer.writeByte(10);
                settings.settings().write(buffer);
            }
            case NetworkRename rename -> {
                buffer.writeByte(11);
                rename.settings().write(buffer);
            }
            case NetworkDelete delete -> {
                buffer.writeByte(12);
                delete.settings().write(buffer);
                delete.deletion().write(buffer);
            }
            case Members members -> {
                buffer.writeByte(7);
                members.network().write(buffer);
                members.page().write(buffer);
                buffer.writeInt(members.administratorLimit());
                buffer.writeUUID(members.selectedMemberId());
            }
            case AdministratorCandidates candidates -> {
                buffer.writeByte(8);
                candidates.network().write(buffer);
                candidates.page().write(buffer);
            }
            case RemoveAdministrator remove -> {
                buffer.writeByte(9);
                remove.network().write(buffer);
                remove.target().write(buffer);
            }
            case NetworkRoot root -> {
                buffer.writeByte(0);
                root.network().write(buffer);
            }
            case TunnelList list -> {
                buffer.writeByte(1);
                list.network().write(buffer);
                list.page().write(buffer);
            }
            case TunnelEdit edit -> {
                buffer.writeByte(2);
                edit.network().write(buffer);
                buffer.writeBoolean(edit.existing() != null);
                if (edit.existing() != null) {
                    edit.existing().write(buffer);
                }
                buffer.writeBoolean(edit.suggestedName() != null);
                if (edit.suggestedName() != null) {
                    NetworkSummary.writeName(buffer, edit.suggestedName());
                }
            }
            case ChannelList list -> {
                buffer.writeByte(3);
                list.network().write(buffer);
                list.tunnel().write(buffer);
                list.page().write(buffer);
            }
            case DeleteConfirmation confirmation -> {
                buffer.writeByte(5);
                confirmation.network().write(buffer);
                confirmation.summary().write(buffer);
            }
            case TunnelSettings settings -> {
                buffer.writeByte(6);
                settings.network().write(buffer);
                settings.tunnel().write(buffer);
            }
        }
    }
}
