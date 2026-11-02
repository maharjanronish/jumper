# Reverse-Engineering Analysis — `jumpjump`

Complete technical breakdown of **Jump Jump** (`io.github.maharjanronish.jumpjump`,
https://github.com/maharjanronish/jumpjump), a from-scratch, zero-dependency
Android remake of WeChat's 2017 mini-game **跳一跳 (Jump Jump)**.

These notes were produced by reading the full source tree (55 tracked files,
~3,544 lines of Java) and cross-checking the build pipeline. They document
**how every feature of the game is implemented**, feature by feature, so the
lean rebuild in the root of this repo (`Jumper`) could be written function-for-function.

## Document index

| Doc | Contents |
|---|---|
| [01-architecture.md](01-architecture.md) | Identity, tech stack, module map, threading model, event flow, manifest, zero-dependency audit |
| [02-gameplay.md](02-gameplay.md) | Input → charge → jump pipeline, flight physics, the landing-resolution ladder, scoring & combos, special-block stay bonuses, difficulty ramp, level generation |
| [03-rendering.md](03-rendering.md) | 2.5D camera math, sprite anchoring, painter's algorithm, the shader programs, runtime font atlas, all visual effects, frame budget |
| [04-audio-persistence.md](04-audio-persistence.md) | All 9 sound effects synthesized as PCM (recipes), WAV plumbing, SoundPool wiring; SharedPreferences leaderboard format |
| [05-build-signing.md](05-build-signing.md) | Dual build paths (Gradle + shell), d8 desugaring trick, committed keystore, CI, quirks & bugs found |
| [06-rebuild-notes.md](06-rebuild-notes.md) | The `Jumper` rebuild: language choice, what is identical, what is optimized, measured deltas |

## Executive summary

- **What it is**: pure Java (no Kotlin), OpenGL ES 2.0 sprite renderer, one
  Activity, **zero external dependencies** (`dependencies {}` is literally
  empty), **zero permissions**, fully offline. APK ≈ 168 KB (README claim;
  ~64 KB before art). No Unity, no Cocos, no libGDX, no native code, no ads,
  no analytics.
- **Architecture**: a strict split between a **pure-Java game core**
  (`core/`, runnable and tested on the desktop JVM) and a thin Android
  presentation layer (GLES2 renderer + SoundPool + SharedPreferences).
  The core communicates to the renderer through a drained event list.
- **Content pipeline is (almost) asset-free**: the only binary assets are
  10 hand-drawn isometric platform PNGs (~102 KB). The chess-piece character,
  the soft shadow disc, the fonts, and **all nine sounds are generated at
  runtime** (Canvas drawing / PCM synthesis).
- **Game loop**: there is no separate logic thread. `GLSurfaceView`'s render
  callback *is* the game loop: `onDrawFrame → game.update(dt)` with dt from
  `System.nanoTime()`, clamped to 50 ms. Touch input is marshalled UI→GL via
  `queueEvent`, UI updates GL→UI via `runOnUiThread`.
- **Feel-matching tuning**: charge time maps *linearly* to jump distance
  (1.15 s → 1.10..8.20 world units), matching the famous
  `wangshub/wechat_jump_game` auto-player research cited in `Config.java`.

## How you would verify this from the APK alone

The analysis here was done from source, but every finding is verifiable from
the shipped APK (`JumpJump.apk`, CI artifact) with standard RE tooling:

```bash
# 1. Structure + manifest + resources (aapt2 comes with build-tools)
aapt2 dump badging JumpJump.apk        # package, sdk versions, permissions (none), launcher
unzip -l JumpJump.apk                  # classes.dex, assets/blocks/*.png, res/mipmaps, resources.arsc

# 2. Dalvik bytecode -> readable Java
jadx-gui JumpJump.apk                  # or: jadx -d out/ JumpJump.apk
#   io.github.maharjanronish.jumpjump      → 8 presentation classes
#   io.github.maharjanronish.jumpjump.core → 7 pure-logic classes (no android.* imports)

# 3. Resources / exact XML
apktool d JumpJump.apk -o out/         # AndroidManifest, layout, drawables, strings

# 4. Confirm "zero dependencies"
unzip -l JumpJump.apk | grep -c '\.so'          # → 0 native libs
aapt2 dump badging JumpJump.apk | grep -i uses-permission   # → none
```

What you would see in jadx: a single-activity app whose `GameView` extends
`GLSurfaceView`, a `GameRenderer` implementing `Renderer` with three tiny
inline GLSL programs, and a `core` package whose imports are all `java.*` —
the giveaway that the logic is desktop-testable by design.

## Provenance & license

Both `jumpjump` (the analyzed game) and this rebuild are MIT-licensed projects
by the same author. The analysis and the rebuild reuse only that author's own
work; game *mechanics* are not copyrightable, and no Tencent assets, audio, or
code are involved (explicitly noted in the upstream README's legal section).
