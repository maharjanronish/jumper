# Jumper

A featherweight, function-by-function rebuild of
[Jump Jump](https://github.com/maharjanronish/jumpjump) (itself an offline
Android remake of WeChat's 跳一跳), restructured for minimal file count and
minimal per-frame work. Same gameplay, same feel, same art — fewer moving
parts.

**Zero dependencies. Zero permissions. Zero XML UI. Fully offline.**

| | Jump Jump (original) | Jumper (this rebuild) |
|---|---|---|
| App source files | 15 (3,099 LOC) + 10 UI XML | **11 (~3,100 LOC), 0 UI XML** |
| Shader programs | 3 | **2** |
| GL textures (sprites) | 13 separate | **1 atlas** (10 block sprites + piece + disc + ring) |
| Draw calls / frame | ~20 | **~5** (atlas-batched world, batched text) |
| Layout / drawable / values XML | 10 files | **0** (programmatic UI; `res/` = icons only) |
| SFX cache WAVs | rewritten every launch | **written once** |
| Event drain | allocates per frame | **zero-allocation swap buffer** |
| APK | ~168 KB | expected similar or smaller (CI builds it) |

The complete reverse-engineering analysis behind this rebuild — architecture,
physics, landing rules, rendering, audio, build/signing, and every quirk found
along the way — lives in [`rea/`](rea/README.md).

## Play

Hold anywhere to charge, release to jump. Land dead-center for combo points
(2, 4, 6 … capped at 32). Stay ~2 s on special blocks for bonuses
(manhole +5, Rubik +10, shop +15, record player +30). Blocks shrink and gaps
widen as you climb.

## Build

**Gradle (IDE):** open the project — AGP 7.4.2, compileSdk 34, minSdk 21,
Java 8, `dependencies {}` empty.

**Shell (canonical release path, what CI uses):**

```bash
ANDROID_HOME=... JAVA_HOME=... scripts/build_apk.sh
# → Jumper.apk (aapt2 + javac + d8 + zipalign + apksigner, no Gradle needed)
```

Every push to `main` builds the APK in CI (`.github/workflows/build.yml`);
`v*` tags publish a Release with the APK attached.

## Test the game logic (desktop JVM, no emulator)

```bash
javac -d out $(find app/src/main/java -path '*/core/*.java') test/LogicTest.java
java -cp out LogicTest
```

34 checks ported from upstream + 4 rebuild-specific (drain contract): camera
composition, a perfect-bot surviving 6,000 jumps across 40 seeds, combo
scoring, mini-hops, all four fall reasons, special-block bonuses, and
level-generation invariants. **All 38 pass.**

## Layout

```
app/src/main/java/io/github/maharjanronish/jumper/
├── core/          pure Java, zero Android imports (runs on the desktop JVM)
│   ├── Cfg.java       every tunable constant (identical values to upstream)
│   ├── Game.java      state machine + Piece + events (the rules)
│   ├── Level.java     block model + generation (runs, kinds, difficulty)
│   ├── Camera.java    fixed-angle 2.5D projection + lateral guard
│   └── Vec3.java
├── MainActivity.java  single-activity host, fully programmatic UI
├── GameView.java      GLSurfaceView (EGL 2.0, touch → queueEvent)
├── GameRenderer.java  batched GLES2 renderer (single atlas + 2 programs)
├── Art.java           texture atlas (10 block sprites + piece + disc + ring),
│                      runtime bitmap fonts, straight-alpha uploads
├── Sfx.java           9 effects synthesized as PCM → temp WAVs → SoundPool
└── Store.java         SharedPreferences best/top-10/sound/first-run
```

`assets/blocks/*.png` are the original hand-drawn isometric platform art
(MIT, same author).

## License

MIT — see [LICENSE](LICENSE). Game mechanics are not copyrightable; this
project contains no Tencent assets, audio, or code.
