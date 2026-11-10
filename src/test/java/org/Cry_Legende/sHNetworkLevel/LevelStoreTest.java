package org.Cry_Legende.sHNetworkLevel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Les modifications sous verrou sur un vrai MariaDB : deux serveurs qui donnent de l'exp au même joueur
 * s'additionnent, et chaque niveau franchi l'est par une seule modification.
 * Ignoré sans SHNL_TEST_URL (ex. jdbc:mariadb://127.0.0.1:3399/shnl_test), SHNL_TEST_USER et SHNL_TEST_PASSWORD.
 * La base doit s'appeler *test* : le test recrée la table shnetworklevel.
 */
class LevelStoreTest {

    private HikariDataSource pool;
    private LevelStore store;

    private static int needed(int level) {
        return 100 + level * 50;
    }

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("SHNL_TEST_URL");
        Assumptions.assumeTrue(url != null, "SHNL_TEST_URL absent : test MariaDB ignoré");
        Assumptions.assumeTrue(url.substring(url.lastIndexOf('/') + 1).contains("test"), "la base de test doit s'appeler *test*");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(System.getenv().getOrDefault("SHNL_TEST_USER", "shnl"));
        hikari.setPassword(System.getenv().getOrDefault("SHNL_TEST_PASSWORD", "shnl"));
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setMaximumPoolSize(16);
        pool = new HikariDataSource(hikari);
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS shnetworklevel");
            st.executeUpdate("CREATE TABLE shnetworklevel (name VARCHAR(16) NOT NULL PRIMARY KEY, level INT NOT NULL, exp INT NOT NULL)");
        }
        store = new LevelStore(pool);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void desDonsDepuisDeuxServeursSAdditionnent() throws Exception {
        int threads = 6;
        int gives = 25;
        int amount = 13;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> crossed = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            // Les deux serveurs écrivent la même ligne, qui n'existe pas encore au départ
            Callable<Integer> task = () -> {
                int levels = 0;
                for (int i = 0; i < gives; i++) {
                    LevelStore.Change change = store.update("Joueur", 0, 0, d -> LevelRules.applyExp(d, "give", amount, LevelStoreTest::needed));
                    levels += change.level() - change.oldLevel();
                }
                return levels;
            };
            crossed.add(executor.submit(task));
        }
        int levelsSeen = 0;
        for (Future<Integer> f : crossed) {
            levelsSeen += f.get();
        }
        executor.shutdown();

        PlayerData expected = new PlayerData(0, 0);
        LevelRules.applyExp(expected, "give", threads * gives * amount, LevelStoreTest::needed);
        PlayerData stored = store.read("Joueur", 0, 0);
        assertEquals(expected.level, stored.level);
        assertEquals(expected.exp, stored.exp);
        // Chaque niveau franchi l'a été par une seule modification : récompensé une seule fois
        assertEquals(expected.level, levelsSeen);
    }

    @Test
    void uneActionRefuseeNEcritRien() throws Exception {
        store.update("Joueur", 3, 20, d -> true);
        assertNull(store.update("Joueur", 3, 20, d -> LevelRules.applyExp(d, "reset", 5, LevelStoreTest::needed)));
        PlayerData stored = store.read("Joueur", 0, 0);
        assertEquals(3, stored.level);
        assertEquals(20, stored.exp);
    }

    @Test
    void laLectureCreeLaLigneSansEcraserUneLigneExistante() throws Exception {
        PlayerData created = store.read("Nouveau", 1, 5);
        assertEquals(1, created.level);
        assertEquals(5, created.exp);
        store.update("Nouveau", 1, 5, d -> LevelRules.applyLevel(d, "set", 9));
        PlayerData again = store.read("Nouveau", 1, 5);
        assertEquals(9, again.level);
    }

    @Test
    void lectureGroupeeParPseudoEnMinuscules() throws Exception {
        store.update("Alpha", 0, 0, d -> LevelRules.applyLevel(d, "set", 2));
        store.update("Beta", 0, 0, d -> LevelRules.applyLevel(d, "set", 4));
        Map<String, PlayerData> rows = store.readAll(List.of("Alpha", "Beta", "Absent"));
        assertEquals(2, rows.get("alpha").level);
        assertEquals(4, rows.get("beta").level);
        assertEquals(2, rows.size());
    }
}
