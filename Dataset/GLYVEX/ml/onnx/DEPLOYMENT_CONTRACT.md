# GLYVEX ONNX Deployment Contract

Input tensor:
- dtype: float32
- shape: `[batch, 1, 128, 128]`
- source: normalized 128x128 grayscale trajectory raster
- normalization: `(pixel/255 - 0.5) / 0.5`

Output tensor:
- dtype: float32
- shape: `[batch, 62]`
- meaning: logits, not probabilities
- apply softmax, then argmax

Class order:
`0-9, a-z, A-Z`

Runtime flow:
`touchpad trajectory -> same rasterizer -> ONNX -> logits -> softmax -> confidence gate -> character -> explicit command registry`

The model must never execute an operating-system command directly.
