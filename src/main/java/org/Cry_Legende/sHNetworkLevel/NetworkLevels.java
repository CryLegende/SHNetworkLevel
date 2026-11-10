package org.Cry_Legende.sHNetworkLevel;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHNetworkLevel.network.NetBus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Mode réseau : plusieurs serveurs partagent la table shnetworklevel. Les modifications relisent la ligne sous
 * verrou ({@link LevelStore}), le cache des joueurs connectés est relu quand un autre serveur écrit, et les
 * niveaux gagnés par un joueur qui se trouve sur un autre serveur lui sont annoncés et payés là où il est.
 *
 * <p>Chargée seulement si SHNetwork est installé.</p>
 */
final class NetworkLevels {

    private static final String UPDATE = "maj";
    private static final String LEVEL_UP = "niveau";

    private final SHNetworkLevel plugin;
    private final NetBus bus;
    private final LevelStore store;
    private final String channel;
    private final long rereadTicks;

    // Un numéro est pris avant chaque lecture : une lecture plus ancienne qui finit après une plus récente ne la remplace pas
    private final AtomicLong tickets = new AtomicLong();
    private final ConcurrentHashMap<String, Long> installed = new ConcurrentHashMap<>();
    // Noms en cours de lecture : un message du bus reçu pendant la lecture déclenche une nouvelle lecture
    private final ConcurrentHashMap<String, Integer> reading = new ConcurrentHashMap<>();
    private BukkitTask rereadTask;

    NetworkLevels(SHNetworkLevel plugin, NetBus bus, LevelStore store, String channel, int rereadSeconds) {
        this.plugin = plugin;
        this.bus = bus;
        this.store = store;
        this.channel = channel;
        this.rereadTicks = Math.max(5, rereadSeconds) * 20L;
    }

    void enable() {
        bus.listen(channel, this::onMessage, true);
        rereadTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reread, rereadTicks, rereadTicks);
    }

    void disable() {
        if (rereadTask != null) {
            rereadTask.cancel();
            rereadTask = null;
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Joueur dans le cache du serveur, relu en base s'il n'y est pas encore. Bloque : hors du fil principal. */
    PlayerData cachedOrLoad(String name) {
        PlayerData cached = plugin.cache().get(name);
        if (cached != null) {
            return cached;
        }
        try {
            return load(name);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de récupérer ou d'initialiser les données pour " + name + ": " + e.getMessage(), e);
            // valeurs par défaut sans les mettre en cache : une nouvelle tentative pourra réussir
            return plugin.defaults();
        }
    }

    /** Lit la base et met le résultat en cache, sauf si une lecture plus récente l'a déjà fait. */
    private PlayerData load(String name) throws SQLException {
        long ticket = tickets.incrementAndGet();
        reading.merge(name, 1, Integer::sum);
        try {
            PlayerData defaults = plugin.defaults();
            return install(name, ticket, store.read(name, defaults.level, defaults.exp));
        } finally {
            reading.compute(name, (key, count) -> count == null || count <= 1 ? null : count - 1);
        }
    }

    private PlayerData install(String name, long ticket, PlayerData data) {
        PlayerData[] kept = {data};
        installed.compute(name, (key, last) -> {
            if (last == null || ticket > last) {
                plugin.cache().put(name, data);
                return ticket;
            }
            PlayerData newer = plugin.cache().get(name);
            if (newer != null) {
                kept[0] = newer;
            }
            return last;
        });
        return kept[0];
    }

    /** Valeurs actuelles en base, sans passer par le cache (commande info). */
    PlayerData fresh(String name) {
        try {
            PlayerData defaults = plugin.defaults();
            return store.read(name, defaults.level, defaults.exp);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire les données de " + name + ": " + e.getMessage(), e);
            return plugin.defaults();
        }
    }

    private void refresh(String name) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                load(name);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Relecture de " + name + " impossible: " + e.getMessage());
            }
        });
    }

    /**
     * Relecture de tous les joueurs connectés : rattrape un message du bus perdu. Les joueurs partis quittent le cache
     * (un joueur hors ligne lu par un placeholder n'a pas d'événement de départ).
     */
    private void reread() {
        Set<String> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getName());
        }
        plugin.cache().keySet().removeIf(name -> !online.contains(name) && !reading.containsKey(name));
        installed.keySet().removeIf(name -> !plugin.cache().containsKey(name) && !reading.containsKey(name));
        if (online.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long ticket = tickets.incrementAndGet();
            try {
                Map<String, PlayerData> rows = store.readAll(online);
                for (String name : online) {
                    PlayerData data = rows.get(name.toLowerCase(Locale.ROOT));
                    if (data != null && plugin.cache().containsKey(name)) {
                        install(name, ticket, data);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Relecture des joueurs connectés impossible: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ écriture

    /**
     * Modifie le joueur en base sous verrou, puis prévient tous les serveurs (celui-ci compris) de relire sa ligne.
     * Bloque : hors du fil principal.
     *
     * @return la modification, ou null si elle a été refusée
     */
    LevelStore.Change update(String name, Predicate<PlayerData> change) throws SQLException {
        PlayerData defaults = plugin.defaults();
        LevelStore.Change done = store.update(name, defaults.level, defaults.exp, change);
        if (done != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", UPDATE);
            message.addProperty("joueur", name);
            bus.publish(channel, message, true);
        }
        return done;
    }

    // ------------------------------------------------------------------ joueur sur un autre serveur

    /** Pseudo exact d'un joueur connecté sur le réseau, ou vide. */
    Optional<String> canonicalName(String input) {
        return bus.find(input).map(NetBus.Presence::name);
    }

    /** Vrai si le joueur est connecté sur un autre serveur que celui-ci. */
    boolean onlineElsewhere(String name) {
        return Bukkit.getPlayerExact(name) == null && bus.find(name).isPresent();
    }

    /**
     * Niveau gagné par un joueur connecté sur un autre serveur : son serveur affiche le message et joue le son,
     * les commandes de récompense passent par runFor (exécutées une seule fois, là où il est).
     */
    void levelUpElsewhere(String name, int level, List<String> rewardCommands) {
        JsonObject message = new JsonObject();
        message.addProperty("type", LEVEL_UP);
        message.addProperty("joueur", name);
        message.addProperty("niveau", level);
        bus.publish(channel, message, false);
        for (String command : rewardCommands) {
            bus.runFor(name, command);
        }
    }

    // ------------------------------------------------------------------ bus (fil principal)

    private void onMessage(JsonObject message) {
        if (!message.has("type") || !message.has("joueur")) {
            return;
        }
        String type = message.get("type").getAsString();
        String name = message.get("joueur").getAsString();
        if (type.equals(UPDATE)) {
            if (plugin.cache().containsKey(name) || reading.containsKey(name) || Bukkit.getPlayerExact(name) != null) {
                refresh(name);
            }
        } else if (type.equals(LEVEL_UP) && message.has("niveau")) {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                plugin.announceLevelUp(player, message.get("niveau").getAsInt());
            }
        }
    }
}
