package org.Cry_Legende.sHNetworkLevel;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bonus d'exp de network des cosmétiques équipés (section bonus de config.yml). Chaque source est un placeholder : elle
 * est active quand il rend autre chose qu'une des valeurs vides. Sans PlaceholderAPI, ou sans le plugin qui fournit le
 * placeholder (il revient alors tel quel), la source reste inactive.
 */
final class CosmeticBonus {

    record Source(String id, String name, String placeholder, int percent) {
    }

    static final CosmeticBonus NONE = new CosmeticBonus(false, Map.of(), Set.of());

    private final boolean enabled;
    private final Map<String, Source> sources;
    private final Set<String> emptyValues;

    private CosmeticBonus(boolean enabled, Map<String, Source> sources, Set<String> emptyValues) {
        this.enabled = enabled;
        this.sources = sources;
        this.emptyValues = emptyValues;
    }

    static CosmeticBonus read(ConfigurationSection section) {
        if (section == null) {
            return NONE;
        }
        Set<String> empty = new TreeSet<>();
        for (String value : section.getStringList("valeurs-vides")) {
            empty.add(value.strip().toLowerCase(Locale.ROOT));
        }
        Map<String, Source> sources = new LinkedHashMap<>();
        ConfigurationSection list = section.getConfigurationSection("sources");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection source = list.getConfigurationSection(id);
                if (source == null) {
                    continue;
                }
                String placeholder = source.getString("placeholder", "").strip();
                int percent = Math.max(0, source.getInt("pourcentage", 0));
                if (!placeholder.isEmpty() && percent > 0) {
                    String key = id.toLowerCase(Locale.ROOT);
                    sources.put(key, new Source(key, source.getString("nom", id), placeholder, percent));
                }
            }
        }
        return new CosmeticBonus(section.getBoolean("actif", true), sources, empty);
    }

    boolean enabled() {
        return enabled && !sources.isEmpty();
    }

    List<Source> sources() {
        return new ArrayList<>(sources.values());
    }

    Source source(String id) {
        return sources.get(id.toLowerCase(Locale.ROOT));
    }

    /** Pourcentage total des sources actives du joueur, 0 si le bonus est coupé. */
    int percent(Player player) {
        if (!enabled() || player == null) {
            return 0;
        }
        int total = 0;
        for (Source source : sources.values()) {
            if (active(player, source)) {
                total += source.percent();
            }
        }
        return total;
    }

    boolean active(Player player, Source source) {
        if (!enabled || player == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return false;
        }
        return counts(source.placeholder(), PlaceholderAPI.setPlaceholders(player, source.placeholder()));
    }

    /** Vrai si le résultat du placeholder veut dire « équipé ». */
    boolean counts(String placeholder, String result) {
        if (result == null || result.equals(placeholder)) {
            return false;
        }
        String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', result)).strip();
        return !plain.isEmpty() && !emptyValues.contains(plain.toLowerCase(Locale.ROOT));
    }
}
