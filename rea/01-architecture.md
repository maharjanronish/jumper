# 01 — Architecture

## Identity

| Property | Value |
|---|---|
| App id / package | `io.github.maharjanronish.jumpjump` |
| Label | "Jump Jump" |
| minSdk / targetSdk / compileSdk | 21 / 34 / 34 |
| Language | Java only (source/target 8, desugared by d8) |
| AndroidX | **disabled** (`android.useAndroidX=false`) — the app uses only framework `android.*` APIs |
| Permissions | **ZERO** (manifest comment: "Fully offline game: ZERO permissions required") |
| Native code / .so | none |
| Third-party SDKs | none (no ads, no analytics, no crash reporting; `dependencies {}` empty) |
| Version | Gradle says 1/"1.0.0" (stale); script/CI releases say 6/"2.1.0" — see 05 |
| Activities | exactly one: `MainActivity` (portrait, `singleTask`, exported, launcher) |

## Module map (15 app source files, 3,099 LOC)

```
app/src/main/java/io/github/maharjanronish/jumpjump/
├── core/                        ← pure Java, ZERO Android imports, runs on desktop JVM
│   ├── Config.java    (209)     every tunable constant (projection, physics, scoring,
│   │                           spawn probabilities, palette, particle budgets)
│   ├── Game.java      (402)     the state machine + input API + landing resolution
│   ├── Blocks.java    (250)     Block model + level generation (runs, kinds, difficulty)
│   ├── Camera.java    (101)     fixed-angle 2.5D projection + lateral guard
│   ├── Piece.java     (35)      player state (pos, squash, wobble, tumble)
│   ├── Vec3.java      (31)      mutable float x/y/z
│   └── GameEvent.java (54)      immutable event records (JUMP, LAND, PERFECT, …)
├── MainActivity.java  (255)     single-activity host, overlays, fullscreen, lifecycle
├── GameRenderer.java  (822)     GLES2 renderer: the frame loop, effects, text
├── Sprites.java       (239)     texture store: 10 block PNGs + procedural piece/disc
├── ShaderUtil.java    (102)     shader compile/link + the 3 programs + DynBuf
├── FontAtlas.java     (134)     runtime bitmap font (glyphs "0123456789+x")
├── Sfx.java           (324)     9 effects synthesized as PCM → temp WAVs → SoundPool
├── Storage.java       (87)      SharedPreferences: best, sound, top-10 leaderboard (JSON)
└── GameView.java      (54)      GLSurfaceView: EGL config, touch → queueEvent

test/LogicTest.java    (400)     34 desktop-JVM checks (camera, bot survival, scoring,
                                 falls, specials, generation invariants)
```

Components in the manifest: **1 activity, 0 services, 0 receivers, 0 providers,
no Application subclass, no fragments.** The game-over card and leaderboard are
plain `View` overlays on top of the GL surface, inflated from
`res/layout/activity_main.xml` (192 lines; the only layout).

## Threading model (the whole picture)

There is **no Handler, no coroutine, no AsyncTask, no service**. Two threads:

1. **UI thread** — `MainActivity`, all overlay Views, `Sfx` (SoundPool), `Storage`.
2. **GL thread** — created by `GLSurfaceView`; runs `RENDERMODE_CONTINUOUSLY`
   (60 fps). **`onDrawFrame` IS the game loop**: it computes dt from
   `System.nanoTime()` (clamped to 0.05 s) and calls `game.update(dt)` — the
   entire simulation ticks inside the render callback.

Cross-thread marshalling is explicit and tiny:

- **UI → GL**: `GameView.onTouchEvent` → `queueEvent(renderer::onPress|onRelease)`.
  Only pointer 0 is honored; `ACTION_CANCEL` also releases (a charge can never
  get stuck).
- **GL → UI**: `GameRenderer.Ui` interface (`onGameOver(int)`, `onFirstJump()`)
  is invoked on the GL thread; `MainActivity` wraps every call in
  `runOnUiThread(...)`.

Battery saver: when the game-over panel is shown, `GameView.setPausedRendering(true)`
switches the surface to `RENDERMODE_WHEN_DIRTY`, stopping the loop entirely
until the player taps *Play Again*.

## Event flow (core → presentation)

The core never touches Android. Instead it emits immutable `GameEvent` records
into an `ArrayList`, which the renderer drains once per frame
(`handleEvents() → game.drainEvents()`):

| Event | Emitted when | Renderer reaction |
|---|---|---|
| `JUMP` | release → flight starts | play whoosh; first one also hides the hint pill |
| `MINIHOP` | landing back on the current block | soft thud (0.5 vol) + 3 dust particles |
| `LAND` | normal landing (+1) | thud + 7 dust |
| `PERFECT` | dead-center landing (combo 2,4,6…) | ding pitched up ±12 semitones by combo, 9 dust + 6 gold sparks, "+N" popup |
| `SPECIAL_BONUS` | stay timer fires on manhole/Rubik/store/music-box | per-kind sound, gold "+N" popup, 12 gold dust, expanding ring |
| `FALL` | any miss | descending glissando |
| `GAME_OVER` | after the fall settles | game-over chord + `ui.onGameOver(score)` → panel |

Each event carries a world anchor (x/y/z) so effects spawn at the right place.

## Resource inventory

- `res/layout/activity_main.xml` — FrameLayout: GL container, hint pill,
  game-over card, records panel.
- `res/values/` — 13 strings, 3 colors (`bg #FAF5EC`, `ink #4A4238`, `ink_soft #9A8F80`), 1 theme (`Theme.Material.Light.NoActionBar` + transparent system bars).
- `res/drawable/` — 5 XML shapes/selectors (badge, btn_primary, btn_ghost, card, hint_pill). **No bitmap drawables.**
- `res/mipmap*/` — launcher PNGs (48→192 px) + adaptive icon (`anydpi-v26`).
- `assets/blocks/` — **10 PNG isometric platform sprites**, 256 px wide,
  ~102 KB total: 5 plain styles + cylinder + manhole + Rubik + record player + shop.
  Per-art anchors (top-face pixel row `TOP_CY[]`, top-face squash `TOP_SQUASH[]`)
  are hardcoded in `Sprites.java`.
- **No `res/raw`, no sound files, no font files** — audio and fonts are runtime-generated.

## Why this architecture is unusually clean

1. **Testability**: because `core/` has zero Android imports, `test/LogicTest.java`
   runs the *actual shipping rules* on a desktop JVM — including a "perfect bot"
   that survives 6,000 jumps across 40 seeds, proving every generated level is
   clearable.
2. **Determinism**: the level generator is seeded (`new Random(seed)`); the same
   seed replays the same chain (used by the tests and `perfectHoldTime()`).
3. **Blast-radius**: presentation can change (it did — v2.0.0 swapped the
   renderer) without touching a single rule.
