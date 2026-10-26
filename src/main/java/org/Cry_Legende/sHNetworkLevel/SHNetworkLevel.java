package org.Cry_Legende.sHNetworkLevel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID; // Importation nécessaire pour getUniqueId
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class SHNetworkLevel extends JavaPlugin {

    private HikariDataSource dataSource;
    // Cache en mémoire des données des joueurs (clé : nom du joueur)
    private ConcurrentHashMap<String, PlayerData> playerCache = new ConcurrentHashMap<>();
    // Noms dont le chargement est en cours (PlaceholderAPI sur le thread principal)
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private SHNetworkLevelPlaceholderExpansion expansion;

    @Override
    public void onEnable() {
        // Sauvegarde de la config par défaut si elle n'existe pas
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Récupération des paramètres de connexion à la base de données depuis la config
        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String database = config.getString("database.database", "minecraft");
        String username = config.getString("database.username", "root");
        String password = config.getString("database.password", "");

        // Construction de l'URL JDBC en utilisant le driver MariaDB et l'option autoReconnect
        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?autoReconnect=true";

        // Configuration de HikariCP
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
        // Test de validité de la connexion
        hikariConfig.setConnectionTestQuery("SELECT 1");
        // Timeout d'inactivité et taille maximum du pool
        hikariConfig.setIdleTimeout(300000); // 5 minutes
        hikariConfig.setMaximumPoolSize(10);

        dataSource = new HikariDataSource(hikariConfig);

        // Création de la table si elle n'existe pas
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS shnetworklevel (name VARCHAR(16) NOT NULL PRIMARY KEY, level INT NOT NULL, exp INT NOT NULL)"
             )) {
            ps.executeUpdate();
            getLogger().info("Table shnetworklevel vérifiée/créée.");
        } catch (SQLException e) {
            getLogger().severe("Impossible de créer ou vérifier la table shnetworklevel !");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Enregistrement de la commande (assurez-vous de déclarer la commande dans plugin.yml)
        this.getCommand("shnetworklevel").setExecutor(this);

        // Enregistrement du Listener pour charger/décharger les données en cache
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        // Hook sur PlaceholderAPI s'il est présent
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            expansion = new SHNetworkLevelPlaceholderExpansion(this);
            expansion.register();
            getLogger().info("Hook PlaceholderAPI enregistré.");
        }
    }

    @Override
    public void onDisable() {
        // persist() garde l'expansion chez PlaceholderAPI : sans désinscription elle continuerait à interroger un pool fermé
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        // Fermeture propre du pool de connexions
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // Traitement de la commande /shnetworklevel
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.usage.main", "&cUsage: /shnetworklevel <exp/level/info> ...")));
            return true;
        }

        if (!sender.hasPermission("shnetworklevel.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.no-permission", "&cVous n'avez pas la permission d'exécuter cette commande.")));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("exp")) {
            if (args.length != 4) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.usage.exp", "&cUsage: /shnetworklevel exp <give/take/set> <player> <number>")));
                return true;
            }
            final String action = args[1].toLowerCase();
            final String targetNameInput = args[2]; // Nom tapé par l'utilisateur
            final int amount;
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.invalid-number", "&cLe nombre fourni est invalide.")));
                return true;
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                final OfflinePlayer target = Bukkit.getOfflinePlayer(targetNameInput);

                // Vérifier si le joueur a un profil (a déjà joué ou est un joueur valide)
                if (target == null || target.getUniqueId() == null || target.getName() == null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.player-not-found", "&cLe joueur '%player%' n'a pas été trouvé ou n'a jamais joué.")
                                .replace("%player%", targetNameInput)));
                    });
                    return;
                }
                final String playerName = target.getName(); // Nom canonique du joueur

                final PlayerData data = getPlayerData(playerName);
                final int oldLevel = data.level;

                if (action.equals("give")) {
                    data.exp += amount;
                    while (data.exp >= getExpForNextLevel(data.level)) {
                        data.exp -= getExpForNextLevel(data.level);
                        data.level++;
                    }
                } else if (action.equals("take")) {
                    data.exp = Math.max(data.exp - amount, 0);
                    // Si l'exp tombe sous 0, il faudrait peut-être ajuster le level à la baisse.
                    // Actuellement, le level ne baisse que si l'exp atteint 0 et qu'on continue de "take".
                    // Ou alors, le level ne devrait jamais baisser avec "take exp".
                } else if (action.equals("set")) {
                    data.exp = Math.max(0, amount); // Assurer que l'exp n'est pas négative
                    // La logique de recalcul du level en cas de "set exp" pourrait être revue.
                    // Actuellement, elle ne fait qu'augmenter le level si l'exp est suffisante.
                    // Si on set l'exp à 0, le level ne change pas.
                    // Si vous voulez que le level soit recalculé de zéro en fonction de l'exp :
                    // data.level = 0; // Réinitialiser le level
                    // while (data.exp >= getExpForNextLevel(data.level)) {
                    //    data.exp -= getExpForNextLevel(data.level);
                    //    data.level++;
                    // }
                    // Pour l'instant, on garde la logique d'augmentation seule :
                    while (data.exp >= getExpForNextLevel(data.level)) {
                        data.exp -= getExpForNextLevel(data.level);
                        data.level++;
                    }
                } else {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.usage.exp", "&cUsage: /shnetworklevel exp <give/take/set> <player> <number>")));
                    });
                    return;
                }

                // Mise à jour de la base de données
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement ps = connection.prepareStatement("REPLACE INTO shnetworklevel (name, level, exp) VALUES (?, ?, ?)")) {
                    ps.setString(1, playerName);
                    ps.setInt(2, data.level);
                    ps.setInt(3, data.exp);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    getLogger().severe("Erreur lors de la mise à jour des données pour " + playerName + ": " + e.getMessage());
                    e.printStackTrace();
                    // Envoyer un message d'erreur à l'admin est une bonne idée ici aussi
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.RED + "Une erreur de base de données est survenue. Veuillez vérifier la console.");
                    });
                    return; // Stopper si la DB échoue
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    String msgKey = "";
                    if (action.equals("give")) {
                        msgKey = "messages.exp-given";
                    } else if (action.equals("take")) {
                        msgKey = "messages.exp-taken";
                    } else if (action.equals("set")) {
                        msgKey = "messages.exp-set";
                    }
                    String msgTemplate = getConfig().getString(msgKey, "&aCommande exécutée.");
                    final String formattedMsg = msgTemplate.replace("%player%", targetNameInput) // Utiliser le nom tapé par l'admin
                        .replace("%amount%", String.valueOf(amount));
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedMsg));

                    if (data.level > oldLevel) {
                        for (int lvl = oldLevel + 1; lvl <= data.level; lvl++) {
                            String lvlMsgTemplate = getConfig().getString("messages.level-up", "&eFélicitations %player% ! Vous êtes passé au niveau %level% !");
                            final String formattedLvlMsg = lvlMsgTemplate.replace("%player%", playerName) // Utiliser le nom canonique
                                .replace("%level%", String.valueOf(lvl));

                            if (!(sender instanceof Player) || !((Player) sender).getName().equals(playerName)) {
                                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                            }

                            if (target.isOnline()) {
                                Player onlinePlayer = target.getPlayer();
                                if (onlinePlayer != null) {
                                    onlinePlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                                    playLevelUpSound(onlinePlayer);
                                    executeRewardCommands(onlinePlayer, lvl);
                                }
                            }
                        }
                    }
                    evictIfOffline(target, playerName);
                });
            });
            return true;

        } else if (sub.equals("level")) {
            if (args.length != 4) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.usage.level", "&cUsage: /shnetworklevel level <give/take/set> <player> <number>")));
                return true;
            }
            final String action = args[1].toLowerCase();
            final String targetNameInput = args[2];
            final int amount;
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 0) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        getConfig().getString("messages.invalid-number-positive", "&cLe niveau doit être un nombre positif ou nul.")));
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.invalid-number", "&cLe nombre fourni est invalide.")));
                return true;
            }


            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                final OfflinePlayer target = Bukkit.getOfflinePlayer(targetNameInput);
                if (target == null || target.getUniqueId() == null || target.getName() == null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.player-not-found", "&cLe joueur '%player%' n'a pas été trouvé ou n'a jamais joué.")
                                .replace("%player%", targetNameInput)));
                    });
                    return;
                }
                final String playerName = target.getName();

                final PlayerData data = getPlayerData(playerName); // Expérience actuelle sera conservée
                int oldLevel = data.level; // Pour la comparaison après modification

                if (action.equals("give")) {
                    data.level += amount;
                } else if (action.equals("take")) {
                    data.level = Math.max(0, data.level - amount); // Level ne peut pas être négatif
                } else if (action.equals("set")) {
                    data.level = Math.max(0, amount); // Level ne peut pas être négatif
                } else {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.usage.level", "&cUsage: /shnetworklevel level <give/take/set> <player> <number>")));
                    });
                    return;
                }
                // Note: changer le level ne change pas l'exp actuelle dans cette logique.
                // Si le level est baissé, l'exp pourrait être supérieure à l'exp nécessaire pour le level actuel.
                // Si le level est augmenté, l'exp reste la même (il faudra plus d'exp pour le prochain level up).
                // Vous pourriez vouloir réinitialiser l'exp à 0 pour le nouveau level : data.exp = 0;

                try (Connection connection = dataSource.getConnection();
                     PreparedStatement ps = connection.prepareStatement("REPLACE INTO shnetworklevel (name, level, exp) VALUES (?, ?, ?)")) {
                    ps.setString(1, playerName);
                    ps.setInt(2, data.level);
                    ps.setInt(3, data.exp); // Conserver l'exp actuelle
                    ps.executeUpdate();
                } catch (SQLException e) {
                    getLogger().severe("Erreur lors de la mise à jour des données pour " + playerName + ": " + e.getMessage());
                    e.printStackTrace();
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.RED + "Une erreur de base de données est survenue. Veuillez vérifier la console.");
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    String msgKey = "";
                    if (action.equals("give")) {
                        msgKey = "messages.level-given";
                    } else if (action.equals("take")) {
                        msgKey = "messages.level-taken";
                    } else if (action.equals("set")) {
                        msgKey = "messages.level-set";
                    }
                    String msgTemplate = getConfig().getString(msgKey, "&aCommande exécutée.");
                    final String formattedMsg = msgTemplate.replace("%player%", targetNameInput)
                        .replace("%amount%", String.valueOf(amount));
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedMsg));

                    // Si le level a augmenté et que le joueur est en ligne, notifier et donner des récompenses
                    // Note: Si le level est "set" à une valeur plus élevée, cela déclenchera aussi.
                    if (data.level > oldLevel) {
                        for (int lvl = oldLevel + 1; lvl <= data.level; lvl++) {
                            String lvlMsgTemplate = getConfig().getString("messages.level-up", "&eFélicitations %player% ! Vous êtes passé au niveau %level% !");
                            final String formattedLvlMsg = lvlMsgTemplate.replace("%player%", playerName)
                                .replace("%level%", String.valueOf(lvl));

                            if (!(sender instanceof Player) || !((Player) sender).getName().equals(playerName)) {
                                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                            }

                            if (target.isOnline()) {
                                Player onlinePlayer = target.getPlayer();
                                if (onlinePlayer != null) {
                                    onlinePlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                                    playLevelUpSound(onlinePlayer);
                                    executeRewardCommands(onlinePlayer, lvl);
                                }
                            }
                        }
                    }
                    evictIfOffline(target, playerName);
                });
            });
            return true;

        } else if (sub.equals("info")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.usage.info", "&cUsage: /shnetworklevel info <player>")));
                return true;
            }
            final String targetNameInput = args[1];

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                final OfflinePlayer target = Bukkit.getOfflinePlayer(targetNameInput);
                if (target == null || target.getUniqueId() == null || target.getName() == null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.player-not-found", "&cLe joueur '%player%' n'a pas été trouvé ou n'a jamais joué.")
                                .replace("%player%", targetNameInput)));
                    });
                    return;
                }
                final String playerName = target.getName();

                PlayerData data = getPlayerData(playerName); // Cet appel est maintenant dans le thread async
                String msgTemplate = getConfig().getString("messages.info", "&aInfos pour %player%: Niveau %level%, Exp %exp%/%expneeded%");
                final int expNeeded = getExpForNextLevel(data.level);

                final String formattedMsg = msgTemplate.replace("%player%", playerName) // Utiliser le nom canonique pour l'info
                    .replace("%level%", String.valueOf(data.level))
                    .replace("%exp%", String.valueOf(data.exp))
                    .replace("%expneeded%", String.valueOf(expNeeded))
                    .replace("%progressbar%", buildProgressBar(data.exp, expNeeded)); // Ajout de la progress bar

                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedMsg));
                    evictIfOffline(target, playerName);
                });
            });
            return true;
        } else {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.usage.main", "&cUsage: /shnetworklevel <exp/level/info> ...")));
            return true;
        }
    }

    /**
     * Retourne l'exp nécessaire pour passer du niveau actuel au niveau suivant.
     */
    public int getExpForNextLevel(int level) {
        FileConfiguration config = getConfig();
        String type = config.getString("progression.type", "linear");
        if (type.equalsIgnoreCase("exponential")) {
            double base = config.getDouble("progression.base", 100);
            double multiplier = config.getDouble("progression.multiplier", 1.1);
            return clampExp(Math.round(base * Math.pow(multiplier, Math.max(0, level)))); // Assurer level >= 0
        } else { // linear par défaut
            double base = config.getDouble("progression.base", 100);
            double increment = config.getDouble("progression.increment", 50);
            return clampExp(Math.round(base + (Math.max(0, level) * increment))); // Assurer level >= 0
        }
    }

    /**
     * Le cast (int) d'un long tronque : au-delà de ~2 milliards d'exp (niveau 42 en exponentiel) le seuil devenait
     * négatif ou minuscule, et la boucle de montée de niveau ne se terminait plus.
     */
    private static int clampExp(long exp) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, exp));
    }

    /**
     * Construit une barre de progression sous forme de chaîne de caractères.
     */
    public String buildProgressBar(int current, int total) {
        FileConfiguration config = getConfig();
        int barAmount = config.getInt("Progress-bar.Amount", 15);
        String barChar = config.getString("Progress-bar.Character", "■");
        String completeColor = config.getString("Progress-bar.Complete", "&3");
        String remainingColor = config.getString("Progress-bar.Remaining", "&7");

        if (total <= 0) total = 1; // Eviter division par zéro si expneeded est 0 (ex: level max)
        current = Math.max(0, Math.min(current, total)); // S'assurer que current est entre 0 et total

        double ratio = (double) current / total;
        int filledSegments = (int) Math.floor(ratio * barAmount);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < barAmount; i++) {
            if (i < filledSegments) {
                bar.append(completeColor).append(barChar);
            } else {
                bar.append(remainingColor).append(barChar);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', bar.toString());
    }

    /**
     * Exécute les commandes de récompense définies pour un level-up.
     */
    private void executeRewardCommands(Player player, int newLevel) {
        FileConfiguration config = getConfig();
        // Vérifier si la section existe et est une liste
        if (!config.isConfigurationSection("rewards." + newLevel) && !config.isList("rewards.levelup-commands")) {
            // Tenter de lire une liste globale si pas de section spécifique au niveau
            if (config.isList("rewards.default-levelup-commands")) { // Nom suggéré pour commandes globales
                for (String command : config.getStringList("rewards.default-levelup-commands")) {
                    String cmd = command.replace("%player%", player.getName())
                        .replace("%level%", String.valueOf(newLevel));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
            return;
        }

        // Logique pour les récompenses par niveau spécifique
        if (config.isConfigurationSection("rewards." + newLevel)) {
            if (config.isList("rewards." + newLevel + ".commands")) {
                for (String command : config.getStringList("rewards." + newLevel + ".commands")) {
                    String cmd = command.replace("%player%", player.getName())
                        .replace("%level%", String.valueOf(newLevel));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
        }
        // Logique pour la liste globale "rewards.levelup-commands" (votre existante)
        // Peut être combinée avec la logique par niveau ou être exclusive.
        // Ici, on les exécute toutes les deux si présentes.
        else if (config.isList("rewards.levelup-commands")) { // Votre ancienne clé
            for (String command : config.getStringList("rewards.levelup-commands")) {
                String cmd = command.replace("%player%", player.getName())
                    .replace("%level%", String.valueOf(newLevel));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    /**
     * Récupère (ou crée) les données d'un joueur en base et les met en cache.
     * Cette méthode peut effectuer des opérations de base de données et doit être appelée de manière asynchrone
     * si elle est appelée depuis le thread principal et que le joueur n'est pas en cache.
     */
    public PlayerData getPlayerData(String playerName) { // Rendre 'public' pour PlaceholderAPI si besoin, sinon 'private'
        PlayerData cached = playerCache.get(playerName);
        if (cached != null) {
            return cached;
        }
        PlayerData data = new PlayerData();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT level, exp FROM shnetworklevel WHERE name = ?")) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                data.level = rs.getInt("level");
                data.exp = rs.getInt("exp");
            } else {
                // Initialisation par défaut si le joueur n'est pas dans la DB
                data.level = getConfig().getInt("default-values.level", 0);
                data.exp = getConfig().getInt("default-values.exp", 0);
                // Insérer le nouveau joueur dans la base de données
                try (PreparedStatement insertPs = connection.prepareStatement(
                    "INSERT INTO shnetworklevel (name, level, exp) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE level=VALUES(level), exp=VALUES(exp)")) {
                    insertPs.setString(1, playerName);
                    insertPs.setInt(2, data.level);
                    insertPs.setInt(3, data.exp);
                    insertPs.executeUpdate();
                }
            }
            // Mettre en cache seulement après une lecture/écriture réussie
            playerCache.put(playerName, data);
        } catch (SQLException e) {
            getLogger().severe("Impossible de récupérer ou d'initialiser les données pour " + playerName + ": " + e.getMessage());
            e.printStackTrace();
            // Retourner un objet PlayerData par défaut en cas d'erreur pour éviter les NullPointerExceptions
            // mais ne pas le mettre en cache pour qu'une nouvelle tentative puisse être faite.
            data.level = getConfig().getInt("default-values.level", 0); // Valeurs par défaut
            data.exp = getConfig().getInt("default-values.exp", 0);
            return data; // Retourner des données par défaut pour éviter de planter
        }
        return data;
    }

    /**
     * Pour PlaceholderAPI : jamais de requête SQL sur le thread principal. Un joueur pas encore en cache est
     * chargé en tâche de fond (valeurs par défaut en attendant) ; les autres threads lisent directement la base.
     */
    private PlayerData cachedData(String playerName) {
        PlayerData cached = playerCache.get(playerName);
        if (cached != null) {
            return cached;
        }
        if (!Bukkit.isPrimaryThread()) {
            return getPlayerData(playerName);
        }
        if (isEnabled() && loading.add(playerName)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    getPlayerData(playerName);
                } finally {
                    loading.remove(playerName);
                }
            });
        }
        return new PlayerData();
    }

    /**
     * Un joueur hors ligne ne reçoit pas d'événement de départ : son entrée de cache serait périmée dès qu'il
     * change de niveau sur un autre serveur du réseau.
     */
    private void evictIfOffline(OfflinePlayer target, String playerName) {
        if (!target.isOnline()) {
            playerCache.remove(playerName);
        }
    }

    /**
     * Classe interne pour stocker les données d'un joueur.
     */
    private class PlayerData {
        public int level;
        public int exp;

        // Constructeur par défaut pour initialiser
        public PlayerData() {
            this.level = getConfig().getInt("default-values.level", 0);
            this.exp = getConfig().getInt("default-values.exp", 0);
        }
    }

    /**
     * Listener pour charger/décharger les données des joueurs lors de leur connexion/déconnexion.
     */
    private class PlayerListener implements Listener {
        @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onPreLogin(AsyncPlayerPreLoginEvent event) {
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                return;
            }
            // Thread asynchrone : les données sont en cache avant le premier placeholder. Attente bornée pour
            // qu'une base indisponible ne retarde pas la connexion (le chargement continue alors en arrière-plan).
            CompletableFuture<PlayerData> future = CompletableFuture.supplyAsync(() -> getPlayerData(event.getName()));
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Repris par onJoin
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            final String playerName = event.getPlayer().getName();
            // Charger les données de manière asynchrone pour ne pas ralentir la connexion
            Bukkit.getScheduler().runTaskAsynchronously(SHNetworkLevel.this, () -> {
                getPlayerData(playerName); // Cela va charger depuis la DB et mettre en cache si pas déjà fait
                getLogger().info("Données chargées pour " + playerName + " lors de la connexion.");
                // Parti pendant le chargement : l'événement de départ est déjà passé, l'entrée resterait en cache
                if (!event.getPlayer().isOnline()) {
                    playerCache.remove(playerName);
                }
            });
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            String playerName = event.getPlayer().getName();
            // Optionnel: Sauvegarder les données ici si elles ne sont pas sauvegardées régulièrement ailleurs.
            // Actuellement, les données sont sauvegardées à chaque commande de modification.
            // Retirer du cache pour libérer de la mémoire.
            PlayerData data = playerCache.remove(playerName);
            if (data != null) {
                getLogger().info("Données déchargées du cache pour " + playerName + " lors de la déconnexion.");
            }
        }
    }

    /**
     * Joue le son de level-up pour le joueur.
     */
    private void playLevelUpSound(Player player) {
        String soundName = getConfig().getString("sounds.levelup.sound", "ENTITY_PLAYER_LEVELUP");
        float volume = (float) getConfig().getDouble("sounds.levelup.volume", 1.0);
        float pitch = (float) getConfig().getDouble("sounds.levelup.pitch", 1.0);
        Sound sound = sound(soundName);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } else {
            getLogger().warning("Le son configuré pour le levelup est invalide: " + soundName);
        }
    }

    /**
     * Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup).
     */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    /**
     * Expansion PlaceholderAPI pour exposer les placeholders :
     * %shnetworklevel_level%, %shnetworklevel_exp%, %shnetworklevel_expneeded%, %shnetworklevel_progressbar%
     * NON MODIFIÉE SELON LA DEMANDE
     */
    public class SHNetworkLevelPlaceholderExpansion extends PlaceholderExpansion {
        private SHNetworkLevel plugin;

        public SHNetworkLevelPlaceholderExpansion(SHNetworkLevel plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean persist() { // Il est bon de l'avoir pour que PAPI sache que les placeholders peuvent changer
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "shnetworklevel";
        }

        @Override
        public String getAuthor() {
            return "Cry_Legende";
        }

        @Override
        public String getVersion() {
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public String onRequest(OfflinePlayer player, String identifier) {
            if (player == null || player.getName() == null) { // Vérification plus robuste
                return "";
            }
            // Pas d'appel DB sur le thread principal : voir cachedData
            PlayerData data = plugin.cachedData(player.getName());

            if (identifier.equalsIgnoreCase("level")) {
                return String.valueOf(data.level);
            } else if (identifier.equalsIgnoreCase("exp")) {
                return String.valueOf(data.exp);
            } else if (identifier.equalsIgnoreCase("expneeded")) {
                int needed = plugin.getExpForNextLevel(data.level);
                return String.valueOf(needed);
            } else if (identifier.equalsIgnoreCase("progressbar")) {
                int needed = plugin.getExpForNextLevel(data.level);
                return plugin.buildProgressBar(data.exp, needed);
            }
            return null; // Important de retourner null si le placeholder n'est pas reconnu
        }
    }
}
