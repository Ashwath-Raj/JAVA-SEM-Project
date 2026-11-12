#!/usr/bin/env python3
from __future__ import annotations

import csv
import math
import random
import shutil
from collections import Counter, defaultdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
DIGILETS = ROOT.parent / "DigiLeTs"
RAW_COMPLETE = DIGILETS / "data" / "raw" / "complete"

IMAGE_DIR = ROOT / "images"
META_DIR = ROOT / "metadata"
PREVIEW_DIR = ROOT / "preview"

CANVAS = 128
MARGIN = 12
LINE_WIDTH = 3
EXPECTED_CLASSES = 62
INSTANCES_PER_CLASS = 5

# DigiLeTs class ordering from the dataset:
# 0-9, a-z, A-Z
CLASSES = list("0123456789") + list("abcdefghijklmnopqrstuvwxyz") + list("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
assert len(CLASSES) == EXPECTED_CLASSES

# Fixed seed so split generation is reproducible.
RANDOM_SEED = 20260927


def parse_float_fields(line: str) -> list[float]:
    return [float(v) for v in line.strip().split() if v]


def parse_label(line: str) -> int:
    values = parse_float_fields(line)
    if len(values) != EXPECTED_CLASSES:
        raise ValueError(f"Expected {EXPECTED_CLASSES} label values, got {len(values)}")
    best = max(range(len(values)), key=lambda i: values[i])
    if values[best] <= 0:
        raise ValueError("Label line does not contain a positive class")
    return best


def parse_trajectory(line: str) -> list[tuple[float, float, int]]:
    values = parse_float_fields(line)
    if len(values) % 5 != 0:
        raise ValueError(
            f"Trajectory token count {len(values)} is not divisible by 5"
        )

    points: list[tuple[float, float, int]] = []
    for i in range(0, len(values), 5):
        x, y, _pressure, pen_down, _timestamp = values[i:i + 5]
        points.append((x, y, int(round(pen_down))))
    return points


def crop_and_normalize(points: list[tuple[float, float, int]]) -> list[tuple[float, float, int]]:
    if not points:
        raise ValueError("Empty trajectory")

    xs = [p[0] for p in points]
    ys = [p[1] for p in points]

    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)

    width = max_x - min_x
    height = max_y - min_y

    # Degenerate trajectories are still rendered safely.
    scale_x = (CANVAS - 2 * MARGIN) / width if width > 1e-12 else float("inf")
    scale_y = (CANVAS - 2 * MARGIN) / height if height > 1e-12 else float("inf")

    if math.isinf(scale_x) and math.isinf(scale_y):
        scale = 1.0
    elif math.isinf(scale_x):
        scale = scale_y
    elif math.isinf(scale_y):
        scale = scale_x
    else:
        scale = min(scale_x, scale_y)

    # First scale into available area while preserving aspect ratio.
    mapped = []
    for x, y, pen_down in points:
        nx = (x - min_x) * scale
        ny = (y - min_y) * scale
        mapped.append((nx, ny, pen_down))

    out_w = max(p[0] for p in mapped) if mapped else 0.0
    out_h = max(p[1] for p in mapped) if mapped else 0.0

    # Center the normalized drawing.
    offset_x = (CANVAS - out_w) / 2.0
    offset_y = (CANVAS - out_h) / 2.0

    centered = [(x + offset_x, y + offset_y, pen_down) for x, y, pen_down in mapped]
    return centered


def render(points: list[tuple[float, float, int]]) -> Image.Image:
    norm = crop_and_normalize(points)

    # White background; black trajectory.
    img = Image.new("L", (CANVAS, CANVAS), 255)
    draw = ImageDraw.Draw(img)

    if len(norm) == 1:
        x, y, _ = norm[0]
        r = max(1, LINE_WIDTH // 2)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=0)
        return img

    prev = None
    for x, y, pen_down in norm:
        # DigiLeTs marks pen-down/stroke-start information in this field.
        # When pen_down is 1, do not connect the previous stroke.
        current = (x, y)
        if prev is not None and pen_down != 1:
            draw.line([prev, current], fill=0, width=LINE_WIDTH)
        r = max(1, LINE_WIDTH // 2)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=0)
        prev = current
        if pen_down == 1:
            # Start point of a new stroke is valid, but it must not connect
            # to the previous stroke.
            prev = current

    # Ensure consistent image mode/content.
    return ImageOps.autocontrast(img)


def parse_participant_file(path: Path) -> list[tuple[int, list[tuple[float, float, int]]]]:
    lines = [line.strip() for line in path.read_text(errors="replace").splitlines() if line.strip()]
    if len(lines) % 2 != 0:
        raise ValueError(f"{path.name}: expected trajectory/label line pairs, got {len(lines)} lines")

    samples = []
    for i in range(0, len(lines), 2):
        trajectory = parse_trajectory(lines[i])
        label_idx = parse_label(lines[i + 1])
        samples.append((label_idx, trajectory))

    expected = EXPECTED_CLASSES * INSTANCES_PER_CLASS
    if len(samples) != expected:
        raise ValueError(
            f"{path.name}: expected {expected} samples, found {len(samples)}"
        )

    counts = Counter(label for label, _ in samples)
    missing = [CLASSES[i] for i in range(EXPECTED_CLASSES) if counts[i] != INSTANCES_PER_CLASS]
    if missing:
        raise ValueError(
            f"{path.name}: class counts are not exactly {INSTANCES_PER_CLASS}; "
            f"problem classes: {missing[:10]}"
        )

    return samples


def participant_id_from_name(path: Path) -> str:
    return path.name


def main() -> None:
    if not RAW_COMPLETE.exists():
        raise SystemExit(f"Raw dataset not found: {RAW_COMPLETE}")

    if IMAGE_DIR.exists():
        shutil.rmtree(IMAGE_DIR)
    IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    META_DIR.mkdir(parents=True, exist_ok=True)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)

    participant_files = sorted(
        p for p in RAW_COMPLETE.iterdir()
        if p.is_file() and not p.name.endswith("_info")
    )

    if not participant_files:
        raise SystemExit("No complete participant files found.")

    class_counter = Counter()
    participant_counter = Counter()
    rows = []
    participant_ids = []

    total = len(participant_files)
    print(f"Found {total} complete participant files.")

    for participant_index, participant_file in enumerate(participant_files, start=1):
        participant_id = participant_id_from_name(participant_file)
        participant_ids.append(participant_id)
        samples = parse_participant_file(participant_file)

        for instance_idx, (label_idx, trajectory) in enumerate(samples, start=1):
            label = CLASSES[label_idx]
            label_dir = IMAGE_DIR / label
            label_dir.mkdir(parents=True, exist_ok=True)

            filename = f"{participant_id}_{instance_idx:02d}.png"
            out_path = label_dir / filename

            img = render(trajectory)
            img.save(out_path, format="PNG", optimize=True)

            class_counter[label] += 1
            participant_counter[participant_id] += 1

            rows.append(
                {
                    "path": str(out_path.relative_to(ROOT)),
                    "label_index": label_idx,
                    "label": label,
                    "participant": participant_id,
                    "instance": instance_idx,
                }
            )

        if participant_index % 10 == 0 or participant_index == total:
            print(f"  converted {participant_index}/{total} participants")

    expected_total = total * EXPECTED_CLASSES * INSTANCES_PER_CLASS
    actual_total = len(rows)

    if actual_total != expected_total:
        raise RuntimeError(
            f"Image total mismatch: expected {expected_total}, got {actual_total}"
        )

    # Verify every class is balanced.
    bad_classes = {
        label: count
        for label, count in class_counter.items()
        if count != total * INSTANCES_PER_CLASS
    }
    if bad_classes:
        raise RuntimeError(f"Class imbalance detected: {bad_classes}")

    # Participant-level split: no writer/participant appears in more than one split.
    shuffled = participant_ids[:]
    random.Random(RANDOM_SEED).shuffle(shuffled)

    n = len(shuffled)
    n_train = round(n * 0.60)
    n_val = round(n * 0.20)
    n_test = n - n_train - n_val

    train_ids = sorted(shuffled[:n_train])
    val_ids = sorted(shuffled[n_train:n_train + n_val])
    test_ids = sorted(shuffled[n_train + n_val:])

    split_by_participant = {}
    for p in train_ids:
        split_by_participant[p] = "train"
    for p in val_ids:
        split_by_participant[p] = "val"
    for p in test_ids:
        split_by_participant[p] = "test"

    if len(split_by_participant) != n:
        raise RuntimeError("Participant split is not disjoint.")

    if set(train_ids) & set(val_ids) or set(train_ids) & set(test_ids) or set(val_ids) & set(test_ids):
        raise RuntimeError("Participant leakage detected between splits.")

    # Rewrite rows with split assignment and write metadata.
    for row in rows:
        row["split"] = split_by_participant[row["participant"]]

    metadata_path = META_DIR / "images.csv"
    with metadata_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=["path", "label_index", "label", "participant", "instance", "split"],
        )
        writer.writeheader()
        writer.writerows(rows)

    split_path = META_DIR / "participant_split.csv"
    with split_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["participant", "split"])
        for p in train_ids:
            writer.writerow([p, "train"])
        for p in val_ids:
            writer.writerow([p, "val"])
        for p in test_ids:
            writer.writerow([p, "test"])

    # Human-readable dataset summary.
    summary = META_DIR / "dataset_summary.txt"
    split_image_counts = Counter(row["split"] for row in rows)
    with summary.open("w", encoding="utf-8") as f:
        f.write("GLYVEX DigiLeTs Rasterized Dataset\n")
        f.write("=================================\n")
        f.write(f"Classes: {EXPECTED_CLASSES}\n")
        f.write(f"Class order: 0-9, a-z, A-Z\n")
        f.write(f"Participants: {n}\n")
        f.write(f"Instances per class per participant: {INSTANCES_PER_CLASS}\n")
        f.write(f"Total images: {actual_total}\n")
        f.write(f"Canvas: {CANVAS}x{CANVAS}, grayscale\n")
        f.write("Features rasterized: x, y trajectory\n")
        f.write("Not rasterized: pressure, timestamp\n")
        f.write(f"Train participants: {len(train_ids)}\n")
        f.write(f"Validation participants: {len(val_ids)}\n")
        f.write(f"Test participants: {len(test_ids)}\n")
        f.write(f"Train images: {split_image_counts['train']}\n")
        f.write(f"Validation images: {split_image_counts['val']}\n")
        f.write(f"Test images: {split_image_counts['test']}\n")
        f.write(f"Random seed: {RANDOM_SEED}\n")

    # Create an alphabet preview: one deterministic sample per class.
    preview = Image.new("L", (EXPECTED_CLASSES * 128, 160), 255)
    draw = ImageDraw.Draw(preview)

    for label_idx, label in enumerate(CLASSES):
        candidates = sorted((IMAGE_DIR / label).glob("*.png"))
        if not candidates:
            raise RuntimeError(f"No image found for class {label}")
        sample = Image.open(candidates[0]).convert("L")
        preview.paste(sample, (label_idx * 128, 0))
        # Labels are intentionally minimal; preview is primarily visual.
        draw.text((label_idx * 128 + 4, 132), label, fill=0)

    preview.save(PREVIEW_DIR / "alphabet_strip.png", format="PNG")

    # A practical 8x8 grid of the first 62 classes.
    cell = 144
    grid = Image.new("L", (8 * cell, 8 * cell), 255)
    draw = ImageDraw.Draw(grid)
    for label_idx, label in enumerate(CLASSES):
        row = label_idx // 8
        col = label_idx % 8
        candidates = sorted((IMAGE_DIR / label).glob("*.png"))
        sample = Image.open(candidates[0]).convert("L").resize((128, 128))
        x = col * cell + 8
        y = row * cell + 4
        grid.paste(sample, (x, y))
        draw.text((col * cell + 58, row * cell + 134), label, fill=0)

    grid.save(PREVIEW_DIR / "alphabet_grid.png", format="PNG")

    # Machine-readable class map.
    with (META_DIR / "class_map.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["label_index", "label"])
        for idx, label in enumerate(CLASSES):
            writer.writerow([idx, label])

    print()
    print("DATASET BUILD COMPLETE")
    print("----------------------")
    print(f"Participants : {n}")
    print(f"Classes      : {EXPECTED_CLASSES}")
    print(f"Images       : {actual_total}")
    print(f"Train        : {len(train_ids)} participants / {split_image_counts['train']} images")
    print(f"Validation   : {len(val_ids)} participants / {split_image_counts['val']} images")
    print(f"Test         : {len(test_ids)} participants / {split_image_counts['test']} images")
    print(f"Metadata     : {metadata_path}")
    print(f"Preview      : {PREVIEW_DIR / 'alphabet_grid.png'}")


if __name__ == "__main__":
    main()
