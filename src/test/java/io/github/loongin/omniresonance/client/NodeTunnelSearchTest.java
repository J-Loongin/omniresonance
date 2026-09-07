// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.glfw.GLFW;

final class NodeTunnelSearchTest {
    @ParameterizedTest
    @ValueSource(ints = {GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER})
    void enterOpensOnlyOnceWithoutSchedulingOrSubmittingAQuery(int keyCode) {
        NodeTunnelSearch search = new NodeTunnelSearch();
        NodeMenuInteractionPolicy.Model model = loaded(NodeTunnelCatalogTest.batch(1, 1, 1, 0));
        assertTrue(search.openFromKey(keyCode, 0, model, false, true));
        assertTrue(search.expanded());
        assertFalse(search.due(100));
        assertFalse(search.openFromKey(keyCode, 0, model, false, true));
        assertTrue(search.expanded(), "Repeated Enter must not collapse the field");
        assertFalse(model.mutationPending());
    }

    @Test
    void enterDoesNotBypassLoadingModalPendingOrDisabledStates() {
        NodeMenuState.DirectTunnelList list = NodeTunnelCatalogTest.batch(1, 1, 1, 0);
        NodeMenuInteractionPolicy.Model model = loaded(list);
        NodeTunnelSearch search = new NodeTunnelSearch();
        assertFalse(search.openFromKey(GLFW.GLFW_KEY_ENTER, 0, model, true, true));
        assertFalse(search.openFromKey(GLFW.GLFW_KEY_ENTER, 0, model, false, false));
        assertFalse(search.openFromKey(
                GLFW.GLFW_KEY_ENTER, 0, model.submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1), false, true));
        assertFalse(search.openFromKey(GLFW.GLFW_KEY_ENTER, 0, NodeMenuInteractionPolicy.Model.loading(), false, true));
        NodeMenuNodeSummary node = list.node();
        NodeMenuNodeSummary disabled = new NodeMenuNodeSummary(
                node.networkId(),
                node.networkName(),
                node.nodeId(),
                node.nodeName(),
                node.revision(),
                node.dimension(),
                node.position(),
                node.form(),
                node.facing(),
                false,
                node.chunkLoadingRequested(),
                node.mode());
        assertFalse(search.openFromKey(
                GLFW.GLFW_KEY_ENTER,
                0,
                loaded(new NodeMenuState.DirectTunnelList(disabled, list.page(), 0)),
                false,
                true));
        assertFalse(search.expanded());
    }

    @Test
    void enterDoesNotCaptureRenameOrOtherPageActions() {
        NodeMenuNodeSummary node = NodeTunnelCatalogTest.batch(1, 1, 1, 0).node();
        for (NodeMenuState state : List.of(
                new NodeMenuState.LinkedRename(node),
                new NodeMenuState.ModeRoot(node),
                new NodeMenuState.DomainRoot(node, null))) {
            NodeTunnelSearch search = new NodeTunnelSearch();
            assertFalse(search.openFromKey(GLFW.GLFW_KEY_ENTER, 0, loaded(state), false, true));
            assertFalse(search.expanded());
        }
    }

    @Test
    void otherKeysAndModifiedEnterKeepTheirExistingMeaning() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        NodeMenuInteractionPolicy.Model model = loaded(NodeTunnelCatalogTest.batch(1, 1, 1, 0));
        for (int key : new int[] {GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_SPACE}) {
            assertFalse(search.openFromKey(key, 0, model, false, true));
        }
        for (int modifier :
                new int[] {GLFW.GLFW_MOD_SHIFT, GLFW.GLFW_MOD_CONTROL, GLFW.GLFW_MOD_ALT, GLFW.GLFW_MOD_SUPER}) {
            assertFalse(search.openFromKey(GLFW.GLFW_KEY_ENTER, modifier, model, false, true));
        }
        assertFalse(search.expanded());
    }

    private static NodeMenuInteractionPolicy.Model loaded(NodeMenuState state) {
        return NodeMenuInteractionPolicy.Model.loading()
                .apply(new NodeMenuResponse.State(7, new UUID(710, 1), 0, state))
                .model();
    }

    @Test
    void freshSearchStaysCollapsedWithoutSchedulingAQuery() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        assertFalse(search.expanded());
        assertEquals("", search.draft());
        assertFalse(search.due(100));
    }

    @Test
    void editsInOneTickAreCoalescedIntoTheLatestQueryOnTheNextTick() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        assertTrue(search.expanded());
        assertFalse(search.due(100));
        search.edit("s", 10);
        search.edit("sd", 10);
        assertFalse(search.due(10));
        assertTrue(search.due(11));
        assertEquals("sd", search.draft());
        search.handled();
        assertFalse(search.due(11));
        assertFalse(search.due(100));
    }

    @Test
    void continuedTypingDoesNotPostponeAnAlreadyPendingUpdate() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        search.edit("s", 10);
        search.edit("sd", 11);
        assertTrue(search.due(11));
        assertEquals("sd", search.draft());
        search.handled();
        search.edit("sd", 11);
        assertFalse(search.due(100), "Unchanged callbacks must not schedule another result update");
        search.edit("suidao", 11);
        assertFalse(search.due(11));
        assertTrue(search.due(12));
    }

    @Test
    void clearingTheQueryRestoresResultsOnTheNextTick() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        search.edit("sd", 1);
        search.handled();
        search.edit("", 10);
        assertFalse(search.due(10));
        assertTrue(search.due(11));
        assertEquals("", search.draft());
    }

    @Test
    void firstEscapeClearsLocallyWhileTheSecondFallsThroughToNavigation() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        search.edit("iron", 10);
        assertTrue(search.close(11));
        assertFalse(search.expanded());
        assertEquals("", search.draft());
        assertTrue(search.due(11));
        assertFalse(search.close(12));
        search.handled();
        assertFalse(search.due(100));
    }

    @Test
    void leavingThePageDropsTheLocalDraftAndPendingUpdate() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        search.edit("iron", 10);
        search.reset();
        assertFalse(search.expanded());
        assertEquals("", search.draft());
        assertFalse(search.due(100));
    }
}
