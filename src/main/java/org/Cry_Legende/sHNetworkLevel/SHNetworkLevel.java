package org.Cry_Legende.sHNetworkLevel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID; // Importation nécessaire pour getUniqueId
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.Cry_Legende.sHNetworkLevel.network.NetBus;
import org.Cry_Legende.sHNetworkLevel.network.ShNetworkBus;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
    // Non nul seulement avec SHNetwork : écritures sous verrou, cache relu quand un autre serveur écrit, niveaux annoncés où est le joueur
    private NetworkLevels net;
    // Textes de messages.yml, remplacés d'un bloc à chaque chargement
    private volatile YamlConfiguration messages = new YamlConfiguration();
    // Bonus des cosmétiques équipés (section bonus), relu au reload
    private volatile CosmeticBonus bonus = CosmeticBonus.NONE;
    // Fraction d'exp de bonus pas encore donnée, par joueur connecté
    private final ConcurrentHashMap<String, Double> bonusCarry = new ConcurrentHashMap<>();

    private static final String MESSAGES_FILE = "messages.yml";

    @Override
    public void onEnable() {
        // Sauvegarde de la config par défaut si elle n'existe pas
        saveDefaultConfig();
        loadMessages();
        FileConfiguration config = getConfig();
        bonus = CosmeticBonus.read(config.getConfigurationSection("bonus"));

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

        enableNetwork();
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private void enableNetwork() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        NetworkLevels levels = null;
        try {
            NetBus bus = ShNetworkBus.open();
            levels = new NetworkLevels(this, bus, new LevelStore(dataSource),
                config.getString("reseau.canal", "networklevel"), config.getInt("reseau.relecture-secondes", 60));
            net = levels;
            levels.enable();
            getLogger().info("SHNetwork détecté : modifications sous verrou, niveaux annoncés sur le serveur du joueur.");
        } catch (RuntimeException | LinkageError e) {
            net = null;
            if (levels != null) levels.disable();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, fonctionnement local à ce serveur : " + e);
        }
    }

    @Override
    public void onDisable() {
        if (net != null) {
            net.disable();
        }
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

    private static final List<String> SUBCOMMANDS = List.of("exp", "level", "info", "reload");
    private static final String PERMISSION_PREFIX = "shnetworklevel.admin.";

    private static boolean can(CommandSender sender, String subCommand) {
        return sender.hasPermission(PERMISSION_PREFIX + subCommand);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    // Traitement de la commande /shnetworklevel : exp, level, info et reload, chacune avec sa permission
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!SUBCOMMANDS.contains(sub)) {
            sendUsage(sender);
            return true;
        }
        if (!can(sender, sub)) {
            send(sender, msg("no-permission", "&cVous n'avez pas la permission d'exécuter cette commande."));
            return true;
        }

        if (sub.equals("reload")) {
            if (reloadFiles(sender)) {
                send(sender, msg("reload-done", "&7&l« &a&lSH &8&l» &7Configuration &arechargée&7."));
            }
            return true;
        }

        if (sub.equals("exp")) {
            if (args.length != 4) {
                send(sender, msg("usage.exp", "&cUsage: /shnetworklevel exp <give/take/set> <player> <number>"));
                return true;
            }
            final String action = args[1].toLowerCase();
            final String targetNameInput = args[2]; // Nom tapé par l'utilisateur
            final int amount;
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                send(sender, msg("invalid-number", "&cLe nombre fourni est invalide."));
                return true;
            }
            // Récompense donnée par la console à un joueur connecté ici : bonus de ses cosmétiques équipés
            int given = amount;
            int percent = 0;
            Player rewarded = action.equals("give") && amount > 0 && !(sender instanceof Player)
                ? Bukkit.getPlayerExact(targetNameInput) : null;
            if (rewarded != null) {
                percent = bonus.percent(rewarded);
                if (percent > 0) {
                    LevelRules.Boost boost = LevelRules.boost(amount, percent, bonusCarry.getOrDefault(rewarded.getName(), 0.0));
                    bonusCarry.put(rewarded.getName(), boost.carry());
                    given = boost.amount();
                }
            }
            changeAsync(sender, "exp", action, targetNameInput, given, percent);
            return true;

        } else if (sub.equals("level")) {
            if (args.length != 4) {
                send(sender, msg("usage.level", "&cUsage: /shnetworklevel level <give/take/set> <player> <number>"));
                return true;
            }
            final String action = args[1].toLowerCase();
            final String targetNameInput = args[2];
            final int amount;
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 0) {
                    send(sender, msg("invalid-number-positive", "&cLe niveau doit être un nombre positif ou nul."));
                    return true;
                }
            } catch (NumberFormatException e) {
                send(sender, msg("invalid-number", "&cLe nombre fourni est invalide."));
                return true;
            }
            changeAsync(sender, "level", action, targetNameInput, amount, 0);
            return true;

        } else {
            if (args.length != 2) {
                send(sender, msg("usage.info", "&cUsage: /shnetworklevel info <player>"));
                return true;
            }
            final String targetNameInput = args[1];

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                final Target target = resolveTarget(targetNameInput);
                if (target == null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        send(sender, msg("player-not-found", "&cLe joueur '%player%' n'a pas été trouvé ou n'a jamais joué.")
                            .replace("%player%", targetNameInput));
                    });
                    return;
                }
                final String playerName = target.name();

                // Avec SHNetwork, la base fait foi : le cache peut avoir un message de retard
                PlayerData data = net != null ? net.fresh(playerName) : getPlayerData(playerName); // Cet appel est maintenant dans le thread async
                String msgTemplate = msg("info", "&aInfos pour %player%: Niveau %level%, Exp %exp%/%expneeded%");
                final int expNeeded = getExpForNextLevel(data.level);

                final String formattedMsg = msgTemplate.replace("%player%", playerName) // Utiliser le nom canonique pour l'info
                    .replace("%level%", String.valueOf(data.level))
                    .replace("%exp%", String.valueOf(data.exp))
                    .replace("%expneeded%", String.valueOf(expNeeded))
                    .replace("%progressbar%", buildProgressBar(data.exp, expNeeded)); // Ajout de la progress bar

                Bukkit.getScheduler().runTask(this, () -> {
                    send(sender, formattedMsg);
                    if (net == null) evictIfOffline(target.offline(), playerName);
                });
            });
            return true;
        }
    }

    /** Une seule ligne d'usage, limitée aux sous-commandes que l'expéditeur peut utiliser. */
    private void sendUsage(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (can(sender, sub)) {
                allowed.add(sub);
            }
        }
        if (allowed.isEmpty()) {
            send(sender, msg("no-permission", "&cVous n'avez pas la permission d'exécuter cette commande."));
            return;
        }
        send(sender, msg("usage.list", "&7&l« &e&lSH &8&l» &7Usage: &e/shnetworklevel <%subcommands%>")
            .replace("%subcommands%", String.join("/", allowed)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (can(sender, sub)) {
                    completions.add(sub);
                }
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (SUBCOMMANDS.contains(sub) && can(sender, sub)) {
                boolean change = sub.equals("exp") || sub.equals("level");
                if (change && args.length == 2) {
                    completions.addAll(List.of("give", "take", "set"));
                } else if ((change && args.length == 3) || (sub.equals("info") && args.length == 2)) {
                    Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
                }
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        completions.removeIf(name -> !name.toLowerCase(Locale.ROOT).startsWith(typed));
        return completions;
    }

    /** Texte de messages.yml (chemin sans préfixe), ou le texte donné si la clé manque partout. */
    private String msg(String path, String fallback) {
        String value = messages.getString(path);
        return value != null ? value : fallback;
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), MESSAGES_FILE);
        if (!file.exists()) {
            saveResource(MESSAGES_FILE, false);
            migrateMessagesFromConfig(file);
        }
        applyMessages(YamlConfiguration.loadConfiguration(file));
    }

    /**
     * Premier démarrage après la mise à jour : messages.yml est créé à partir du jar, puis les textes de l'ancienne
     * section messages: de config.yml (ceux du serveur) y sont reportés. Cette section n'est plus lue ensuite.
     */
    private void migrateMessagesFromConfig(File target) {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            return;
        }
        ConfigurationSection old = YamlConfiguration.loadConfiguration(configFile).getConfigurationSection("messages");
        if (old == null) {
            return;
        }
        YamlConfiguration created = YamlConfiguration.loadConfiguration(target);
        int moved = 0;
        for (String key : old.getKeys(true)) {
            if (!old.isConfigurationSection(key) && created.contains(key) && !created.isConfigurationSection(key)) {
                created.set(key, old.get(key));
                moved++;
            }
        }
        try {
            created.save(target);
            getLogger().info("messages.yml créé : " + moved + " texte(s) repris de la section messages de config.yml.");
        } catch (IOException e) {
            getLogger().warning("messages.yml créé sans les textes de config.yml : " + e.getMessage());
        }
    }

    /** Remplace les messages ; les clés absentes du fichier du serveur reprennent le texte du jar. */
    private void applyMessages(YamlConfiguration loaded) {
        try (Reader defaults = new InputStreamReader(getResource(MESSAGES_FILE), StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        } catch (IOException | RuntimeException e) {
            getLogger().warning("messages.yml du jar illisible : " + e.getMessage());
        }
        messages = loaded;
    }

    /**
     * Relit config.yml et messages.yml. Si l'un des deux est invalide, rien n'est appliqué : l'ancienne configuration
     * reste en place et l'expéditeur reçoit le fichier et l'erreur. Ne touche ni au cache des joueurs ni à la base ;
     * database.* et reseau.* demandent un redémarrage.
     *
     * @return vrai si tout a été rechargé
     */
    private boolean reloadFiles(CommandSender sender) {
        YamlConfiguration newMessages = parse(sender, MESSAGES_FILE);
        if (newMessages == null || parse(sender, "config.yml") == null) {
            return false;
        }
        reloadConfig();
        applyMessages(newMessages);
        bonus = CosmeticBonus.read(getConfig().getConfigurationSection("bonus"));
        return true;
    }

    private YamlConfiguration parse(CommandSender sender, String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), name));
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Rechargement annulé, " + name + " est invalide : " + e.getMessage());
            String text = String.valueOf(e.getMessage()).strip();
            int end = text.indexOf('\n');
            String error = end < 0 ? text : text.substring(0, end).strip();
            send(sender, msg("reload-failed", "&cRechargement annulé : %file% est invalide (%error%).")
                .replace("%file%", name)
                .replace("%error%", error.length() > 160 ? error.substring(0, 160) + "..." : error));
            return null;
        }
    }

    /** Joueur visé par une commande : son pseudo exact et, quand il est connu de ce serveur, son profil. */
    private record Target(String name, OfflinePlayer offline) {
    }

    /**
     * Retrouve le joueur visé. Avec SHNetwork, un joueur connecté sur un autre serveur est trouvé par la présence du
     * réseau : ce serveur ne le connaît pas forcément. Sinon (et sur un serveur seul), comme avant : profil du serveur.
     */
    private Target resolveTarget(String input) {
        if (net != null) {
            Optional<String> online = net.canonicalName(input);
            if (online.isPresent()) {
                return new Target(online.get(), null);
            }
        }
        final OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        // Vérifier si le joueur a un profil (a déjà joué ou est un joueur valide)
        if (offline == null || offline.getUniqueId() == null || offline.getName() == null) {
            return null;
        }
        return new Target(offline.getName(), offline); // Nom canonique du joueur
    }

    /** Joueur visé s'il est connecté à ce serveur. */
    private Player onlinePlayer(Target target) {
        if (target.offline() != null && target.offline().isOnline()) {
            Player online = target.offline().getPlayer();
            if (online != null) {
                return online;
            }
        }
        return net != null ? Bukkit.getPlayerExact(target.name()) : null;
    }

    /**
     * Commandes exp et level (give, take, set) : calcul, écriture en base, puis messages et récompenses.
     * Sans SHNetwork, les valeurs du cache sont modifiées puis réécrites (REPLACE). Avec SHNetwork, la ligne est
     * relue sous verrou et modifiée, pour que deux serveurs qui écrivent en même temps s'additionnent.
     */
    private void changeAsync(CommandSender sender, String kind, String action, String targetNameInput, int amount, int bonusPercent) {
        final String usageKey = "usage." + kind;
        final String usageDefault = "&cUsage: /shnetworklevel " + kind + " <give/take/set> <player> <number>";
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            final Target target = resolveTarget(targetNameInput);
            if (target == null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        msg("player-not-found", "&cLe joueur '%player%' n'a pas été trouvé ou n'a jamais joué.")
                            .replace("%player%", targetNameInput)));
                });
                return;
            }
            final String playerName = target.name();
            final Predicate<PlayerData> change = data -> kind.equals("exp")
                ? LevelRules.applyExp(data, action, amount, this::getExpForNextLevel)
                : LevelRules.applyLevel(data, action, amount);

            final int oldLevel;
            final int newLevel;
            if (net != null) {
                if (!LevelRules.validAction(action)) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(usageKey, usageDefault)));
                    });
                    return;
                }
                final LevelStore.Change done;
                try {
                    done = net.update(playerName, change);
                } catch (SQLException e) {
                    databaseError(sender, playerName, e);
                    return;
                }
                if (done == null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(usageKey, usageDefault)));
                    });
                    return;
                }
                oldLevel = done.oldLevel();
                newLevel = done.level();
            } else {
                final PlayerData data = getPlayerData(playerName); // Expérience actuelle conservée par la commande level
                oldLevel = data.level; // Pour la comparaison après modification
                if (!change.test(data)) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg(usageKey, usageDefault)));
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
                    databaseError(sender, playerName, e);
                    return; // Stopper si la DB échoue
                }
                newLevel = data.level;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                String msgKey = kind + "-" + (action.equals("give") ? "given" : action.equals("take") ? "taken" : "set");
                String msgTemplate = msg(msgKey, "&aCommande exécutée.");
                final String formattedMsg = msgTemplate.replace("%player%", targetNameInput) // Utiliser le nom tapé par l'admin
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%bonus%", bonusText(bonusPercent));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedMsg));

                // Si le level a augmenté (give, ou set d'exp/de niveau plus élevé) : notifier et récompenser le joueur
                if (newLevel > oldLevel) {
                    final Player online = onlinePlayer(target);
                    final boolean elsewhere = online == null && net != null && net.onlineElsewhere(playerName);
                    for (int lvl = oldLevel + 1; lvl <= newLevel; lvl++) {
                        String lvlMsgTemplate = msg("level-up", "&eFélicitations %player% ! Vous êtes passé au niveau %level% !");
                        final String formattedLvlMsg = lvlMsgTemplate.replace("%player%", playerName) // Utiliser le nom canonique
                            .replace("%level%", String.valueOf(lvl));

                        if (!(sender instanceof Player) || !((Player) sender).getName().equals(playerName)) {
                            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                        }

                        if (online != null) {
                            online.sendMessage(ChatColor.translateAlternateColorCodes('&', formattedLvlMsg));
                            playLevelUpSound(online);
                            executeRewardCommands(online, lvl);
                        } else if (elsewhere) {
                            // Connecté sur un autre serveur : il y reçoit le message, le son et la récompense (une seule fois)
                            net.levelUpElsewhere(playerName, lvl, rewardCommands(playerName, lvl));
                        }
                    }
                }
                if (net == null) evictIfOffline(target.offline(), playerName);
            });
        });
    }

    /** « (+15%) » quand le bonus est positif (bonus.texte), sinon bonus.texte-aucun. */
    String bonusText(int percent) {
        return percent > 0
            ? msg("bonus.texte", " (+%bonus%%)").replace("%bonus%", String.valueOf(percent))
            : msg("bonus.texte-aucun", "");
    }

    /**
     * Placeholders du bonus, sans lecture de la base : bonus, bonus_texte, bonus_&lt;source&gt; (pourcentage si équipée,
     * sinon 0) et bonus_&lt;source&gt;_etat (ligne de menu). Rend null pour un autre identifiant.
     */
    String bonusPlaceholder(Player player, String identifier) {
        String id = identifier.toLowerCase(Locale.ROOT);
        if (id.equals("bonus")) {
            return String.valueOf(bonus.percent(player));
        }
        if (id.equals("bonus_texte")) {
            return bonusText(bonus.percent(player));
        }
        if (!id.startsWith("bonus_")) {
            return null;
        }
        boolean state = id.endsWith("_etat");
        String sourceId = id.substring("bonus_".length(), state ? id.length() - "_etat".length() : id.length());
        CosmeticBonus.Source source = bonus.source(sourceId);
        if (source == null) {
            return null;
        }
        boolean active = bonus.active(player, source);
        if (!state) {
            return String.valueOf(active ? source.percent() : 0);
        }
        String line = active
            ? msg("bonus.source-active", "&a✔ &f%nom% &a+%pourcentage%%")
            : msg("bonus.source-inactive", "&8✘ &7%nom% &8+%pourcentage%%");
        return line.replace("%nom%", source.name()).replace("%pourcentage%", String.valueOf(source.percent()));
    }

    private void databaseError(CommandSender sender, String playerName, SQLException e) {
        getLogger().severe("Erreur lors de la mise à jour des données pour " + playerName + ": " + e.getMessage());
        e.printStackTrace();
        // Envoyer un message d'erreur à l'admin est une bonne idée ici aussi
        Bukkit.getScheduler().runTask(this, () -> {
            sender.sendMessage(ChatColor.RED + "Une erreur de base de données est survenue. Veuillez vérifier la console.");
        });
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
        for (String cmd : rewardCommands(player.getName(), newLevel)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    /**
     * Commandes de récompense d'un level-up, avec le pseudo et le niveau remplacés.
     */
    List<String> rewardCommands(String playerName, int newLevel) {
        FileConfiguration config = getConfig();
        List<String> source = List.of();
        // Vérifier si la section existe et est une liste
        if (!config.isConfigurationSection("rewards." + newLevel) && !config.isList("rewards.levelup-commands")) {
            // Tenter de lire une liste globale si pas de section spécifique au niveau
            if (config.isList("rewards.default-levelup-commands")) { // Nom suggéré pour commandes globales
                source = config.getStringList("rewards.default-levelup-commands");
            }
        } else if (config.isConfigurationSection("rewards." + newLevel)) {
            // Logique pour les récompenses par niveau spécifique
            if (config.isList("rewards." + newLevel + ".commands")) {
                source = config.getStringList("rewards." + newLevel + ".commands");
            }
        } else if (config.isList("rewards.levelup-commands")) { // Liste globale
            source = config.getStringList("rewards.levelup-commands");
        }
        List<String> commands = new ArrayList<>();
        for (String command : source) {
            commands.add(command.replace("%player%", playerName).replace("%level%", String.valueOf(newLevel)));
        }
        return commands;
    }

    /**
     * Message et son de level-up d'un joueur de ce serveur.
     */
    void announceLevelUp(Player player, int level) {
        String template = msg("level-up", "&eFélicitations %player% ! Vous êtes passé au niveau %level% !");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            template.replace("%player%", player.getName()).replace("%level%", String.valueOf(level))));
        playLevelUpSound(player);
    }

    /** Cache des joueurs (clé : pseudo). */
    ConcurrentHashMap<String, PlayerData> cache() {
        return playerCache;
    }

    /** Valeurs d'un joueur sans ligne en base. */
    PlayerData defaults() {
        return new PlayerData(getConfig().getInt("default-values.level", 0), getConfig().getInt("default-values.exp", 0));
    }

    /**
     * Récupère (ou crée) les données d'un joueur en base et les met en cache.
     * Cette méthode peut effectuer des opérations de base de données et doit être appelée de manière asynchrone
     * si elle est appelée depuis le thread principal et que le joueur n'est pas en cache.
     */
    PlayerData getPlayerData(String playerName) {
        PlayerData cached = playerCache.get(playerName);
        if (cached != null) {
            return cached;
        }
        if (net != null) {
            return net.cachedOrLoad(playerName);
        }
        PlayerData data = defaults();
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
        // Vrai aussi sur les fils de monde quand les mondes tournent en parallèle : aucun SQL sur un fil de tick
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
        return defaults();
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
            bonusCarry.remove(playerName);
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
     * %shnetworklevel_level%, %shnetworklevel_exp%, %shnetworklevel_expneeded%, %shnetworklevel_progressbar%, et ceux du
     * bonus des cosmétiques (voir bonusPlaceholder).
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
            if (identifier.toLowerCase(Locale.ROOT).startsWith("bonus")) {
                // Cosmétiques lus sur le joueur connecté ici ; hors ligne, aucun bonus
                return plugin.bonusPlaceholder(player.getPlayer(), identifier);
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
