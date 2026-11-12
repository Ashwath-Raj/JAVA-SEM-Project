from __future__ import annotations

import json
import math
import os
import random
import time
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image, ImageDraw

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset
from torchvision import models, transforms

from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score, precision_recall_fscore_support

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

ROOT = Path(__file__).resolve().parents[4]
GLYVEX = ROOT / "Dataset" / "GLYVEX"
META = GLYVEX / "metadata"
IMAGES = GLYVEX / "images"
ML = GLYVEX / "ml"
TRAIN = ML / "training"
EVAL = ML / "evaluation"
ONNX = ML / "onnx"

IMAGE_SIZE = int(os.getenv("IMAGE_SIZE", "128"))
NUM_CLASSES = int(os.getenv("NUM_CLASSES", "62"))
EPOCHS = int(os.getenv("EPOCHS", "50"))
BATCH_SIZE = int(os.getenv("BATCH_SIZE", "128"))
LR = float(os.getenv("LR", "0.0003"))
WEIGHT_DECAY = float(os.getenv("WEIGHT_DECAY", "0.0001"))
PATIENCE = int(os.getenv("PATIENCE", "8"))
NUM_WORKERS = int(os.getenv("NUM_WORKERS", "4"))
SEED = int(os.getenv("SEED", "20260927"))

CLASSES = list("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
AMP = DEVICE.type == "cuda"


def seed_all(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.benchmark = True


def save_json(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2), encoding="utf-8")


class GlyphDataset(Dataset):
    def __init__(self, frame: pd.DataFrame, transform):
        self.frame = frame.reset_index(drop=True)
        self.transform = transform

    def __len__(self):
        return len(self.frame)

    def __getitem__(self, idx):
        row = self.frame.iloc[idx]
        img = Image.open(GLYVEX / row["path"]).convert("L")
        if self.transform:
            img = self.transform(img)
        return img, int(row["label_index"])


def build_model():
    m = models.resnet18(weights=None)
    m.conv1 = nn.Conv2d(1, 64, kernel_size=3, stride=1, padding=1, bias=False)
    m.maxpool = nn.Identity()
    m.fc = nn.Linear(m.fc.in_features, NUM_CLASSES)
    return m


def epoch_pass(model, loader, criterion, optimizer=None, scaler=None):
    training = optimizer is not None
    model.train(training)
    total_loss = 0.0
    n = 0
    ys, ps = [], []
    for x, y in loader:
        x = x.to(DEVICE, non_blocking=True)
        y = y.to(DEVICE, non_blocking=True)
        if training:
            optimizer.zero_grad(set_to_none=True)
        with torch.cuda.amp.autocast(enabled=AMP):
            logits = model(x)
            loss = criterion(logits, y)
        if training:
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        bs = y.size(0)
        total_loss += loss.item() * bs
        n += bs
        ys.extend(y.detach().cpu().numpy().tolist())
        ps.extend(logits.argmax(1).detach().cpu().numpy().tolist())
    return {
        "loss": total_loss / max(1, n),
        "accuracy": accuracy_score(ys, ps),
        "macro_f1": f1_score(ys, ps, average="macro", zero_division=0),
    }


def predict_all(model, loader):
    model.eval()
    ys, ps, probs = [], [], []
    with torch.no_grad():
        for x, y in loader:
            x = x.to(DEVICE, non_blocking=True)
            with torch.cuda.amp.autocast(enabled=AMP):
                logits = model(x)
            p = torch.softmax(logits, 1)
            ys.extend(y.numpy().tolist())
            ps.extend(logits.argmax(1).cpu().numpy().tolist())
            probs.append(p.cpu())
    return np.asarray(ys), np.asarray(ps), torch.cat(probs).numpy()


def make_confusions(y_true, y_pred):
    cm = confusion_matrix(y_true, y_pred, labels=range(NUM_CLASSES))
    np.savetxt(EVAL / "confusion_matrix.csv", cm, delimiter=",", fmt="%d")
    plt.figure(figsize=(22, 20))
    sns.heatmap(cm, cmap="Blues", xticklabels=CLASSES, yticklabels=CLASSES, square=True, linewidths=0.1)
    plt.title("GLYVEX Test Confusion Matrix")
    plt.xlabel("Predicted")
    plt.ylabel("True")
    plt.tight_layout()
    plt.savefig(EVAL / "confusion_matrix.png", dpi=180)
    plt.close()
    norm = cm / np.maximum(cm.sum(1, keepdims=True), 1)
    plt.figure(figsize=(22, 20))
    sns.heatmap(norm, cmap="Blues", xticklabels=CLASSES, yticklabels=CLASSES, square=True, vmin=0, vmax=1, linewidths=0.1)
    plt.title("GLYVEX Normalized Test Confusion Matrix")
    plt.xlabel("Predicted")
    plt.ylabel("True")
    plt.tight_layout()
    plt.savefig(EVAL / "confusion_matrix_normalized.png", dpi=180)
    plt.close()


def save_per_class(y_true, y_pred):
    p, r, f, s = precision_recall_fscore_support(y_true, y_pred, labels=range(NUM_CLASSES), zero_division=0)
    pd.DataFrame([
        {"label_index": i, "label": CLASSES[i], "precision": float(p[i]), "recall": float(r[i]), "f1": float(f[i]), "support": int(s[i])}
        for i in range(NUM_CLASSES)
    ]).to_csv(EVAL / "per_class_metrics.csv", index=False)


def save_errors(frame, y_true, y_pred, probs):
    rows = []
    for i in np.where(y_true != y_pred)[0]:
        rows.append({
            "row_index": int(i),
            "path": frame.iloc[i]["path"],
            "true": CLASSES[int(y_true[i])],
            "pred": CLASSES[int(y_pred[i])],
            "confidence": float(probs[i].max()),
        })
    err = pd.DataFrame(rows).sort_values("confidence", ascending=False)
    err.to_csv(EVAL / "misclassified_samples.csv", index=False)
    if err.empty:
        return
    selected = err.head(64)
    cw, ch, cols = 170, 165, 8
    rows_n = math.ceil(len(selected) / cols)
    canvas = Image.new("RGB", (cw * cols, ch * rows_n), "white")
    draw = ImageDraw.Draw(canvas)
    for j, (_, row) in enumerate(selected.iterrows()):
        img = Image.open(GLYVEX / row["path"]).convert("L").resize((128, 128))
        rgb = Image.merge("RGB", (img, img, img))
        x, y = (j % cols) * cw + 20, (j // cols) * ch + 2
        canvas.paste(rgb, (x, y))
        draw.multiline_text((x, y + 130), f"T:{row['true']} P:{row['pred']}\nconf={row['confidence']:.2f}", fill="black")
    canvas.save(EVAL / "misclassified_examples.png")


def main():
    seed_all(SEED)
    df = pd.read_csv(META / "images.csv")
    split = pd.read_csv(META / "participant_split.csv")

    required = {"path", "label_index", "label", "participant", "instance", "split"}
    missing_cols = required - set(df.columns)
    if missing_cols:
        raise RuntimeError(f"Missing metadata columns: {missing_cols}")
    if len(CLASSES) != NUM_CLASSES:
        raise RuntimeError("Class count mismatch")

    # Verify every metadata path and participant-safe split.
    missing = [p for p in df["path"] if not (GLYVEX / p).exists()]
    if missing:
        raise RuntimeError(f"Missing image files, first examples: {missing[:10]}")
    train_p = set(split.loc[split.split == "train", "participant"])
    val_p = set(split.loc[split.split == "val", "participant"])
    test_p = set(split.loc[split.split == "test", "participant"])
    assert not (train_p & val_p or train_p & test_p or val_p & test_p)

    train_df = df[df.split == "train"].copy()
    val_df = df[df.split == "val"].copy()
    test_df = df[df.split == "test"].copy()

    train_tf = transforms.Compose([
        transforms.RandomAffine(degrees=8, translate=(0.06, 0.06), scale=(0.92, 1.08), shear=4, fill=255),
        transforms.ToTensor(),
        transforms.Normalize((0.5,), (0.5,)),
    ])
    eval_tf = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize((0.5,), (0.5,)),
    ])

    train_set = GlyphDataset(train_df, train_tf)
    val_set = GlyphDataset(val_df, eval_tf)
    test_set = GlyphDataset(test_df, eval_tf)
    pin = DEVICE.type == "cuda"
    kw = dict(batch_size=BATCH_SIZE, num_workers=NUM_WORKERS, pin_memory=pin, persistent_workers=NUM_WORKERS > 0)
    train_loader = DataLoader(train_set, shuffle=True, **kw)
    val_loader = DataLoader(val_set, shuffle=False, **kw)
    test_loader = DataLoader(test_set, shuffle=False, **kw)

    model = build_model().to(DEVICE)
    criterion = nn.CrossEntropyLoss(label_smoothing=0.05)
    optimizer = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=WEIGHT_DECAY)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=EPOCHS, eta_min=LR * 0.02)
    scaler = torch.cuda.amp.GradScaler(enabled=AMP)

    nparams = sum(p.numel() for p in model.parameters())
    save_json(TRAIN / "model_config.json", {
        "architecture": "ResNet-18 grayscale, modified stem, 62 outputs",
        "input": [1, 1, IMAGE_SIZE, IMAGE_SIZE],
        "classes": CLASSES,
        "parameters": nparams,
        "device": str(DEVICE),
        "amp": AMP,
        "optimizer": "AdamW",
        "learning_rate": LR,
        "weight_decay": WEIGHT_DECAY,
        "loss": "CrossEntropyLoss(label_smoothing=0.05)",
        "seed": SEED,
        "dataset": {"train_images": len(train_df), "val_images": len(val_df), "test_images": len(test_df), "train_participants": len(train_p), "val_participants": len(val_p), "test_participants": len(test_p)},
    })

    best_f1 = -1.0
    best_acc = -1.0
    best_epoch = 0
    stale = 0
    history = []
    best_ckpt = TRAIN / "best_model.pt"
    last_ckpt = TRAIN / "last_model.pt"
    start = time.time()

    print("=" * 68)
    print("GLYVEX TRAINING")
    print("=" * 68)
    print(f"Device: {DEVICE}")
    if DEVICE.type == "cuda":
        print(f"GPU: {torch.cuda.get_device_name(0)}")
    print(f"Images: {len(train_df)} train / {len(val_df)} val / {len(test_df)} test")
    print(f"Participants: {len(train_p)} train / {len(val_p)} val / {len(test_p)} test")
    print(f"Parameters: {nparams:,}")

    for epoch in range(1, EPOCHS + 1):
        t0 = time.time()
        tr = epoch_pass(model, train_loader, criterion, optimizer, scaler)
        va = epoch_pass(model, val_loader, criterion)
        scheduler.step()
        rec = {
            "epoch": epoch,
            "lr": optimizer.param_groups[0]["lr"],
            "train_loss": tr["loss"], "train_accuracy": tr["accuracy"], "train_macro_f1": tr["macro_f1"],
            "val_loss": va["loss"], "val_accuracy": va["accuracy"], "val_macro_f1": va["macro_f1"],
            "seconds": time.time() - t0,
        }
        history.append(rec)
        print(f"Epoch {epoch:03d}/{EPOCHS} | train loss {tr['loss']:.4f} acc {tr['accuracy']:.4f} F1 {tr['macro_f1']:.4f} | val loss {va['loss']:.4f} acc {va['accuracy']:.4f} F1 {va['macro_f1']:.4f} | lr {rec['lr']:.2e} | {rec['seconds']:.1f}s")
        pd.DataFrame(history).to_csv(TRAIN / "history.csv", index=False)

        torch.save({"epoch": epoch, "model_state_dict": model.state_dict(), "optimizer_state_dict": optimizer.state_dict(), "scheduler_state_dict": scheduler.state_dict(), "class_names": CLASSES, "image_size": IMAGE_SIZE}, last_ckpt)

        improved = (va["macro_f1"] > best_f1 + 1e-6) or (abs(va["macro_f1"] - best_f1) <= 1e-6 and va["accuracy"] > best_acc)
        if improved:
            best_f1, best_acc, best_epoch, stale = va["macro_f1"], va["accuracy"], epoch, 0
            torch.save({"epoch": epoch, "model_state_dict": model.state_dict(), "class_names": CLASSES, "image_size": IMAGE_SIZE, "val_accuracy": best_acc, "val_macro_f1": best_f1}, best_ckpt)
            print(f"  -> NEW BEST checkpoint: val_acc={best_acc:.6f} val_macro_f1={best_f1:.6f}")
        else:
            stale += 1
            if stale >= PATIENCE:
                print(f"Early stopping after {epoch} epochs.")
                break

    elapsed = (time.time() - start) / 60.0
    print(f"Training wall time: {elapsed:.2f} min")

    # Test only best checkpoint.
    ck = torch.load(best_ckpt, map_location=DEVICE, weights_only=False)
    model.load_state_dict(ck["model_state_dict"])
    y_true, y_pred, probs = predict_all(model, test_loader)

    test_acc = accuracy_score(y_true, y_pred)
    test_f1 = f1_score(y_true, y_pred, average="macro", zero_division=0)
    top5 = float(np.mean([int(y_true[i] in np.argsort(probs[i])[-5:]) for i in range(len(y_true))]))

    metrics = {
        "device": str(DEVICE),
        "best_epoch": int(best_epoch),
        "best_val_accuracy": float(best_acc),
        "best_val_macro_f1": float(best_f1),
        "test_accuracy": float(test_acc),
        "test_macro_f1": float(test_f1),
        "test_top5_accuracy": top5,
        "test_samples": len(y_true),
        "training_minutes": elapsed,
        "classes": CLASSES,
    }
    save_json(EVAL / "test_metrics.json", metrics)
    (EVAL / "classification_report.txt").write_text(classification_report(y_true, y_pred, labels=range(NUM_CLASSES), target_names=CLASSES, digits=4, zero_division=0), encoding="utf-8")
    save_per_class(y_true, y_pred)
    make_confusions(y_true, y_pred)
    save_errors(test_df.reset_index(drop=True), y_true, y_pred, probs)
    np.save(EVAL / "test_probabilities.npy", probs)
    np.save(EVAL / "test_targets.npy", y_true)
    np.save(EVAL / "test_predictions.npy", y_pred)

    print("=" * 68)
    print("TEST RESULTS")
    print("=" * 68)
    print(f"Test accuracy:       {test_acc:.6f}")
    print(f"Test macro-F1:       {test_f1:.6f}")
    print(f"Test top-5 accuracy: {top5:.6f}")
    print("\n" + (EVAL / "classification_report.txt").read_text())

    # ONNX export.
    model_cpu = model.to("cpu").eval()
    dummy = torch.zeros(1, 1, IMAGE_SIZE, IMAGE_SIZE)
    onnx_path = ONNX / "glyvex_char_classifier.onnx"
    print("=" * 68)
    print("ONNX EXPORT")
    print("=" * 68)
    try:
        torch.onnx.export(model_cpu, dummy, onnx_path, opset_version=17, input_names=["input"], output_names=["logits"], dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}}, dynamo=False)
    except TypeError:
        torch.onnx.export(model_cpu, dummy, onnx_path, opset_version=17, input_names=["input"], output_names=["logits"], dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}})
    if not onnx_path.exists() or onnx_path.stat().st_size == 0:
        raise RuntimeError("ONNX export failed")

    import onnx
    onnx.checker.check_model(onnx.load(str(onnx_path)))
    import onnxruntime as ort
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = session.get_inputs()[0].name
    out = session.get_outputs()[0].name
    test_input = np.zeros((1, 1, IMAGE_SIZE, IMAGE_SIZE), dtype=np.float32)
    with torch.no_grad():
        torch_logits = model_cpu(torch.from_numpy(test_input)).numpy()
    ort_logits = session.run([out], {inp: test_input})[0]
    diff = float(np.max(np.abs(torch_logits - ort_logits)))
    if diff >= 1e-3:
        raise RuntimeError(f"ONNX numerical mismatch: {diff}")

    save_json(ONNX / "classes.json", {"class_names": CLASSES, "index_to_class": {str(i): c for i, c in enumerate(CLASSES)}})
    save_json(ONNX / "model_metadata.json", {
        "model": "ResNet-18 grayscale character classifier",
        "opset": 17,
        "input_name": inp,
        "output_name": out,
        "input_shape": ["batch", 1, IMAGE_SIZE, IMAGE_SIZE],
        "input_dtype": "float32",
        "normalization": {"mean": [0.5], "std": [0.5]},
        "classes": CLASSES,
        "test_accuracy": float(test_acc),
        "test_macro_f1": float(test_f1),
        "test_top5_accuracy": top5,
        "best_epoch": int(best_epoch),
        "outputs_are": "logits; apply softmax in consumer",
    })
    save_json(ONNX / "onnx_sanity_check.json", {"max_absolute_logit_difference": diff, "pass": True})

    # Final report.
    final = f"""GLYVEX CNN BASELINE — FINAL RESULT
===================================

Architecture: ResNet-18 adapted to 1-channel 128x128 inputs
Classes: 62 (0-9, a-z, A-Z)

Train: {len(train_df)} images / {len(train_p)} participants
Val:   {len(val_df)} images / {len(val_p)} participants
Test:  {len(test_df)} images / {len(test_p)} participants

Best epoch: {best_epoch}
Best val accuracy: {best_acc:.6f}
Best val macro-F1: {best_f1:.6f}

TEST ACCURACY:       {test_acc:.6f}
TEST MACRO-F1:       {test_f1:.6f}
TEST TOP-5 ACCURACY: {top5:.6f}

ONNX: {onnx_path}
ONNX Runtime max absolute logit difference: {diff:.8f}

Deployment input:
  float32 [batch,1,128,128]
  normalized as (pixel/255 - 0.5) / 0.5

Deployment output:
  float32 logits [batch,62]
  class order: 0-9, a-z, A-Z

IMPORTANT:
  The model recognizes characters; it must NOT directly execute commands.
  Java should perform confidence gating and then route the recognized class
  through the explicit GLYVEX allowlisted command registry.
"""
    (ML / "FINAL_RESULT.txt").write_text(final, encoding="utf-8")
    print(final)


if __name__ == "__main__":
    main()
