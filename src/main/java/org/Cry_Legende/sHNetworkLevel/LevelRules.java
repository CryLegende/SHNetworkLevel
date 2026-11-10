package org.Cry_Legende.sHNetworkLevel;

import java.util.function.IntUnaryOperator;

/**
 * Calcul des commandes /shnetworklevel exp et level sur des données déjà lues. Le même calcul sert avec et sans SHNetwork.
 */
final class LevelRules {

    private LevelRules() {
    }

    static boolean validAction(String action) {
        return action.equals("give") || action.equals("take") || action.equals("set");
    }

    /**
     * Applique give, take ou set sur l'exp. Un give et un set font monter de niveau tant que l'exp atteint le seuil.
     *
     * @param needed exp nécessaire pour passer du niveau donné au suivant
     * @return faux si l'action est inconnue (rien n'est modifié)
     */
    static boolean applyExp(PlayerData data, String action, int amount, IntUnaryOperator needed) {
        if (action.equals("give")) {
            data.exp += amount;
            levelUp(data, needed);
        } else if (action.equals("take")) {
            data.exp = Math.max(data.exp - amount, 0);
        } else if (action.equals("set")) {
            data.exp = Math.max(0, amount);
            levelUp(data, needed);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Applique give, take ou set sur le niveau, sans toucher à l'exp.
     *
     * @return faux si l'action est inconnue (rien n'est modifié)
     */
    static boolean applyLevel(PlayerData data, String action, int amount) {
        if (action.equals("give")) {
            data.level += amount;
        } else if (action.equals("take")) {
            data.level = Math.max(0, data.level - amount);
        } else if (action.equals("set")) {
            data.level = Math.max(0, amount);
        } else {
            return false;
        }
        return true;
    }

    private static void levelUp(PlayerData data, IntUnaryOperator needed) {
        while (data.exp >= needed.applyAsInt(data.level)) {
            data.exp -= needed.applyAsInt(data.level);
            data.level++;
        }
    }
}
