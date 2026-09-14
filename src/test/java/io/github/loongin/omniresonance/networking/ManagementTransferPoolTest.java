// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagementTransferPoolTest {
    @Test
    void reservesEntireDeclarationBeforeInvokingFactoryAndRejectsOverflowWithoutCallingIt() {
        ManagementTransferPool pool = new ManagementTransferPool();
        AtomicInteger calls = new AtomicInteger();
        UUID session = new UUID(0, 1);
        UUID transfer = new UUID(0, 2);
        for (int i = 0; i < 4; i++) {
            int count = i + 1;
            pool.beginDownload(new UUID(1, i), session, transfer, 16777216, 0, () -> {
                calls.incrementAndGet();
                assertEquals(count * 16777216L, pool.reservedBytes());
                return new byte[16777216];
            });
        }
        assertEquals(67108864, pool.reservedBytes());
        assertThrows(
                IllegalStateException.class,
                () -> pool.beginDownload(new UUID(1, 4), session, transfer, 1, 0, () -> {
                    calls.incrementAndGet();
                    return new byte[1];
                }));
        assertEquals(4, calls.get());
    }

    private static final UUID PLAYER = new UUID(10, 1);
    private static final UUID SESSION = new UUID(10, 2);
    private static final UUID TRANSFER = new UUID(10, 3);
    private static final UUID OTHER = new UUID(10, 4);

    @Test
    void rejectsInvalidDeclarationAndTickOverflowBeforeFactoryOrReservation() {
        ManagementTransferPool pool = new ManagementTransferPool();
        AtomicInteger calls = new AtomicInteger();
        for (int length : new int[] {-1, 0, 16777217, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pool.beginDownload(PLAYER, SESSION, TRANSFER, length, 0, () -> {
                        calls.incrementAndGet();
                        return new byte[1];
                    }));
        }
        assertThrows(
                ArithmeticException.class,
                () -> pool.beginDownload(PLAYER, SESSION, TRANSFER, 1, Long.MAX_VALUE - 199, () -> {
                    calls.incrementAndGet();
                    return new byte[1];
                }));
        assertEquals(0, calls.get());
        assertEquals(0, pool.reservedBytes());
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 16777216, Long.MAX_VALUE - 200);
        assertEquals(16777216, pool.reservedBytes());
        pool.expire(Long.MAX_VALUE);
        assertEquals(0, pool.reservedBytes());
    }

    @Test
    void sharedPlayerSlotAndWrongIdentifiersCannotDisplaceAnUpload() {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 2, 0);
        assertThrows(
                IllegalStateException.class, () -> pool.beginDownload(PLAYER, OTHER, OTHER, 1, 0, () -> new byte[1]));
        assertFalse(pool.abort(PLAYER, SESSION, OTHER));
        assertFalse(pool.abort(OTHER, SESSION, TRANSFER));
        pool.cancelSession(PLAYER, OTHER);
        assertThrows(IllegalStateException.class, () -> pool.upload(PLAYER, OTHER, TRANSFER, 0, new byte[] {1}, 0));
        assertThrows(IllegalStateException.class, () -> pool.upload(OTHER, SESSION, TRANSFER, 0, new byte[] {1}, 0));
        assertThrows(IllegalStateException.class, () -> pool.upload(PLAYER, SESSION, OTHER, 0, new byte[] {1}, 0));
        assertEquals(2, pool.reservedBytes());
        pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1, 2}, 1);
        AtomicInteger calls = new AtomicInteger();
        pool.finishUpload(
                PLAYER,
                SESSION,
                TRANSFER,
                2,
                value -> calls.incrementAndGet(),
                () -> true,
                value -> calls.incrementAndGet());
        assertEquals(2, calls.get());
        assertEquals(0, pool.reservedBytes());
    }

    @Test
    void invalidChunksAbortWholeTransferAndNeverValidate() {
        for (int scenario = 0; scenario < 6; scenario++) {
            ManagementTransferPool pool = new ManagementTransferPool();
            pool.beginUpload(PLAYER, SESSION, TRANSFER, 3, 0);
            int offset = scenario == 0 ? -1 : scenario == 1 ? 1 : 0;
            byte[] data = scenario == 2
                    ? new byte[0]
                    : scenario == 3 ? new byte[4] : scenario == 4 ? new byte[261121] : new byte[] {1};
            if (scenario == 5) pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 0);
            assertThrows(IllegalArgumentException.class, () -> pool.upload(PLAYER, SESSION, TRANSFER, offset, data, 1));
            assertEquals(0, pool.reservedBytes());
        }
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 3, 0);
        pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 0);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IllegalArgumentException.class,
                () -> pool.finishUpload(
                        PLAYER,
                        SESSION,
                        TRANSFER,
                        1,
                        value -> calls.incrementAndGet(),
                        () -> true,
                        value -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
        assertEquals(0, pool.reservedBytes());
    }

    @Test
    void completeValidationPrecedesReauthorizationAndCommitWithScopedOwnedBytes() {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 2, 0);
        byte[] input = {4, 5};
        pool.upload(PLAYER, SESSION, TRANSFER, 0, input, 0);
        input[0] = 99;
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<ManagementTransferPool.WholeObject> retained = new AtomicReference<>();
        pool.finishUpload(
                PLAYER,
                SESSION,
                TRANSFER,
                1,
                value -> {
                    assertEquals(0, stage.getAndIncrement());
                    assertEquals(2, pool.reservedBytes());
                    assertEquals(2, value.length());
                    assertEquals(4, value.byteAt(0));
                    retained.set(value);
                },
                () -> {
                    assertEquals(1, stage.getAndIncrement());
                    return true;
                },
                value -> {
                    assertEquals(2, stage.getAndIncrement());
                    assertEquals(5, value.byteAt(1));
                    assertEquals(2, pool.reservedBytes());
                });
        assertEquals(3, stage.get());
        assertEquals(0, pool.reservedBytes());
        assertThrows(IllegalStateException.class, () -> retained.get().byteAt(0));
    }

    @Test
    void callbackFailureAndAuthorizationFailureAlwaysReleaseWithoutCommit() {
        for (int stage = 0; stage < 3; stage++) {
            ManagementTransferPool pool = new ManagementTransferPool();
            pool.beginUpload(PLAYER, SESSION, TRANSFER, 1, 0);
            pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 0);
            int failureStage = stage;
            AtomicInteger commits = new AtomicInteger();
            assertThrows(
                    IllegalStateException.class,
                    () -> pool.finishUpload(
                            PLAYER,
                            SESSION,
                            TRANSFER,
                            1,
                            value -> {
                                if (failureStage == 0) throw new IllegalStateException("validation");
                            },
                            () -> {
                                if (failureStage == 1) return false;
                                throw new IllegalStateException("authorization");
                            },
                            value -> commits.incrementAndGet()));
            assertEquals(0, commits.get());
            assertEquals(0, pool.reservedBytes());
        }
    }

    @Test
    void downloadsAreOwnedOnDemandAndReleaseOnLastFragment() {
        ManagementTransferPool pool = new ManagementTransferPool();
        byte[] source = new byte[261122];
        source[0] = 3;
        source[261120] = 7;
        pool.beginDownload(PLAYER, SESSION, TRANSFER, source.length, 0, () -> source);
        source[0] = 99;
        byte[] first = pool.nextDownload(PLAYER, SESSION, TRANSFER, 1);
        assertEquals(261120, first.length);
        assertEquals(3, first[0]);
        first[0] = 42;
        assertEquals(261122, pool.reservedBytes());
        assertArrayEquals(new byte[] {7, 0}, pool.nextDownload(PLAYER, SESSION, TRANSFER, 2));
        assertEquals(0, pool.reservedBytes());
        assertThrows(IllegalStateException.class, () -> pool.nextDownload(PLAYER, SESSION, TRANSFER, 3));
    }

    @Test
    void factoriesReleaseOnFailureLengthMismatchAndReentrantCancellation() {
        for (int scenario = 0; scenario < 3; scenario++) {
            ManagementTransferPool pool = new ManagementTransferPool();
            int failure = scenario;
            assertThrows(
                    RuntimeException.class,
                    () -> pool.beginDownload(PLAYER, SESSION, TRANSFER, 2, 0, () -> {
                        if (failure == 0) throw new IllegalStateException("factory");
                        if (failure == 1) return new byte[1];
                        pool.disconnect(PLAYER);
                        return new byte[2];
                    }));
            assertEquals(0, pool.reservedBytes());
        }
    }

    @Test
    void allLifecyclePathsReleaseIdempotentlyAndCannotReviveExpiredOrClosedState() {
        for (int scenario = 0; scenario < 5; scenario++) {
            ManagementTransferPool pool = new ManagementTransferPool();
            pool.beginUpload(PLAYER, SESSION, TRANSFER, 1, 20);
            switch (scenario) {
                case 0 -> {
                    assertTrue(pool.abort(PLAYER, SESSION, TRANSFER));
                    assertFalse(pool.abort(PLAYER, SESSION, TRANSFER));
                }
                case 1 -> {
                    pool.cancelSession(PLAYER, SESSION);
                    pool.cancelSession(PLAYER, SESSION);
                }
                case 2 -> {
                    pool.disconnect(PLAYER);
                    pool.disconnect(PLAYER);
                }
                case 3 -> {
                    pool.expire(219);
                    assertEquals(1, pool.reservedBytes());
                    pool.expire(220);
                    pool.expire(220);
                }
                default -> {
                    pool.close();
                    pool.close();
                    assertThrows(IllegalStateException.class, () -> pool.beginUpload(PLAYER, SESSION, OTHER, 1, 21));
                }
            }
            assertEquals(0, pool.reservedBytes());
            assertThrows(
                    IllegalStateException.class, () -> pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 221));
        }
    }

    @Test
    void maximumUploadIsValidatedOnlyAfterAllFragmentsAndMaximumDownloadIsLazy() {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 16777216, 0);
        int offset = 0;
        while (offset < 16777216) {
            int length = Math.min(261120, 16777216 - offset);
            byte[] fragment = new byte[length];
            fragment[0] = (byte) (offset / 261120);
            pool.upload(PLAYER, SESSION, TRANSFER, offset, fragment, 1);
            offset += length;
        }
        pool.finishUpload(
                PLAYER,
                SESSION,
                TRANSFER,
                2,
                value -> {
                    assertEquals(16777216, value.length());
                    for (int start = 0; start < value.length(); start += 261120)
                        assertEquals((byte) (start / 261120), value.byteAt(start));
                },
                () -> true,
                value -> assertEquals(16777216, pool.reservedBytes()));
        assertEquals(0, pool.reservedBytes());
        AtomicInteger calls = new AtomicInteger();
        pool.beginDownload(PLAYER, SESSION, OTHER, 16777216, 3, () -> {
            calls.incrementAndGet();
            return new byte[16777216];
        });
        int received = 0;
        while (received < 16777216) {
            byte[] fragment = pool.nextDownload(PLAYER, SESSION, OTHER, 4);
            assertTrue(fragment.length > 0 && fragment.length <= 261120);
            received += fragment.length;
            assertEquals(received == 16777216 ? 0 : 16777216, pool.reservedBytes());
        }
        assertEquals(16777216, received);
        assertEquals(1, calls.get());
    }

    @Test
    void commitFailureIsNotRetriedAndCallbackReentryCannotDropReservation() {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 1, 0);
        pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {9}, 1);
        AtomicInteger commits = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () -> pool.finishUpload(
                        PLAYER,
                        SESSION,
                        TRANSFER,
                        2,
                        value -> {
                            assertThrows(IllegalStateException.class, () -> pool.close());
                            assertThrows(IllegalStateException.class, () -> pool.abort(PLAYER, SESSION, TRANSFER));
                            assertEquals(1, pool.reservedBytes());
                        },
                        () -> true,
                        value -> {
                            commits.incrementAndGet();
                            throw new IllegalStateException("unknown commit result");
                        }));
        assertEquals(1, commits.get());
        assertEquals(0, pool.reservedBytes());
    }

    @Test
    void directionMismatchAbortsOnlyMatchingTransferAndProgressDoesNotRenewTimeout() {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginDownload(PLAYER, SESSION, TRANSFER, 2, 0, () -> new byte[2]);
        assertThrows(
                IllegalArgumentException.class, () -> pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 0));
        assertEquals(0, pool.reservedBytes());
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> pool.nextDownload(PLAYER, SESSION, TRANSFER, 0));
        assertEquals(0, pool.reservedBytes());
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 2, 0);
        pool.upload(PLAYER, SESSION, TRANSFER, 0, new byte[] {1}, 199);
        assertThrows(IllegalStateException.class, () -> pool.upload(PLAYER, SESSION, TRANSFER, 1, new byte[] {2}, 200));
        assertEquals(0, pool.reservedBytes());
    }

    @Test
    void ownerThreadIsEnforcedWithoutBackgroundWork() throws InterruptedException {
        ManagementTransferPool pool = new ManagementTransferPool();
        pool.beginUpload(PLAYER, SESSION, TRANSFER, 1, 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                pool.disconnect(PLAYER);
            } catch (Throwable rejected) {
                failure.set(rejected);
            }
        });
        thread.start();
        thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(1, pool.reservedBytes());
        pool.close();
    }
}
