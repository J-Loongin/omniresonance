// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagementDownloadAssemblerTest {
    private static final UUID SESSION = new UUID(1, 2), TRANSFER = new UUID(3, 4), CHANNEL = new UUID(5, 6);

    static ManagementTransferMessage.Begin begin(ManagementTransferMessage.Direction direction, int length) {
        return new ManagementTransferMessage.Begin(
                SESSION,
                TRANSFER,
                direction,
                ManagementTransferMessage.Context.NODE,
                CHANNEL,
                ManagementTransferMessage.Purpose.NODE_POLICY,
                length);
    }

    static ManagementDownloadAssembler.Expected expected(int length) {
        return new ManagementDownloadAssembler.Expected(
                SESSION,
                TRANSFER,
                ManagementTransferMessage.Context.NODE,
                CHANNEL,
                ManagementTransferMessage.Purpose.NODE_POLICY,
                length);
    }

    @Test
    void fullSixteenMiBDownloadsInSixtyFiveTickFragmentsWithinFixedDeadline() {
        int length = 16777216;
        var assembler = new ManagementDownloadAssembler();
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, length), expected(length), 0);
        int offset = 0, chunks = 0;
        while (offset < length) {
            byte[] fragment = new byte[Math.min(261120, length - offset)];
            java.util.Arrays.fill(fragment, (byte) 93);
            assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, offset, fragment), ++chunks);
            offset += fragment.length;
        }
        assertEquals(65, chunks);
        assembler.finish(new ManagementTransferMessage.Finish(SESSION, TRANSFER), 66, view -> {
            assertEquals(16777216, view.length());
            assertEquals(93, view.byteAt(0));
            assertEquals(93, view.byteAt(16777215));
        });
        assertEquals(0, assembler.reservedBytes());
        assertThrows(IllegalArgumentException.class, () -> expected(16777217));
    }

    @Test
    void offThreadAccessExpiryAndClosedAdmissionNeverRetainOrReviveBuffers() throws InterruptedException {
        var assembler = new ManagementDownloadAssembler();
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 1), expected(1), 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                assembler.reservedBytes();
            } catch (Throwable rejected) {
                failure.set(rejected);
            }
        });
        other.start();
        other.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertFalse(assembler.expire(199));
        assertTrue(assembler.expire(200));
        assertEquals(0, assembler.reservedBytes());
        assembler.close();
        assembler.close();
        assertThrows(
                IllegalStateException.class,
                () -> assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 1), expected(1), 201));
        assertEquals(0, assembler.reservedBytes());
    }

    @Test
    void wrongDirectionOrContextCannotAllocateOrReplaceCurrentDownload() {
        var assembler = new ManagementDownloadAssembler();
        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.begin(begin(ManagementTransferMessage.Direction.UPLOAD, 3), expected(3), 0));
        assertEquals(0, assembler.reservedBytes());
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), expected(3), 0);
        assertThrows(
                IllegalStateException.class,
                () -> assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), expected(3), 1));
        assertEquals(3, assembler.reservedBytes());
        assembler.close();
        var wrong = new ManagementDownloadAssembler.Expected(
                SESSION,
                TRANSFER,
                ManagementTransferMessage.Context.TERMINAL,
                CHANNEL,
                ManagementTransferMessage.Purpose.NODE_POLICY,
                3);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagementDownloadAssembler()
                        .begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), wrong, 0));
    }

    @Test
    void lateOldFramesCannotCancelNewTransferAndDeadlineNeverRenews() {
        var assembler = new ManagementDownloadAssembler();
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), expected(3), 10);
        assertFalse(
                assembler.append(new ManagementTransferMessage.Chunk(SESSION, new UUID(7, 8), 0, new byte[] {1}), 100));
        assertFalse(assembler.abort(SESSION, new UUID(7, 8)));
        assertTrue(assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, new byte[] {1}), 209));
        assertThrows(
                IllegalStateException.class,
                () -> assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 1, new byte[] {2}), 210));
        assertEquals(0, assembler.reservedBytes());
    }

    @Test
    void completePolicyDecodesInScopedViewAndReleasesEvenWhenCallbackFails() {
        var edit = ResourcePolicyEdit.fromStored(
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), Map.of()));
        byte[] bytes = ResourcePolicyEditCodec.encode(edit);
        var assembler = new ManagementDownloadAssembler();
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, bytes.length), expected(bytes.length), 0);
        assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, bytes), 1);
        AtomicReference<ManagementObjectView> escaped = new AtomicReference<>();
        assertTrue(assembler.finish(new ManagementTransferMessage.Finish(SESSION, TRANSFER), 2, view -> {
            assertEquals(bytes.length, assembler.reservedBytes());
            assertEquals(edit, ResourcePolicyEditCodec.decode(view));
            escaped.set(view);
            assertThrows(IllegalStateException.class, assembler::close);
        }));
        assertEquals(0, assembler.reservedBytes());
        assertThrows(IllegalStateException.class, () -> escaped.get().length());
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 1), expected(1), 3);
        assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, new byte[] {1}), 4);
        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.finish(new ManagementTransferMessage.Finish(SESSION, TRANSFER), 5, view -> {
                    throw new IllegalArgumentException("decoder");
                }));
        assertEquals(0, assembler.reservedBytes());
    }

    @Test
    void incompleteAndOutOfOrderCurrentFramesAbortWithoutPublishing() {
        var assembler = new ManagementDownloadAssembler();
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), expected(3), 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.append(new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 1, new byte[] {1}), 1));
        assertEquals(0, assembler.reservedBytes());
        assembler.begin(begin(ManagementTransferMessage.Direction.DOWNLOAD, 3), expected(3), 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.finish(new ManagementTransferMessage.Finish(SESSION, TRANSFER), 3, view -> {
                    throw new AssertionError("Incomplete publication");
                }));
        assertEquals(0, assembler.reservedBytes());
    }
}
