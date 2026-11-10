package org.Cry_Legende.sHNetworkLevel.network;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.util.Optional;
import java.util.function.Consumer;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public String serverName() {
        return api.serverName();
    }

    @Override
    public void publish(String channel, JsonObject message, boolean includeSelf) {
        api.publish(channel, message.toString(), includeSelf);
    }

    @Override
    public void listen(String channel, Consumer<JsonObject> handler, boolean mainThread) {
        api.subscribe(channel, message -> {
            JsonObject json;
            try {
                json = message.object();
            } catch (RuntimeException e) {
                return; // message illisible : le bus ne s'arrête jamais pour lui
            }
            handler.accept(json);
        }, mainThread);
    }

    @Override
    public void runFor(String player, String command) {
        api.runFor(player, command);
    }

    @Override
    public Optional<Presence> find(String name) {
        return api.find(name).map(player -> new Presence(player.name(), player.server()));
    }
}
