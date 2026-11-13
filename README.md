<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHNetworkLevel

*Niveau de network partagé entre les serveurs (exp et niveaux en MariaDB), avec placeholders.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale%20%C2%B7%20Lobby-3aa655?style=for-the-badge" alt="SkyHand Opale · Lobby">

</div>

---

## Présentation

Niveau de network partagé entre les serveurs (exp et niveaux en MariaDB), avec placeholders.

Utilisé sur : **Opale · Lobby**.

## Dépendances

- Optionnelles : `PlaceholderAPI`, `SHNetwork`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shnetworklevel exp <give\|take\|set> <joueur> <nombre>` | — | Modifie l'exp. Un `give` ou un `set` fait monter de niveau tant que l'exp atteint le seuil. | `shnetworklevel.admin.exp` |
| `/shnetworklevel level <give\|take\|set> <joueur> <nombre>` | — | Modifie le niveau sans toucher à l'exp. | `shnetworklevel.admin.level` |
| `/shnetworklevel info <joueur>` | — | Niveau et exp du joueur. | `shnetworklevel.admin.info` |
| `/shnetworklevel reload` | — | Recharge `config.yml` et `messages.yml` ; si l'un est invalide, rien ne change et le message donne le fichier et l'erreur. `database` et `reseau` demandent un redémarrage. | `shnetworklevel.admin.reload` |

Toutes marchent depuis la console : d'autres plugins lancent `shnetworklevel exp give <joueur> <nombre>` pour leurs récompenses. `/shnetworklevel` sans argument donne une ligne d'usage limitée aux sous-commandes autorisées.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shnetworklevel.admin` | Toutes les sous-commandes (parent des quatre suivantes). | `op` |
| `shnetworklevel.admin.exp` | `/shnetworklevel exp` | `op` |
| `shnetworklevel.admin.level` | `/shnetworklevel level` | `op` |
| `shnetworklevel.admin.info` | `/shnetworklevel info` | `op` |
| `shnetworklevel.admin.reload` | `/shnetworklevel reload` | `op` |

Placeholders : `%shnetworklevel_level%`, `%shnetworklevel_exp%`, `%shnetworklevel_expneeded%`, `%shnetworklevel_progressbar%`, et ceux du bonus ci-dessous.

## Bonus des cosmétiques

Chaque cosmétique équipé ajoute un pourcentage à l'exp gagnée (section `bonus` de `config.yml` : +5 % pour un titre, +5 % pour un compagnon, +5 % pour des particules, donc +15 % avec les trois). Une source est un placeholder : elle compte quand il rend autre chose qu'un texte vide ou qu'une des `valeurs-vides` (`%shtitres_id%`, `%shcompagnons_current_pet_type%`, `%playerparticles_active_amount%`). Sans PlaceholderAPI ou sans le plugin du placeholder, elle ne compte pas.

- Le bonus s'applique à `exp give` lancé par la console (les récompenses des autres plugins) pour un joueur connecté sur ce serveur. Une commande tapée par un joueur donne le nombre exact.
- Les fractions ne se perdent pas : +1 avec 15 % donne 1, et la septième fois 2. Elles sont gardées jusqu'à la déconnexion.
- Les messages de gain des autres plugins affichent le bonus avec `%shnetworklevel_bonus_texte%` juste après « exp de network » : `&b+10 exp de network%shnetworklevel_bonus_texte% &7(/network)` donne « +10 exp de network (+15%) (/network) ».

| Placeholder | Rend |
|---|---|
| `%shnetworklevel_bonus%` | Bonus total en pourcentage (`15`) |
| `%shnetworklevel_bonus_texte%` | ` (+15%)`, vide sans bonus (`bonus.texte` et `bonus.texte-aucun` de `messages.yml`) |
| `%shnetworklevel_bonus_<source>%` | Pourcentage de la source si elle est équipée, sinon `0` |
| `%shnetworklevel_bonus_<source>_etat%` | Ligne de menu `✔ Titre +5%` ou `✘ Titre +5%` (`bonus.source-active` et `bonus.source-inactive`) |

À chaque niveau gagné, le joueur reçoit le message `level-up` de `messages.yml`, entend le son de `sounds.levelup` et les commandes de `rewards` sont exécutées pour lui. Un joueur hors ligne n'en reçoit pas.

## Plusieurs serveurs avec SHNetwork

Les niveaux sont déjà dans une base commune à tous les serveurs (table `shnetworklevel`, une ligne par pseudo). Seul, le plugin garde les valeurs du joueur en cache et les réécrit en entier à chaque commande : c'est ce qui se passe sur le lobby et sur un serveur seul.

Quand SHNetwork est installé (et `reseau.actif: true`), le plugin change de fonctionnement :

- **Écritures sous verrou.** Chaque `exp` et `level` relit la ligne du joueur (`SELECT ... FOR UPDATE`), applique la modification puis l'écrit (`UPDATE`), dans une transaction. De l'exp donnée depuis deux serveurs en même temps s'additionne, et chaque niveau franchi l'est par une seule commande : sa récompense n'est donnée qu'une fois. La ligne d'un joueur inconnu est créée avec les valeurs par défaut, sans jamais écraser une ligne existante.
- **Cache relu.** Après chaque écriture, le serveur prévient tous les autres (canal `reseau.canal`) : ceux où le joueur est connecté relisent sa ligne. Une lecture plus ancienne qui finit après une plus récente ne la remplace jamais. Tous les `reseau.relecture-secondes`, les joueurs connectés sont relus en une requête au cas où un message se perdrait. `/shnetworklevel info` lit toujours la base.
- **Joueur sur un autre serveur.** La commande retrouve le joueur par la présence du réseau, même s'il n'a jamais joué sur ce serveur. S'il est connecté ailleurs, son serveur lui affiche le message de level-up et joue le son, et les commandes de `rewards` sont exécutées là où il est, une seule fois (`runFor`).
- **Pas de barrière de passage.** Aucune donnée n'est gardée en attente : chaque commande est écrite en base avant de rendre la main, un joueur qui change de serveur retrouve donc ses valeurs.

| Clé | Rôle |
|---|---|
| `reseau.actif` | `false` garde le fonctionnement d'un serveur seul même avec SHNetwork. |
| `reseau.canal` | Canal du bus SHNetwork où les serveurs se préviennent d'une modification et d'un niveau gagné. |
| `reseau.relecture-secondes` | Relecture en base des joueurs connectés. |

La base est celle de la section `database` de `config.yml` (déjà utilisée sans SHNetwork) : aucune table nouvelle. Ces clés demandent un redémarrage. Sans SHNetwork, rien du mode réseau n'est chargé.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHNetworkLevel/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. Les tests de calcul tournent sans serveur ; `LevelStoreTest` (écritures sous verrou) ne s'exécute qu'avec `SHNL_TEST_URL` (une base dont le nom contient `test`, ex. `jdbc:mariadb://127.0.0.1:3399/shnl_test`), `SHNL_TEST_USER` et `SHNL_TEST_PASSWORD`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
