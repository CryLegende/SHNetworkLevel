package org.Cry_Legende.sHNetworkLevel.network;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Ce dont le plugin a besoin du réseau. Seule {@link ShNetworkBus} touche à SHNetwork, donc rien n'est chargé sans lui.
 */
public interface NetBus {

    /** Joueur connecté quelque part sur le réseau : pseudo avec sa casse et serveur. */
    record Presence(String name, String server) {
    }

    /** Nom de ce serveur sur le réseau. */
    String serverName();

    /** Envoie un message sur le canal ; avec {@code includeSelf}, ce serveur le reçoit aussi. */
    void publish(String channel, JsonObject message, boolean includeSelf);

    /** Écoute un canal ; avec {@code mainThread}, le gestionnaire s'exécute sur le fil principal. */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** Exécute une commande console là où se trouve le joueur, une seule fois. */
    void runFor(String player, String command);

    /** Cherche un joueur connecté au réseau, sans attente. */
    Optional<Presence> find(String name);
}
