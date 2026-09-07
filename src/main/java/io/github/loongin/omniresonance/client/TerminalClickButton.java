// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.registry.ModSounds;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;

/** Shared button base that gives every terminal control the same audible click feedback. */
abstract class TerminalClickButton extends Button {
    private static final float CLICK_PITCH = 1.0F;
    private static final float CLICK_VOLUME = 1.0F;

    TerminalClickButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    static ClickFeedback clickFeedback() {
        return new ClickFeedback(ModSounds.UI_CLICK.get(), CLICK_PITCH, CLICK_VOLUME);
    }

    @Override
    public final void playDownSound(SoundManager soundManager) {
        soundManager.play(clickFeedback().createSound());
    }

    record ClickFeedback(SoundEvent event, float pitch, float volume) {
        SoundInstance createSound() {
            return SimpleSoundInstance.forUI(event, pitch, volume);
        }
    }
}
