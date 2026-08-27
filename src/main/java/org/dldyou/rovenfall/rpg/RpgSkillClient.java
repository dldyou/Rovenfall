package org.dldyou.rovenfall.rpg;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import org.dldyou.rovenfall.Rovenfall;

/** Physical-client key mappings and the server-issued activation cursor. */
public final class RpgSkillClient {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "skills"));
    private static final int[] DEFAULT_KEYS = {
            InputConstants.KEY_Z, InputConstants.KEY_X, InputConstants.KEY_C, InputConstants.KEY_V
    };
    private static final KeyMapping[] SKILL_KEYS = new KeyMapping[RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS];
    private static long definitionRevision;
    private static long nextRequestId;
    private static int enabledSlots;
    private static UUID session;

    private RpgSkillClient() {
    }

    public static void register(IEventBus modBus) {
        for (int slot = 0; slot < SKILL_KEYS.length; slot++) {
            SKILL_KEYS[slot] = new KeyMapping(
                    "key.rovenfall.active_skill_" + (slot + 1),
                    InputConstants.Type.KEYSYM,
                    DEFAULT_KEYS[slot],
                    CATEGORY);
        }
        modBus.addListener(RpgSkillClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(RpgSkillClient::onClientTick);
    }

    public static void accept(RpgSkillPayloads.StateSync payload) {
        if (payload == null || payload.packetRevision() != RpgSkillPayloads.PACKET_REVISION
                || payload.definitionRevision() < 1 || payload.nextRequestId() < 1
                || payload.enabledSlots() < 1 || payload.enabledSlots() > SKILL_KEYS.length
                || payload.session() == null) {
            return;
        }
        definitionRevision = payload.definitionRevision();
        nextRequestId = payload.nextRequestId();
        enabledSlots = payload.enabledSlots();
        session = payload.session();
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping mapping : SKILL_KEYS) {
            event.register(mapping);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
                || session == null || definitionRevision < 1
                || nextRequestId < 1 || nextRequestId == Long.MAX_VALUE) {
            return;
        }
        for (int slot = 0; slot < SKILL_KEYS.length; slot++) {
            while (SKILL_KEYS[slot].consumeClick()) {
                if (slot >= enabledSlots) {
                    continue;
                }
                int targetEntityId = minecraft.hitResult instanceof EntityHitResult entityHit
                        ? entityHit.getEntity().getId()
                        : -1;
                long requestId = nextRequestId++;
                ClientPacketDistributor.sendToServer(new RpgSkillPayloads.Activate(
                        RpgSkillPayloads.PACKET_REVISION,
                        definitionRevision,
                        requestId,
                        slot,
                        minecraft.level.dimension().identifier(),
                        targetEntityId,
                        session));
            }
        }
    }
}
