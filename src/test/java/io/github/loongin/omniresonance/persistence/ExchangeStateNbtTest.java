// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeFilterSnapshot;
import io.github.loongin.omniresonance.exchange.ExchangeInvitation;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ExchangeStateNbtTest {
    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static ExchangeConsent consent() {
        return new ExchangeConsent(
                id(1),
                id(2),
                id(3),
                id(4),
                id(5),
                7,
                2,
                new ExchangeConsent.Approval(true, true),
                new ExchangeConsent.Approval(false, false),
                false);
    }

    @Test
    void completeAgreementRetainsDetachedFilterAcrossNativeEncoding() {
        ResourceFilterPreset root = new ResourceFilterPreset(id(20), new ManagedName("Approved filter"), 3, List.of());
        ExchangeFilterSnapshot filter = ExchangeFilterSnapshot.capture(root.id(), Map.of(root.id(), root), 1, 0);
        var terms = new io.github.loongin.omniresonance.exchange.ExchangeTerms(
                io.github.loongin.omniresonance.transfer.ResourceScope.all(),
                io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                filter,
                64,
                Map.of(),
                1);
        var agreement = new io.github.loongin.omniresonance.exchange.ExchangeAgreement(id(90), consent(), terms);
        CompoundTag encoded = ExchangeStateNbt.encodeAgreement(agreement);
        var restored = ExchangeStateNbt.decodeAgreement(encoded);
        assertEquals(filter.presets(), restored.terms().filter().presets());
        assertEquals(encoded, ExchangeStateNbt.encodeAgreement(restored));
    }

    @Test
    void agreementRoundTripPreservesTermsAndRejectsDuplicateRates() {
        var type = net.minecraft.resources.ResourceLocation.parse("example:future_resource");
        var terms = new io.github.loongin.omniresonance.exchange.ExchangeTerms(
                io.github.loongin.omniresonance.transfer.ResourceScope.customSet(java.util.Set.of(type)),
                io.github.loongin.omniresonance.filter.FilterMode.BLACKLIST,
                null,
                Long.MAX_VALUE,
                Map.of(type, 42L),
                7);
        var agreement = new io.github.loongin.omniresonance.exchange.ExchangeAgreement(id(90), consent(), terms);
        CompoundTag tag = ExchangeStateNbt.encodeAgreement(agreement);
        assertEquals(agreement, ExchangeStateNbt.decodeAgreement(tag));
        ListTag rates = (ListTag) tag.getCompound("terms").get("rates");
        rates.add(rates.get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeAgreement(tag));
        CompoundTag invalid = ExchangeStateNbt.encodeAgreement(agreement);
        invalid.getCompound("terms").putString("filter_mode", "future");
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeAgreement(invalid));
    }

    @Test
    void consentRoundTripPreservesBothSidesAndRejectsMalformedFields() {
        ExchangeConsent original = consent();
        CompoundTag tag = ExchangeStateNbt.encodeConsent(original);
        assertEquals(original, ExchangeStateNbt.decodeConsent(tag));
        tag.putByte("source_approved", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeConsent(tag));
        CompoundTag extra = ExchangeStateNbt.encodeConsent(original);
        extra.putInt("unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeConsent(extra));
        CompoundTag wrong = ExchangeStateNbt.encodeConsent(original);
        wrong.putString("revision", "7");
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeConsent(wrong));
    }

    @Test
    void invitationRoundTripPreservesLifecycleAndExpiry() {
        for (ExchangeInvitation.State state : ExchangeInvitation.State.values()) {
            ExchangeInvitation original = new ExchangeInvitation(id(1), id(2), id(3), 20, 12020, 2, state);
            assertEquals(original, ExchangeStateNbt.decodeInvitation(ExchangeStateNbt.encodeInvitation(original)));
        }
        CompoundTag tag = ExchangeStateNbt.encodeInvitation(
                new ExchangeInvitation(id(1), id(2), id(3), 0, 12000, 0, ExchangeInvitation.State.OPEN));
        tag.putString("state", "future");
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeInvitation(tag));
        tag.putString("state", "open");
        tag.putLong("expires_tick", 12001);
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeInvitation(tag));
    }

    @Test
    void snapshotRoundTripRejectsDuplicateAndUnreachablePresets() {
        ResourceFilterPreset child = new ResourceFilterPreset(id(2), new ManagedName("Child"), 3, List.of());
        ResourceFilterPreset root = new ResourceFilterPreset(
                id(1), new ManagedName("Root"), 4, List.of(new ResourceFilterRule.Reference(id(10), child.id())));
        ExchangeFilterSnapshot snapshot =
                ExchangeFilterSnapshot.capture(root.id(), Map.of(root.id(), root, child.id(), child), 2, 1);
        CompoundTag tag = ExchangeStateNbt.encodeFilter(snapshot);
        ExchangeFilterSnapshot decoded = ExchangeStateNbt.decodeFilter(tag);
        assertEquals(snapshot.root(), decoded.root());
        assertEquals(snapshot.presets(), decoded.presets());
        ListTag rows = (ListTag) tag.get("presets");
        rows.add(rows.get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeFilter(tag));
        CompoundTag extra = ExchangeStateNbt.encodeFilter(snapshot);
        ((ListTag) extra.get("presets"))
                .add(ResourceFilterPresetNbt.encode(
                        new ResourceFilterPreset(id(3), new ManagedName("Unrelated"), 0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> ExchangeStateNbt.decodeFilter(extra));
        tag.getAllKeys().clear();
        assertEquals(2, decoded.presets().size());
    }
}
