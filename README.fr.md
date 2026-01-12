🌐 [English](README.md) | [Deutsch](README.de.md) | **Français**

> ⚠️ **Avertissement :** Cette application ne devrait être utilisée que sur un téléphone secondaire dédié en mode follower, car elle limite considérablement l'utilisation normale du smartphone.

# Companion FX

Application compagnon pour CamAPS FX qui lit les valeurs de glycémie à l'écran et les téléverse vers Nightscout.

## Fonctionnalités

- **Lecture d'écran** : Lit les valeurs de glycémie, tendances et données de pompe depuis CamAPS FX
- **Intégration Nightscout** : Téléverse les lectures de glycémie vers votre instance Nightscout
- **Suivi SAGE/IAGE** : Surveille l'âge du capteur et de l'insuline, téléverse vers Nightscout
- **Widget écran d'accueil** : Affiche la valeur de glycémie actuelle et le graphique de tendance
- **Multilingue** : Prend en charge les versions allemande, anglaise et française de CamAPS FX

## Installation

1. Télécharger et installer l'APK
2. Accorder les permissions requises (voir ci-dessous)
3. Configurer votre URL Nightscout et clé API dans les Paramètres
4. Activer le Service d'accessibilité

## Permissions requises

### Service d'accessibilité (Requis)

L'application utilise le Service d'accessibilité d'Android pour lire les données de glycémie depuis CamAPS FX.

**Configuration :**
1. Ouvrir les **Paramètres** Android
2. Aller dans **Accessibilité** (ou rechercher "Accessibilité")
3. Trouver **Companion FX** dans la liste
4. Activer le service
5. Confirmer la boîte de dialogue de permission

### Optimisation de la batterie (Recommandé)

Pour des lectures fiables en arrière-plan, désactivez l'optimisation de la batterie pour l'application :

1. Ouvrir les **Paramètres** Android
2. Aller dans **Applications** > **Companion FX**
3. Appuyer sur **Batterie**
4. Sélectionner **Non restreint** ou **Ne pas optimiser**

Sur certains appareils (Samsung, Xiaomi, Huawei), vous devrez peut-être aussi :
- Ajouter l'application à la liste "Applications protégées" ou "Démarrage auto"
- Désactiver la "Batterie adaptative" pour cette application

### Permission de notification (Android 13+)

L'application demandera la permission de notification au premier lancement. Ceci est nécessaire pour :
- Afficher la notification du service de premier plan
- Les notifications de lecture sur l'écran de verrouillage

## Configuration

### Paramètres Nightscout

1. Entrer votre URL Nightscout (ex : `https://votre-nightscout.herokuapp.com`)
2. Entrer votre clé API
3. Tester la connexion
4. Activer la synchronisation Nightscout

### Intervalle de lecture

Configurer la fréquence de lecture des valeurs de glycémie :
- Par défaut : 1 minute
- Options : 1 min, 5 min, 15 min

### Intervalle SAGE/IAGE

Configurer la fréquence de vérification de l'âge du capteur et de l'insuline :
- Par défaut : 30 minutes
- Options : 1 min, 15 min, 30 min, 1 heure, 6 heures

## Appareils supportés

- **Android** : 8.0 (API 26) et supérieur
- **CamAPS FX** : Toutes versions avec interface allemande, anglaise ou française

## Dépannage

### L'application ne lit pas les valeurs de glycémie

1. Vérifier que le Service d'accessibilité est activé
2. Vérifier que CamAPS FX est défini comme application cible
3. Vérifier que l'optimisation de la batterie est désactivée

### L'envoi vers Nightscout échoue

1. Vérifier votre URL Nightscout (pas de barre oblique finale)
2. Vérifier que la clé API est correcte
3. Tester la connexion dans les Paramètres

### Le widget ne se met pas à jour

1. Vérifier que le service fonctionne (la notification devrait être visible)
2. Supprimer et réajouter le widget
3. Désactiver l'optimisation de la batterie

## Confidentialité

- Toutes les données sont stockées localement sur votre appareil
- Les données sont uniquement envoyées à votre instance Nightscout personnelle
- Aucune donnée n'est envoyée à d'autres serveurs

## Licence

Licence MIT - voir [LICENSE](LICENSE)

## Avertissement

Cette application n'est pas affiliée à CamAPS FX, Ypsomed ou Abbott. C'est une application compagnon indépendante pour la gestion personnelle du diabète. Vérifiez toujours les lectures de glycémie avec votre CGM ou glucomètre pour les décisions médicales.
