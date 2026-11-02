package io.github.maharjanronish.jumper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete game logic: a pure-Java state machine with zero platform
 * dependencies, so the exact same rules run in desktop tests and on Android.
 *
 * Rules replicated from the original WeChat "Jump Jump" (identical tuning):
 *  - hold to charge, release to jump; distance grows linearly with hold time
 *  - the jump follows the LANE from the current block to the next one (runs of
 *    3-6 blocks per direction, switching sides like a level switch)
 *  - +1 for a normal landing; dead-center: 2, 4, 6, ... (+2 per consecutive,
 *    capped at 32)
 *  - stay ~2 s on manhole / Rubik / store / music box: +5 / +10 / +15 / +30
 *  - blocks shrink and gaps widen as the score climbs
 *  - tiny hops within the same block are allowed (adjustment, no points)
 *  - missing the block ends the run
 */
public final class Game {

    public enum State { READY, CHARGING, FLYING, LANDED, SPECIAL_WAIT, SPECIAL_ANIM, FALLING, OVER }

    // ---------------------------------------------------------------- events

    /** Immutable event record emitted for the presentation layer. */
    public static final class Ev {
        public enum Type { JUMP, MINIHOP, LAND, PERFECT, SPECIAL_BONUS, FALL, GAME_OVER }
        public enum Fall { SHORT_WALL, SHORT_GAP, OVERSHOOT, EDGE_TIP }

        public final Type type;
        /** Points gained / fall-reason ordinal / final score. */
        public final int a;
        /** Block-kind ordinal (SPECIAL_BONUS) or combo count (PERFECT). */
        public final int b;
        /** World anchor (piece base or head). */
        public final float x, y, z;

        private Ev(Type type, int a, int b, float x, float y, float z) {
            this.type = type; this.a = a; this.b = b;
            this.x = x; this.y = y; this.z = z;
        }

        static Ev jump(float x, float y, float z) { return new Ev(Type.JUMP, 0, 0, x, y, z); }
        static Ev minihop(float x, float y, float z) { return new Ev(Type.MINIHOP, 0, 0, x, y, z); }
        static Ev land(int gained, float x, float y, float z) { return new Ev(Type.LAND, gained, 0, x, y, z); }
        static Ev perfect(int gained, int combo, float x, float y, float z) { return new Ev(Type.PERFECT, gained, combo, x, y, z); }
        static Ev bonus(Level.Kind kind, int pts, float x, float y, float z) { return new Ev(Type.SPECIAL_BONUS, pts, kind.ordinal(), x, y, z); }
        static Ev fall(Ev.Fall r, float x, float y, float z) { return new Ev(Type.FALL, r.ordinal(), 0, x, y, z); }
        static Ev gameOver(int score) { return new Ev(Type.GAME_OVER, score, 0, 0, 0, 0); }

        public Level.Kind kind() { return Level.Kind.values()[b]; }
        public Ev.Fall reason() { return Ev.Fall.values()[a]; }
    }

    // ---------------------------------------------------------------- piece

    /** The player's chess piece: pure state, the renderer builds the visual. */
    public static final class Piece {
        /** World position of the base (feet). */
        public final Vec3 pos = new Vec3();
        /** Squash & stretch (scaleY / scaleXZ around the feet). */
        public float squashY = 1f;
        public float widenXZ = 1f;
        /** Lean while wobbling on an edge (radians, visual). */
        public float wobble;
        /** Tumble rotation while falling (degrees, visual). */
        public float tumbleDeg;
        public boolean airborne;
        public boolean dead;

        void reset(Vec3 start) {
            pos.set(start);
            squashY = 1f; widenXZ = 1f;
            wobble = 0f; tumbleDeg = 0f;
            airborne = false; dead = false;
        }

        /** Center of the head (world) - popup anchor. */
        public float headY() { return pos.y + Cfg.PIECE_H_UNITS * squashY; }
    }

    // ---------------------------------------------------------------- state

    private final Level level;
    private final Piece piece = new Piece();
    /** Double-buffered event lists: drain() swaps, so no per-frame allocation. */
    private List<Ev> events = new ArrayList<>(16);
    private List<Ev> drained = new ArrayList<>(16);

    private State state = State.READY;
    private int score;
    private int combo;                 // consecutive perfect landings
    private float totalTime;

    // charging
    private float chargeT;
    // flight (origin + lane direction + distance)
    private float p0x, p0z;
    private float jumpD;
    private float segDx, segDz;        // active lane direction (cur -> next)
    private float flightT, flightTTotal, arcH;
    // landing settle
    private float landT;
    // special stay
    private float waitT, animT;
    private boolean pendingSpecial;
    // falling
    private float vy, vAlongU, teeterT, tumbleRate, overDelay;
    private Ev.Fall fallReason;
    private boolean bounceDone;

    private Level.Block cur, next;
    /** Smoothed camera focus (world). */
    private final Vec3 camFocus = new Vec3();
    private static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Game(long seed) {
        level = new Level(seed);
        reset(seed);
    }

    // ---------------------------------------------------------------- lifecycle

    public void reset(long seed) {
        level.reset();
        score = 0;
        combo = 0;
        events.clear();
        drained.clear();
        cur = level.get(0);
        next = level.get(1);
        segDx = cur.toNextX;
        segDz = cur.toNextZ;
        p0x = 0;
        p0z = 0;
        piece.reset(ZERO);
        camFocus.set(cur.center);
        state = State.READY;
        chargeT = 0; flightT = 0; landT = 0; waitT = 0; animT = 0;
        pendingSpecial = false; overDelay = 0;
        ensureAhead();
    }

    private void ensureAhead() {
        List<Level.Block> c = level.chain();
        int idx = c.indexOf(cur);
        // Keep exactly the blocks that are drawn visible; the newest one drops
        // in on screen right when it is needed.
        while (idx < 0 || c.size() - idx < Cfg.KEEP_AHEAD + 1) {
            level.spawnNext(score);
            idx = c.indexOf(cur);
        }
    }

    // ---------------------------------------------------------------- input

    /** Finger down. Returns false if the press is ignored in this state. */
    public boolean press() {
        switch (state) {
            case READY:
            case LANDED:
                state = State.CHARGING;
                chargeT = 0;
                return true;
            case SPECIAL_WAIT:
                // Jumping away forfeits the stay bonus (as in the original).
                state = State.CHARGING;
                chargeT = 0;
                return true;
            default:
                return false;
        }
    }

    /** Finger up: performs the jump. Returns false if ignored. */
    public boolean release() {
        if (state != State.CHARGING) return false;
        float hold = Math.max(chargeT, Cfg.CHARGE_MIN_T);
        float power = Math.min(hold, Cfg.CHARGE_MAX_T) / Cfg.CHARGE_MAX_T;
        jumpD = Cfg.JUMP_MIN_DIST + power * (Cfg.JUMP_MAX_DIST - Cfg.JUMP_MIN_DIST);
        p0x = piece.pos.x;
        p0z = piece.pos.z;
        flightT = 0;
        flightTTotal = Cfg.FLIGHT_T_BASE + Cfg.FLIGHT_T_PER_D * jumpD;
        arcH = Cfg.ARC_H_BASE + Cfg.ARC_H_PER_D * jumpD;
        state = State.FLYING;
        piece.airborne = true;
        emit(Ev.jump(piece.pos.x, piece.pos.y, piece.pos.z));
        return true;
    }

    // ---------------------------------------------------------------- main update

    public void update(float dt) {
        dt = Math.min(dt, 0.05f);
        totalTime += dt;

        // camera focus: rides a little ahead of the piece toward the next
        // block; the camera pans smoothly to it (official feel).
        {
            Vec3 n = next.center;
            float goalX = piece.pos.x + (n.x - piece.pos.x) * Cfg.FOCUS_AHEAD;
            float goalZ = piece.pos.z + (n.z - piece.pos.z) * Cfg.FOCUS_AHEAD;
            float a = 1f - (float) Math.exp(-Cfg.CAM_LERP_RATE * dt);
            camFocus.x += (goalX - camFocus.x) * a;
            camFocus.z += (goalZ - camFocus.z) * a;
        }

        // block settle + drop-in animations
        for (Level.Block b : visible()) {
            if (b.settleT > 0) b.settleT = Math.max(0, b.settleT - dt);
            if (b.spawnT > 0) {
                b.spawnT -= dt;
                if (b.spawnT <= 0) { b.spawnT = 0; b.settleT = 0.16f; }
            }
        }

        switch (state) {
            case READY: {
                piece.squashY = 1f + 0.018f * (float) Math.sin(totalTime * 2.6f);
                piece.widenXZ = 1f;
                piece.wobble *= Math.max(0, 1 - dt * 5);
                break;
            }
            case CHARGING: {
                chargeT += dt;
                float power = power();
                piece.squashY = 1f - Cfg.PIECE_CHARGE_SQUASH * power;
                piece.widenXZ = 1f + Cfg.PIECE_CHARGE_WIDEN * power;
                break;
            }
            case FLYING: {
                flightT += dt;
                float k = Math.min(1f, flightT / flightTTotal);
                float d = jumpD * k;
                piece.pos.x = p0x + segDx * d;
                piece.pos.z = p0z + segDz * d;
                piece.pos.y = arcH * 4 * k * (1 - k);
                piece.squashY = 1f + 0.10f * (float) Math.sin(Math.PI * k);
                piece.widenXZ = 1f;
                if (k >= 1f) resolveLanding();
                break;
            }
            case LANDED: {
                landT += dt;
                float k2 = Math.min(1f, landT / Cfg.PIECE_LAND_T);
                float e = (1 - k2) * (1 - k2);
                piece.squashY = 1f - Cfg.PIECE_LAND_SQUASH * e;
                piece.widenXZ = 1f + Cfg.PIECE_LAND_SQUASH * 0.7f * e;
                piece.wobble *= Math.max(0, 1 - dt * 4);
                if (k2 >= 1f) {
                    if (pendingSpecial) { state = State.SPECIAL_WAIT; waitT = 0; }
                    else state = State.READY;
                }
                break;
            }
            case SPECIAL_WAIT: {
                waitT += dt;
                piece.squashY = 1f;
                if (waitT >= Cfg.SPECIAL_STAY_T) triggerSpecial();
                break;
            }
            case SPECIAL_ANIM: {
                animT += dt;
                piece.squashY = 1f;
                if (animT >= Cfg.SPECIAL_ANIM_T) state = State.READY;
                break;
            }
            case FALLING: {
                if (teeterT > 0) {
                    teeterT -= dt;
                    piece.wobble = (float) (Math.sin(teeterT * 16f) * 0.16 * (teeterT / Cfg.WOBBLE_TIME));
                    piece.tumbleDeg += dt * 22f;
                } else {
                    vy -= Cfg.GRAVITY * dt;
                    piece.pos.y += vy * dt;
                    piece.pos.x += vAlongU * segDx * dt;
                    piece.pos.z += vAlongU * segDz * dt;
                    piece.tumbleDeg += tumbleRate * dt;
                    // A short fall may clip the target block's wall and bounce back.
                    if (!bounceDone && fallReason == Ev.Fall.SHORT_GAP && vAlongU > 0) {
                        float rx = piece.pos.x - next.center.x, rz = piece.pos.z - next.center.z;
                        float along = rx * segDx + rz * segDz;
                        if (along >= -next.halfW && along <= next.halfW
                                && next.contains(piece.pos.x, piece.pos.z, 0.4f)) {
                            vAlongU = -vAlongU * 0.35f;
                            bounceDone = true;
                        }
                    }
                    if (piece.pos.y < Cfg.FALL_KILL_Y) {
                        overDelay += dt;
                        if (overDelay >= Cfg.OVER_DELAY && state == State.FALLING) {
                            state = State.OVER;
                            emit(Ev.gameOver(score));
                        }
                    }
                }
                break;
            }
            case OVER:
                break;
        }
    }

    // ---------------------------------------------------------------- landing

    private void resolveLanding() {
        piece.airborne = false;
        float lx = p0x + segDx * jumpD;
        float lz = p0z + segDz * jumpD;

        // 1) tiny hop landing back on the current block (adjustment hop)
        if (cur.contains(lx, lz, -0.10f)) {
            piece.pos.set(lx, 0, lz);
            state = State.LANDED;
            landT = 0;
            pendingSpecial = false;
            emit(Ev.minihop(lx, 0, lz));
            return;
        }

        // 2) where did we land relative to the target block?
        float dx = lx - next.center.x;
        float dz = lz - next.center.z;
        float rad2 = dx * dx + dz * dz;
        float along = dx * segDx + dz * segDz;      // >0 past center, <0 short
        float lateral = -dx * segDz + dz * segDx;   // sideways offset in lane frame
        float halfW = next.halfW;
        float perfectR = Math.min(Cfg.PERFECT_R_ABS, halfW * Cfg.PERFECT_R_FRAC);

        if (rad2 <= perfectR * perfectR) {
            // dead-center: combo 2, 4, 6, 8 ...
            combo++;
            int gained = Math.min(2 * combo, Cfg.COMBO_CAP);
            score += gained;
            piece.pos.set(lx, 0, lz);
            advance(gained, true);
        } else if (next.contains(lx, lz, -Cfg.EDGE_INSET)) {
            combo = 0;
            score += Cfg.SCORE_NORMAL;
            piece.pos.set(lx, 0, lz);
            if (rad2 > (halfW * Cfg.WOBBLE_FRAC) * (halfW * Cfg.WOBBLE_FRAC)) {
                piece.wobble = 0.12f;
            }
            advance(Cfg.SCORE_NORMAL, false);
        } else if (next.contains(lx, lz, Cfg.EDGE_TOLERANCE)) {
            // right at the edge: teeter and fall
            piece.pos.set(lx, 0, lz);
            startFall(Ev.Fall.EDGE_TIP, 0.35f);
        } else if (along < 0) {
            // undershoot
            piece.pos.set(lx, 0, lz);
            if (Math.abs(lateral) <= halfW + 0.35f && along >= -(halfW + Cfg.WALL_HIT_RANGE)) {
                // smacked into the target's front wall
                startFall(Ev.Fall.SHORT_WALL, -2.2f);
            } else {
                startFall(Ev.Fall.SHORT_GAP, 1.4f);
            }
        } else {
            // overshoot (or far sideways): keeps drifting forward while dropping
            piece.pos.set(lx, 0, lz);
            startFall(Ev.Fall.OVERSHOOT, 2.4f);
        }
    }

    private void advance(int gained, boolean perfect) {
        cur = next;
        cur.settleT = Cfg.SETTLE_T;
        ensureAhead();
        next = level.chain().get(level.chain().indexOf(cur) + 1);
        segDx = cur.toNextX;
        segDz = cur.toNextZ;
        state = State.LANDED;
        landT = 0;
        pendingSpecial = cur.kind.isSpecial() && !cur.bonusGiven;
        if (perfect) emit(Ev.perfect(gained, combo, piece.pos.x, piece.headY(), piece.pos.z));
        else emit(Ev.land(gained, piece.pos.x, 0, piece.pos.z));
    }

    private void startFall(Ev.Fall reason, float drift) {
        state = State.FALLING;
        fallReason = reason;
        piece.dead = true;
        piece.airborne = true;
        vy = reason == Ev.Fall.OVERSHOOT ? -0.5f : 0.5f;
        vAlongU = drift;
        teeterT = reason == Ev.Fall.EDGE_TIP ? Cfg.WOBBLE_TIME : 0;
        tumbleRate = reason == Ev.Fall.SHORT_WALL ? -300f : 260f;
        overDelay = 0;
        bounceDone = false;
        emit(Ev.fall(reason, piece.pos.x, piece.pos.y, piece.pos.z));
    }

    private void triggerSpecial() {
        int pts = cur.kind.bonus();
        score += pts;
        cur.markBonusGiven();
        state = State.SPECIAL_ANIM;
        animT = 0;
        emit(Ev.bonus(cur.kind, pts, cur.center.x, 1.2f, cur.center.z));
    }

    private void emit(Ev e) {
        events.add(e);
    }

    // ---------------------------------------------------------------- queries

    /**
     * Returns the events accumulated since the last call. The returned list
     * is REUSED by the next call (it becomes the accumulator again), so
     * consume it before draining again - exactly what the renderer does.
     */
    public List<Ev> drain() {
        List<Ev> out = events;
        events = drained;
        drained = out;
        // `events` (previously returned) is now the accumulator; clear its tail.
        events.clear();
        return out;
    }

    /** Blocks in the draw window: one behind .. two ahead. */
    public List<Level.Block> visible() {
        List<Level.Block> all = level.chain();
        int ci = all.indexOf(cur);
        int from = Math.max(0, ci - Cfg.KEEP_BEHIND);
        int to = Math.min(all.size(), ci + Cfg.KEEP_AHEAD + 1);
        return all.subList(from, to);
    }

    public State state() { return state; }
    public int score() { return score; }
    public int combo() { return combo; }
    public Piece piece() { return piece; }
    public Level.Block cur() { return cur; }
    public Level.Block next() { return next; }
    public Vec3 camFocus() { return camFocus; }
    /** Current direction phase (advances on each lane switch -> bg tint). */
    public int blocksPhase() { return level.phase(); }
    /** Active lane direction (cur -> next), unit length in world XZ. */
    public float segDx() { return segDx; }
    public float segDz() { return segDz; }
    /** Center distance to the next block. */
    public float camDist() { return next != null ? next.s - cur.s : 4f; }
    /** Perfect-zone radius of the TARGET block (drives the center marker). */
    public float targetPerfectR() {
        return next == null ? Cfg.PERFECT_R_ABS
                : Math.min(Cfg.PERFECT_R_ABS, next.halfW * Cfg.PERFECT_R_FRAC);
    }
    /** Charge power 0..1 while charging. */
    public float power() {
        return state == State.CHARGING ? Math.min(chargeT, Cfg.CHARGE_MAX_T) / Cfg.CHARGE_MAX_T : 0f;
    }
    public float totalTime() { return totalTime; }

    /** Exact hold time (s) to land dead-center on the next block. Used by tests/bots. */
    public float perfectHoldTime() {
        float need = (next.center.x - piece.pos.x) * segDx
                + (next.center.z - piece.pos.z) * segDz;
        float power = (need - Cfg.JUMP_MIN_DIST) / (Cfg.JUMP_MAX_DIST - Cfg.JUMP_MIN_DIST);
        power = Math.max(0f, Math.min(1f, power));
        return Math.max(Cfg.CHARGE_MIN_T, power * Cfg.CHARGE_MAX_T);
    }
}
