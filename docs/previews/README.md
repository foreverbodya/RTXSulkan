# Actual prototype captures

These are native GPU captures from `qa-rough-noise-2`, not image-generation
mockups. Output is 2560x1440, reconstructed by native DLSS Quality from 1707x960.
The full interior PNG uses the optional rough reconstruction. The GIF compares
24 adjacent fixed-camera floor crops with reconstruction off/on in one frozen
authored room; trials use different jitter/ray sequences. It uses one fixed GIF
palette. No moving-camera, no-ghosting or general cinematic acceptance is claimed.

See `../../ROUGH_DENOISING.md` and `../../VALIDATION_SNAPSHOT.json` for scope,
retained rejected first revision and the local raw/filtered noise measurements.

File SHA-256:

- interior-rough-denoised.png: `8709df9fa3b5e156fb517dae1a490cc9733de1181c08c4a5f1a4fab4b1f086ee`
- rough-noise-before-after.gif: `daa07c0dfbb5ae33fd1e29ac57177226da33e158e3226beb443bee9b2d533069`
