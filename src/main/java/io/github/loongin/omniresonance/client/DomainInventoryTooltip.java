// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Pure visible tooltip policy; search metadata and tag-copy actions are independent. */
final class DomainInventoryTooltip {
    private static final String[] SUFFIXES = {"", "K", "M", "G", "T", "P", "E"};

    private DomainInventoryTooltip() {}

    static List<Component> lines(
            String name,
            List<Component> nativeLines,
            boolean item,
            boolean visibleBar,
            boolean fluid,
            boolean opaque,
            long amount,
            String unit) {
        if (amount < 0) throw new IllegalArgumentException("Negative tooltip amount");
        var result = new ArrayList<Component>();
        result.add(Component.literal(name));
        if (opaque) result.add(DomainInventoryView.text("opaque"));
        else for (var line : nativeLines) if (!line.getString().equals(name)) result.add(line.copy());
        // Our slot text abbreviates from 1000, unlike AE2's configurable slot-font thresholds.
        if (!item || opaque || visibleBar || amount >= 1000) {
            var quantity = quantity(amount, fluid);
            String number = quantity.text();
            result.add(
                    item || unit.isEmpty()
                            ? DomainInventoryView.text(
                                    quantity.approximate() ? "quantity_exact_approximate" : "quantity_exact", number)
                            : DomainInventoryView.text(
                                    quantity.approximate() ? "quantity_approximate" : "quantity", number, unit));
        }
        return List.copyOf(result);
    }

    private record Quantity(String text, boolean approximate) {}

    private static Quantity quantity(long amount, boolean buckets) {
        BigDecimal value = BigDecimal.valueOf(amount, buckets ? 3 : 0);
        int suffix = 0;
        while (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && suffix < SUFFIXES.length - 1) {
            value = value.movePointLeft(3);
            suffix++;
        }
        BigDecimal displayed = suffix > 0 ? value.setScale(2, RoundingMode.DOWN) : value;
        return new Quantity(
                displayed.stripTrailingZeros().toPlainString() + SUFFIXES[suffix], displayed.compareTo(value) != 0);
    }
}
