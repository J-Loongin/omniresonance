// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NetworkMemberCodecTest {
    private static final UUID SNAPSHOT = new UUID(820, 1);

    @Test
    void memberAndOnlinePagesRoundTripAtTheirWireBounds() {
        List<NetworkMemberSummary> members = new ArrayList<>();
        List<OnlinePlayerSummary> players = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            UUID id = new UUID(821, index);
            String name = "😀".repeat(64);
            members.add(new NetworkMemberSummary(
                    id,
                    name,
                    index == 0 ? NetworkMemberSummary.Role.OWNER : NetworkMemberSummary.Role.ADMINISTRATOR,
                    index % 2 == 0));
            players.add(new OnlinePlayerSummary(id, name));
        }
        NetworkMemberPage memberPage = new NetworkMemberPage(members, 262145, false, true);
        OnlinePlayerPage playerPage = new OnlinePlayerPage(SNAPSHOT, players, 262016, 262144, false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            memberPage.write(buffer);
            playerPage.write(buffer);
            assertEquals(memberPage, NetworkMemberPage.read(buffer));
            assertEquals(playerPage, OnlinePlayerPage.read(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void duplicateIdentitiesAndInconsistentPageMetadataAreRejected() {
        var member = new NetworkMemberSummary(SNAPSHOT, "Owner", NetworkMemberSummary.Role.OWNER, true);
        var player = new OnlinePlayerSummary(SNAPSHOT, "Player");
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkMemberPage(List.of(member, member), 2, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMemberPage(List.of(member), 262146, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnlinePlayerPage(SNAPSHOT, List.of(player, player), 0, 2, false));
        assertThrows(
                IllegalArgumentException.class, () -> new OnlinePlayerPage(SNAPSHOT, List.of(player), -1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new OnlinePlayerPage(SNAPSHOT, List.of(player), 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new OnlinePlayerPage(SNAPSHOT, List.of(), 0, 1, true));
        assertThrows(
                IllegalArgumentException.class, () -> new OnlinePlayerPage(SNAPSHOT, List.of(player), 0, 262145, true));
        assertEquals(List.of(), new OnlinePlayerPage(SNAPSHOT, List.of(), 0, 0, false).entries());
    }

    @Test
    void oversizedPagesAndUnknownRolesFailBeforeReadingTheirBodies() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(129);
            assertThrows(DecoderException.class, () -> NetworkMemberPage.read(buffer));
            buffer.clear();
            buffer.writeUUID(SNAPSHOT);
            buffer.writeVarInt(129);
            assertThrows(DecoderException.class, () -> OnlinePlayerPage.read(buffer));
            buffer.clear();
            buffer.writeUUID(SNAPSHOT);
            NetworkSummary.writeName(buffer, "Player");
            buffer.writeByte(2);
            buffer.writeBoolean(true);
            assertThrows(DecoderException.class, () -> NetworkMemberSummary.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
