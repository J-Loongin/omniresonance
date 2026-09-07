// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

final class TerminalClickButtonTest {
    @Test
    void approvedClickPlaysLocallyOnceAtItsOriginalPitchAndLevel() {
        TerminalClickButton.ClickFeedback feedback = TerminalClickButton.clickFeedback();
        SoundInstance click = feedback.createSound();

        assertEquals(ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_click"), click.getLocation());
        assertEquals(1.0F, feedback.volume());
        assertEquals(1.0F, feedback.pitch());
        assertEquals(SoundSource.MASTER, click.getSource());
        assertEquals(SoundInstance.Attenuation.NONE, click.getAttenuation());
        assertTrue(click.isRelative());
        assertFalse(click.isLooping());
    }
}
