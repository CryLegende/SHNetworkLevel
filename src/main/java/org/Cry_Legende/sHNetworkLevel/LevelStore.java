package org.Cry_Legende.sHNetworkLevel;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Accès à la table shnetworklevel quand plusieurs serveurs écrivent : chaque modification relit la ligne
 * verrouillée avant de la changer, au lieu de réécrire des valeurs gardées en mémoire. Deux serveurs qui donnent
 * de l'exp au même joueur s'additionnent, et chaque niveau franchi l'est par une seule modification.
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
final class LevelStore {

    /** Résultat d'une modification validée. */
    record Change(int oldLevel, int level, int exp) {
    }

    private static final int ATTEMPTS = 3;
    private static final int CHUNK = 200;

    private final DataSource source;

    LevelStore(DataSource source) {
        this.source = source;
    }

    /**
     * Modifie la ligne du joueur sous verrou. La ligne est créée avant avec les valeurs par défaut si elle manque
     * (jamais écrasée), hors transaction : un INSERT IGNORE sur une ligne existante garde un verrou partagé,
     * et deux transactions qui le garderaient en voulant le verrou exclusif s'interbloqueraient.
     *
     * @param change modification appliquée aux valeurs lues ; faux si l'action est refusée
     * @return la modification, ou null si elle a été refusée (rien n'est écrit)
     */
    Change update(String name, int defaultLevel, int defaultExp, Predicate<PlayerData> change) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return updateOnce(name, defaultLevel, defaultExp, change);
            } catch (SQLException e) {
                if (!deadlock(e) || attempt >= ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private Change updateOnce(String name, int defaultLevel, int defaultExp, Predicate<PlayerData> change) throws SQLException {
        try (Connection c = source.getConnection()) {
            insertIfMissing(c, name, defaultLevel, defaultExp);
            c.setAutoCommit(false);
            try {
                PlayerData data;
                try (PreparedStatement ps = c.prepareStatement("SELECT level, exp FROM shnetworklevel WHERE name = ? FOR UPDATE")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Ligne de " + name + " introuvable dans shnetworklevel");
                        }
                        data = new PlayerData(rs.getInt("level"), rs.getInt("exp"));
                    }
                }
                int oldLevel = data.level;
                if (!change.test(data)) {
                    c.rollback();
                    return null;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE shnetworklevel SET level = ?, exp = ? WHERE name = ?")) {
                    ps.setInt(1, data.level);
                    ps.setInt(2, data.exp);
                    ps.setString(3, name);
                    ps.executeUpdate();
                }
                c.commit();
                return new Change(oldLevel, data.level, data.exp);
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // la connexion est déjà perdue : le serveur annule la transaction
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // rendue au pool avec la connexion
                }
            }
        }
    }

    /** Lit le joueur ; sans ligne, elle est créée avec les valeurs par défaut sans écraser une ligne créée entre-temps. */
    PlayerData read(String name, int defaultLevel, int defaultExp) throws SQLException {
        try (Connection c = source.getConnection()) {
            PlayerData data = select(c, name);
            if (data != null) {
                return data;
            }
            insertIfMissing(c, name, defaultLevel, defaultExp);
            data = select(c, name);
            return data != null ? data : new PlayerData(defaultLevel, defaultExp);
        }
    }

    /** Lit plusieurs joueurs d'un coup (clé : pseudo en minuscules). Les joueurs sans ligne sont absents du résultat. */
    Map<String, PlayerData> readAll(Collection<String> names) throws SQLException {
        Map<String, PlayerData> result = new HashMap<>();
        List<String> all = new ArrayList<>(names);
        try (Connection c = source.getConnection()) {
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
                String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("SELECT name, level, exp FROM shnetworklevel WHERE name IN (" + marks + ")")) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.put(rs.getString("name").toLowerCase(Locale.ROOT), new PlayerData(rs.getInt("level"), rs.getInt("exp")));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static PlayerData select(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT level, exp FROM shnetworklevel WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new PlayerData(rs.getInt("level"), rs.getInt("exp")) : null;
            }
        }
    }

    private static void insertIfMissing(Connection c, String name, int level, int exp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO shnetworklevel (name, level, exp) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setInt(2, level);
            ps.setInt(3, exp);
            ps.executeUpdate();
        }
    }

    private static boolean deadlock(SQLException e) {
        return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213;
    }
}
