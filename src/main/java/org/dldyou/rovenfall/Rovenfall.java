package org.dldyou.rovenfall;

import java.util.List;
import net.minecraft.gametest.framework.BuiltinTestFunctions;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.dldyou.rovenfall.administration.RovenfallCommands;

@Mod(Rovenfall.MOD_ID)
public final class Rovenfall {
    public static final String MOD_ID = "rovenfall";

    public Rovenfall(IEventBus modBus) {
        modBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.addListener(RovenfallCommands::register);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("empty"), new TestEnvironmentDefinition.AllOf(List.of()));
        var testData = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
        event.registerTest(id("foundation"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
