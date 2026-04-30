# Triviidae Detector - Application Android de Détection IA de Coquillages

Application Android de détection automatisée de coquillages Triviidae sur la plage utilisant TensorFlow Lite avec validation intégrée et télécommande Bluetooth compatible.

## 📱 Installation

### 1. Téléchargement
```bash
git clone https://github.com/MarineRvdy/ProjetM1
```

### 2. Installation sur Android
1. Sur le smartphone Android, ouvrir le fichier `TriviidaeDetector.apk`
2. Accepter l'installation depuis une source inconnue si demandé par Android
3. Lancer l'application et accorder les permissions demandées :
   - **Caméra** : obligatoire pour le flux vidéo en temps réel
   - **Galerie/Stockage** : obligatoire pour enregistrer les photos
   - **Vibration** : pour les alertes haptiques lors des détections

### 3. Dépendances développeur

**Windows :**
```bash
./download_tlflite_libs.bat
```
(situé dans `/app/src/main/cpp/tflite/`)

**Linux :**
```bash
./download_tlflite_libs.sh
```
(situé dans `/app/src/main/cpp/tflite/`)

## 🎯 Fonctionnalités

- **Détection en temps réel** : Analyse continue du flux caméra avec TensorFlow Lite
- **Alertes multimodales** : Signal sonore + vibration lors des détections
- **Triple mode de fonctionnement** : Temps réel, validation automatique, validation manuelle
- **Interface intuitive** : Barre supérieure avec contrôles rapides
- **Télécommande Bluetooth** : Compatible avec perche à selfie Forever SST-100
- **Organisation intelligente** : Sauvegarde automatique dans 3 dossiers thématiques

## 🖥️ Interface utilisateur

### Barre supérieure
| Élément | Description |
|---------|-------------|
| Logo ISEN | Logo de l'ISEN Brest |
| Titre | « Détection Triviidae » |
| Nombre(s) | Nombre de coquillages détectés dans la frame courante |
| 🔊 Son | Active/désactive le signal sonore (activé par défaut) |
| 📳 Vibration | Active/désactive la vibration (activée par défaut) |
| 🔦 Flash | Active/désactive la lampe torche (désactivé par défaut) |

## ⚙️ Modes de fonctionnement

### 1. Mode temps réel (mode par défaut)
- **Flux continu** : La caméra analyse en continu à la recherche de Triviidae
- **Alertes automatiques** : Son + vibration lors d'une détection
- **Visualisation** : Bounding boxes rouges avec labels et scores de confiance
- **Capture manuelle** : Bouton caméra pour captures manuelles

**Distance optimale** : 15-30 cm entre le smartphone et le coquillage

### 2. Mode validation automatique
Déclenché automatiquement lors d'une détection :

1. **Cadre de centrage** : Cadre vert 320×320 pixels apparaît
2. **Positionnement** : Centrer la bounding box rouge dans le cadre vert
3. **Capture automatique** : Photo prise après stabilisation
4. **Validation** : Trois boutons apparaissent :
   - **DÉTECTION VRAIE** → `detection_vraie/`
   - **DÉTECTION FAUSSE** → `fausse_alarme/`
   - **TRASH** → Ignore l'image

### 3. Mode validation manuelle
Déclenché par appui sur le bouton caméra sans détection automatique :

1. **Capture manuelle** : Photo prise immédiatement
2. **Validation** : Deux boutons apparaissent :
   - **OBJET NON DÉTECTÉ** → `non_detection_fausse/`
   - **TRASH** → Ignore l'image

## 📁 Organisation des images

Les images sont sauvegardées dans `Images > TriviidaeDetection` :

| Dossier | Contenu |
|---------|---------|
| `detection_vraie` | Détections confirmées comme correctes |
| `fausse_alarme` | Faux positifs signalés par l'utilisateur |
| `non_detection_fausse` | Triviidae visibles mais non détectés |

**Métadonnées** : Chaque image contient la date, l'heure et le nombre de détections.

## 🎮 Télécommande Bluetooth

### Compatibilité
- **Modèle** : Forever Perche à Selfie Bluetooth Trépied Extensible SST-100
- **Connexion** : Appairer le périphérique « SST-100 » via Paramètres > Bluetooth

### Mapping des boutons

| Mode | Action | Résultat |
|------|--------|----------|
| **Temps réel** | 1 clic | Capture photo (mode manuel) |
| **Validation manuelle** | 1 clic | Objet non détecté |
| | 3 clics | Trash |
| **Validation automatique** | 1 clic | Détection vraie |
| | 2 clics | Détection fausse |
| | 3 clics | Trash |

**Note** : Les clics doivent être effectués dans un délai d'une seconde pour être reconnus comme une séquence.

## 💡 Conseils d'utilisation

- **Distance** : Maintenir 15-30 cm du sol pour des détections précises
- **Éclairage** : Utiliser le flash en cas de faible luminosité
- **Stabilité** : Garder le smartphone stable lors du centrage
- **Précision** : Les bounding boxes sont plus précises dans la zone centrale
- **Réentraînement** : Les images peuvent être réimportées dans Edge Impulse Studio

## ⚙️ Configuration technique

- **Modèle IA** : TensorFlow Lite pour la détection d'objets
- **Seuil de confiance** : 0.4
- **Dimensions du modèle** : 160x160 pixels
- **Compatible** : Android 10+ (scoped storage)
- **Cadre de validation** : 320×320 pixels

## 🔧 Développement

### Fichiers principaux
- `app/src/main/java/com/example/trividaedetection/MainActivity.kt` : Logique principale
- `app/src/main/res/layout/activity_main.xml` : Interface utilisateur
- `app/src/main/cpp/native-lib.cpp` : Traitement natif TensorFlow Lite

### Commandes Git utiles
```bash
git add <fichier>
git commit -m "message descriptif"
git push
```


