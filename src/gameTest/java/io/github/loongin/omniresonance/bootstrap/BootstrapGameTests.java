// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only smoke tests and reporting for the dedicated GameTest process. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = OmniResonanceMod.MOD_ID)
public final class BootstrapGameTests {
    private BootstrapGameTests() {}

    /** Verifies the production mod is discoverable in a running dedicated test server. */
    @GameTest(template = "bootstrap")
    public static void productionModLoads(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(OmniResonanceMod.MOD_ID), "Production mod was not loaded");
        helper.succeed();
    }

    /** Installs the build-selected reporter during test registration, without accessing a world. */
    @SubscribeEvent
    public static void configureReporter(RegisterGameTestsEvent event) throws ParserConfigurationException {
        String report = System.getProperty("omniresonance.game_test_report");
        if (report != null) {
            GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(report)));
        }
    }
}
