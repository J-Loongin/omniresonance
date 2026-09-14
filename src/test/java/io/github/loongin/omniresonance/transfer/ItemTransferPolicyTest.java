// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemTransferPolicyTest {
    @Test
    void switchPreservesCommonFieldsAndResetsDirectionSpecificFields() {
        UUID id = new UUID(1, 2);
        ItemTransferPolicy input =
                new ItemTransferPolicy.Input(4, 32, RedstoneCondition.SIGNAL, id, FilterMode.BLACKLIST, 123);
        ItemTransferPolicy output = input.switchDirection(TransferDirection.OUTPUT);
        assertEquals(
                new ItemTransferPolicy.Output(4, 32, RedstoneCondition.SIGNAL, id, FilterMode.BLACKLIST, 0), output);
        assertEquals(
                new ItemTransferPolicy.Input(4, 32, RedstoneCondition.SIGNAL, id, FilterMode.BLACKLIST, 0),
                output.switchDirection(TransferDirection.INPUT));
    }

    @Test
    void defaultsAndBoundsAreValidated() {
        assertEquals(
                new ItemTransferPolicy.Input(
                        1, Integer.MAX_VALUE, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0),
                ItemTransferPolicy.defaults(TransferDirection.INPUT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemTransferPolicy.Input(0, 1, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemTransferPolicy.Output(1, 0, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemTransferPolicy.Input(1, 1, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, -1));
    }
}
