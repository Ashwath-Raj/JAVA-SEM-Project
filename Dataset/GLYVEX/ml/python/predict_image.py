import argparse, json
from pathlib import Path
import numpy as np
from PIL import Image
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[2]
ONNX = ROOT / "Dataset" / "GLYVEX" / "ml" / "onnx"

def softmax(x):
    x = x - x.max(axis=1, keepdims=True)
    e = np.exp(x)
    return e / e.sum(axis=1, keepdims=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--threshold", type=float, default=0.0)
    args = ap.parse_args()
    classes = json.loads((ONNX / "classes.json").read_text())["class_names"]
    img = Image.open(args.image).convert("L").resize((128,128))
    x = np.asarray(img, dtype=np.float32) / 255.0
    x = ((x - 0.5) / 0.5)[None,None,:,:]
    s = ort.InferenceSession(str(ONNX / "glyvex_char_classifier.onnx"), providers=["CPUExecutionProvider"])
    logits = s.run([s.get_outputs()[0].name], {s.get_inputs()[0].name:x})[0]
    p = softmax(logits)[0]
    top = np.argsort(p)[::-1][:5]
    best = int(top[0])
    print(f"prediction : {classes[best]}")
    print(f"confidence : {p[best]:.6f}")
    print("top-5:")
    for i in top:
        print(f"  {classes[int(i)]}: {p[int(i)]:.6f}")
    if p[best] < args.threshold:
        print(f"REJECT: confidence below {args.threshold:.6f}")

if __name__ == "__main__":
    main()
