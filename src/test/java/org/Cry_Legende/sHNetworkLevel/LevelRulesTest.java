package org.Cry_Legende.sHNetworkLevel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelRulesTest {

    // Progression linéaire : 100 d'exp pour le niveau 1, puis 50 de plus à chaque niveau
    private static int needed(int level) {
        return 100 + level * 50;
    }

    @Test
    void giveFranchitPlusieursNiveaux() {
        PlayerData data = new PlayerData(0, 0);
        assertTrue(LevelRules.applyExp(data, "give", 1000, LevelRulesTest::needed));
        // 100 + 150 + 200 + 250 + 300 = 1000 : cinq niveaux, exp à zéro
        assertEquals(5, data.level);
        assertEquals(0, data.exp);
    }

    @Test
    void giveGardeLeReste() {
        PlayerData data = new PlayerData(2, 40);
        LevelRules.applyExp(data, "give", 200, LevelRulesTest::needed);
        // 240 d'exp au niveau 2 (seuil 200) : niveau 3 avec 40
        assertEquals(3, data.level);
        assertEquals(40, data.exp);
    }

    @Test
    void takeNeDescendPasSousZeroEtGardeLeNiveau() {
        PlayerData data = new PlayerData(3, 30);
        LevelRules.applyExp(data, "take", 500, LevelRulesTest::needed);
        assertEquals(3, data.level);
        assertEquals(0, data.exp);
    }

    @Test
    void setExpMonteDeNiveau() {
        PlayerData data = new PlayerData(0, 10);
        LevelRules.applyExp(data, "set", 250, LevelRulesTest::needed);
        // 250 : niveau 1 (100), reste 150 : niveau 2 (150), reste 0
        assertEquals(2, data.level);
        assertEquals(0, data.exp);
    }

    @Test
    void niveauSansToucherALExp() {
        PlayerData data = new PlayerData(4, 77);
        LevelRules.applyLevel(data, "give", 3);
        assertEquals(7, data.level);
        LevelRules.applyLevel(data, "take", 10);
        assertEquals(0, data.level);
        LevelRules.applyLevel(data, "set", 12);
        assertEquals(12, data.level);
        assertEquals(77, data.exp);
    }

    @Test
    void actionInconnueNeModifieRien() {
        PlayerData data = new PlayerData(4, 77);
        assertFalse(LevelRules.applyExp(data, "reset", 5, LevelRulesTest::needed));
        assertFalse(LevelRules.applyLevel(data, "reset", 5));
        assertFalse(LevelRules.validAction("reset"));
        assertEquals(4, data.level);
        assertEquals(77, data.exp);
    }
}
