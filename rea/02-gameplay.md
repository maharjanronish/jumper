# 02 — Gameplay mechanics (feature by feature)

All constants live in `core/Config.java`; all rules in `core/Game.java`
(state machine) and `core/Blocks.java` (generation).

## Input model

Touch only (no tilt, no keyboard). Pointer 0 only.

```
finger down  → Game.press()    → state CHARGING   (piece squashes up to −30% height, +20% width;
                                                   the block under it dips −15%; absorb dots spawn)
finger up    → Game.release()  → state FLYING
```

`press()` is accepted from READY / LANDED / SPECIAL_WAIT (jumping away from a
special block **forfeits** its stay bonus — matches the original).

## Charge → distance (the core "feel" formula)

```java
hold  = max(chargeT, 0.05)                  // CHARGE_MIN_T
power = min(hold, 1.15) / 1.15              // CHARGE_MAX_T = 1.15 s
jumpD = 1.10 + power * (8.20 − 1.10)        // JUMP_MIN_DIST .. JUMP_MAX_DIST
```

Charge time maps **linearly** to distance — deliberately matching the
`wangshub/wechat_jump_game` auto-player research (~1.393 ms/px) cited in
`Config.java`. `perfectHoldTime()` inverts this formula to compute the exact
hold that lands dead-center (used by the test bot).

## Flight (kinematic, not force-based)

The jump is parametric along the lane unit vector `segD` (current→next block):

```
k   = flightT / (0.46 + 0.030·jumpD)        // FLIGHT_T_BASE + FLIGHT_T_PER_D
pos = p0 + segD · (jumpD · k)               // straight along the lane
y   = arcH · 4k(1−k),  arcH = 1.75 + 0.150·jumpD
squashY = 1 + 0.10·sin(πk)                  // stretch mid-air
```

So flight time and arc height grow mildly with distance. There is **no real
gravity during flight** — gravity (`GRAVITY = 26`) only applies to the death fall.

## Landing resolution ladder (`Game.resolveLanding()`)

Computed in the *lane frame* of the target block: `along` (forward offset),
`lateral` (sideways offset), `rad2` (squared distance from center).

| # | Case | Test | Outcome |
|---|---|---|---|
| 1 | **Mini-hop** | inside current block with margin −0.10 | adjustment hop, **0 points**, dust burst, state LANDED |
| 2 | **Perfect** | `rad2 ≤ min(0.30, 0.16·halfW)²` | combo++, gain `min(2·combo, 32)`; popup "+N" |
| 3 | **Normal** | inside footprint inset −0.06 | +1, combo reset; wobble 0.12 if beyond 0.62·halfW |
| 4 | **Edge tip** | inside footprint + 0.18 tolerance | teeter 0.55 s (sine wobble + tumble) then fall `EDGE_TIP` |
| 5 | **Undershoot, wall** | `along < 0`, \|lateral\| ≤ halfW+0.35, along ≥ −(halfW+0.60) | smacks the target's front face → fall `SHORT_WALL`, tumbles backward |
| 6 | **Undershoot, gap** | `along < 0`, otherwise | fall `SHORT_GAP`; if the drift carries it into the block face it **bounces off at 0.35 restitution** |
| 7 | **Overshoot** | everything else | fall `OVERSHOOT`, keeps drifting forward while dropping |

Fall physics: `vy −= 26·dt` (initial vy ±0.5), drift along the lane at
`vAlongU` (per reason: −2.2 / +1.4 / +2.4), tumble 260°/s (−300 for wall hits),
kill plane `y < −6.5`, then 0.45 s delay → state OVER + `GAME_OVER` event.

## Scoring rules (exactly the original's)

- Normal landing: **+1**
- Dead-center ("perfect") landings: **2, 4, 6, 8, …** (+2 per consecutive perfect), capped at **32** after 16 in a row
- Any non-perfect landing **resets** the streak (a slightly-off landing scores +1)
- Combo streak indicator "x N" drawn under the score while ≥ 2
- Special-block stay bonuses (below) are **added** on top

## Special blocks (stay ~2 s to collect)

| Kind | Sprite | Bonus | Sound when collected |
|---|---|---|---|
| Manhole | `06_manhole_platform.png` | **+5** | water plip-plip |
| Rubik's cube | `07_rubiks_cube.png` | **+10** | three ratchet clicks |
| Small shop | `09_small_shop_platform.png` | **+15** | C6–E6–G6 door chime |
| Record player / music box | `08_record_player_platform.png` | **+30** | original 8-note pentatonic lullaby |

Mechanics: landing on a special (not yet collected) sets `pendingSpecial`; after
the land-settle animation the state becomes `SPECIAL_WAIT`; standing still for
**1.9 s** (`SPECIAL_STAY_T`) fires the bonus + `SPECIAL_ANIM` (0.85 s).
`bonusGiven` makes it **one-shot per block**; jumping away early **forfeits** it.

## Level generation (`Blocks.java`)

- **Lane runs**: the chain runs 3–6 blocks (uniform random) in one direction at
  18° ± 5° off the forward axis, then flips side ("level switch"). Each flip
  increments `phase`, which drives the background tint.
- Screen-right is world −X for this camera, hence `dirX = −side·sin(ang)`,
  `dirZ = cos(ang)`.
- **Widths shrink** with score: `lerp(2.84→1.72, 3.72→2.56)` over 0..120 points
  (half-widths 1.42→0.86 / 1.86→1.28, random uniform).
- **Air gaps widen**: `lerp(1.05→2.25, 1.75→3.15)` along the lane over 0..130 points.
- **Center distance** = footprints converted to lane distance (`/cos18°`) + air gap,
  hard-capped at `JUMP_MAX_DIST · 0.92` — **every level is reachable by design**
  (verified by the bot test).
- **Intro blocks** (first 2): forced plain kind, width ≥ 1.40, friendly gaps
  (reach + 0.90..1.20), no drop-in animation for the very first target.
- **Spawn roll** per new block (after intro): cylinder with probability
  0.10→0.34 by score 60 (only if the previous block was plain); else a special
  with P = 0.10 (manhole .34 / Rubik .26 / store .21 / music box .19), never
  twice in a row; else plain, cycling the 5 styles with no immediate repeats.
- **Progressive reveal**: `Game.ensureAhead()` keeps `KEEP_AHEAD = 1` block
  beyond the current; the newest target **drops in from 4.5 world units above**
  over 0.30 s (ease-in `t²`) right after each landing, then settles 0.16 s.
- **Memory**: the chain is capped at 64 blocks (old ones dropped); only the
  window `KEEP_BEHIND=1 .. KEEP_AHEAD=1` is ever drawn (~3 blocks on screen).

## Camera & world model

- Fixed-angle **top-view 2.5D**: `screenX = W/2 + (x−camX)·scale`;
  `screenY = H·0.58 − (z−camZ)·scale·RHO − h·scale·RISE_K` with
  `RHO = 0.50` (ground squash, classic 2:1 iso) and `RISE_K = 0.80`.
- `scale = min(0.152·W, 0.068·H)` px per world unit.
- Focus = piece position + 30% toward the next block, smoothed exponentially at
  7/s. The camera never rotates — it only pans.
- **Lateral guard**: a correction term `corrX` (exp 12/s) keeps the *target*
  block's center inside the 24–76% horizontal band, but never pushes the
  *current* block outside 14–86% — worst-case-wide gaps always stay fully
  visible (verified over 150 random jumps in the test suite).

## Difficulty curve summary

| Score | Block width | Air gap | Cylinder P |
|---|---|---|---|
| 0 | 2.84–3.72 | 1.05–1.75 | 0.10 |
| ~60 | ~2.4–3.2 | ~1.6–2.4 | 0.34 (max) |
| 120+ | 1.72–2.56 (min) | 2.25–3.15 (max, capped 92% of max jump) | 0.34 |

## Visual juice tied to mechanics

- Charging: piece squash −30%/+20%, block dips −15%, ivory **absorb dots** spawn
  at 6–32/s (ramping with power) in a ring 0.65–1.35 units out and home into the
  body at 4.5/s (cap 42 dots).
- Flight: fading **trail puffs** every 0.03 s (0.085→0.030 units, 0.55 alpha).
- Landing: dust burst (3/7/9 particles; mini-hop/normal/perfect) with gravity
  11 and **one bounce** (restitution 0.38); perfect adds 6 gold sparks.
- Perfect zone: pulsing annulus + dot at the target center, radius
  `min(0.30, 0.16·halfW)` — **exactly the scoring radius**, drawn with each
  art's own top-face squash so it reads concentric.
- Background: 6 warm gradient phases (cream/rose/butter/sage/apricot/lilac),
  gliding over ~1 s (exp 2.8/s) on every direction switch.
