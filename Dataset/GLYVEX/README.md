# GLYVEX — Dataset Preparation

This directory contains the rasterized DigiLeTs dataset used for the GLYVEX character-classification pipeline.

## Pipeline

`DigiLeTs trajectory (x,y,stroke state)`
→ `bounding-box normalization`
→ `128×128 grayscale raster`
→ `CNN character classifier`

Pressure and timestamp are intentionally not used by the CNN image representation.

## Dataset

The class order is:

`0-9`, `a-z`, `A-Z`

The converter expects 5 examples per class for each complete participant.

## Split policy

The split is **participant-level**, not image-level:

- `train`: 60%
- `val`: 20%
- `test`: remaining participants

This prevents the same writer/participant from appearing in both training and evaluation data.

## Important modality note

DigiLeTs was captured from a pen tablet. GLYVEX will ultimately run on laptop touchpad trajectories. Therefore the CNN test result on DigiLeTs is not, by itself, evidence of touchpad-domain accuracy. A small real-touchpad validation set should be collected later.

## Generated files

- `images/<class>/*.png` — rasterized samples
- `metadata/images.csv` — per-image metadata
- `metadata/participant_split.csv` — writer-independent split
- `metadata/class_map.csv` — class index mapping
- `metadata/dataset_summary.txt` — dataset summary
- `preview/alphabet_grid.png` — visual sanity check
- `preview/alphabet_strip.png` — class strip
- `scripts/convert_to_images.py` — deterministic conversion script
- `run_output.txt` — full conversion output
