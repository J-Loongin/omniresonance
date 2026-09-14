// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeResourceComponentsCodecTest {
    private static final UUID CATALOG = new UUID(1, 2);

    @Test
    void statusSummaryIncludesMissingOverridesWithoutSendingTheirValues() {
        var stored = new StoredResourcePolicy(
                ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                Map.of(ResourceLocation.parse("absent:steam"), new StoredResourcePolicy.RawOverride(null, null, null)));
        var summary = NodeResourcePolicySummary.from(stored);
        assertEquals(ResourceScope.Kind.ALL, summary.scopeKind());
        assertEquals(0, summary.scopeTypeCount());
        assertEquals(1, summary.overrideCount());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeResourcePolicySummary.write(buffer, summary);
            assertEquals(14, buffer.writerIndex());
            assertEquals(summary, NodeResourcePolicySummary.read(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void pageRespectsActualEnvelopeBudgetAndRoundTripsOnlyBoundedDescriptors() {
        var directory = ResourceAdapterDirectory.nativeDefaults();
        var full = ResourceTypeCatalogPage.from(directory, CATALOG, 0, 262144, 73);
        assertEquals(3, full.entries().size());
        var first = new ResourceTypeCatalogPage(CATALOG, 0, 3, full.entries().subList(0, 1));
        var page = ResourceTypeCatalogPage.from(directory, CATALOG, 0, 73 + first.encodedSize(), 73);
        assertEquals(first, page);
        assertTrue(page.hasNext());
        var next = ResourceTypeCatalogPage.from(directory, CATALOG, 1, 262144, 73);
        assertEquals(2, next.entries().size());
        assertFalse(next.hasNext());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeZero(73);
            ResourceTypeCatalogPage.write(buffer, page);
            assertEquals(73 + page.encodedSize(), buffer.writerIndex());
            buffer.skipBytes(73);
            assertEquals(page, ResourceTypeCatalogPage.read(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourceTypeCatalogPage.from(directory, CATALOG, 0, 73 + first.encodedSize() - 1, 73));
    }

    @Test
    void forgedCountsDuplicateTypesAndImpossibleOffsetsFail() {
        var item = ResourceAdapterDirectory.nativeDefaults()
                .find(ResourceLocation.parse("minecraft:item"))
                .orElseThrow();
        assertThrows(
                IllegalArgumentException.class, () -> new ResourceTypeCatalogPage(CATALOG, 0, 2, List.of(item, item)));
        assertThrows(IllegalArgumentException.class, () -> new ResourceTypeCatalogPage(CATALOG, 1, 1, List.of(item)));
        assertThrows(IllegalArgumentException.class, () -> new ResourceTypeCatalogPage(CATALOG, 0, 1, List.of()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(CATALOG).writeInt(0).writeInt(262144).writeVarInt(129);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> ResourceTypeCatalogPage.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
