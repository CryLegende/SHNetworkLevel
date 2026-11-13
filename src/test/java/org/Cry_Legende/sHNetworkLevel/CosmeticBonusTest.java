package org.Cry_Legende.sHNetworkLevel;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticBonusTest {

    private static CosmeticBonus read(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return CosmeticBonus.read(config.getConfigurationSection("bonus"));
    }

    private static final String CONFIG = """
        bonus:
          actif: true
          valeurs-vides:
            - "0"
            - "aucun"
          sources:
            titre:
              nom: "Titre"
              placeholder: "%shtitres_id%"
              pourcentage: 5
            Particules:
              placeholder: "%playerparticles_active_amount%"
              pourcentage: 5
            vide:
              placeholder: "%x%"
              pourcentage: 0
        """;

    @Test
    void litLesSourcesValides() throws InvalidConfigurationException {
        CosmeticBonus bonus = read(CONFIG);
        assertTrue(bonus.enabled());
        assertEquals(2, bonus.sources().size());
        assertEquals("Titre", bonus.source("titre").name());
        assertEquals("particules", bonus.source("PARTICULES").id());
        assertNull(bonus.source("vide"));
    }

    @Test
    void reconnaitUnCosmetiqueEquipe() throws InvalidConfigurationException {
        CosmeticBonus bonus = read(CONFIG);
        assertTrue(bonus.counts("%shtitres_id%", "skyhand"));
        assertTrue(bonus.counts("%playerparticles_active_amount%", "2"));
        assertFalse(bonus.counts("%playerparticles_active_amount%", "0"));
        assertFalse(bonus.counts("%shtitres_id%", ""));
        assertFalse(bonus.counts("%shtitres_id%", "  &7 "));
        assertFalse(bonus.counts("%shtitres_id%", "Aucun"));
        // Placeholder rendu tel quel : le plugin qui le fournit est absent
        assertFalse(bonus.counts("%shtitres_id%", "%shtitres_id%"));
        assertFalse(bonus.counts("%shtitres_id%", null));
    }

    @Test
    void sansSectionOuCoupeRienNeCompte() throws InvalidConfigurationException {
        assertFalse(CosmeticBonus.read(null).enabled());
        assertFalse(read(CONFIG.replace("actif: true", "actif: false")).enabled());
        assertEquals(0, read(CONFIG).percent(null));
    }
}
