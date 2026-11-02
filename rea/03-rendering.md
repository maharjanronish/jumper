# 03 — Rendering pipeline

Everything visual lives in `GameRenderer.java` (822 lines) + `Sprites.java` +
`ShaderUtil.java` + `FontAtlas.java`. Target budget (README claim):
**~20 draw calls, ~40 quads, 60 fps on a 2015-class phone.**

## GL setup

`GameView` (a `GLSurfaceView` subclass):
- `setEGLContextClientVersion(2)` — GLES 2.0
- `setEGLConfigChooser(8,8,8,0,16,0)` — RGB888 + 16-bit depth, no stencil
  (manual RGB565 picks render blank on some drivers — hard-won fix, see commit
  `1cd2ace` "Fix blank gameplay screen")
- `setPreserveEGLContextOnPause(true)` — instant resume
- `RENDERMODE_CONTINUOUSLY` (switched to `WHEN_DIRTY` on the game-over screen)

Global state: **depth test disabled** (painter's algorithm instead), culling
disabled, standard alpha blending `(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)`.

## The camera (`core/Camera.java`)

No matrices anywhere. The world is drawn as **screen-space quads in pixel
coordinates**, converted to NDC at emit time:

```
scale   = min(0.152·W, 0.068·H)
x_px    = W/2 + (x − camX)·scale
y_px    = H·0.58 − (z − camZ)·scale·RHO − h·scale·RISE_K     (RHO=0.50, RISE_K=0.80)
ndcX(p) = p/W·2 − 1        ndcY(p) = 1 − p/H·2
```

`RHO` compresses the ground plane (the iso look); `RISE_K` is how fast heights
(jump arcs, drop-ins) climb on screen. Plus the smoothed lateral guard (see 02).

## Shader programs (`ShaderUtil.java`) — three tiny ones

| Program | Vertex format | Used for |
|---|---|---|
| `TexProgram` | pos2 + uv2 + rgba4 (32 B) | block sprites, piece, shadow, absorb/trail discs, marker dot |
| `SpriteProgram` | pos2 + rgba4 (24 B) | background gradient, particles, annulus rings |
| `FontProgram` | pos2 + uv2 + uniform rgba | score, combo, popups (tint applies per draw call) |

All three are straight pass-through vertex shaders writing `gl_Position = vec4(aPos,0,1)`
— the "camera" is just the CPU-side pixel math. `TexProgram`'s fragment shader
multiplies texture RGB by per-vertex tint and texture A by per-vertex alpha.

One persistent **dynamic VBO** is created once in `onSurfaceCreated` and reused
every frame (`glBufferData` with `GL_DYNAMIC_DRAW`) — no per-frame buffer churn,
no stale attrib pointers. `DynBuf` is a growable direct `FloatBuffer` that
returns itself cleared.

## Textures (`Sprites.java`)

- **10 block sprites** decoded from `assets/blocks/*.png` (256 px wide).
  Each carries measured anchors:
  - `topCy` — pixel row of the **top-face center** (the landing surface), so art
    and collision zone coincide exactly (e.g. stone slab 84.05 px at 256 width);
  - `squash` — that art's top-face diamond h/w (0.486–0.662), so the perfect-zone
    ring renders concentric with the drawn face.
- **The chess piece is drawn procedurally** (no asset): 220×370 px `Canvas`/
  `Path` — dark tapered body `#343238`, left shade `#26242A`, collar ring,
  ivory head `#F6F1E4` with highlight. Anchor = bottom-center (the feet).
- **Soft disc**: 64×64 `RadialGradient` white blob (shadow, marker dot).
- Uploads use **straight alpha**: `Bitmap.getPixels` → RGBA bytes →
  `glTexImage2D(GL_RGBA)`. This deliberately avoids `GLUtils`' driver-dependent
  premultiplication quirks.

`Sprites.idOf(kind, style)` maps kind+style → sprite index (5 plain styles,
cylinder, 4 specials).

## Frame order (`onDrawFrame`)

```
dt = nanoTime delta (clamp 0.05)
game.update(dt) → handleEvents() → updateEffects(dt) → updateChargeAndTrail(dt)
→ updateBackground(dt) → camera.update(...)
clear (bgBot color) →
1. drawBackground()      2-triangle full-screen vertical gradient (per-vertex colors, 1 draw call)
2. drawWorld()           blocks + piece + marker + bonus rings (painter's order)
3. drawChargeAndTrail()  soft-disc batch: trail puffs + absorb dots (1 draw call)
4. drawParticles()       flat-color batch (1 draw call)
5. drawText()            score (+shadow pass), combo "x N", "+N" popups
```

## Painter's algorithm details (`drawWorld`)

- Visible blocks are drawn **far-to-near** (z descending).
- **The standing block is never drawn over the piece**: while not dead, the code
  computes which block the piece is over (`contains(..., 0.35f)`) and inserts
  the piece *in front of* it in the z-ordered walk — otherwise far-half landings
  would sink the piece "into" the box art.
- While charging, the current block **squashes down 15% together with the piece**
  (the dip is also added to the piece's screen Y so they move as one).
- Squash math per block sprite: the sprite is compressed around its **bottom
  edge**, so the top-face anchor (and the piece standing on it) rides DOWN by
  `(1−q)·bodyHeight` — computed from each sprite's own `topCy`.
- Drop-in: `dropOf(block) = (spawnT/0.30)² · 4.5` world units subtracted via
  `camera.rise()`; landing settle adds a brief 5% squash.
- Perfect marker: pulsing (`0.82+0.18·sin(5.5t)`) ellipse annulus (20 segments,
  flat program) + tinted soft-disc dot, radius `1.25 ×` the scoring radius,
  ellipse ratio from that art's `squash`.

## Piece rendering

- Blob shadow: soft disc under the feet on whichever block is below (within
  `halfW+0.6`), shrinking and fading with height (`r = 0.42·(1−min(0.5, 0.09h))`).
- The piece quad is drawn **rotated around its bottom-center anchor** (4 corner
  offsets rotated by `tilt = wobble·150° + clamp(tumbleDeg, ±24°)`).
- Squash & stretch: `w = 1.10·scale·widenXZ`, `h = 1.85·scale·squashY`.
- Piece is hidden once it falls below `FALL_KILL_Y − 1`.

## Text (`FontAtlas.java`)

Runtime bitmap font — **zero font assets**. Glyphs `"0123456789+x"` are drawn
white with `Canvas.drawText` (`sans-serif-light` for the big score,
`sans-serif-medium` for popups) at 128 px onto an ARGB_8888 strip, uploaded as
GL_RGBA (some GPUs mishandle ALPHA_8). Per-frame:
- Big score centered at `H·0.115`, size `H·0.085` — drawn **twice**: a soft
  shadow pass (alpha 0.16, offset −0.016 NDC) under the crisp pass (alpha 0.95).
- Combo "x N" (`H·0.028`) under the score while combo ≥ 2.
- "+N" popups anchored to world points, rising 0.9 units and fading over 0.85 s,
  ivory for perfects, gold `#EBA940` for special bonuses.

Because `uColor` is a *uniform* on `FontProgram`, each popup needs its own draw
call; the score's two passes are also separate calls — a small, known cost.

## Effects simulation (CPU, world space)

- **Particles**: ivory dust (size 0.05–0.09) or gold sparks (0.075–0.115);
  velocity spread ring, gravity 11, one bounce (restitution 0.38, ground friction
  0.65), life 0.85 s, hard cap 72 live particles.
- **Absorb dots** while charging: spawn rate 6–32/s ∝ power, ring radius
  0.65–1.35, height 0.03–0.48, exponential homing into the body
  (`k = 1−e^(−4.5·dt·(0.6+power))`), absorbed when within ~0.07 units, cap 42.
- **Trail puffs**: one per 0.03 s of flight at `feet+0.55`, radius 0.085→0.030,
  alpha 0.55→0, life 0.50 s, cap 96.
- **Rings** (special bonus only): expanding ellipse 0.15→0.85 world units over
  0.45 s, fading 0.34→0.
- All transient effect lists are plain `ArrayList`s with remove-from-tail
  iteration; cleared on `resetGame`.

## Render-loop lifecycle notes

- `onSurfaceCreated`: compile programs, create fonts + textures, gen the VBO.
  (Textures are *not* re-created on surface change — `preserveEGLContext` keeps them.)
- `resetGame(seed)` clears every effect list and re-times the frame clock —
  called on the GL thread via `queueEvent` from the Play Again button.
- `onSurfaceChanged`: viewport + one camera update with `dt = 0` (snap).
