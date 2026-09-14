// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.persistence.ResourceFilterPresetNbt;
import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FullFilterCodecTest {
    private static final UUID PRESET = new UUID(1, 2), RULE = new UUID(3, 4);

    @Test
    void actualRegisteredTypedRequestRoundTripsWithoutClientComponentValues() {
        var intent = new ResourceRuleIntent.Match(
                ResourceTypes.FLUID,
                ResourceFilterRule.Selector.glob("minecraft:wa*"),
                ComponentCondition.Mode.SELECTED,
                Set.of(ResourceLocation.parse("minecraft:custom_data")),
                new UUID(5, 6));
        assertEquals(FullFilterCodec.intentSize(intent), FullFilterCodec.intent(intent).length);
        var request = new NetworkTerminalRequest.SaveResourceRule(PRESET, RULE, 7, intent);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkTerminalRequest.STREAM_CODEC.encode(buffer, request);
            var decoded = (NetworkTerminalRequest.SaveResourceRule) NetworkTerminalRequest.STREAM_CODEC.decode(buffer);
            assertEquals(intent.typeId(), ((ResourceRuleIntent.Match) decoded.intent()).typeId());
            assertEquals(intent.selectedKeys(), ((ResourceRuleIntent.Match) decoded.intent()).selectedKeys());
            assertEquals(intent.sampleToken(), ((ResourceRuleIntent.Match) decoded.intent()).sampleToken());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void fullSnapshotsAreSeparateFromIntentAndPreserveGeneralIdentityAndSelectedValues() {
        var condition = ComponentCondition.fromPersistenceSnapshot(new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.SELECTED,
                ResourceTypes.ITEM,
                null,
                List.of(new ComponentCondition.SelectedComponent(
                        ResourceLocation.parse("test:value"), CanonicalResourceNbt.encode(IntTag.valueOf(7))))));
        var preset = new ResourceFilterPreset(
                PRESET,
                new ManagedName("Snapshot"),
                9,
                List.of(new ResourceFilterRule.Match(
                        RULE,
                        ResourceTypes.ITEM,
                        ResourceFilterRule.Selector.exact(ResourceLocation.parse("minecraft:stone")),
                        condition)));
        byte[] bytes = FullFilterCodec.snapshot(preset);
        assertEquals(bytes.length, FullFilterCodec.snapshotSize(preset));
        assertEquals(
                ResourceFilterPresetNbt.encode(preset),
                ResourceFilterPresetNbt.encode(FullFilterCodec.readSnapshot(bytes)));
        assertThrows(IllegalArgumentException.class, () -> FullFilterCodec.readIntent(bytes));
        byte[] trailing = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> FullFilterCodec.readSnapshot(trailing));
        assertThrows(
                IllegalArgumentException.class,
                () -> FullFilterCodec.readSnapshot(FullFilterCodec.intent(new ResourceRuleIntent.Reference(PRESET))));
    }

    @Test
    void actualTypedSummaryPagePreservesRuleIdentityWithoutUsingItAsDisplayId() {
        var page = new FilterRulePage(
                List.of("#c:water", "minecraft:wa*", "neoforge:energy"),
                0,
                3,
                0,
                List.of(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            page.write(buffer);
            assertEquals(page, FilterRulePage.read(buffer));
        } finally {
            buffer.release();
        }
        assertTrue(page.fullDomain());
        assertThrows(IllegalArgumentException.class, () -> new FilterRulePage(List.of("#c:water"), 0, 1, 0));
    }

    @Test
    void malformedIntentCountAndTrailingFieldsCannotInventSelectedValues() {
        byte[] bytes = FullFilterCodec.intent(new ResourceRuleIntent.Match(
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.wholeType(),
                ComponentCondition.Mode.ID_ONLY,
                Set.of(),
                null));
        byte[] trailing = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> FullFilterCodec.readIntent(trailing));
        bytes[bytes.length - 1] = 1;
        assertThrows(IllegalArgumentException.class, () -> FullFilterCodec.readIntent(bytes));
    }

    @Test
    void maximumLegalTagRemainsOpenableInActualFullSummaryPage() {
        var tag = ResourceLocation.parse("test:" + "a".repeat(65530));
        var rule = new ResourceFilterRule.Match(
                RULE, ResourceTypes.FLUID, ResourceFilterRule.Selector.tag(tag), ComponentCondition.idOnly());
        String display = io.github.loongin.omniresonance.filter.ItemFilterService.ruleLabel(rule);
        var page = new FilterRulePage(List.of(display), 0, 1, 0, List.of(RULE));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            page.write(buffer);
            assertEquals(page, FilterRulePage.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void actualMaximumInlineIntentUsesExactEnvelopeAndCannotRequestUnnecessaryChunks() {
        Set<ResourceLocation> base = new java.util.HashSet<>();
        for (String namespace : List.of("a", "b", "c"))
            base.add(ResourceLocation.parse(namespace + ":" + "x".repeat(65533)));
        base.add(ResourceLocation.parse("d:" + "x".repeat(64000)));
        var initial = new ResourceRuleIntent.Match(
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.wholeType(),
                ComponentCondition.Mode.SELECTED,
                base,
                null);
        int remaining = 262060 - FullFilterCodec.intentSize(initial);
        base.remove(ResourceLocation.parse("d:" + "x".repeat(64000)));
        base.add(ResourceLocation.parse("d:" + "x".repeat(64000 + remaining)));
        var intent = new ResourceRuleIntent.Match(
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.wholeType(),
                ComponentCondition.Mode.SELECTED,
                base,
                null);
        assertEquals(262060, FullFilterCodec.intentSize(intent));
        assertTrue(FullFilterCodec.intentFitsPacket(262060));
        org.junit.jupiter.api.Assertions.assertFalse(FullFilterCodec.intentFitsPacket(262061));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalRequest.PrepareResourceRuleUpload(PRESET, RULE, 1, PRESET, 262060));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkTerminalRequest.STREAM_CODEC.encode(
                    buffer, new NetworkTerminalRequest.SaveResourceRule(PRESET, RULE, 1, intent));
            assertEquals(262105, buffer.readableBytes());
            assertEquals(
                    262144,
                    buffer.readableBytes() + FullFilterCodec.registeredIdBytes(NetworkTerminalRequest.TYPE.id()));
            var decoded = (NetworkTerminalRequest.SaveResourceRule) NetworkTerminalRequest.STREAM_CODEC.decode(buffer);
            assertEquals(base, ((ResourceRuleIntent.Match) decoded.intent()).selectedKeys());
        } finally {
            buffer.release();
        }
    }

    @Test
    void registeredSnapshotDecisionAndBothBodyDecodersAccountForPayloadIds() {
        assertEquals(39, FullFilterCodec.registeredIdBytes(NetworkTerminalRequest.TYPE.id()));
        assertEquals(40, FullFilterCodec.registeredIdBytes(NetworkTerminalResponse.TYPE.id()));
        assertTrue(FullFilterCodec.snapshotFitsPacket(262050, false));
        org.junit.jupiter.api.Assertions.assertFalse(FullFilterCodec.snapshotFitsPacket(262051, false));
        assertTrue(FullFilterCodec.snapshotFitsPacket(262034, true));
        org.junit.jupiter.api.Assertions.assertFalse(FullFilterCodec.snapshotFitsPacket(262035, true));
        FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[262106]));
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[262105]));
        try {
            assertThrows(
                    io.netty.handler.codec.DecoderException.class,
                    () -> NetworkTerminalRequest.STREAM_CODEC.decode(request));
            assertThrows(
                    io.netty.handler.codec.DecoderException.class,
                    () -> NetworkTerminalResponse.STREAM_CODEC.decode(response));
            assertEquals(0, request.readerIndex());
            assertEquals(0, response.readerIndex());
        } finally {
            request.release();
            response.release();
        }
    }
}
