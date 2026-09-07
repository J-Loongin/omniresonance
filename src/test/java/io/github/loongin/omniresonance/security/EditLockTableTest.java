// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.security.EditLockTable.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class EditLockTableTest {
    private static final UUID OBJECT = new UUID(0, 1);
    private static final UUID OTHER_OBJECT = new UUID(0, 2);
    private static final UUID THIRD_OBJECT = new UUID(0, 3);
    private static final UUID ALICE = new UUID(1, 1);
    private static final UUID BOB = new UUID(1, 2);

    @Test
    void differentObjectsCanBeEditedConcurrently() {
        EditLockTable table = new EditLockTable();
        Token first = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        Token second = table.tryAcquire(OTHER_OBJECT, BOB, 100).orElseThrow();

        assertTrue(table.isHeld(first, ALICE, 100));
        assertTrue(table.isHeld(second, BOB, 100));
        assertTrue(second.generation() > first.generation());
    }

    @Test
    void secondPlayerCannotAcquireUntilExactExpiry() {
        EditLockTable table = new EditLockTable();
        Token first = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.tryAcquire(OBJECT, BOB, 299).isEmpty());
        assertTrue(table.isHeld(first, ALICE, 299));
        Token second = table.tryAcquire(OBJECT, BOB, 300).orElseThrow();
        assertFalse(table.isHeld(first, ALICE, 300));
        assertFalse(table.release(first, ALICE));
        assertTrue(table.isHeld(second, BOB, 300));
    }

    @Test
    void samePlayerMustRenewInsteadOfReacquiring() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.tryAcquire(OBJECT, ALICE, 140).isEmpty());
        assertFalse(table.isHeld(token, ALICE, 300));
    }

    @Test
    void heartbeatReplacesOriginalExpiry() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.renew(token, ALICE, 140));
        table.expire(300);
        assertTrue(table.isHeld(token, ALICE, 300));
        assertTrue(table.isHeld(token, ALICE, 339));
        assertFalse(table.isHeld(token, ALICE, 340));
    }

    @Test
    void heartbeatCannotReviveAnExpiredLease() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.isHeld(token, ALICE, 299));
        assertFalse(table.renew(token, ALICE, 300));
        assertFalse(table.isHeld(token, ALICE, 300));
        assertTrue(table.tryAcquire(OBJECT, BOB, 300).isPresent());
    }

    @Test
    void wrongSenderCannotVerifyRenewOrRelease() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertFalse(table.isHeld(token, BOB, 140));
        assertFalse(table.renew(token, BOB, 140));
        assertFalse(table.release(token, BOB));
        assertTrue(table.isHeld(token, ALICE, 299));
        assertFalse(table.isHeld(token, ALICE, 300));
    }

    @Test
    void wrongGenerationCannotVerifyRenewOrRelease() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        Token forged = new Token(OBJECT, ALICE, token.generation() + 1);

        assertFalse(table.isHeld(forged, ALICE, 140));
        assertFalse(table.renew(forged, ALICE, 140));
        assertFalse(table.release(forged, ALICE));
        assertTrue(table.isHeld(token, ALICE, 299));
        assertFalse(table.isHeld(token, ALICE, 300));
    }

    @Test
    void alteredPlayerOrObjectCannotReuseAnotherTokensGeneration() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        Token wrongPlayer = new Token(OBJECT, BOB, token.generation());
        Token wrongObject = new Token(OTHER_OBJECT, ALICE, token.generation());

        for (Token forged : List.of(wrongPlayer, wrongObject)) {
            assertFalse(table.isHeld(forged, forged.playerId(), 140));
            assertFalse(table.renew(forged, forged.playerId(), 140));
            assertFalse(table.release(forged, forged.playerId()));
        }
        assertTrue(table.isHeld(token, ALICE, 299));
    }

    @Test
    void releaseAllowsReacquisitionWithoutOldTokenReplay() {
        EditLockTable table = new EditLockTable();
        Token first = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.release(first, ALICE));
        assertFalse(table.release(first, ALICE));
        Token second = table.tryAcquire(OBJECT, ALICE, 140).orElseThrow();
        assertTrue(second.generation() > first.generation());
        assertFalse(table.isHeld(first, ALICE, 140));
        assertFalse(table.renew(first, ALICE, 140));
        assertFalse(table.release(first, ALICE));
        table.expire(300);
        assertTrue(table.isHeld(second, ALICE, 300));
    }

    @Test
    void logoutReleasesOnlyThatPlayersLeases() {
        EditLockTable table = new EditLockTable();
        Token first = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        Token second = table.tryAcquire(OTHER_OBJECT, ALICE, 100).orElseThrow();
        Token unaffected = table.tryAcquire(THIRD_OBJECT, BOB, 100).orElseThrow();

        table.releasePlayer(ALICE);
        assertFalse(table.isHeld(first, ALICE, 140));
        assertFalse(table.isHeld(second, ALICE, 140));
        assertTrue(table.isHeld(unaffected, BOB, 140));
        Token replacement = table.tryAcquire(OBJECT, BOB, 140).orElseThrow();
        table.expire(300);
        assertTrue(table.isHeld(replacement, BOB, 300));
    }

    @Test
    void clearDoesNotResetGenerationOrReviveOldTokens() {
        EditLockTable table = new EditLockTable();
        Token old = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        Token other = table.tryAcquire(OTHER_OBJECT, BOB, 100).orElseThrow();

        table.clear();
        assertFalse(table.isHeld(old, ALICE, 140));
        assertFalse(table.isHeld(other, BOB, 140));
        Token replacement = table.tryAcquire(OBJECT, ALICE, 140).orElseThrow();
        assertTrue(replacement.generation() > other.generation());
        assertFalse(table.release(old, ALICE));
        table.expire(300);
        assertTrue(table.isHeld(replacement, ALICE, 300));
    }

    @Test
    void conflictExemptionAppliesOnlyToExactAllowedToken() {
        EditLockTable table = new EditLockTable();
        Token allowed = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();
        table.tryAcquire(OTHER_OBJECT, ALICE, 100).orElseThrow();

        assertFalse(table.hasConflictingLocks(List.of(OBJECT), allowed, 140));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT, OTHER_OBJECT), allowed, 140));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT), null, 140));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT), new Token(OBJECT, ALICE, allowed.generation() + 1), 140));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT), new Token(OBJECT, BOB, allowed.generation()), 140));
        assertFalse(table.hasConflictingLocks(List.of(THIRD_OBJECT), allowed, 140));
        assertFalse(table.hasConflictingLocks(List.of(), null, 140));
        assertFalse(table.hasConflictingLocks(List.of(OBJECT, OTHER_OBJECT), null, 300));
    }

    @Test
    void readOnlyChecksNeitherRenewNorRemoveLeases() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertTrue(table.isHeld(token, ALICE, 299));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT), null, 299));
        assertFalse(table.isHeld(token, ALICE, 300));
        assertFalse(table.hasConflictingLocks(List.of(OBJECT), null, 300));
        // A historical query observes the original lease: neither query cleaned the indexes.
        assertTrue(table.isHeld(token, ALICE, 299));
        assertTrue(table.hasConflictingLocks(List.of(OBJECT), null, 299));
    }

    @Test
    void expiryRemovesOnlyDueLeasesAmongOneThousandSessions() {
        EditLockTable table = new EditLockTable();
        Token due = table.tryAcquire(OBJECT, ALICE, 0).orElseThrow();
        List<Token> live = new ArrayList<>();
        for (int index = 0; index < 999; index++) {
            live.add(table.tryAcquire(new UUID(2, index), BOB, 1).orElseThrow());
        }

        table.expire(200);
        assertFalse(table.isHeld(due, ALICE, 199));
        for (Token token : live) {
            assertTrue(table.isHeld(token, BOB, 200));
        }
        table.expire(201);
        for (Token token : live) {
            assertFalse(table.isHeld(token, BOB, 200));
        }
    }

    @Test
    void pollingExpiryReturnsOnlyDueTokensInDeadlineOrder() {
        EditLockTable table = new EditLockTable();
        Token first = table.tryAcquire(OBJECT, ALICE, 0).orElseThrow();
        Token second = table.tryAcquire(OTHER_OBJECT, BOB, 1).orElseThrow();

        assertTrue(table.pollExpired(199).isEmpty());
        assertEquals(first, table.pollExpired(200).orElseThrow());
        assertTrue(table.pollExpired(200).isEmpty());
        assertEquals(second, table.pollExpired(201).orElseThrow());
        assertTrue(table.pollExpired(201).isEmpty());
    }

    @Test
    void repeatedHeartbeatsExpireAtOnlyTheFinalDeadline() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 0).orElseThrow();
        for (long nowTick = 40; nowTick <= 40_000; nowTick += 40) {
            assertTrue(table.renew(token, ALICE, nowTick));
            table.expire(nowTick);
            assertTrue(table.isHeld(token, ALICE, nowTick));
        }

        table.expire(40_199);
        assertTrue(table.isHeld(token, ALICE, 40_199));
        table.expire(40_200);
        assertFalse(table.release(token, ALICE));
        assertTrue(table.tryAcquire(OBJECT, BOB, 40_200).isPresent());
    }

    @Test
    void negativeTicksAreRejectedBeforeAnyMutation() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 0).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> table.tryAcquire(OBJECT, ALICE, -1));
        assertThrows(IllegalArgumentException.class, () -> table.renew(token, ALICE, -1));
        assertThrows(IllegalArgumentException.class, () -> table.isHeld(token, ALICE, -1));
        assertThrows(IllegalArgumentException.class, () -> table.hasConflictingLocks(List.of(), null, -1));
        assertThrows(IllegalArgumentException.class, () -> table.expire(-1));
        assertThrows(IllegalArgumentException.class, () -> table.pollExpired(-1));
        assertTrue(table.isHeld(token, ALICE, 199));
    }

    @Test
    void acquireOverflowDoesNotConsumeGenerationOrDisturbExistingLease() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, 100).orElseThrow();

        assertThrows(ArithmeticException.class, () -> table.tryAcquire(OBJECT, BOB, Long.MAX_VALUE - 199));
        assertTrue(table.isHeld(token, ALICE, 299));
        assertThrows(ArithmeticException.class, () -> table.tryAcquire(OTHER_OBJECT, BOB, Long.MAX_VALUE - 199));
        Token second = table.tryAcquire(OTHER_OBJECT, BOB, 100).orElseThrow();
        assertEquals(2, second.generation());
    }

    @Test
    void renewalOverflowPreservesTheLatestValidDeadline() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, Long.MAX_VALUE - 300).orElseThrow();

        assertTrue(table.renew(token, ALICE, Long.MAX_VALUE - 200));
        assertThrows(ArithmeticException.class, () -> table.renew(token, ALICE, Long.MAX_VALUE - 199));
        table.expire(Long.MAX_VALUE - 100);
        assertTrue(table.isHeld(token, ALICE, Long.MAX_VALUE - 1));
        assertFalse(table.isHeld(token, ALICE, Long.MAX_VALUE));
        table.expire(Long.MAX_VALUE);
        assertFalse(table.release(token, ALICE));
    }

    @Test
    void blockedAcquireAndInvalidRenewDoNotCalculateUnusedDeadlines() {
        EditLockTable table = new EditLockTable();
        Token token = table.tryAcquire(OBJECT, ALICE, Long.MAX_VALUE - 200).orElseThrow();

        assertTrue(table.tryAcquire(OBJECT, BOB, Long.MAX_VALUE - 1).isEmpty());
        assertFalse(table.renew(token, BOB, Long.MAX_VALUE - 1));
        assertTrue(table.isHeld(token, ALICE, Long.MAX_VALUE - 1));
    }

    @Test
    void tokenRejectsMissingIdentityAndNonpositiveGeneration() {
        assertThrows(NullPointerException.class, () -> new Token(null, ALICE, 1));
        assertThrows(NullPointerException.class, () -> new Token(OBJECT, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Token(OBJECT, ALICE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Token(OBJECT, ALICE, -1));
    }

    @Test
    void tableRejectsMissingRequiredInputs() {
        EditLockTable table = new EditLockTable();
        Token token = new Token(OBJECT, ALICE, 1);

        assertThrows(NullPointerException.class, () -> table.tryAcquire(null, ALICE, 0));
        assertThrows(NullPointerException.class, () -> table.tryAcquire(OBJECT, null, 0));
        assertThrows(NullPointerException.class, () -> table.renew(null, ALICE, 0));
        assertThrows(NullPointerException.class, () -> table.renew(token, null, 0));
        assertThrows(NullPointerException.class, () -> table.isHeld(null, ALICE, 0));
        assertThrows(NullPointerException.class, () -> table.isHeld(token, null, 0));
        assertThrows(NullPointerException.class, () -> table.release(null, ALICE));
        assertThrows(NullPointerException.class, () -> table.release(token, null));
        assertThrows(NullPointerException.class, () -> table.releasePlayer(null));
        assertThrows(NullPointerException.class, () -> table.hasConflictingLocks(null, null, 0));
        assertThrows(
                NullPointerException.class, () -> table.hasConflictingLocks(Collections.singletonList(null), null, 0));
    }

    @Test
    void everyTableOperationRejectsCallsFromAnotherThread() throws Exception {
        EditLockTable table = new EditLockTable();
        Token token = new Token(OBJECT, ALICE, 1);
        List<Runnable> operations = List.of(
                () -> table.tryAcquire(OBJECT, ALICE, 0),
                () -> table.renew(token, ALICE, 0),
                () -> table.isHeld(token, ALICE, 0),
                () -> table.release(token, ALICE),
                () -> table.releasePlayer(ALICE),
                table::clear,
                () -> table.hasConflictingLocks(List.of(), null, 0),
                () -> table.expire(0));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            for (Runnable operation : operations) {
                Future<?> result = executor.submit(operation);
                ExecutionException exception = assertThrows(ExecutionException.class, result::get);
                assertInstanceOf(IllegalStateException.class, exception.getCause());
            }
        }
        assertTrue(table.tryAcquire(OBJECT, ALICE, 0).isPresent());
    }
}
