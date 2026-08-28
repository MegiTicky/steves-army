package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Serializes synchronous grenade world simulations to one complete search per server tick. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class GrenadeArcCalculationScheduler {
    private static final ArrayDeque<Request> PREPARATION_REQUESTS = new ArrayDeque<>();
    private static final ArrayDeque<Request> NEW_REQUESTS = new ArrayDeque<>();
    private static final Set<GrenadeTacticalController> QUEUED =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private GrenadeArcCalculationScheduler() {}

    public static boolean request(GrenadeTacticalController controller, boolean preparationReplan) {
        if (controller.getCalculationServer() == null) return false;
        if (!QUEUED.add(controller)) return false;
        Request request = new Request(controller, preparationReplan,
            controller.getCalculationServer());
        if (preparationReplan) {
            PREPARATION_REQUESTS.addLast(request);
        } else {
            NEW_REQUESTS.addLast(request);
        }
        return true;
    }

    public static void cancel(GrenadeTacticalController controller) {
        QUEUED.remove(controller);
        PREPARATION_REQUESTS.removeIf(request -> request.controller() == controller);
        NEW_REQUESTS.removeIf(request -> request.controller() == controller);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Request request = poll(event.getServer());
        if (request == null) return;
        QUEUED.remove(request.controller());
        request.controller().processQueuedArcCalculation(request.preparationReplan());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PREPARATION_REQUESTS.clear();
        NEW_REQUESTS.clear();
        QUEUED.clear();
    }

    private static Request poll(MinecraftServer server) {
        Request request = pollMatching(PREPARATION_REQUESTS, server);
        return request != null ? request : pollMatching(NEW_REQUESTS, server);
    }

    private static Request pollMatching(ArrayDeque<Request> requests, MinecraftServer server) {
        int size = requests.size();
        while (size-- > 0) {
            Request request = requests.removeFirst();
            if (request.server() == server) return request;
            requests.addLast(request);
        }
        return null;
    }

    private record Request(GrenadeTacticalController controller,
                           boolean preparationReplan,
                           MinecraftServer server) {}
}
