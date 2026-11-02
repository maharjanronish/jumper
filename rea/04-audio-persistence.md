# 04 — Audio synthesis & persistence

## Audio: zero asset files, everything synthesized

`Sfx.java` (324 lines) generates **all nine sound effects as 16-bit PCM at
44.1 kHz** on first construction, writes each as a tiny WAV into
`getCacheDir()`, and loads them into a `SoundPool` (4 streams,
`USAGE_GAME` + `CONTENT_TYPE_SONIFICATION`). Total decoded sound ≈ 400 KB of RAM.
No copyrighted material — every recipe below is original synthesis.

### Shared helpers

```java
env(t, attack, total)   // attack ramp then exponential decay e^(−4.5·(t−attack)/(total−attack))
alloc(seconds)          // short[] of seconds·44100
```

### The nine recipes

| ID | Effect | Recipe | Length |
|---|---|---|---|
| `JUMP` | airy whoosh | low-passed noise (`avg = avg·0.82 + noise·0.18`) + rising sine 420→1320 Hz, env(0.03) | 0.16 s |
| `LAND` | soft thud | sine 95→55 Hz + noise burst `e^(−45t)`, env(0.004) | 0.14 s |
| `PERFECT` | bright ding | 1318 + 1976 + 2637 Hz sines (0.55/0.30/0.12), env(0.004) | 0.28 s |
| `FALL` | glissando | sine sweeping 620 → `620·0.32^(t/0.42)` Hz, env(0.02) | 0.42 s |
| `GAME_OVER` | somber 2 notes | G3 (196 Hz) then D3 (147 Hz), 0.34 s apart, env(0.01) | 0.80 s |
| `MANHOLE` | water plip-plip | two 560→300 Hz drops with 34 Hz tremolo, 0.16 s apart | 0.50 s |
| `RUBIK` | ratchet clicks | 3 noise clicks `e^(−80t)` + 240 Hz ping, 0.13 s apart | 0.45 s |
| `STORE` | door chime | C6–E6–G6 arpeggio (1046.5/1318.5/1568), 0.09 s apart, +2nd harmonic | 0.55 s |
| `MUSICBOX` | music-box lullaby | **original** 8-note pentatonic melody E5 G5 A5 B5 D6 B5 A5 G5, plucked sines with 2nd (0.07) and 3rd (0.02) harmonics, decay `e^(−3.2t)` | 2.9 s |

### Pitch-shifting trick

The perfect ding is played through SoundPool's **playback-rate** parameter:
`rate = 2^(semitones/12)` where semitones = current combo (clamped 0..12) —
the reward climbs in pitch as the streak grows, exactly like the original's feel.

### WAV plumbing

`writeWav()` hand-writes a 44-byte RIFF header (PCM, mono, 44.1 kHz, 16-bit)
plus the samples, then `pool.load(path, 1)`. A `setOnLoadCompleteListener`
gates playback on `loaded` (SoundPool loads async). The whole constructor is
wrapped in try/catch — "sound is a nicety; never crash the game for it".
`release()` is called from `Activity.onDestroy()`.

### Toggle

`sound_on` persists in SharedPreferences; the game-over card shows a
"Sound: On/Off" ghost button; `Sfx.setSoundOn()` is a volatile flag checked at play time.

## Persistence (`Storage.java`, 87 lines)

Fully offline, one SharedPreferences file `"jumpjump"`:

| Key | Type | Meaning |
|---|---|---|
| `best` | int | best score ever |
| `records` | String (JSON) | top-10 leaderboard: `JSONArray` of `{"s": score, "t": epochMs}` |
| `sound_on` | boolean | sound toggle |
| `first_run_done` | boolean | hint pill shown until the first jump |

`addScore(score)` appends the run, sorts **score-desc, then timestamp-asc**
(newer wins ties in display order), truncates to 10, writes the JSON back,
updates `best`, and returns the 1-based rank (or −1 if outside the top 10) —
used to decide the "New best!" badge (actually `score > prevBest && score > 0`).

Serialization uses the framework's `org.json` (part of the platform, not an
external dependency). All parsing is exception-guarded — a corrupt blob simply
yields an empty leaderboard.

## What the app deliberately does NOT have

- No network code of any kind (zero permissions — not even INTERNET)
- No accounts, no cloud sync, no ads, no analytics, no crash reporting
- No saved game mid-run (a run lives only in `Game`'s memory; only finished
  scores persist)
