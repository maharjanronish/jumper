# 05 — Build system, signing & quirks

## Two build paths

### 1. Gradle (for IDE use)

- `settings.gradle` — `rootProject.name "JumpJump"`, `include ':app'`,
  repos google/mavenCentral/gradlePluginPortal.
- Root `build.gradle` — **AGP 7.4.2** only. Comment: "The project has ZERO external dependencies."
- `app/build.gradle` — namespace + applicationId
  `io.github.maharjanronish.jumpjump`, compile/target 34, min 21, Java 8 compat,
  `minifyEnabled false` ("the APK is ~64 KB anyway"), `dependencies { }` **empty**.
- `gradle.properties` — `org.gradle.jvmargs=-Xmx2048m`,
  **`android.useAndroidX=false`** (the app touches no AndroidX artifact).

### 2. `scripts/build_apk.sh` — the canonical release path (95 lines, used by CI)

Bypasses Gradle entirely:

```
aapt2 compile --dir res → res.zip
aapt2 link --manifest ... -I android.jar -A assets --java gen → base.apk + R.java
javac -source 8 -target 8 -classpath android.jar (NOT bootclasspath) → classes
d8 --release --min-api 21 --lib android.jar → classes.dex
zipalign 4 → apksigner sign → JumpJump.apk
```

**The desugaring trick** (documented in the script): `android.jar` is put on
the *classpath* rather than the bootclasspath, "so javac can see
`java.lang.invoke.LambdaMetafactory`; d8 desugars the lambdas for min-api 21".
That's what lets the source use method references (`renderer::onPress`,
`this::onGameOver`) while targeting API 21.

Defaults `VERSION_CODE=6`, `VERSION_NAME=2.1.0` (overridable via env).

## Signing — a keystore is committed ON PURPOSE

`signing.keystore` (PKCS12, alias `jumpjump`, **store & key password
`jumpjump`**, RSA-2048, 10000-day validity, `CN=Jump Jump, OU=Game, O=JumpJump,
L=Kathmandu, C=NP`) lives in the repo, deliberately excluded from
`.gitignore` with an explanatory comment. If missing, the script regenerates an
identical one (same dname/passwords) so local builds and CI builds share one
signature and **in-place updates keep working**. For a free offline game with
zero secrets this is a reasonable trade — but note it means anyone can build a
signature-compatible APK.

## CI (`.github/workflows/build.yml`)

Triggers: push to master/main, tags `v*`, manual. ubuntu-latest + JDK 17
(temurin) + build-tools 35.0.0 ("d8 fix for enums"). Runs the script, uploads
`JumpJump.apk` as an artifact, and cuts a GitHub Release on `v*` tags.

## Git history (5 commits, all 2026-11-02, tags v1.1.0 / v2.0.0 / v2.1.0)

```
0a2415f v2.1.0: marker centered, piece stays on top, absorb + trail, warm bg
197862a v2.0.0: official-style sprite renderer with your block art
1cd2ace Fix blank gameplay screen: upload mesh VBOs to GPU (v1.1.0)
a8bbf2f Polish: bound the records panel height (ScrollView maxHeight is not honored)
4e14a95 Jump Jump: featherweight offline Android remake of WeChat Jump Jump
```

## Quirks & bugs found during analysis

1. **Stale Gradle version**: `app/build.gradle` still says `versionCode 1 /
   versionName "1.0.0"` while the script/CI ship 6/"2.1.0" — building via
   Gradle alone produces a wrongly-versioned APK.
2. **Deprecated `onBackPressed`** override in `MainActivity` (works, but is
   deprecated since API 33; the rebuild uses `OnBackPressedDispatcher`-free
   simple override retained for API 21 compat — or `finish()` semantics).
3. **Duplicated constant**: the perfect-zone radius formula
   `min(PERFECT_R_ABS, halfW·PERFECT_R_FRAC)` exists both in
   `Game.resolveLanding()`/`Game.targetPerfectR()` (logic) — consistent, but
   the marker radius multiplies by 1.25 in the renderer (intentional visual margin).
4. **`SETTLE_T = 0.18f` duplicated** in `GameRenderer` ("must match Game's
   settle") — a magic-number sync between files.
5. **Font upload premultiplied vs sprites straight**: `FontAtlas` uses
   `GLUtils.texImage2D` (premultiplying) while `Sprites` uses manual
   straight-alpha uploads — inconsistent but visually indistinguishable for
   white glyphs; the rebuild normalizes everything to straight alpha.
6. **Temp WAV churn**: 9 files (~400 KB) written to cacheDir on every launch;
   never cleaned (overwritten in place). Harmless, but the rebuild writes them
   only if absent.
7. **`Storage.addScore` rank semantics**: returns the *first* index with an
   equal score — for tied scores the displayed rank may differ from the
   just-added record's actual position.

## Performance claims from the README (upstream, as stated)

- APK ≈ 168 KB (with block art; ~64 KB without)
- PSS memory 25–35 MB
- ~20 draw calls / ~40 quads per frame
- 60 fps on 2015-class hardware
