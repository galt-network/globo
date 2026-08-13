# User Figure Models for Globe.gl / Three.js

This document describes two lightweight 3D humanoid figures intended for use as placeable markers on a Globe.gl-based 3D world map (hundreds of instances). Both models are designed for low poly count and small file size.

## Model Overview

| Name                  | File                        | Size   | Structure                          | Best for                          |
|-----------------------|-----------------------------|--------|------------------------------------|-----------------------------------|
| `user-figure-simple`  | `user-figure-simple.glb`         | 5.4 KB | Single mesh + vertex colors        | Uniform recoloring, maximum performance |
| `user-figure-parts`   | `user-figure-parts.glb`   | 7.9 KB | Multiple named meshes + PBR materials | Per-part recoloring (head, body, arms, legs) |

Both models:
- Use **Y-up** coordinate system (standard for glTF / Three.js)
- Have their **origin at the feet** (min Y = 0) so they stand correctly on the globe surface
- Are approximately **1.30 units tall**
- Use a simplified / Lego-ish / Pegman-inspired proportions (slightly enlarged head for better silhouette recognition at small sizes)
- Are extremely low-poly (~120–140 vertices, ~210–250 faces)

---

## 1. `user-figure-simple`

**File**: `resources/public/3d/user-figure-simple.glb`

### Technical structure
- **Single mesh** (one draw call)
- No materials defined in the glTF
- Coloring is done exclusively via **vertex colors** (`COLOR_0` attribute)
- Three distinct vertex colors are baked in:
  - Skin tone (head + neck)
  - Blue (torso + arms)
  - Dark blue (legs)

When loaded with Three.js `GLTFLoader`, a default material (usually `MeshStandardMaterial`) is created with `vertexColors: true`.

### How to recolor the whole model

Because there is only one mesh and no named materials, the cleanest approach is to **replace the material**:

```js
const clone = base.clone(true);

clone.traverse((child) => {
  if (child.isMesh) {
    child.material = new THREE.MeshLambertMaterial({
      color: 0xff4444          // any hex / CSS color / THREE.Color
    });
  }
});
```

**Tinting** (keeps relative differences between body parts):

```js
clone.traverse((child) => {
  if (child.isMesh && child.material) {
    child.material.color.set(0xff4444);   // multiplies with existing vertex colors
    child.material.needsUpdate = true;
  }
});
```

### When to use this model
- You only need one color per figure instance
- Maximum performance is important (hundreds of simultaneous instances)
- You do not need to change head vs body vs legs independently

---

## 2. `user-figure-parts`

**File**: `user-figure-parts.glb`

### Technical structure
This is a **multi-node scene** with seven separate meshes, each with its own named PBR material.

#### Node / Mesh names (use these exact strings)
| Node name    | Description          | Default material name |
|--------------|----------------------|-----------------------|
| `head`       | Head (sphere)        | `mat_head`            |
| `neck`       | Neck                 | `mat_neck`            |
| `torso`      | Torso (box)          | `mat_torso`           |
| `left_arm`   | Left arm             | `mat_arm`             |
| `right_arm`  | Right arm            | `mat_arm`             |
| `left_leg`   | Left leg             | `mat_leg`             |
| `right_leg`  | Right leg            | `mat_leg`             |

#### Hierarchy after loading
```
Group / Scene
├── left_leg   (Mesh)
├── right_leg  (Mesh)
├── torso      (Mesh)
├── left_arm   (Mesh)
├── right_arm  (Mesh)
├── neck       (Mesh)
└── head       (Mesh)
```

All meshes are direct children of the root (flat hierarchy). You can reach any part with `object.getObjectByName("head")`.

### How to recolor individual parts

**Always clone the material** before changing it, otherwise every instance of the model will share the same material instance and change color together.

```js
function setPartColor(object3d, partName, color) {
  const part = object3d.getObjectByName(partName);
  if (part && part.isMesh) {
    part.material = part.material.clone();
    part.material.color.set(color);     // accepts hex, "#rrggbb", or THREE.Color
  }
}

// Example usage after cloning
const clone = base.clone(true);

setPartColor(clone, "head",      0xffcc99);
setPartColor(clone, "neck",      0xffcc99);
setPartColor(clone, "torso",     0xe74c3c);
setPartColor(clone, "left_arm",  0xe74c3c);
setPartColor(clone, "right_arm", 0xe74c3c);
setPartColor(clone, "left_leg",  0x2c3e50);
setPartColor(clone, "right_leg", 0x2c3e50);
```

#### One-pass version (recommended for production)

```js
clone.traverse((child) => {
  if (!child.isMesh) return;

  const colors = {
    head:      0xffcc99,
    neck:      0xffcc99,
    torso:     0xe74c3c,
    left_arm:  0xe74c3c,
    right_arm: 0xe74c3c,
    left_leg:  0x2c3e50,
    right_leg: 0x2c3e50
  };

  if (colors[child.name] !== undefined) {
    child.material = child.material.clone();
    child.material.color.set(colors[child.name]);
  }
});
```

### When to use this model
- You need different colors for head / body / arms / legs on the same figure
- You want the ability to highlight or restyle individual body parts at runtime
- Slightly higher cost than the simple version is acceptable

---

## Integration with Globe.gl / the `globo` project

In the existing codebase the models are preloaded via `preload-user-models` and instantiated in `create-3d-object` (see `src/is/galt/globo/ui/presentation/map.cljs`).

### Typical placeable data shape

```clojure
{:model-id "user-figure-parts"          ; or "user-figure-simple"
 :path     "3d/user-figure-parts.glb" ; relative to assets-base-url
 :lat      40.7128
 :lng      -74.0060
 :scale    0.12                         ; recommended starting scale
 :rotation {:x 0 :y 0 :z 0}             ; optional
 :colors   {"head" 0xffcc99             ; only useful with user-figure-parts
            "torso" 0x3498db
            ...}}
```

### Suggested extension to `create-3d-object`

```clojure
(defn create-3d-object
  [d]
  (let [model-key (or (j/get d :model-id) "carrot")
        base      (get @model-cache model-key)]
    (if base
      (let [clone  (j/call base :clone true)
            colors (j/get d :colors)]

        ;; Scale
        (j/update! clone :scale
                   (fn [s] (j/call s :setScalar (or (j/get d :scale) 1))))

        ;; Optional rotation
        (when-let [rot (j/get d :rotation)]
          (j/call clone :rotation :set
                  (or (j/get rot :x) 0)
                  (or (j/get rot :y) 0)
                  (or (j/get rot :z) 0)))

        ;; Per-part coloring (only works meaningfully with user-figure-parts)
        (when colors
          (j/call clone :traverse
                  (fn [child]
                    (when (and (j/get child :isMesh)
                               (j/get child :name)
                               (j/get colors (j/get child :name)))
                      (let [mat (j/call (j/get child :material) :clone)
                            col (j/get colors (j/get child :name))]
                        (j/call (j/get mat :color) :set col)
                        (set! (.-material child) mat))))))

        clone)

      ;; Fallback while loading
      (let [geom (THREE/SphereGeometry. 0.5 16 16)
            mat  (THREE/MeshLambertMaterial. #js {:color 0x00ff88})]
        (THREE/Mesh. geom mat)))))
```

For the simple model you can instead do a single material replacement when a top-level `:color` key is present.

---

## Performance notes

- Both models are intentionally tiny. Prefer `user-figure-simple` when you only need uniform coloring.
- Always **clone** the loaded scene (`base.clone(true)`) so each placed figure is independent.
- Always **clone materials** before mutating color when using `user-figure-parts`.
- The models contain no textures, no animations, and no skeletons — they are pure static geometry.
- Recommended visual scale on the globe is roughly `0.08` – `0.20` depending on camera altitude and desired marker size.

---

## Quick reference – which model should I use?

- Need only one color per person → **`user-figure-simple`**
- Need to color head / shirt / pants / arms independently → **`user-figure-parts`**
- Want the absolute smallest possible file and fewest draw calls → **`user-figure-simple`**

Both models are ready to drop into `resources/public/3d/` (or your configured assets directory) and work with the existing `GLTFLoader` + Draco pipeline used by the project.
