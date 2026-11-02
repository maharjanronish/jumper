# 06 — The `Jumper` rebuild: choices & deltas

This repo's root is a **function-by-function rebuild** of the analyzed game,
restructured for minimal file count and minimal per-frame work, preserving the
gameplay feel exactly. This doc records what was kept, what changed, and why.

## Language choice: Java (deliberately)

The original question was "Java, or which language would be best?" For a game
whose entire point is *minimal resource use*, the answer is plain Java:

| Option | APK delta | Verdict |
|---|---|---|
| **Java (chosen)** | baseline | zero extra runtime; `d8` desugars the few lambdas; core runs on the desktop JVM |
| Kotlin | + kotlin-stdlib (≈1.5 MB dex or extra R8 pass) | nicer syntax, but you pay in either size or build complexity — against the goal |
| C++ / NDK | + one `.so` per ABI (×4 today) + JNI bridge | makes sense for AAA physics, absurd here; the logic is 2 ms/frame of float math |
| Rust / others | same native-bloat problem + immature Android tooling for zero gain | no |

The rebuild keeps the original's killer feature: a **pure-Java `core/` package
with zero Android imports**, so the shipping rules are verified on a desktop
JVM by `test/LogicTest.java` — no emulator needed.

## What is IDENTICAL (function-for-function parity)

- Every gameplay tuning constant — projection (RHO 0.50 / RISE_K 0.80 / scale),
  lanes (18°±5°, runs 3–6), charge physics (1.15 s → 1.10..8.20 units),
  flight/arc formulas, landing ladder thresholds (inset 0.06, edge 0.18, wall
  0.60, perfect `min(0.30, 0.16·halfW)`, wobble 0.62), scoring (2,4,6… cap 32),
  stay bonuses (+5/+10/+15/+30 after 1.9 s, one-shot, forfeit-on-jump),
  difficulty ramps, spawn probabilities, particle/popup/absorb/trail budgets.
- The state machine (`READY, CHARGING, FLYING, LANDED, SPECIAL_WAIT,
  SPECIAL_ANIM, FALLING, OVER`) and every transition.
- The camera math, including the lateral guard bands (24–76% / 14–86%).
- The block art (`assets/blocks/*.png`, same 10 sprites + measured anchors),
  the procedural chess piece, the soft disc, the runtime bitmap fonts.
- All nine PCM synth recipes and the combo pitch-shift trick.
- Zero permissions, zero dependencies, GLES 2.0, `android.useAndroidX=false`,
  portrait single-task activity, EGL config (RGB888 + 16-bit depth),
  preserve-context-on-pause, battery-saving `WHEN_DIRTY` on the game-over panel.
- Leaderboard semantics: top-10 JSON in SharedPreferences, best, sound toggle,
  first-run hint pill, back-button behavior (records → card → quick retry).

## What is restructured / optimized

| # | Change | Original | Rebuild | Why |
|---|---|---|---|---|
| 1 | **Zero XML UI** | `activity_main.xml` (192 ln) + 5 drawable XMLs + strings/styles/colors | programmatic UI (`GradientDrawable`/`StateListDrawable` in code); `res/` = launcher icons only | fewer resources, smaller `resources.arsc`, nothing aapt can get wrong |
| 2 | **2 shader programs** | 3 (Tex, Sprite, Font) | 2 (Tex, Sprite) — text renders through the textured program with **per-vertex tint** (white glyphs × tint ≡ uniform-color math) | one less program bind; enables batching text passes |
| 3 | **Single-texture atlas + batched submission** | 13 separate textures, one `glBufferData`+draw per quad (~20 calls/frame) | all 13 sprites (10 blocks + piece + disc + a new ring sprite) packed into ONE 512-px atlas, submitted in painter order as ONE draw call; bg/particles → 1 flat call; score shadow+crisp → 1 call; combo+popups → 1 call | typical frame drops to **~5 draw calls** with identical painter order; the 20-segment annulus becomes a 2-triangle ring sprite |
| 4 | **Event drain without allocation** | `drainEvents()` copies a new `ArrayList` every frame | swap-buffer drain, zero garbage per frame | smoother GC on low-end devices |
| 5 | **SFX cache write-once** | 9 temp WAVs rewritten on every launch | written only if absent | no per-launch disk churn |
| 6 | **File consolidation** | 15 app files / 3,099 LOC + 10 UI XML files | 11 app files / 3,113 LOC, **0 UI XML** (`Piece`+`Ev` nested into `Game`, `ShaderUtil`+`DynBuf` into `GameRenderer`, `FontAtlas` into `Art`; the UI XML became programmatic code and the batcher adds lines while cutting draw calls — LOC is at parity, file and resource count drop) | fewer seams, no resource layer at all |
| 7 | **Magic-number sync removed** | `SETTLE_T` duplicated in renderer "must match Game" | one constant in `Cfg` | kills a latent bug class |
| 8 | **Straight alpha everywhere** | fonts uploaded premultiplied (GLUtils), sprites straight | all textures straight alpha | one consistent blending path |
| 9 | Version hygiene | Gradle stuck at 1/"1.0.0" vs script 6/"2.1.0" | single source of truth (script env defaults = Gradle defaults = 1/"1.0.0") | no more stale-version APKs |

App identity changes (so both games can coexist): package
`io.github.maharjanronish.jumper`, label **"Jumper"**, prefs file `"jumper"`.

## Verification

`test/LogicTest.java` (desktop JVM, no Android) ports the original 34-check
suite and adds a rebuild-specific group — **38 checks, all passing**:

```bash
javac -d out $(find app/src/main/java -name '*.java' | grep /core/) test/LogicTest.java
java -cp out LogicTest
```

Groups: camera composition on a simulated 1080×2340 screen · perfect-bot
survival over 40 seeds × 150 jumps (6,000 jumps, zero deaths, all levels
clearable) · combo scoring 2,4,6,…,cap 32 · mini-hops · fall detection for all
four reasons · special-block stay bonuses (one-shot + forfeit) · generation
invariants (no overlap, always reachable, clean intro, all kinds appear,
blocks shrink with score) · the zero-allocation drain contract (including
buffer-reuse identity).

The full presentation layer is compile-verified against `android-34`
(`javac -source 8 -target 8` with `android.jar` on the classpath — the exact
pipeline `scripts/build_apk.sh` uses) and is built into a signed APK by CI on
push; the drawing math is a 1:1 port of the original's proven formulas.

## Known deltas (intentional)

- Text color is now per-vertex instead of per-draw uniform — visually identical.
- The score's soft shadow uses per-vertex alpha 0.16 (same value as before).
- Records panel / game-over card are built in code, so pixel-identical layout
  is approximated (margins/radii kept in dp, values unchanged).
- `onBackPressed` override kept for API 21–32 compat (same as upstream; the
  deprecation is noted in rea/05).
