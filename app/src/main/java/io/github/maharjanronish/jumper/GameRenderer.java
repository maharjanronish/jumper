package io.github.maharjanronish.jumper;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import io.github.maharjanronish.jumper.core.Camera;
import io.github.maharjanronish.jumper.core.Cfg;
import io.github.maharjanronish.jumper.core.Game;
import io.github.maharjanronish.jumper.core.Level;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * All rendering for the game, batched: the entire sprite world (blocks,
 * piece, shadow, markers, rings, absorb dots, trail puffs) lives in ONE
 * texture atlas and is submitted as ONE draw call in painter order; text
 * batches per font with per-vertex tint. A typical frame is ~5 draw calls
 * (the original issued ~20).
 *
 * Draw sequence per frame (exactly the original painter order):
 *   1. background gradient        (flat program)
 *   2. world + absorb + trail     (textured, single atlas, painter order)
 *   3. particles                  (flat program)
 *   4. score(+shadow) + combo     (textured, score font, one batch)
 *   5. "+N" popups                (textured, popup font, one batch)
 */
final class GameRenderer implements GLSurfaceView.Renderer {

    interface Ui {
        /** Called from the GL thread when the run ends. */
        void onGameOver(int score);
        /** Called from the GL thread after the very first jump (hide hint). */
        void onFirstJump();
    }

    private final Game game;
    private final Ui ui;
    private final Sfx sfx;
    private final Camera camera = new Camera();
    private final Art art = new Art();
    private final Context context;

    /** Light elegant face for the big score; medium for popups. */
    private final Art.Font scoreFont = new Art.Font("sans-serif-light", android.graphics.Typeface.NORMAL);
    private final Art.Font popupFont = new Art.Font("sans-serif-medium", android.graphics.Typeface.NORMAL);

    // ---------------------------------------------------------------- GL objects

    private int texProg, flatProg;
    private int tAPos, tAUV, tAColor, tUTex;
    private int fAPos, fAColor;
    private int vbo;

    // batch state (growable direct buffers, reused every frame)
    private FloatBuffer texBuf, flatBuf;
    private int texPos, flatPos;      // floats written into the current batch
    /** Texture bound to the pending textured batch; switching flushes it. */
    private int batchTex = -1;

    /** Switches the batch texture, flushing any pending quads first. */
    private void useTex(int t) {
        if (t != batchTex) {
            flushTex();
            batchTex = t;
        }
    }

    // ---------------------------------------------------------------- effects

    private static final class Popup {
        float wx, wy, wz; String text; float age; boolean gold;
    }
    /** Expanding ring on a special bonus. */
    private static final class Ring {
        float wx, wz; float age;
    }
    /** World-space particle with gravity + bounce (landing bursts). */
    private static final class Particle {
        float x, y, z, vx, vy, vz, age, size; boolean gold;
    }
    /** Charge dot spiraling INTO the piece while holding. */
    private static final class Absorb {
        float x, y, z, age;
    }
    /** One fading puff of the flight trail. */
    private static final class TrailDot {
        float x, y, z, age;
    }
    private final List<Popup> popups = new ArrayList<>(8);
    private final List<Ring> rings = new ArrayList<>(4);
    private final List<Particle> particles = new ArrayList<>(48);
    private final List<Absorb> absorbs = new ArrayList<>(32);
    private final List<TrailDot> trail = new ArrayList<>(64);
    private float absorbClock, trailClock;
    private final Random fxRandom = new Random();

    // background glide (phase -> warm tint)
    private final float[] bgTop = new float[3];
    private final float[] bgBot = new float[3];
    private int bgPhaseSeen = -1;

    private long lastFrameNanos;
    private boolean firstJumpSeen;

    GameRenderer(Context ctx, Game game, Sfx sfx, Ui ui) {
        this.context = ctx;
        this.game = game;
        this.sfx = sfx;
        this.ui = ui;
    }

    // ---------------------------------------------------------------- input (GL thread)

    /** Called via GLSurfaceView.queueEvent from the UI thread. */
    void onPress() { game.press(); }

    /** Called via GLSurfaceView.queueEvent from the UI thread. */
    void onRelease() { game.release(); }

    /** Restarts the run; call on the GL thread. */
    void resetGame(long seed) {
        game.reset(seed);
        popups.clear();
        rings.clear();
        particles.clear();
        absorbs.clear();
        trail.clear();
        absorbClock = 0;
        trailClock = 0;
        bgPhaseSeen = -1;
        lastFrameNanos = System.nanoTime();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        texProg = link(
                "attribute vec2 aPos; attribute vec2 aUV; attribute vec4 aColor;"
                        + "varying vec2 vUV; varying vec4 vColor;"
                        + "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; vColor = aColor; }",
                "precision mediump float;"
                        + "uniform sampler2D uTex; varying vec2 vUV; varying vec4 vColor;"
                        + "void main(){"
                        + "  vec4 t = texture2D(uTex, vUV);"
                        + "  gl_FragColor = vec4(t.rgb * vColor.rgb, t.a * vColor.a);"
                        + "}");
        tAPos = attr(texProg, "aPos");
        tAUV = attr(texProg, "aUV");
        tAColor = attr(texProg, "aColor");
        tUTex = uniform(texProg, "uTex");

        flatProg = link(
                "attribute vec2 aPos; attribute vec4 aColor; varying vec4 vColor;"
                        + "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vColor = aColor; }",
                "precision mediump float; varying vec4 vColor;"
                        + "void main(){ gl_FragColor = vColor; }");
        fAPos = attr(flatProg, "aPos");
        fAColor = attr(flatProg, "aColor");

        scoreFont.create();
        popupFont.create();
        art.create(context);

        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        vbo = ids[0];

        // sprite world: no depth buffer usage - painter's algorithm
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
        camera.update(game, Math.max(1, width), Math.max(1, height), 0f);
    }

    @Override public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameNanos) * 1e-9f);
        lastFrameNanos = now;

        game.update(dt);
        handleEvents();
        updateEffects(dt);
        updateChargeAndTrail(dt);
        updateBackground(dt);
        camera.update(game, camera.viewW(), camera.viewH(), dt);

        GLES20.glClearColor(bgBot[0], bgBot[1], bgBot[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 1. background gradient
        emitBackground();
        flushFlat();

        // 2. the whole sprite world (single atlas, painter order)
        useTex(art.atlasTex);
        drawWorld();
        drawChargeAndTrail();

        // 3. particles
        emitParticles();
        flushFlat();

        // 4 + 5. text (score via score font, combo + popups via popup font)
        drawText();
        flushTex();
    }

    // ---------------------------------------------------------------- events

    private void handleEvents() {
        List<Game.Ev> ev = game.drain();
        for (Game.Ev e : ev) {
            switch (e.type) {
                case JUMP:
                    sfx.play(Sfx.JUMP);
                    if (!firstJumpSeen) { firstJumpSeen = true; ui.onFirstJump(); }
                    break;
                case MINIHOP:
                    sfx.play(Sfx.LAND, 0.5f);
                    spawnBurst(e.x, e.z, Cfg.DUST_MINIHOP, false);
                    break;
                case LAND:
                    // plain landing: dust only - the original shows no "+1" text
                    sfx.play(Sfx.LAND);
                    spawnBurst(e.x, e.z, Cfg.DUST_LAND, false);
                    break;
                case PERFECT:
                    sfx.play(Sfx.PERFECT, Math.min(e.b, 8));
                    spawnBurst(e.x, e.z, Cfg.DUST_PERFECT, false);
                    spawnBurst(e.x, e.z, Cfg.SPARK_PERFECT, true);
                    addPopup(e.x, e.y + 1.1f, e.z, "+" + e.a, false);
                    break;
                case SPECIAL_BONUS:
                    sfx.play(Sfx.special(e.kind()));
                    addPopup(e.x, e.y + 0.9f, e.z, "+" + e.a, true);
                    spawnBurst(e.x, e.z, Cfg.DUST_SPECIAL, true);
                    addRing(e.x, e.z);
                    break;
                case FALL:
                    sfx.play(Sfx.FALL);
                    break;
                case GAME_OVER:
                    sfx.play(Sfx.GAME_OVER);
                    ui.onGameOver(e.a);
                    break;
            }
        }
    }

    private void addPopup(float x, float y, float z, String text, boolean gold) {
        Popup p = new Popup();
        p.wx = x; p.wy = y; p.wz = z;
        p.text = text; p.gold = gold; p.age = 0;
        popups.add(p);
    }

    private void addRing(float x, float z) {
        Ring r = new Ring();
        r.wx = x; r.wz = z; r.age = 0;
        rings.add(r);
    }

    /** Burst of particles at a world point: ivory dust, or gold sparks. */
    private void spawnBurst(float x, float z, int n, boolean gold) {
        for (int i = 0; i < n; i++) {
            if (particles.size() >= 72) break;   // hard cap for low-end phones
            Particle p = new Particle();
            p.x = x; p.y = 0.10f; p.z = z;
            double a = fxRandom.nextDouble() * Math.PI * 2;
            float sp = 0.6f + fxRandom.nextFloat() * (gold ? 1.6f : 1.2f);
            p.vx = (float) Math.cos(a) * sp;
            p.vz = (float) Math.sin(a) * sp;
            p.vy = (gold ? 1.5f : 0.8f) + fxRandom.nextFloat() * (gold ? 1.6f : 1.0f);
            p.age = 0;
            p.size = (gold ? 0.075f : 0.05f) + fxRandom.nextFloat() * 0.04f;
            p.gold = gold;
            particles.add(p);
        }
    }

    private void updateEffects(float dt) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            popups.get(i).age += dt;
            if (popups.get(i).age > Cfg.POPUP_T) popups.remove(i);
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            rings.get(i).age += dt;
            if (rings.get(i).age > Cfg.RING_T) rings.remove(i);
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            if (p.age > Cfg.PARTICLE_T) { particles.remove(i); continue; }
            p.vy -= Cfg.PARTICLE_G * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            if (p.y < 0f && p.vy < 0f) {       // one bounce on the block top
                p.y = 0f;
                p.vy = -p.vy * Cfg.PARTICLE_BOUNCE;
                p.vx *= 0.65f;
                p.vz *= 0.65f;
            }
        }
    }

    // ---------------------------------------------------------------- charge absorb + flight trail

    /**
     * While CHARGING, ivory dots spawn in a ring around the piece and get
     * sucked into its body (rate ramps with power). While FLYING, the piece
     * drops a fading trail of soft puffs.
     */
    private void updateChargeAndTrail(float dt) {
        Game.Piece p = game.piece();

        if (game.state() == Game.State.CHARGING) {
            float power = game.power();
            float rate = Cfg.ABSORB_RATE_MIN
                    + (Cfg.ABSORB_RATE_MAX - Cfg.ABSORB_RATE_MIN) * power;
            absorbClock += dt * rate;
            while (absorbClock >= 1f) {
                absorbClock -= 1f;
                if (absorbs.size() >= Cfg.ABSORB_CAP) { absorbClock = 0; break; }
                Absorb a = new Absorb();
                double ang = fxRandom.nextDouble() * Math.PI * 2;
                float r = Cfg.ABSORB_R_MIN
                        + fxRandom.nextFloat() * (Cfg.ABSORB_R_MAX - Cfg.ABSORB_R_MIN);
                a.x = p.pos.x + (float) Math.cos(ang) * r;
                a.z = p.pos.z + (float) Math.sin(ang) * r;
                a.y = 0.03f + fxRandom.nextFloat() * 0.45f;
                a.age = 0;
                absorbs.add(a);
            }
        } else {
            absorbClock = 0;
        }

        // home every dot into the piece's body; absorb on arrival
        float tx = p.pos.x, tz = p.pos.z;
        float ty = 0.62f * p.squashY + 0.08f;
        float k = 1f - (float) Math.exp(-Cfg.ABSORB_HOMING * dt * (0.6f + game.power()));
        for (int i = absorbs.size() - 1; i >= 0; i--) {
            Absorb a = absorbs.get(i);
            a.age += dt;
            a.x += (tx - a.x) * k;
            a.y += (ty - a.y) * k;
            a.z += (tz - a.z) * k;
            float dx = a.x - tx, dy = a.y - ty, dz = a.z - tz;
            if (a.age > Cfg.ABSORB_T_MAX || dx * dx + dy * dy + dz * dz < 0.005f) {
                absorbs.remove(i);
            }
        }

        if (game.state() == Game.State.FLYING) {
            trailClock += dt;
            while (trailClock >= Cfg.TRAIL_PERIOD) {
                trailClock -= Cfg.TRAIL_PERIOD;
                if (trail.size() >= 96) break;
                TrailDot t = new TrailDot();
                t.x = p.pos.x;
                t.y = p.pos.y + Cfg.TRAIL_Y_OFF;
                t.z = p.pos.z;
                t.age = 0;
                trail.add(t);
            }
        } else {
            trailClock = 0;
        }
        for (int i = trail.size() - 1; i >= 0; i--) {
            trail.get(i).age += dt;
            if (trail.get(i).age > Cfg.TRAIL_T) trail.remove(i);
        }
    }

    /** Emits the flight trail puffs and charge absorb dots (soft discs). */
    private void drawChargeAndTrail() {
        // trail: round white puffs, shrinking + fading with age
        for (TrailDot t : trail) {
            float k2 = t.age / Cfg.TRAIL_T;
            float r = Cfg.TRAIL_R0 + (Cfg.TRAIL_R1 - Cfg.TRAIL_R0) * k2;
            float alpha = Cfg.TRAIL_ALPHA * (1f - k2) * Math.min(1f, t.age / 0.06f);
            emitSoftDisc(t.x, t.y, t.z, r, 1f, 0.99f, 0.96f, alpha);
        }
        // charge dots: ivory, fade in fast
        for (Absorb a : absorbs) {
            float fadeIn = Math.min(1f, a.age / 0.12f);
            emitSoftDisc(a.x, a.y, a.z, 0.052f, 1f, 0.99f, 0.95f, 0.75f * fadeIn);
        }
    }

    /** One soft-disc quad (world point + radius, round on screen). */
    private void emitSoftDisc(float x, float y, float z, float rWorld,
                              float r, float g, float b, float a) {
        if (a <= 0.02f) return;
        float cx = camera.screenX(x, z);
        float cy = camera.screenY(x, z, y);
        float w = camera.spanX(rWorld) * 2f;
        quadPx(art.disc, cx - w * 0.5f, cy - w * 0.5f, w, w, r, g, b, a);
    }

    // ---------------------------------------------------------------- background

    /** Glides the warm gradient toward the current phase's tint. */
    private void updateBackground(float dt) {
        int phase = game.blocksPhase();
        float[] top = Cfg.BG_PHASES[phase % Cfg.BG_PHASES.length];
        if (bgPhaseSeen < 0) {
            // first frame: snap to the phase color
            bgPhaseSeen = phase;
            System.arraycopy(top, 0, bgTop, 0, 3);
            System.arraycopy(top, 3, bgBot, 0, 3);
            return;
        }
        bgPhaseSeen = phase;
        float a = 1f - (float) Math.exp(-Cfg.BG_LERP_RATE * dt);
        for (int i = 0; i < 3; i++) {
            bgTop[i] += (top[i] - bgTop[i]) * a;
            bgBot[i] += (top[i + 3] - bgBot[i]) * a;
        }
    }

    private void emitBackground() {
        float[] t = bgTop, bo = bgBot;
        // two triangles: top edge uses bgTop, bottom edge bgBot
        flatPut(-1, 1, t[0], t[1], t[2], 1);
        flatPut(1, 1, t[0], t[1], t[2], 1);
        flatPut(1, -1, bo[0], bo[1], bo[2], 1);
        flatPut(-1, 1, t[0], t[1], t[2], 1);
        flatPut(1, -1, bo[0], bo[1], bo[2], 1);
        flatPut(-1, -1, bo[0], bo[1], bo[2], 1);
    }

    // ---------------------------------------------------------------- world (sprites)

    private void drawWorld() {
        List<Level.Block> vis = game.visible();
        Game.Piece p = game.piece();

        // charging: the current block squashes down together with the piece
        float power = game.power();
        float curQ = 1f - Cfg.BLOCK_CHARGE_SQUASH * power;
        float curDipPx = 0f;
        Level.Block cur = game.cur();
        if (curQ < 1f) {
            Art.Tex cs = art.blocks[Art.idOf(cur.kind, cur.style)];
            if (cs.w > 0) {
                float w = 2 * cur.halfW * camera.scale();
                float h = w * cs.h / (float) cs.w;
                float topCy = cs.topCy * (w / cs.w);
                curDipPx = (h - topCy) * (1f - curQ);
            }
        }

        // the block the piece is standing on must never be drawn over the
        // piece (far-half landings otherwise sink the piece "into" the box)
        Level.Block nextB = game.next();
        Level.Block standing = null;
        if (!p.dead) {
            if (cur != null && cur.contains(p.pos.x, p.pos.z, 0.35f)) standing = cur;
            else if (nextB != null && nextB.contains(p.pos.x, p.pos.z, 0.35f)) standing = nextB;
        }

        // painter's algorithm: far blocks first (z desc); the piece sits in
        // front of the block it stands on and behind strictly nearer blocks
        boolean pieceDrawn = false;
        for (int i = vis.size() - 1; i >= 0; i--) {
            Level.Block blk = vis.get(i);
            if (!pieceDrawn && blk != standing && blk.center.z < p.pos.z - 1e-4f) {
                emitPieceAndShadow(curDipPx);
                pieceDrawn = true;
            }
            float q = (blk == cur) ? curQ : 1f;
            if (blk.settleT > 0) q *= 1f - 0.05f * (blk.settleT / Cfg.SETTLE_T);
            emitBlockSprite(blk, q);
            if (blk == game.next() && game.state() != Game.State.OVER) {
                emitMarker(blk);
            }
        }
        if (!pieceDrawn) emitPieceAndShadow(curDipPx);

        // small expanding rings (special bonus)
        for (Ring rg : rings) {
            float t = rg.age / Cfg.RING_T;
            float r = Cfg.RING_R0 + (Cfg.RING_R1 - Cfg.RING_R0) * t;
            float cx = camera.screenX(rg.wx, rg.wz);
            float cy = camera.screenY(rg.wx, rg.wz);
            float rx = camera.spanX(r);
            float ry = camera.spanY(r);
            quadPx(art.ring, cx - rx, cy - ry, rx * 2, ry * 2,
                    0.55f, 0.45f, 0.30f, 0.34f * (1f - t));
        }
    }

    /**
     * Emits one block sprite anchored at its top-face center. The squash
     * compresses the sprite around its BOTTOM edge, so the top-face center
     * (and the piece standing on it) rides DOWN by (1-q) * body height.
     */
    private void emitBlockSprite(Level.Block blk, float q) {
        Art.Tex s = art.blocks[Art.idOf(blk.kind, blk.style)];
        if (s.w == 0) return;
        float cx = camera.screenX(blk.center.x, blk.center.z);
        float drop = dropOf(blk);
        float cy = camera.screenY(blk.center.x, blk.center.z) - camera.rise(drop);
        float w = 2 * blk.halfW * camera.scale();
        float h = w * s.h / (float) s.w;
        float topCy = s.topCy * (w / s.w);
        float body = h - topCy;               // sprite px below the anchor
        float h2 = h * q;
        float anchorY = cy + body * (1f - q); // anchor rides down while squashing
        quadPx(s, cx - w * 0.5f, anchorY - topCy * q, w, h2, 1, 1, 1, 1f);
    }

    private void emitPieceAndShadow(float curDipPx) {
        Game.Piece p = game.piece();
        if (p.dead && game.state() == Game.State.OVER && p.pos.y < Cfg.FALL_KILL_Y - 1f) {
            return;   // piece is gone below; skip drawing
        }

        // blob shadow under the piece, on whichever block it is over
        Level.Block cur = game.cur();
        Level.Block next = game.next();
        Level.Block over = null;
        if (cur.dist2(p.pos.x, p.pos.z) < (cur.halfW + 0.6f) * (cur.halfW + 0.6f)) over = cur;
        else if (next != null
                && next.dist2(p.pos.x, p.pos.z) < (next.halfW + 0.6f) * (next.halfW + 0.6f)) over = next;
        if (over != null && p.pos.y > -0.1f) {
            float h = Math.max(0, p.pos.y);
            float r = 0.42f * (1f - Math.min(0.5f, h * 0.09f));
            float a = Cfg.SHADOW_ALPHA * (1f - Math.min(0.8f, h * 0.16f));
            float sx = camera.screenX(p.pos.x, p.pos.z);
            float sy = camera.screenY(p.pos.x, p.pos.z) + (over == cur ? curDipPx : 0f);
            float rx = camera.spanX(r);
            float ry = camera.spanY(r);
            quadPx(art.disc, sx - rx, sy - ry, rx * 2, ry * 2,
                    Cfg.SHADOW_RGB[0], Cfg.SHADOW_RGB[1], Cfg.SHADOW_RGB[2], a);
        }

        // the piece sprite: squash & stretch around the feet anchor, tilting
        // while wobbling on an edge or tumbling into the fall
        float fx = camera.screenX(p.pos.x, p.pos.z);
        float fy = camera.screenY(p.pos.x, p.pos.z, p.pos.y) + curDipPx;
        float w = Cfg.PIECE_W_UNITS * camera.scale() * p.widenXZ;
        float h = Cfg.PIECE_H_UNITS * camera.scale() * p.squashY;
        float tilt = p.wobble * 150f + clamp(p.tumbleDeg, -Cfg.PIECE_TILT_MAX, Cfg.PIECE_TILT_MAX);
        quadRotPx(art.piece, fx, fy, w, h, tilt);
    }

    /**
     * Perfect-zone marker: a bold pulsing ellipse ring + dot at the TARGET's
     * top-face center, exactly the radius that scores the 2/4/6... combo.
     * The ellipse uses THIS art's own top-face squash so the ring reads
     * concentric with the drawn face on every block style.
     */
    private void emitMarker(Level.Block blk) {
        Art.Tex s = art.blocks[Art.idOf(blk.kind, blk.style)];
        if (s.w == 0) return;
        float pr = game.targetPerfectR();
        float cx = camera.screenX(blk.center.x, blk.center.z);
        float cy = camera.screenY(blk.center.x, blk.center.z) - camera.rise(dropOf(blk));
        float pulse = 0.82f + 0.18f * (float) Math.sin(game.totalTime() * 5.5f);
        float[] ink = Cfg.MARKER_INK;
        float rx = camera.spanX(pr) * 1.25f;
        float ry = rx * s.squash;
        quadPx(art.ring, cx - rx, cy - ry, rx * 2, ry * 2,
                ink[0], ink[1], ink[2], Cfg.MARKER_ALPHA * pulse);
        // center dot via the soft disc, tinted
        float dotR = Math.max(4f, rx * 0.20f);
        quadPx(art.disc, cx - dotR, cy - dotR * s.squash, dotR * 2, dotR * 2 * s.squash,
                ink[0], ink[1], ink[2], Math.min(1f, Cfg.MARKER_ALPHA * 1.3f) * pulse);
    }

    /** Current drop-in height of a spawning block (world units). */
    private float dropOf(Level.Block blk) {
        if (blk.spawnT <= 0) return 0f;
        float t = blk.spawnT / Cfg.SPAWN_T;
        return t * t * Cfg.SPAWN_DROP_H;
    }

    // ---------------------------------------------------------------- particles (flat, NDC)

    private void emitParticles() {
        for (Particle p : particles) {
            float t = p.age / Cfg.PARTICLE_T;
            float alpha = 0.85f * (1f - t);
            float sx = camera.screenX(p.x, p.z);
            float sy = camera.screenY(p.x, p.z, p.y);
            float halfPx = Math.max(1.5f, p.size * camera.scale()) * (1f - t * 0.45f);
            float dx = halfPx / camera.viewW() * 2f;
            float dy = halfPx / camera.viewH() * 2f;
            float cxn = camera.ndcX(sx), cyn = camera.ndcY(sy);
            float r, g, bl;
            if (p.gold) { r = 0.97f; g = 0.80f; bl = 0.36f; }
            else { r = 0.97f; g = 0.96f; bl = 0.91f; }
            flatPut(cxn - dx, cyn - dy, r, g, bl, alpha);
            flatPut(cxn + dx, cyn - dy, r, g, bl, alpha);
            flatPut(cxn + dx, cyn + dy, r, g, bl, alpha);
            flatPut(cxn - dx, cyn - dy, r, g, bl, alpha);
            flatPut(cxn + dx, cyn + dy, r, g, bl, alpha);
            flatPut(cxn - dx, cyn + dy, r, g, bl, alpha);
        }
    }

    // ---------------------------------------------------------------- text

    private void drawText() {
        if (game.state() != Game.State.OVER) {
            String s = String.valueOf(game.score());
            float h = camera.viewH() * 0.085f;
            float topPx = camera.viewH() * 0.115f;   // clears notches/status areas
            float cyNdc = camera.ndcY(topPx + h * 0.5f);
            float[] ink = Cfg.SCORE_INK;
            // soft shadow pass + crisp number in ONE batch (per-vertex alpha)
            useTex(scoreFont.texture);
            // generous bound: up to 16 glyphs x 2 passes x 48 floats
            FloatBuffer b = texBegin(32 * 48);
            int verts = scoreFont.emit(b, s, 0f, cyNdc - 0.016f, h,
                    camera.viewW(), camera.viewH(), ink[0], ink[1], ink[2], 0.16f);
            verts += scoreFont.emit(b, s, 0f, cyNdc, h,
                    camera.viewW(), camera.viewH(), ink[0], ink[1], ink[2], 0.95f);
            texEnd(verts);
        }

        // combo streak under the score + floating "+N" popups: same popup
        // font, so they share ONE batch (per-vertex tint/alpha)
        boolean combo = game.state() != Game.State.OVER && game.combo() >= 2;
        if (combo || !popups.isEmpty()) {
            useTex(popupFont.texture);
            // generous bound: combo (2 glyphs) + up to 8 glyphs per popup
            FloatBuffer b = texBegin((popups.size() + 1) * 8 * 48);
            int verts = 0;
            if (combo) {
                float h = camera.viewH() * 0.085f;
                float topPx = camera.viewH() * 0.115f;
                float ch = camera.viewH() * 0.028f;
                float ccy = camera.ndcY(topPx + h + ch * 0.8f);
                verts += popupFont.emit(b, "x" + game.combo(), 0f, ccy,
                        ch, camera.viewW(), camera.viewH(), 0.45f, 0.36f, 0.30f, 0.62f);
            }
            for (Popup p : popups) {
                float t = p.age / Cfg.POPUP_T;
                float alpha = t < 0.6f ? 1f : (1f - (t - 0.6f) / 0.4f);
                float rise = t * 0.9f;
                float sxPx = camera.screenX(p.wx, p.wz);
                float syPx = camera.screenY(p.wx, p.wz, p.wy + rise);
                float sxNdc = camera.ndcX(sxPx);
                float syNdc = camera.ndcY(syPx);
                if (p.gold) {
                    verts += popupFont.emit(b, p.text, sxNdc, syNdc,
                            camera.viewH() * 0.05f, camera.viewW(), camera.viewH(),
                            Cfg.GOLD[0], Cfg.GOLD[1], Cfg.GOLD[2], alpha);
                } else {
                    verts += popupFont.emit(b, p.text, sxNdc, syNdc,
                            camera.viewH() * 0.05f, camera.viewW(), camera.viewH(),
                            1f, 1f, 0.97f, alpha);
                }
            }
            texEnd(verts);
        }
    }

    // ---------------------------------------------------------------- batching core

    /** Axis-aligned atlas quad in PIXEL coordinates (top-left + size + tint). */
    private void quadPx(Art.Tex t, float leftPx, float topPx, float wPx, float hPx,
                        float r, float g, float b, float a) {
        if (t == null || t.atlas < 0 || a <= 0.01f) return;
        ensureTex(48);
        texBuf.position(texPos);
        float x0 = camera.ndcX(leftPx), x1 = camera.ndcX(leftPx + wPx);
        float y0 = camera.ndcY(topPx), y1 = camera.ndcY(topPx + hPx);
        float u0 = t.u0, u1 = t.u1, v0 = t.v0, v1 = t.v1;
        texBuf.put(x0); texBuf.put(y0); texBuf.put(u0); texBuf.put(v0); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texBuf.put(x1); texBuf.put(y0); texBuf.put(u1); texBuf.put(v0); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texBuf.put(x1); texBuf.put(y1); texBuf.put(u1); texBuf.put(v1); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texBuf.put(x0); texBuf.put(y0); texBuf.put(u0); texBuf.put(v0); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texBuf.put(x1); texBuf.put(y1); texBuf.put(u1); texBuf.put(v1); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texBuf.put(x0); texBuf.put(y1); texBuf.put(u0); texBuf.put(v1); texBuf.put(r); texBuf.put(g); texBuf.put(b); texBuf.put(a);
        texPos += 48;
    }

    /** Atlas quad rotated around its BOTTOM-CENTER anchor (the piece). */
    private void quadRotPx(Art.Tex t, float axPx, float ayPx, float wPx, float hPx, float tiltDeg) {
        if (t == null || t.atlas < 0) return;
        float rad = (float) Math.toRadians(tiltDeg);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        // corner offsets from the anchor (feet); v0 = image TOP row
        float[] ox = {-wPx * 0.5f, wPx * 0.5f, wPx * 0.5f, -wPx * 0.5f};
        float[] oy = {-hPx, -hPx, 0f, 0f};
        float[] u = {t.u0, t.u1, t.u1, t.u0};
        float[] v = {t.v0, t.v0, t.v1, t.v1};
        ensureTex(48);
        texBuf.position(texPos);
        int[] idx = {0, 1, 2, 0, 2, 3};
        for (int k = 0; k < 6; k++) {
            int i = idx[k];
            float px = axPx + ox[i] * c - oy[i] * s;
            float py = ayPx + ox[i] * s + oy[i] * c;
            texBuf.put(camera.ndcX(px)); texBuf.put(camera.ndcY(py));
            texBuf.put(u[i]); texBuf.put(v[i]);
            texBuf.put(1); texBuf.put(1); texBuf.put(1); texBuf.put(1);
        }
        texPos += 48;
    }

    /** One flat vertex (NDC pos + RGBA) into the flat batch. */
    private void flatPut(float x, float y, float r, float g, float b, float a) {
        ensureFlat(6);
        flatBuf.position(flatPos);
        flatBuf.put(x); flatBuf.put(y); flatBuf.put(r); flatBuf.put(g); flatBuf.put(b); flatBuf.put(a);
        flatPos += 6;
    }

    /** Begins a font emit: returns the live buffer positioned at the batch end. */
    private FloatBuffer texBegin(int minFloats) {
        ensureTex(minFloats);
        texBuf.position(texPos);
        return texBuf;
    }

    /** Ends a font emit started with texBegin. */
    private void texEnd(int verts) {
        texPos += verts * 8;
    }

    private void ensureTex(int extraFloats) {
        int need = texPos + extraFloats;
        if (texBuf == null || texBuf.capacity() < need) {
            int cap = Math.max(2048, need * 2);
            FloatBuffer nb = ByteBuffer.allocateDirect(cap * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (texBuf != null) {
                texBuf.position(0);
                texBuf.limit(texPos);
                nb.put(texBuf);
            }
            texBuf = nb;
        }
    }

    private void ensureFlat(int extraFloats) {
        int need = flatPos + extraFloats;
        if (flatBuf == null || flatBuf.capacity() < need) {
            int cap = Math.max(1024, need * 2);
            FloatBuffer nb = ByteBuffer.allocateDirect(cap * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (flatBuf != null) {
                flatBuf.position(0);
                flatBuf.limit(flatPos);
                nb.put(flatBuf);
            }
            flatBuf = nb;
        }
    }

    /** Uploads and draws the pending textured batch (one draw call). */
    private void flushTex() {
        if (texPos == 0) return;
        texBuf.position(0);
        texBuf.limit(texPos);
        GLES20.glUseProgram(texProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, batchTex);
        GLES20.glUniform1i(tUTex, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, texPos * 4, texBuf, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glEnableVertexAttribArray(tAPos);
        GLES20.glVertexAttribPointer(tAPos, 2, GLES20.GL_FLOAT, false, 32, 0);
        GLES20.glEnableVertexAttribArray(tAUV);
        GLES20.glVertexAttribPointer(tAUV, 2, GLES20.GL_FLOAT, false, 32, 8);
        GLES20.glEnableVertexAttribArray(tAColor);
        GLES20.glVertexAttribPointer(tAColor, 4, GLES20.GL_FLOAT, false, 32, 16);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, texPos / 8);
        GLES20.glDisableVertexAttribArray(tAColor);
        GLES20.glDisableVertexAttribArray(tAUV);
        GLES20.glDisableVertexAttribArray(tAPos);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        texBuf.limit(texBuf.capacity());
        texPos = 0;
    }

    /** Uploads and draws the pending flat batch (one draw call). */
    private void flushFlat() {
        if (flatPos == 0) return;
        flatBuf.position(0);
        flatBuf.limit(flatPos);
        GLES20.glUseProgram(flatProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, flatPos * 4, flatBuf, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glEnableVertexAttribArray(fAPos);
        GLES20.glVertexAttribPointer(fAPos, 2, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glEnableVertexAttribArray(fAColor);
        GLES20.glVertexAttribPointer(fAColor, 4, GLES20.GL_FLOAT, false, 24, 8);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, flatPos / 6);
        GLES20.glDisableVertexAttribArray(fAColor);
        GLES20.glDisableVertexAttribArray(fAPos);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        flatBuf.limit(flatBuf.capacity());
        flatPos = 0;
    }

    // ---------------------------------------------------------------- gl helpers

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("shader compile error: " + GLES20.glGetShaderInfoLog(sh));
        }
        return sh;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new RuntimeException("program link error: " + GLES20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int attr(int prog, String name) { return GLES20.glGetAttribLocation(prog, name); }
    private static int uniform(int prog, String name) { return GLES20.glGetUniformLocation(prog, name); }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
