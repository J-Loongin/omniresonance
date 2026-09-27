// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Pure bounded parsing of explicit user input. Never guesses an installed mod, touches a registry or grants authority. */
final class TerminalRuleInput {
    private static final ResourceLocation SOURCE = ResourceTypes.SOURCE;
    private static final ResourceLocation CHEMICAL = ResourceLocation.parse("mekanism:chemical");

    private TerminalRuleInput() {}

    static boolean explicitSyntax(String input) {
        String value = input.trim();
        return value.equals("energy") || value.equals("source") || value.equals("soul") || prefix(value) != null;
    }

    static String body(String input) {
        String value = input.trim();
        if (value.equals("energy") || value.equals("source") || value.equals("soul")) return "";
        return prefix(value) == null
                ? input
                : value.substring(value.indexOf(':') + 1).trim();
    }

    static @Nullable ResourceRuleIntent.Match explicit(String input) {
        if (input.length() > 65535) throw new IllegalArgumentException("Rule input exceeds bound");
        String value = input.trim();
        if (value.equals("energy")) return match(ResourceTypes.ENERGY, ResourceFilterRule.Selector.wholeType());
        if (value.equals("source")) return match(SOURCE, ResourceFilterRule.Selector.wholeType());
        if (value.equals("soul")) return match(ResourceTypes.SOUL, ResourceFilterRule.Selector.wholeType());
        ResourceLocation type = prefix(value);
        if (type == null) return null;
        String body = body(value);
        if (body.isEmpty()) throw new IllegalArgumentException("Incomplete typed rule");
        var selector = body.startsWith("#")
                ? ResourceFilterRule.Selector.tag(id(body.substring(1)))
                : body.indexOf('*') >= 0 || body.indexOf('?') >= 0
                        ? ResourceFilterRule.Selector.glob(body)
                        : ResourceFilterRule.Selector.exact(id(body));
        return match(type, selector);
    }

    private static @Nullable ResourceLocation prefix(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) return null;
        return switch (text.substring(0, colon)) {
            case "item" -> ResourceTypes.ITEM;
            case "fluid" -> ResourceTypes.FLUID;
            case "chemical", "gas" -> CHEMICAL;
            default -> null;
        };
    }

    private static ResourceLocation id(String body) {
        if (body.isEmpty()) throw new IllegalArgumentException("Missing resource ID");
        var id = ResourceLocation.parse(body);
        if (id.getPath().isEmpty() || body.indexOf(':') >= 0 && !id.toString().equals(body))
            throw new IllegalArgumentException("Invalid resource ID");
        return id;
    }

    private static ResourceRuleIntent.Match match(ResourceLocation type, ResourceFilterRule.Selector selector) {
        return new ResourceRuleIntent.Match(type, selector, ComponentCondition.Mode.ID_ONLY, Set.of(), null);
    }
}
