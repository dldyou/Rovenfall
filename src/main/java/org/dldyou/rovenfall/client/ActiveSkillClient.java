package org.dldyou.rovenfall.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.network.UseActiveSkillPayload;
import org.lwjgl.glfw.GLFW;

public final class ActiveSkillClient {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "active_skills"));
    private static final List<KeyMapping> KEYS = List.of(
            key(1, GLFW.GLFW_KEY_Z),
            key(2, GLFW.GLFW_KEY_X),
            key(3, GLFW.GLFW_KEY_C),
            key(4, GLFW.GLFW_KEY_V));

    private ActiveSkillClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ActiveSkillClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(ActiveSkillClient::onClientTick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        KEYS.forEach(event::register);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        for (int index = 0; index < KEYS.size(); index++) {
            while (KEYS.get(index).consumeClick()) {
                ClientPacketDistributor.sendToServer(new UseActiveSkillPayload(index + 1));
            }
        }
    }

    private static KeyMapping key(int slot, int keyCode) {
        return new KeyMapping(
                "key.rovenfall.active_skill_" + slot,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                keyCode,
                CATEGORY);
    }
}
