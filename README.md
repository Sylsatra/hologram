# Hologram Projector (Fabric 1.21.8)

Render 3D “holograms” inside tinted-glass cuboids and drive them with redstone. Models and animations are powered by the BlazeRod rendering library. This project is intended for creators who want to place their own models/animations in-world and control them with a simple universal bus.

- No models are bundled with the mod. You must provide your own content.
- Server play is currently not tested.

---

## Features

- BlazeRod-based model rendering with CPU-transform fallback for stability.
- Per-cuboid, per-frame animation playback.
- External VMD animation support for PMX/PMD models (place .vmd files next to the model file).
- Redstone-driven “Universal Edge Bus” to set:
  - Model code (0–255)
  - Animation code (0–255; 0 means rest pose)
  - Ctrl code (0–15; reserved)
  - Param bus (scale/offsets), quantized 0–15
- In-game commands for discovery and control (with clickable map coordinates).

---

## How it works (high-level)

- Build a filled tinted-glass cuboid in-world.
- “Watch” the cuboid so the mod starts reading its redstone “bus” from the edges.
- Place your model files on disk in a folder based on the model code.
- Use redstone (or commands) to select model, animation, and parameters.
- The client renders the selected model in the cuboid’s center each frame, applying any selected animation clip.

Client-side rendering:
- `src/client/kotlin/com/sylsatra/hologram/client/HologramWorldRenderer.kt`
- `src/client/kotlin/com/sylsatra/hologram/client/HologramModelManager.kt`

Server-side bus and watching logic:
- `src/main/kotlin/com/sylsatra/hologram/state/HoloActivationRegistry.kt`

---

## Content folder hierarchy

Models and animations live in your Minecraft run directory (next to `mods/`, `saves/`, etc.):

- Windows: `%appdata%/.minecraft/hologram/models/`
- Linux: `~/.minecraft/hologram/models/`

Per-model code directories are 3-digit zero-padded numbers:

```
.hologram/
└─ models/
   └─ 003/
      ├─ model.pmx        # or .pmd, .glb, .gltf, .vrm
      ├─ Walk.vmd         # optional VMD clips for PMX/PMD
      ├─ Idle.vmd
      └─ manifest.json    # optional
```

Supported formats:
- Models: `.pmx`, `.pmd`, `.vrm`, `.glb`, `.gltf`
- Animations: `.vmd` (for MMD/PMX/PMD models)

Notes:
- VMD is an MMD format. It expects PMX/PMD rigs with matching MMD bone names.
- External `.vmd` files are auto-loaded from the model’s folder at scene load and lazily re-scanned if animations were initially empty.

---

## Optional manifest.json

Place next to your model (in `hologram/models/NNN/`):

```json
{
  "file": "model.pmx",
  "scale": 1.0,
  "offset": [0.0, 0.0, 0.0],
  "defaultAnimation": 0
}
```

- `file`: model filename to load. If omitted, the first supported file in the folder is used.
- `scale`: multiplicative scale applied to the root.
- `offset`: [x, y, z] translation applied to the root.
- `defaultAnimation`: currently not used when anim bus = 0 (because anim=0 means rest pose). Use anim > 0 to select clips.

---

## Universal Edge Bus (UEB)

The mod encodes three buses on the cuboid’s edges:
- Model bus: 8 bits (value 0–255)
- Anim bus: 8 bits (value 0–255)
- Ctrl bus: 4 bits (value 0–15, reserved for future features)

Each bus can be wired with:
- Digital bits (multiple edge ports → bitmask)
- Analog fallback (if only one port exists for that bus → single 0–15 nibble)

See implementation in `HoloActivationRegistry.computeCodes()` and `readBus()`.

### Port layout (edge enumeration)

- Bottom face edges → Model bits (8)
- Top face edges → Anim bits (8)
- Vertical edges (middle Y, centers) → Ctrl bits (4)
- Ports are enumerated to best fit the cuboid. If a bus can only fit a single port, it uses an analog 0–15 fallback.

Use `/holo map` to print the bus map for the cuboid you’re looking at. Coordinates are clickable:
- OP (permission ≥ 2): click to teleport.
- Non-OP: click to get a suggested TP command in chat.

---

## Parameter bus (scale and offsets)

Four additional analog “param” inputs are sampled at the middle layer’s face centers:
- scaleQ: north face center
- offXQ: east face center
- offYQ: south face center
- offZQ: west face center

Quantization: 0..15. A value of 0 means “inactive” (use manifest defaults).

Mapping used client-side in `HologramWorldRenderer`:
- Scale: `pScale = 0.25 + (scaleQ / 15) * 1.75` if `scaleQ > 0`, else `1.0`
- Offsets: `((Q - 8) / 8.0)` when `Q > 0`, else `0.0`

These parameters combine with manifest `scale` and `offset` at the render root.

---

## Animation behavior

- Anim bus values:
  - `0` → rest pose (no animation applied)
  - `1..N` → plays clip indices `0..N−1` (wraps if beyond available clips)
- On animation clip change, the mod:
  - Resets all `RELATIVE_ANIMATION` transforms to identity
  - Zeros all morph target weights
  - Resets time to 0 for a clean start

Troubleshooting:
- If animations don’t play:
  - Use PMX/PMD models when using VMD
  - Place `.vmd` inside the same `NNN/` folder
  - Watch the client log for “Loaded scene for code=… animations=K”; if `K=0`, no clips were loaded
  - Set the anim bus > 0
- If switching clips leaves weird poses:
  - A per-switch reset is applied. If issues persist, please report the format/rig used.

---

## Redstone wiring tips

- Build a solid tinted-glass cuboid (edges/size vary the available port count).
- Use `/holo map` to list exact coordinates of:
  - Model bits, Anim bits, Ctrl bits
  - Param bus face centers (scale, offX, offY, offZ)
- Digital bits: power a position with strength ≥ 1 to set that bit to 1.
- Analog fallback: when a bus only exposes one port, its 0..15 strength directly sets the value (0..15).

Examples:
- To play animation 10: set anim bus = 10
  - Command: `/holo setcodes <model> 10 <ctrl>` (requires OP)
  - Redstone: if analog fallback, feed strength 10; otherwise binary-encode 10 across the edge bits
- To select model folder `003`: set model bus = 3

---

## Commands

All commands live under `/holo`. They operate on the cuboid you’re looking at (look at a tinted glass block within ~20 blocks), or you can specify a position.

- `/holo detect [x y z]`
  - Detects and prints the cuboid bounds and size.
- `/holo map [x y z]`
  - Prints the bus map (Model/Anim/Ctrl bit port coordinates and Param ports).
  - Coordinates are clickable:
    - OP: click to teleport
    - Non-OP: click to get a suggested command in chat
- `/holo codes [x y z]`
  - Prints current Model/Anim/Ctrl codes for that cuboid (must be watched).
- `/holo setcodes <model> <anim> <ctrl> [x y z]`
  - Sets codes, broadcasts to clients immediately.
  - Requires permission level 2 (OP).
- `/holo watch [x y z]`
  - Begin watching a cuboid so its bus values are read and broadcast.
- `/holo unwatch [x y z]`
  - Stop watching a cuboid.

Permissions:
- All `/holo` commands are available to players, except `setcodes` which requires OP (level 2).

---

## Installation (players)

- Download the release jar and put it into your `mods/` folder.
- Requires:
  - Fabric Loader (Minecraft 1.21.8)
  - Fabric API
  - Fabric Language Kotlin
- No model content is included. Create your `hologram/models/NNN/` folders in your Minecraft run directory and add your own models/animations.

---

## Building from source (creators)

- Requirements:
  - JDK 21
  - Gradle (wrapper included)
- Optional: place prebuilt BlazeRod jars under `libs/` before building (the build is set up to jar-in-jar them).
  - `build.gradle` references:
    - `blazerod.jar`
    - `blazerod-render.jar`
    - `blazerod-model-base.jar`
    - `blazerod-model-formats.jar`
    - `blazerod-model-formats-gltf.jar`
    - `blazerod-model-formats-pmd.jar`
    - `blazerod-model-formats-pmx.jar`
    - `blazerod-model-formats-vmd.jar`
    - `blazerod-model-formats-bedrock.jar`
- Build:
  - `./gradlew build`
  - Output at `build/libs/*.jar`

---

## Known limitations

- GPU transform path is disabled by default; CPU transform is used for stability.
- VMD animations require PMX/PMD rigs with MMD-compatible bones.
- `anim = 0` intentionally shows rest pose (no animation).
- Server is not tested.

---

## Credits

- Rendering library: BlazeRod by Fifth Light (aka `fifth_light`). Thank you for the renderer and format loaders.
- Example media (not bundled with the mod; only referenced in the repository folder `vide0&image/`):
  - Image with Ditto — model credit: https://www.deviantart.com/sab64
  - Video featuring Ditto and Baltimore — model credits: https://www.deviantart.com/sab64 (Ditto), https://fantia.jp/fanclubs/104882 (Baltimore)
  - If you showcase the video, please mention both creator links above.

All rights to the BlazeRod library and the example models remain with their respective authors. This mod does not redistribute any third-party model content.

---

## License

- This mod is licensed under **GPLv3**.
- The BlazeRod library is licensed under **LGPL 3.0**. Please consult the BlazeRod license for details.
- Any third-party model or media content remains the property of its authors and is subject to their respective licenses/terms. This mod does not include or redistribute model files.
