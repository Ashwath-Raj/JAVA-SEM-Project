from __future__ import annotations

import json
import math
import os
import random
import time
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset
from torchvision import models, transforms

from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    fbeta_score,
    precision_recall_fscore_support,
)

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[4]
GLYVEX = ROOT / "Dataset" / "GLYVEX"
META = GLYVEX / "metadata"
ML = GLYVEX / "ml"
DEMO = ML / "demo_retrain"
CKPT_DIR = DEMO / "checkpoints"
EVAL_DIR = DEMO / "evaluation"
ONNX_DIR = DEMO / "onnx"
BASELINE_CKPT = ML / "training" / "best_model.pt"

IMAGE_SIZE = int(os.getenv("IMAGE_SIZE", "128"))
EPOCHS = int(os.getenv("EPOCHS", "35"))
BATCH_SIZE = int(os.getenv("BATCH_SIZE", "32"))
LR = float(os.getenv("LR", "0.0001"))
WEIGHT_DECAY = float(os.getenv("WEIGHT_DECAY", "0.0001"))
PATIENCE = int(os.getenv("PATIENCE", "7"))
NUM_WORKERS = int(os.getenv("NUM_WORKERS", "2"))
SEED = int(os.getenv("SEED", "20260930"))

DEMO_CLASSES = [x.strip() for x in os.getenv("DEMO_CLASSES", "B,C,E,F,L,S,T").split(",") if x.strip()]
BASELINE_CLASSES = list("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
AMP = DEVICE.type == "cuda"

if len(DEMO_CLASSES) < 2:
    raise RuntimeError("Need at least two demo classes")
if len(set(DEMO_CLASSES)) != len(DEMO_CLASSES):
    raise RuntimeError(f"Duplicate demo classes: {DEMO_CLASSES}")
if any(c not in BASELINE_CLASSES for c in DEMO_CLASSES):
    raise RuntimeError(f"Unsupported class in DEMO_CLASSES: {DEMO_CLASSES}")

DEMO_TO_INDEX = {c: i for i, c in enumerate(DEMO_CLASSES)}


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
        img_path = GLYVEX / row["path"]
        img = Image.open(img_path).convert("L")
        if self.transform:
            img = self.transform(img)
        return img, int(row["demo_index"])


class OOVGlyphDataset(Dataset):
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
        return img


def build_model(n_classes: int) -> nn.Module:
    m = models.resnet18(weights=None)
    m.conv1 = nn.Conv2d(1, 64, kernel_size=3, stride=1, padding=1, bias=False)
    m.maxpool = nn.Identity()
    m.fc = nn.Linear(m.fc.in_features, n_classes)
    return m


def load_baseline_backbone(model: nn.Module):
    if not BASELINE_CKPT.exists():
        return False, "scratch"

    ck = torch.load(BASELINE_CKPT, map_location="cpu", weights_only=False)
    state = ck.get("model_state_dict", ck)

    # The baseline has the same ResNet-18 backbone but a 62-way classifier.
    # Load all compatible backbone tensors; skip fc.* due to output-size change.
    own = model.state_dict()
    loaded = {}
    skipped = []
    for k, v in state.items():
        if k.startswith("fc."):
            skipped.append(k)
            continue
        if k in own and own[k].shape == v.shape:
            loaded[k] = v
    missing, unexpected = model.load_state_dict(loaded, strict=False)
    if missing:
        # The only intentional missing tensors should be the new demo head.
        unexpected = list(unexpected)
    return True, f"transfer_from_62_class_epoch_{ck.get('epoch', 'unknown')}"


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
        total_loss += float(loss.item()) * bs
        n += bs
        ys.extend(y.detach().cpu().numpy().tolist())
        ps.extend(logits.argmax(1).detach().cpu().numpy().tolist())

    return {
        "loss": total_loss / max(1, n),
        "accuracy": accuracy_score(ys, ps),
        "macro_f1": fbeta_score(ys, ps, beta=1, average="macro", zero_division=0),
    }


def predict_all(model, loader):
    model.eval()
    ys, ps, probs = [], [], []
    with torch.no_grad():
        for x, y in loader:
            x = x.to(DEVICE, non_blocking=True)
            with torch.cuda.amp.autocast(enabled=AMP):
                logits = model(x)
            p = torch.softmax(logits, dim=1)
            ys.extend(y.numpy().tolist())
            ps.extend(logits.argmax(1).cpu().numpy().tolist())
            probs.append(p.cpu())
    return np.asarray(ys), np.asarray(ps), torch.cat(probs).numpy()


def save_learning_curves(history: pd.DataFrame):
    # Accuracy curve
    plt.figure(figsize=(10, 6))
    plt.plot(history["epoch"], history["train_accuracy"], label="Train accuracy")
    plt.plot(history["epoch"], history["val_accuracy"], label="Validation accuracy")
    plt.xlabel("Epoch")
    plt.ylabel("Accuracy")
    plt.title("GLYVEX Demo — Accuracy")
    plt.legend()
    plt.tight_layout()
    plt.savefig(EVAL_DIR / "accuracy_curve.png", dpi=180)
    plt.close()

    # Macro-F1 curve
    plt.figure(figsize=(10, 6))
    plt.plot(history["epoch"], history["train_macro_f1"], label="Train Macro-F1")
    plt.plot(history["epoch"], history["val_macro_f1"], label="Validation Macro-F1")
    plt.xlabel("Epoch")
    plt.ylabel("Macro-F1")
    plt.title("GLYVEX Demo — Macro-F1")
    plt.legend()
    plt.tight_layout()
    plt.savefig(EVAL_DIR / "macro_f1_curve.png", dpi=180)
    plt.close()

    # Loss curve
    plt.figure(figsize=(10, 6))
    plt.plot(history["epoch"], history["train_loss"], label="Train loss")
    plt.plot(history["epoch"], history["val_loss"], label="Validation loss")
    plt.xlabel("Epoch")
    plt.ylabel("Loss")
    plt.title("GLYVEX Demo — Loss")
    plt.legend()
    plt.tight_layout()
    plt.savefig(EVAL_DIR / "loss_curve.png", dpi=180)
    plt.close()


def calibrate_confidence_gate(model, val_loader, oov_loader):
    # In-vocabulary validation: the model should classify accepted samples correctly.
    y_true, y_pred, probs = predict_all(model, val_loader)
    conf = probs.max(axis=1)
    correct = (y_true == y_pred)

    # Out-of-vocabulary validation: excluded characters should be rejected, not
    # forcibly mapped to one of the seven command classes.
    model.eval()
    oov_conf = []
    with torch.no_grad():
        for x in oov_loader:
            x = x.to(DEVICE, non_blocking=True)
            with torch.cuda.amp.autocast(enabled=AMP):
                logits = model(x)
            oov_conf.extend(torch.softmax(logits, dim=1).max(dim=1).values.cpu().numpy().tolist())
    oov_conf = np.asarray(oov_conf, dtype=np.float32)

    rows = []
    thresholds = np.round(np.arange(0.50, 1.0001, 0.01), 2)
    for t in thresholds:
        accepted = conf >= t
        n = int(accepted.sum())
        coverage = float(n / len(conf)) if len(conf) else 0.0
        accepted_accuracy = float(correct[accepted].mean()) if n else 0.0
        wrong_accepted = int((accepted & ~correct).sum())
        oov_false_accept_rate = float((oov_conf >= t).mean()) if len(oov_conf) else float("nan")
        rows.append({
            "threshold": float(t),
            "accepted_samples": n,
            "coverage": coverage,
            "accepted_accuracy": accepted_accuracy,
            "wrong_accepted": wrong_accepted,
            "rejection_rate": float(1.0 - coverage),
            "oov_samples": int(len(oov_conf)),
            "oov_false_accept_rate": oov_false_accept_rate,
        })
    table = pd.DataFrame(rows)
    table.to_csv(EVAL_DIR / "confidence_gate_candidates.csv", index=False)

    eligible = table[
        (table["accepted_samples"] > 0)
        & (table["accepted_accuracy"] >= 0.99)
        & (table["oov_false_accept_rate"] <= 0.01)
    ]
    if len(eligible):
        # Lowest threshold meeting both: 99% accepted validation accuracy and
        # <=1% false acceptance of excluded validation classes.
        chosen = eligible.sort_values(["threshold"]).iloc[0]
        gate = {
            "policy": "accept top-1 prediction only when confidence >= threshold; otherwise reject",
            "threshold": float(chosen["threshold"]),
            "validation_accepted_accuracy": float(chosen["accepted_accuracy"]),
            "validation_coverage": float(chosen["coverage"]),
            "validation_wrong_accepted": int(chosen["wrong_accepted"]),
            "validation_oov_false_accept_rate": float(chosen["oov_false_accept_rate"]),
            "validation_oov_samples": int(chosen["oov_samples"]),
            "selection_rule": "lowest threshold with accepted accuracy >= 0.99 and OOV false-accept rate <= 0.01",
        }
    else:
        gate = {
            "policy": "manual_threshold_required",
            "threshold": None,
            "selection_rule": "No threshold in [0.50,1.00] achieved both >=0.99 accepted validation accuracy and <=0.01 OOV false-accept rate",
            "note": "Do not execute commands automatically until a threshold is selected from validation evidence.",
        }
    save_json(DEMO / "confidence_gate.json", gate)
    return gate



def save_confusions(y_true, y_pred):
    labels = np.arange(len(DEMO_CLASSES))
    cm = confusion_matrix(y_true, y_pred, labels=labels)
    np.savetxt(EVAL_DIR / "confusion_matrix.csv", cm, delimiter=",", fmt="%d")

    plt.figure(figsize=(10, 8))
    plt.imshow(cm, interpolation="nearest")
    plt.title("GLYVEX Demo — Test Confusion Matrix")
    plt.colorbar()
    ticks = np.arange(len(DEMO_CLASSES))
    plt.xticks(ticks, DEMO_CLASSES)
    plt.yticks(ticks, DEMO_CLASSES)
    plt.xlabel("Predicted")
    plt.ylabel("True")
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            plt.text(j, i, int(cm[i, j]), ha="center", va="center")
    plt.tight_layout()
    plt.savefig(EVAL_DIR / "confusion_matrix.png", dpi=180)
    plt.close()

    norm = cm / np.maximum(cm.sum(axis=1, keepdims=True), 1)
    plt.figure(figsize=(10, 8))
    plt.imshow(norm, interpolation="nearest", vmin=0, vmax=1)
    plt.title("GLYVEX Demo — Normalized Test Confusion Matrix")
    plt.colorbar()
    plt.xticks(ticks, DEMO_CLASSES)
    plt.yticks(ticks, DEMO_CLASSES)
    plt.xlabel("Predicted")
    plt.ylabel("True")
    for i in range(norm.shape[0]):
        for j in range(norm.shape[1]):
            plt.text(j, i, f"{norm[i, j]:.2f}", ha="center", va="center")
    plt.tight_layout()
    plt.savefig(EVAL_DIR / "confusion_matrix_normalized.png", dpi=180)
    plt.close()


def main():
    seed_all(SEED)
    df = pd.read_csv(META / "images.csv")
    split = pd.read_csv(META / "participant_split.csv")

    required = {"path", "label_index", "label", "participant", "instance", "split"}
    missing_cols = required - set(df.columns)
    if missing_cols:
        raise RuntimeError(f"Missing metadata columns: {missing_cols}")

    source_counts = df["label"].value_counts().to_dict()
    missing_demo = [c for c in DEMO_CLASSES if c not in source_counts]
    if missing_demo:
        raise RuntimeError(f"Demo labels absent from dataset: {missing_demo}")

    demo_df = df[df["label"].isin(DEMO_CLASSES)].copy()
    demo_df["demo_index"] = demo_df["label"].map(DEMO_TO_INDEX).astype(int)

    # Ensure no image is missing and split boundaries are unchanged.
    missing_paths = [p for p in demo_df["path"] if not (GLYVEX / p).exists()]
    if missing_paths:
        raise RuntimeError(f"Missing demo image files; first examples: {missing_paths[:10]}")

    train_p = set(split.loc[split.split == "train", "participant"])
    val_p = set(split.loc[split.split == "val", "participant"])
    test_p = set(split.loc[split.split == "test", "participant"])
    assert not (train_p & val_p or train_p & test_p or val_p & test_p)

    train_df = demo_df[demo_df.split == "train"].copy()
    val_df = demo_df[demo_df.split == "val"].copy()
    test_df = demo_df[demo_df.split == "test"].copy()

    # OOV validation examples come from the same held-out validation participants
    # but from classes excluded from the demo vocabulary. These are used only to
    # calibrate rejection; they are never used to train the classifier.
    oov_val_df = df[(df["split"] == "val") & (~df["label"].isin(DEMO_CLASSES))].copy()

    counts = {
        "train": train_df["label"].value_counts().reindex(DEMO_CLASSES).to_dict(),
        "val": val_df["label"].value_counts().reindex(DEMO_CLASSES).to_dict(),
        "test": test_df["label"].value_counts().reindex(DEMO_CLASSES).to_dict(),
    }
    save_json(DEMO / "dataset_summary.json", {
        "classes": DEMO_CLASSES,
        "num_classes": len(DEMO_CLASSES),
        "source_dataset_classes": len(BASELINE_CLASSES),
        "images": {"train": len(train_df), "val": len(val_df), "test": len(test_df)},
        "participants": {"train": len(train_p), "val": len(val_p), "test": len(test_p)},
        "oov_validation_images": len(oov_val_df),
        "per_class_counts": counts,
        "split_policy": "unchanged participant-level split from baseline dataset",
    })

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
    oov_val_set = OOVGlyphDataset(oov_val_df, eval_tf)
    pin = DEVICE.type == "cuda"
    kw = dict(batch_size=BATCH_SIZE, num_workers=NUM_WORKERS, pin_memory=pin, persistent_workers=NUM_WORKERS > 0)
    train_loader = DataLoader(train_set, shuffle=True, **kw)
    val_loader = DataLoader(val_set, shuffle=False, **kw)
    test_loader = DataLoader(test_set, shuffle=False, **kw)
    oov_loader = DataLoader(oov_val_set, shuffle=False, **kw)

    model = build_model(len(DEMO_CLASSES)).to(DEVICE)
    transfer_used, init_mode = load_baseline_backbone(model)

    criterion = nn.CrossEntropyLoss(label_smoothing=0.04)
    optimizer = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=WEIGHT_DECAY)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=EPOCHS, eta_min=LR * 0.03)
    scaler = torch.cuda.amp.GradScaler(enabled=AMP)

    nparams = sum(p.numel() for p in model.parameters())
    config = {
        "architecture": "ResNet-18 grayscale, modified stem, reduced demo head",
        "input": [None, 1, IMAGE_SIZE, IMAGE_SIZE],
        "classes": DEMO_CLASSES,
        "parameters": nparams,
        "device": str(DEVICE),
        "amp": AMP,
        "initialization": init_mode,
        "baseline_checkpoint": str(BASELINE_CKPT) if BASELINE_CKPT.exists() else None,
        "optimizer": "AdamW",
        "learning_rate": LR,
        "weight_decay": WEIGHT_DECAY,
        "loss": "CrossEntropyLoss(label_smoothing=0.04)",
        "seed": SEED,
    }
    save_json(DEMO / "model_config.json", config)

    print("=" * 72)
    print("GLYVEX DEMO RETRAIN / FINE-TUNE")
    print("=" * 72)
    print(f"Classes:      {DEMO_CLASSES}")
    print(f"Initialization: {init_mode}")
    print(f"Device:       {DEVICE}")
    if DEVICE.type == "cuda":
        print(f"GPU:          {torch.cuda.get_device_name(0)}")
    print(f"Images:       {len(train_df)} train / {len(val_df)} val / {len(test_df)} test")
    print(f"Participants: {len(train_p)} train / {len(val_p)} val / {len(test_p)} test")
    print(f"Parameters:   {nparams:,}")

    best_f1 = -1.0
    best_acc = -1.0
    best_epoch = 0
    stale = 0
    history = []
    best_ckpt = CKPT_DIR / "best_demo_model.pt"
    last_ckpt = CKPT_DIR / "last_demo_model.pt"
    start = time.time()

    for epoch in range(1, EPOCHS + 1):
        t0 = time.time()
        tr = epoch_pass(model, train_loader, criterion, optimizer, scaler)
        va = epoch_pass(model, val_loader, criterion)
        scheduler.step()
        rec = {
            "epoch": epoch,
            "lr": optimizer.param_groups[0]["lr"],
            "train_loss": tr["loss"],
            "train_accuracy": tr["accuracy"],
            "train_macro_f1": tr["macro_f1"],
            "val_loss": va["loss"],
            "val_accuracy": va["accuracy"],
            "val_macro_f1": va["macro_f1"],
            "seconds": time.time() - t0,
        }
        history.append(rec)
        print(
            f"Epoch {epoch:03d}/{EPOCHS} | "
            f"train loss {tr['loss']:.4f} acc {tr['accuracy']:.4f} F1 {tr['macro_f1']:.4f} | "
            f"val loss {va['loss']:.4f} acc {va['accuracy']:.4f} F1 {va['macro_f1']:.4f} | "
            f"lr {rec['lr']:.2e} | {rec['seconds']:.1f}s"
        )
        pd.DataFrame(history).to_csv(DEMO / "training_history.csv", index=False)

        torch.save({
            "epoch": epoch,
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "scheduler_state_dict": scheduler.state_dict(),
            "class_names": DEMO_CLASSES,
            "image_size": IMAGE_SIZE,
        }, last_ckpt)

        improved = (va["macro_f1"] > best_f1 + 1e-6) or (
            abs(va["macro_f1"] - best_f1) <= 1e-6 and va["accuracy"] > best_acc
        )
        if improved:
            best_f1, best_acc, best_epoch, stale = va["macro_f1"], va["accuracy"], epoch, 0
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "class_names": DEMO_CLASSES,
                "image_size": IMAGE_SIZE,
                "val_accuracy": best_acc,
                "val_macro_f1": best_f1,
            }, best_ckpt)
            print(f"  -> NEW BEST: val_acc={best_acc:.6f} val_macro_f1={best_f1:.6f}")
        else:
            stale += 1
            if stale >= PATIENCE:
                print(f"Early stopping after {epoch} epochs.")
                break

    history_df = pd.DataFrame(history)
    history_df.to_csv(DEMO / "training_history.csv", index=False)
    save_learning_curves(history_df)

    elapsed = (time.time() - start) / 60.0
    ck = torch.load(best_ckpt, map_location=DEVICE, weights_only=False)
    model.load_state_dict(ck["model_state_dict"])

    gate = calibrate_confidence_gate(model, val_loader, oov_loader)
    y_true, y_pred, probs = predict_all(model, test_loader)

    precision_macro, recall_macro, f1_macro, support = precision_recall_fscore_support(
        y_true, y_pred, labels=np.arange(len(DEMO_CLASSES)), average="macro", zero_division=0
    )
    precision_weighted, recall_weighted, f1_weighted, _ = precision_recall_fscore_support(
        y_true, y_pred, labels=np.arange(len(DEMO_CLASSES)), average="weighted", zero_division=0
    )

    metrics = {
        "best_epoch": int(best_epoch),
        "best_val_accuracy": float(best_acc),
        "best_val_macro_f1": float(best_f1),
        "test_accuracy": float(accuracy_score(y_true, y_pred)),
        "test_precision_macro": float(precision_macro),
        "test_recall_macro": float(recall_macro),
        "test_f1_macro": float(f1_macro),
        "test_precision_weighted": float(precision_weighted),
        "test_recall_weighted": float(recall_weighted),
        "test_f1_weighted": float(f1_weighted),
        "test_f0_5_macro": float(fbeta_score(y_true, y_pred, beta=0.5, average="macro", zero_division=0)),
        "test_f2_macro": float(fbeta_score(y_true, y_pred, beta=2.0, average="macro", zero_division=0)),
        "test_f0_5_weighted": float(fbeta_score(y_true, y_pred, beta=0.5, average="weighted", zero_division=0)),
        "test_f2_weighted": float(fbeta_score(y_true, y_pred, beta=2.0, average="weighted", zero_division=0)),
        "test_samples": int(len(y_true)),
        "training_minutes": float(elapsed),
        "classes": DEMO_CLASSES,
        "initialization": init_mode,
    }
    save_json(EVAL_DIR / "metrics.json", metrics)
    (EVAL_DIR / "classification_report.txt").write_text(
        classification_report(
            y_true,
            y_pred,
            labels=np.arange(len(DEMO_CLASSES)),
            target_names=DEMO_CLASSES,
            digits=4,
            zero_division=0,
        ),
        encoding="utf-8",
    )
    p, r, f, s = precision_recall_fscore_support(
        y_true, y_pred, labels=np.arange(len(DEMO_CLASSES)), zero_division=0
    )
    pd.DataFrame([
        {"class": DEMO_CLASSES[i], "precision": float(p[i]), "recall": float(r[i]), "f1": float(f[i]), "support": int(s[i])}
        for i in range(len(DEMO_CLASSES))
    ]).to_csv(EVAL_DIR / "per_class_metrics.csv", index=False)

    save_confusions(y_true, y_pred)
    np.save(EVAL_DIR / "test_probabilities.npy", probs)
    np.save(EVAL_DIR / "test_targets.npy", y_true)
    np.save(EVAL_DIR / "test_predictions.npy", y_pred)

    # Export best checkpoint as ONNX.
    model_cpu = model.to("cpu").eval()
    dummy = torch.zeros(1, 1, IMAGE_SIZE, IMAGE_SIZE)
    onnx_path = ONNX_DIR / "glyvex_demo_char_classifier.onnx"
    try:
        torch.onnx.export(
            model_cpu,
            dummy,
            onnx_path,
            opset_version=17,
            input_names=["input"],
            output_names=["logits"],
            dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
            dynamo=False,
        )
    except TypeError:
        torch.onnx.export(
            model_cpu,
            dummy,
            onnx_path,
            opset_version=17,
            input_names=["input"],
            output_names=["logits"],
            dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
        )

    import onnx
    import onnxruntime as ort
    onnx.checker.check_model(onnx.load(str(onnx_path)))
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

    save_json(ONNX_DIR / "classes.json", {
        "class_names": DEMO_CLASSES,
        "index_to_class": {str(i): c for i, c in enumerate(DEMO_CLASSES)},
    })
    save_json(ONNX_DIR / "model_metadata.json", {
        "model": "GLYVEX demo ResNet-18 grayscale character classifier",
        "opset": 17,
        "input_name": inp,
        "output_name": out,
        "input_shape": ["batch", 1, IMAGE_SIZE, IMAGE_SIZE],
        "input_dtype": "float32",
        "normalization": {"mean": [0.5], "std": [0.5]},
        "classes": DEMO_CLASSES,
        "num_classes": len(DEMO_CLASSES),
        "outputs_are": "logits; apply softmax in consumer",
        "best_epoch": int(best_epoch),
        "best_val_accuracy": float(best_acc),
        "best_val_macro_f1": float(best_f1),
        "test_metrics": metrics,
        "confidence_gate": gate,
        "onnx_max_absolute_logit_difference": diff,
    })
    save_json(ONNX_DIR / "onnx_sanity_check.json", {
        "max_absolute_logit_difference": diff,
        "pass": True,
    })

    report = f"""GLYVEX DEMO REDUCED-VOCABULARY RESULT
=========================================

Classes: {', '.join(DEMO_CLASSES)}
Initialization: {init_mode}

Train: {len(train_df)} images / {len(train_p)} participants
Val:   {len(val_df)} images / {len(val_p)} participants
Test:  {len(test_df)} images / {len(test_p)} participants

Best epoch:             {best_epoch}
Best val accuracy:      {best_acc:.6f}
Best val Macro-F1:      {best_f1:.6f}

TEST ACCURACY:          {metrics['test_accuracy']:.6f}
TEST PRECISION (MACRO): {metrics['test_precision_macro']:.6f}
TEST RECALL (MACRO):    {metrics['test_recall_macro']:.6f}
TEST MACRO-F1:          {metrics['test_f1_macro']:.6f}
TEST WEIGHTED-F1:       {metrics['test_f1_weighted']:.6f}
TEST F0.5 MACRO:        {metrics['test_f0_5_macro']:.6f}
TEST F2 MACRO:          {metrics['test_f2_macro']:.6f}

ONNX: {onnx_path}
ONNX max absolute logit difference: {diff:.8f}

Confidence gate:
  {json.dumps(gate, indent=2)}

Input: float32 [batch,1,128,128]
Normalize: (pixel/255 - 0.5) / 0.5
Output: float32 logits [batch,{len(DEMO_CLASSES)}]
Class order: {', '.join(DEMO_CLASSES)}

Java runtime policy:
  ONNX -> softmax -> confidence gate -> class allowlist -> command registry
  The model must never execute an OS command directly.
"""
    (DEMO / "FINAL_RESULT.txt").write_text(report, encoding="utf-8")

    print("=" * 72)
    print("TEST RESULTS")
    print("=" * 72)
    print(report)
    print("Classification report:\n")
    print((EVAL_DIR / "classification_report.txt").read_text())


if __name__ == "__main__":
    main()
