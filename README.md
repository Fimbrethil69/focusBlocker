# FocusBlocker

Application Android qui :

- **bloque les Shorts YouTube en permanence** : dès que le lecteur Shorts s'ouvre dans l'application YouTube, elle revient en arrière. Le reste de YouTube fonctionne normalement ;
- **bloque les réseaux sociaux pendant des plages horaires** (par défaut **9h-12h et 14h-20h**, tous les jours) : l'application est fermée et un écran « bloqué jusqu'à… » s'affiche ;
- **verrouille ses propres réglages** : les horaires, les jours et la liste des applications ne sont modifiables **qu'en dehors des plages de blocage**, et toute modification ne prend effet **que le lendemain**.

Applications bloquées par défaut : Instagram, TikTok, Facebook (et Lite), Snapchat, X, Threads, Reddit. On peut aussi cocher Pinterest, LinkedIn, BeReal ou YouTube en entier.

## Installation sur le téléphone

1. Sur le téléphone, ouvrez la page **Releases** du dépôt GitHub et téléchargez `FocusBlocker.apk` depuis la release « latest ».
2. Ouvrez le fichier. Android demande d'autoriser l'installation depuis le navigateur : acceptez.
3. Lancez FocusBlocker, touchez **Ouvrir les paramètres**, puis activez **FocusBlocker** dans *Accessibilité → Applications installées*.
   - Sur Android 13 et plus, si l'option est grisée (« paramètre restreint ») : *Paramètres → Applications → FocusBlocker → ⋮ (en haut à droite) → Autoriser les paramètres restreints*, puis réessayez.
4. Conseillé : *Paramètres → Applications → FocusBlocker → Batterie → Sans restriction*, pour qu'Android ne coupe pas le service.

Les nouvelles versions s'installent par-dessus l'ancienne sans perdre les réglages.

## Fonctionnement

L'application utilise un **service d'accessibilité** pour connaître l'application au premier plan et, dans YouTube, repérer le lecteur Shorts grâce aux identifiants de ses vues (`reel_recycler`, `reel_player_page_container`…). Si une mise à jour de YouTube renomme ces vues, il faut compléter la liste `SHORTS_VIEW_IDS` dans `BlockerAccessibilityService.kt`.

Rien ne quitte le téléphone : pas d'accès réseau, pas de collecte de données.

### Limites connues
- Désactiver le service d'accessibilité ou désinstaller l'application lève le blocage. C'est un choix : les paramètres d'Android ne sont pas bloqués.
- Changer l'heure du téléphone à la main fausse les plages horaires.
- Les versions web (instagram.com, youtube.com/shorts dans un navigateur) ne sont pas bloquées.

## Développement

```
app/   application Android (Kotlin, Jetpack Compose)
core/  logique des horaires et du verrouillage (Kotlin pur, testée)
```

- Tests de la logique : `./gradlew :core:test`
- APK : `./gradlew :app:assembleDebug` (ou ouvrir le projet dans Android Studio)
- Chaque push déclenche GitHub Actions, qui compile l'APK. Sur `main`, l'APK est publié dans la release « latest ».
