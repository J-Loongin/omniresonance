// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.exchange.ExchangeInvitationCode;
import io.github.loongin.omniresonance.exchange.ExchangeTermsDraft;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangeIntentCodecTest {
    private static final UUID ID = new UUID(1, 1);

    private static ExchangeTermsDraft draft(ExchangeTermsDraft.FilterChoice filter) {
        return new ExchangeTermsDraft(
                ResourceScope.customSet(List.of(ResourceTypes.ITEM)),
                FilterMode.BLACKLIST,
                filter,
                Long.MAX_VALUE,
                Map.of(ResourceTypes.ITEM, 7L),
                Integer.MAX_VALUE);
    }

    @Test
    void pairingAndChannelsRoundTripWithoutClientAuthoredOwnerOrNetworkIdentity() {
        var terms = new io.github.loongin.omniresonance.exchange.ExchangeChannelDraft(
                new io.github.loongin.omniresonance.network.ManagedName("Iron"),
                false,
                draft(new ExchangeTermsDraft.KeepApproved()));
        for (var intent : List.<ExchangeIntent>of(
                new ExchangeIntent.ListTunnels(false),
                new ExchangeIntent.Pair(ExchangeInvitationCode.encode(ID)),
                new ExchangeIntent.ApprovePair(ID, 7),
                new ExchangeIntent.ClosePair(ID, 7),
                new ExchangeIntent.ListChannels(ID, true),
                new ExchangeIntent.CreateChannel(ID, 7, terms),
                new ExchangeIntent.ReviseChannel(ID, 7, terms))) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ExchangeIntentCodec.encode(buffer, intent);
                assertEquals(intent, ExchangeIntentCodec.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void forgedCountsDuplicateRatesAndTruncationCannotProduceDrafts() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(4)
                    .writeUtf(ExchangeInvitationCode.encode(ID), 22)
                    .writeByte(1)
                    .writeVarInt(262145);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
            buffer.clear();
            buffer.writeByte(4)
                    .writeUtf(ExchangeInvitationCode.encode(ID), 22)
                    .writeByte(0)
                    .writeByte(0)
                    .writeByte(0)
                    .writeLong(1)
                    .writeInt(1)
                    .writeVarInt(2);
            for (int i = 0; i < 2; i++)
                buffer.writeUtf(ResourceTypes.ITEM.toString(), 128).writeLong(7);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
            buffer.clear();
            ExchangeIntentCodec.encode(
                    buffer,
                    new ExchangeIntent.Propose(
                            ExchangeInvitationCode.encode(ID), draft(new ExchangeTermsDraft.None())));
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void everyOperationAndFilterChoiceRoundTrips() {
        for (var choice : List.<ExchangeTermsDraft.FilterChoice>of(
                new ExchangeTermsDraft.None(),
                new ExchangeTermsDraft.KeepApproved(),
                new ExchangeTermsDraft.OwnerPreset(ID, 12))) {
            for (ExchangeIntent value : List.of(
                    new ExchangeIntent.ListRules(false),
                    new ExchangeIntent.ListRules(true),
                    new ExchangeIntent.Detail(ID),
                    new ExchangeIntent.IssueCode(),
                    new ExchangeIntent.RevokeCode(ID, 5),
                    new ExchangeIntent.Propose(ExchangeInvitationCode.encode(ID), draft(choice)),
                    new ExchangeIntent.Revise(ID, 6, draft(choice)))) roundTrip(value);
        }
        for (ExchangeIntent.Action action : ExchangeIntent.Action.values())
            roundTrip(new ExchangeIntent.Change(ID, 7, action));
    }

    private static void roundTrip(ExchangeIntent value) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExchangeIntentCodec.encode(buffer, value);
            assertEquals(value, ExchangeIntentCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void trailingBytesUnknownOperationsAndMalformedBooleanReject() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(0).writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
            buffer.clear();
            buffer.writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
            buffer.clear();
            ExchangeIntentCodec.encode(buffer, new ExchangeIntent.IssueCode());
            buffer.writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> ExchangeIntentCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void negativeRevisionsAndMalformedCodesRejectBeforeEncoding() {
        assertThrows(
                IllegalArgumentException.class, () -> new ExchangeIntent.Change(ID, -1, ExchangeIntent.Action.APPROVE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeIntent.Propose("A".repeat(21) + "B", draft(new ExchangeTermsDraft.None())));
    }
}
