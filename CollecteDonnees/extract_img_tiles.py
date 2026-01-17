# !pip install opencv-python

import cv2
import os
import glob
import numpy as np

# --- Paramètres ---
input_videos_dir = "videos"     # dossier contenant toutes tes vidéos
frame_step = 300                 # 1 frame toutes les 10 frames
tile_size = 192                  # taille des tiles 192x192
tile_overlap = 42                # chevauchement en pixels entre tiles, pour ne pas couper les coquillages
min_variance = 10                  # filtrer les tiles trop uniformes (sable)


output_base_dir = "dataset"      # dossier de sortie

# --- Créer les dossiers de sortie ---
classes = ["arctica", "monacha", "arctica_monacha", "aucun"]
for cls in classes:
    os.makedirs(os.path.join(output_base_dir, cls), exist_ok=True)

# --- Fonction pour déterminer la classe selon le nom de la vidéo ---
def get_class_from_name(video_name):
    name = video_name.lower()
    if "arctica" in name and "monacha" in name:
        return "arctica_monacha"
    elif "arctica" in name:
        return "arctica"
    elif "monacha" in name:
        return "monacha"
    else:
        return "aucun"

# --- Parcourir toutes les vidéos ---
video_paths = glob.glob(os.path.join(input_videos_dir, "*.mp4"))

for video_path in video_paths:
    video_name = os.path.basename(video_path)
    video_class = get_class_from_name(video_name)
    output_dir = os.path.join(output_base_dir, video_class)

    # Ouvrir la vidéo
    cap = cv2.VideoCapture(video_path)
    frame_count = 0
    saved_tiles = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break  # fin de la vidéo

        if frame_count % frame_step == 0:
            img_h, img_w, _ = frame.shape

            # --- Découper en tiles ---
            tile_id = 0
            y = 0
            while y < img_h:
                x = 0
                while x < img_w:
                    tile = frame[y:y+tile_size, x:x+tile_size]

                    # Vérifier que la tile n'est pas vide
                    if tile.shape[0] != 0 and tile.shape[1] != 0:
                        # Redimensionner à 192x192 si nécessaire
                        if tile.shape[0] != tile_size or tile.shape[1] != tile_size:
                            tile = cv2.resize(tile, (tile_size, tile_size))

                        # Filtrer les tiles trop uniformes (sable)
                        if np.var(tile) >= min_variance:
                            # Nommer le fichier
                            base_name = os.path.splitext(video_name)[0]
                            tile_filename = os.path.join(
                                output_dir,
                                f"{base_name}_frame{frame_count:04d}_tile{tile_id:03d}.jpg"
                            )
                            cv2.imwrite(tile_filename, tile)
                            tile_id += 1
                            saved_tiles += 1

                    x += tile_size - tile_overlap
                y += tile_size - tile_overlap

        frame_count += 1

    cap.release()
    print(f"{video_name} : {saved_tiles} tiles sauvegardées dans '{output_dir}'")

print("Extraction complète pour toutes les vidéos !")
