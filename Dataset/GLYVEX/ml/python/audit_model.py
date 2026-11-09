from pathlib import Path
import json
import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score, balanced_accuracy_score, cohen_kappa_score,
    confusion_matrix, matthews_corrcoef, precision_score, recall_score,
    f1_score, fbeta_score, precision_recall_fscore_support,
    roc_auc_score, average_precision_score,
)
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path.cwd()
G = ROOT / "Dataset" / "GLYVEX"
ML = G / "ml"
TR = ML / "training"
EV = ML / "evaluation"
AU = ML / "audit"
classes = list("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
K = len(classes)

hist = pd.read_csv(TR / "history.csv")
needed = {"epoch","train_loss","val_loss","train_accuracy","val_accuracy","train_macro_f1","val_macro_f1"}
missing = needed - set(hist.columns)
if missing: raise SystemExit(f"history.csv missing: {sorted(missing)}")

# Epoch-level fit diagnostics
hist = hist.copy()
hist["accuracy_gap"] = hist.train_accuracy - hist.val_accuracy
hist["f1_gap"] = hist.train_macro_f1 - hist.val_macro_f1
best_i = hist.val_macro_f1.idxmax()
best = hist.loc[best_i]
post = hist[hist.epoch >= best.epoch]
cands = post[(post.train_macro_f1 > best.train_macro_f1) & (post.f1_gap >= 0.05)]
overfit_epoch = int(cands.iloc[0].epoch) if len(cands) else None
hist.to_csv(AU / "epoch_diagnostics.csv", index=False)

fig, ax = plt.subplots(1,3,figsize=(17,5))
ax[0].plot(hist.epoch,hist.train_loss,label="train"); ax[0].plot(hist.epoch,hist.val_loss,label="val"); ax[0].set_title("Loss"); ax[0].legend(); ax[0].grid(alpha=.25)
ax[1].plot(hist.epoch,hist.train_accuracy,label="train"); ax[1].plot(hist.epoch,hist.val_accuracy,label="val"); ax[1].set_title("Accuracy"); ax[1].legend(); ax[1].grid(alpha=.25)
ax[2].plot(hist.epoch,hist.train_macro_f1,label="train"); ax[2].plot(hist.epoch,hist.val_macro_f1,label="val"); ax[2].set_title("Macro-F1"); ax[2].legend(); ax[2].grid(alpha=.25)
fig.suptitle("GLYVEX Train/Validation Fit Diagnostic"); fig.tight_layout(); fig.savefig(AU/"learning_curves.png",dpi=180); plt.close(fig)

# Reuse exact held-out test outputs if they exist.
Y = EV / "test_targets.npy"
P = EV / "test_predictions.npy"
PR = EV / "test_probabilities.npy"
if not (Y.exists() and P.exists()):
    raise SystemExit("Missing test_targets.npy/test_predictions.npy. The model was evaluated, but those arrays were not saved by the training script. Do not retrain; ask for the recompute-audit script.")
y = np.load(Y); pred = np.load(P)
probs = np.load(PR) if PR.exists() else None

metrics = {
    "accuracy": accuracy_score(y,pred),
    "balanced_accuracy": balanced_accuracy_score(y,pred),
    "precision_macro": precision_score(y,pred,average="macro",zero_division=0),
    "recall_macro": recall_score(y,pred,average="macro",zero_division=0),
    "precision_micro": precision_score(y,pred,average="micro",zero_division=0),
    "recall_micro": recall_score(y,pred,average="micro",zero_division=0),
    "precision_weighted": precision_score(y,pred,average="weighted",zero_division=0),
    "recall_weighted": recall_score(y,pred,average="weighted",zero_division=0),
    "f0.5_macro": fbeta_score(y,pred,beta=.5,average="macro",zero_division=0),
    "f1_macro": f1_score(y,pred,average="macro",zero_division=0),
    "f2_macro": fbeta_score(y,pred,beta=2,average="macro",zero_division=0),
    "f0.5_weighted": fbeta_score(y,pred,beta=.5,average="weighted",zero_division=0),
    "f1_weighted": f1_score(y,pred,average="weighted",zero_division=0),
    "f2_weighted": fbeta_score(y,pred,beta=2,average="weighted",zero_division=0),
    "mcc": matthews_corrcoef(y,pred),
    "cohen_kappa": cohen_kappa_score(y,pred),
}
for k in (1,3,5):
    if probs is not None:
        metrics[f"top{k}_accuracy"] = float(np.mean([int(t in np.argsort(r)[-k:]) for t,r in zip(y,probs)]))
try:
    if probs is not None: metrics["roc_auc_ovr_macro"] = roc_auc_score(y,probs,multi_class="ovr",average="macro",labels=np.arange(K))
except Exception as e: metrics["roc_auc_note"] = str(e)
try:
    if probs is not None:
        oh = np.eye(K)[y]
        metrics["pr_auc_macro"] = average_precision_score(oh,probs,average="macro")
except Exception as e: metrics["pr_auc_note"] = str(e)

cm = confusion_matrix(y,pred,labels=np.arange(K))
np.savetxt(AU/"confusion_matrix.csv",cm,fmt="%d",delimiter=",")
fig,ax=plt.subplots(figsize=(18,16)); im=ax.imshow(cm.astype(float)/np.maximum(cm.sum(1,keepdims=True),1),cmap="Blues",vmin=0,vmax=1); ax.set_xticks(range(K)); ax.set_yticks(range(K)); ax.set_xticklabels(classes,fontsize=7); ax.set_yticklabels(classes,fontsize=7); ax.set_xlabel("Predicted"); ax.set_ylabel("True"); ax.set_title("GLYVEX Normalized Test Confusion Matrix"); fig.colorbar(im,ax=ax); fig.tight_layout(); fig.savefig(AU/"confusion_matrix.png",dpi=180); plt.close(fig)

pp,rr,ff,sup = precision_recall_fscore_support(y,pred,labels=np.arange(K),zero_division=0)
f2 = fbeta_score(y,pred,beta=2,labels=np.arange(K),average=None,zero_division=0)
rows=[]
for i,c in enumerate(classes):
    tp=cm[i,i]; fn=cm[i].sum()-tp; fp=cm[:,i].sum()-tp; tn=cm.sum()-tp-fn-fp
    rows.append({"label":c,"precision":pp[i],"recall":rr[i],"specificity":tn/max(1,tn+fp),"f1":ff[i],"f2":f2[i],"support":int(sup[i])})
pd.DataFrame(rows).sort_values("f1").to_csv(AU/"per_class_metrics.csv",index=False)

pairs=[]
for i in range(K):
    for j in range(K):
        if i!=j and cm[i,j]: pairs.append((int(cm[i,j]),classes[i],classes[j]))
pd.DataFrame(sorted(pairs,reverse=True),columns=["count","true","predicted"]).to_csv(AU/"top_confusions.csv",index=False)

json.dump({k:(float(v) if isinstance(v,(np.floating,float)) else v) for k,v in metrics.items()},open(AU/"metrics.json","w"),indent=2)

report=[]
report += ["GLYVEX MODEL AUDIT","="*72,"", "FIT DIAGNOSTIC","-"*72]
report += [f"Best validation Macro-F1: epoch {int(best.epoch)} = {best.val_macro_f1:.6f}",f"Best validation accuracy: {hist.loc[hist.val_accuracy.idxmax(),'val_accuracy']:.6f}",f"Minimum validation loss: epoch {int(hist.val_loss.idxmin())} = {hist.val_loss.min():.6f}",f"Final train Macro-F1: {hist.iloc[-1].train_macro_f1:.6f}",f"Final val Macro-F1: {hist.iloc[-1].val_macro_f1:.6f}",f"Final train-val F1 gap: {hist.iloc[-1].f1_gap:.6f}"]
report += [f"Heuristic overfitting onset: epoch {overfit_epoch}" if overfit_epoch else "Heuristic overfitting onset: not detected"]
report += ["", "TEST METRICS","-"*72]
for k,v in metrics.items(): report.append(f"{k}: {v:.6f}" if isinstance(v,(float,np.floating)) else f"{k}: {v}")
report += ["", "INTERPRETATION","-"*72]
if overfit_epoch:
    report.append(f"Training continued improving after validation peaked; an increasing generalization gap appears from about epoch {overfit_epoch}. The saved best checkpoint at epoch {int(best.epoch)} is preferable to the final epoch for deployment.")
else:
    report.append("No strong classical overfitting signal was detected by the heuristic. Inspect the curves and per-class metrics rather than relying on a single number.")
report += ["F2 weights recall more heavily than F1; F0.5 weights precision more heavily.","Macro averages treat all 62 classes equally; weighted averages reflect support.","The held-out test set is evaluation-only and should not be used to choose a new checkpoint.","", "FILES","-"*72, str(AU/"epoch_diagnostics.csv"),str(AU/"learning_curves.png"),str(AU/"confusion_matrix.png"),str(AU/"per_class_metrics.csv"),str(AU/"top_confusions.csv"),str(AU/"metrics.json")]
(AU/"GLYVEX_MODEL_AUDIT.txt").write_text("\n".join(report)+"\n")
print("\n".join(report))
