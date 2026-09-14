// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeItemPolicyDraftTest {
    @Test
    void directionResetPreservesCommonFieldsAndNeverResurrectsExclusiveValues() {
        UUID preset = new UUID(10, 11);
        ItemTransferPolicy original =
                new ItemTransferPolicy.Input(20, 64, RedstoneCondition.SIGNAL, preset, FilterMode.BLACKLIST, 16);
        NodeItemPolicyDraft draft = new NodeItemPolicyDraft(original, "Iron");
        assertEquals(original, draft.policy());
        draft.confirmDirectionChange();
        assertEquals(
                new ItemTransferPolicy.Output(20, 64, RedstoneCondition.SIGNAL, preset, FilterMode.BLACKLIST, 0),
                draft.policy());
        draft.quantity = "7";
        draft.confirmDirectionChange();
        assertEquals(
                new ItemTransferPolicy.Input(20, 64, RedstoneCondition.SIGNAL, preset, FilterMode.BLACKLIST, 0),
                draft.policy());
        assertEquals(16, ((ItemTransferPolicy.Input) original).keepCount());
    }

    @Test
    void invalidNumbersRemainDraftsAndCannotBecomeSavedPolicies() {
        NodeItemPolicyDraft draft = new NodeItemPolicyDraft(ItemTransferPolicy.defaults(TransferDirection.INPUT), null);
        draft.quantity = "9223372036854775807";
        assertEquals(Long.MAX_VALUE, ((ItemTransferPolicy.Input) draft.policy()).keepCount());
        draft.rate = "0";
        assertThrows(IllegalArgumentException.class, draft::policy);
        draft.rate = "2147483647";
        draft.interval = "2147483648";
        assertThrows(IllegalArgumentException.class, draft::policy);
        draft.interval = "1";
        draft.quantity = "-1";
        assertThrows(IllegalArgumentException.class, draft::policy);
        draft.confirmDirectionChange();
        draft.quantity = "-2147483648";
        assertEquals(Integer.MIN_VALUE, ((ItemTransferPolicy.Output) draft.policy()).priority());
    }
}
