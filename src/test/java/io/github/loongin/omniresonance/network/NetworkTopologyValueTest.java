// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Pure immutable contracts for v4 tunnel, channel and node-direction values. */
final class NetworkTopologyValueTest {
    private static final UUID TUNNEL = new UUID(301, 1);
    private static final UUID CHANNEL = new UUID(301, 2);
    private static final UUID NODE = new UUID(301, 3);

    @Test
    void directionsHaveExactStableNamesAndRejectUnknownValues() {
        assertAll(
                () -> assertEquals("input", TransferDirection.INPUT.serializedName()),
                () -> assertEquals("output", TransferDirection.OUTPUT.serializedName()),
                () -> assertSame(TransferDirection.INPUT, TransferDirection.fromSerializedName("input")),
                () -> assertSame(TransferDirection.OUTPUT, TransferDirection.fromSerializedName("output")),
                () -> assertThrows(
                        IllegalArgumentException.class, () -> TransferDirection.fromSerializedName("sideways")),
                () -> assertThrows(NullPointerException.class, () -> TransferDirection.fromSerializedName(null)));
    }

    @Test
    void tunnelReplacementsAdvanceOneRevisionAndAllocateChannelsMonotonically() {
        NetworkTunnelRecord fresh = NetworkTunnelRecord.fresh(TUNNEL, 4, new ManagedName("Factory"));
        NetworkTunnelRecord renamed = fresh.withName(new ManagedName("Ore processing"));
        NetworkTunnelRecord disabled = renamed.withEnabled(false);
        NetworkTunnelRecord allocated = disabled.withLastChannelNumber(1);

        assertAll(
                () -> assertEquals(0, fresh.revision()),
                () -> assertTrue(fresh.enabled()),
                () -> assertEquals(0, fresh.lastChannelNumber()),
                () -> assertSame(fresh, fresh.withName(fresh.name())),
                () -> assertSame(fresh, fresh.withEnabled(true)),
                () -> assertSame(fresh, fresh.withLastChannelNumber(0)),
                () -> assertEquals(1, renamed.revision()),
                () -> assertEquals(2, disabled.revision()),
                () -> assertFalse(disabled.enabled()),
                () -> assertEquals(3, allocated.revision()),
                () -> assertEquals(1, allocated.lastChannelNumber()),
                () -> assertThrows(IllegalArgumentException.class, () -> allocated.withLastChannelNumber(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> allocated.withLastChannelNumber(3)));
    }

    @Test
    void channelAndConfigurationValuesOwnExactRelationships() {
        NetworkChannelRecord channel = NetworkChannelRecord.fresh(CHANNEL, TUNNEL, 2, new ManagedName("Items"));
        NetworkChannelRecord renamed = channel.withName(new ManagedName("Primary items"));
        DirectNodeBinding direct = new DirectNodeBinding(NODE, CHANNEL, TransferDirection.INPUT);
        DomainNodeConfiguration domain = new DomainNodeConfiguration(NODE, TransferDirection.OUTPUT);

        assertAll(
                () -> assertEquals(TUNNEL, channel.tunnelId()),
                () -> assertEquals(2, channel.channelNumber()),
                () -> assertEquals(0, channel.revision()),
                () -> assertSame(channel, channel.withName(channel.name())),
                () -> assertEquals(1, renamed.revision()),
                () -> assertEquals(CHANNEL, direct.channelId()),
                () -> assertEquals(TransferDirection.INPUT, direct.direction()),
                () -> assertEquals(TransferDirection.OUTPUT, domain.direction()));
    }

    @Test
    void nodeConfigurationAndMovementAdvanceExactlyOnceAndPreservePublicFields() {
        GlobalPos position = GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 70, -9));
        NetworkNodeRecord node = new NetworkNodeRecord(
                NODE,
                6,
                new ManagedName("Input"),
                position,
                NodeForm.PANEL,
                Direction.WEST,
                8,
                false,
                true,
                NodeMode.DIRECT);

        NetworkNodeRecord configured = node.withConfigurationChanged();
        NetworkNodeRecord moved = configured.moveTo(12, new ManagedName("Moved input"));

        assertAll(
                () -> assertEquals(9, configured.revision()),
                () -> assertEquals(NodeMode.DIRECT, configured.mode()),
                () -> assertEquals(10, moved.revision()),
                () -> assertEquals(12, moved.nodeNumber()),
                () -> assertEquals("Moved input", moved.name().value()),
                () -> assertEquals(position, moved.position()),
                () -> assertEquals(NodeForm.PANEL, moved.form()),
                () -> assertEquals(Direction.WEST, moved.facing()),
                () -> assertFalse(moved.enabled()),
                () -> assertTrue(moved.chunkLoadingRequested()),
                () -> assertEquals(NodeMode.UNCONFIGURED, moved.mode()));
    }

    @Test
    void malformedAndExhaustedValuesRejectBeforeReplacement() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new NetworkTunnelRecord(TUNNEL, 0, new ManagedName("T"), 0, true, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new NetworkChannelRecord(CHANNEL, TUNNEL, 0, new ManagedName("C"), 0)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new DirectNodeBinding(null, CHANNEL, TransferDirection.INPUT)),
                () -> assertThrows(NullPointerException.class, () -> new DomainNodeConfiguration(NODE, null)));

        NetworkTunnelRecord tunnel = new NetworkTunnelRecord(TUNNEL, 1, new ManagedName("T"), Long.MAX_VALUE, true, 0);
        NetworkChannelRecord channel =
                new NetworkChannelRecord(CHANNEL, TUNNEL, 1, new ManagedName("C"), Long.MAX_VALUE);
        NetworkNodeRecord node = new NetworkNodeRecord(
                NODE,
                1,
                new ManagedName("N"),
                GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO),
                NodeForm.BLOCK,
                Direction.DOWN,
                Long.MAX_VALUE,
                true,
                false,
                NodeMode.DIRECT);

        assertAll(
                () -> assertThrows(ArithmeticException.class, () -> tunnel.withEnabled(false)),
                () -> assertThrows(ArithmeticException.class, () -> tunnel.withName(new ManagedName("Changed"))),
                () -> assertThrows(ArithmeticException.class, () -> tunnel.withLastChannelNumber(1)),
                () -> assertThrows(ArithmeticException.class, () -> channel.withName(new ManagedName("Changed"))),
                () -> assertThrows(ArithmeticException.class, node::withConfigurationChanged),
                () -> assertThrows(ArithmeticException.class, () -> node.moveTo(2, new ManagedName("Moved"))));
    }
}
