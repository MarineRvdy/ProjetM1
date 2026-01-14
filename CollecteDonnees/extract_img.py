# !pip install opencv-python

import cv2
import os

# --- Paramètres ---
video_path = "videos/monacha1.mp4"  # chemin de ta vidéo
frame_step = 10              # 1 frame toutes les 10 frames

# --- Déterminer le dossier de sortie selon le nom de la vidéo ---
video_name = os.path.basename(video_path).lower()  # nom du fichier en minuscules

if "arctica" in video_name and "monacha" in video_name:
    output_dir = "dataset/arctica_monacha"
elif "arctica" in video_name:
    output_dir = "dataset/arctica"
elif "monacha" in video_name:
    output_dir = "dataset/monacha"
else:
    output_dir = "dataset/aucun"

# Créer le dossier s'il n'existe pas
os.makedirs(output_dir, exist_ok=True)

# --- Ouvrir la vidéo ---
cap = cv2.VideoCapture(video_path)

frame_count = 0
saved_count = 0

while True:
    ret, frame = cap.read()
    if not ret:
        break  # fin de la vidéo

    # Sauvegarder 1 frame toutes les 'frame_step'
    if frame_count % frame_step == 0:
        # enlever l'extension .mp4 pour le nom de fichier
        base_name = os.path.splitext(video_name)[0]
        filename = os.path.join(output_dir, f"{base_name}_frame_{saved_count:04d}.jpg")
        cv2.imwrite(filename, frame)
        saved_count += 1

    frame_count += 1

cap.release()
print(f"Extraction terminée : {saved_count} frames sauvegardées dans '{output_dir}'")
