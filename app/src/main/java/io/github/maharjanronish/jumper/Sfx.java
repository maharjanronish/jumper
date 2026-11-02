package io.github.maharjanronish.jumper;

import android.content.Context;
import android.media.SoundPool;

import io.github.maharjanronish.jumper.core.Level;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * All sound effects are synthesized at runtime as 16-bit PCM and fed to
 * SoundPool through tiny WAV files in the cache dir - zero audio assets,
 * zero copyrighted material, ~400 KB of decoded sound in RAM. The WAVs are
 * written ONCE and reused across launches (the original rewrote them every
 * start). Same nine recipes as the original game.
 */
public final class Sfx {

    public static final int JUMP = 0;
    public static final int LAND = 1;
    public static final int PERFECT = 2;
    public static final int FALL = 3;
    public static final int GAME_OVER = 4;
    public static final int MANHOLE = 5;
    public static final int RUBIK = 6;
    public static final int STORE = 7;
    public static final int MUSICBOX = 8;
    private static final int COUNT = 9;

    private static final int RATE = 44100;

    private final SoundPool pool;
    private final int[] ids = new int[COUNT];
    private final Map<Integer, Boolean> loaded = new HashMap<>();
    private volatile boolean soundOn = true;

    public Sfx(Context ctx) {
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) loaded.put(sampleId, true);
        });
        try {
            writeWav(ctx, JUMP, synthJump());
            writeWav(ctx, LAND, synthLand());
            writeWav(ctx, PERFECT, synthPerfect());
            writeWav(ctx, FALL, synthFall());
            writeWav(ctx, GAME_OVER, synthGameOver());
            writeWav(ctx, MANHOLE, synthWater());
            writeWav(ctx, RUBIK, synthRatchet());
            writeWav(ctx, STORE, synthChime());
            writeWav(ctx, MUSICBOX, synthMusicBox());
        } catch (Exception ignored) {
            // sound is a nicety; never crash the game for it
        }
    }

    public void setSoundOn(boolean on) { soundOn = on; }

    public static int special(Level.Kind kind) {
        switch (kind) {
            case MANHOLE:  return MANHOLE;
            case RUBIK:    return RUBIK;
            case STORE:    return STORE;
            case MUSICBOX: return MUSICBOX;
            default:       return -1;
        }
    }

    public void play(int id) { play(id, 1f, 0); }

    public void play(int id, float volume) { play(id, volume, 0); }

    /** @param semitones pitch shift for the perfect jingle as the combo climbs. */
    public void play(int id, float volume, int semitonesParam) {
        if (!soundOn || id < 0 || id >= COUNT) return;
        int sid = ids[id];
        if (!Boolean.TRUE.equals(loaded.get(sid))) return;
        float rate = 1f;
        if (id == PERFECT) {
            int s = Math.max(0, Math.min(12, semitonesParam));
            rate = (float) Math.pow(2, s / 12.0);
        }
        float v = Math.max(0f, Math.min(1f, volume));
        pool.play(sid, v, v, 1, 0, rate);
    }

    public void release() {
        try { pool.release(); } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- WAV plumbing

    /** Writes the WAV only if absent, then loads it into the pool. */
    private void writeWav(Context ctx, int id, short[] pcm) throws Exception {
        File f = new File(ctx.getCacheDir(), "jumper_sfx_" + id + ".wav");
        if (!f.isFile()) {
            try (OutputStream os = new FileOutputStream(f)) {
                int dataLen = pcm.length * 2;
                byte[] header = new byte[44];
                writeAscii(header, 0, "RIFF");
                putInt32(header, 4, 36 + dataLen);
                writeAscii(header, 8, "WAVE");
                writeAscii(header, 12, "fmt ");
                putInt32(header, 16, 16);
                putInt16(header, 20, (short) 1);      // PCM
                putInt16(header, 22, (short) 1);      // mono
                putInt32(header, 24, RATE);
                putInt32(header, 28, RATE * 2);
                putInt16(header, 32, (short) 2);
                putInt16(header, 34, (short) 16);
                writeAscii(header, 36, "data");
                putInt32(header, 40, dataLen);
                os.write(header);
                byte[] buf = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    buf[i * 2] = (byte) pcm[i];
                    buf[i * 2 + 1] = (byte) (pcm[i] >> 8);
                }
                os.write(buf);
            }
        }
        ids[id] = pool.load(f.getAbsolutePath(), 1);
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }
    private static void putInt32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }
    private static void putInt16(byte[] b, int off, short v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
    }

    // ---------------------------------------------------------------- synthesis

    /** Envelope helper: attack ramp then exponential decay. */
    private static float env(float t, float attack, float total) {
        if (t < attack) return t / attack;
        return (float) Math.exp(-4.5 * (t - attack) / Math.max(0.001f, total - attack));
    }

    private static short[] alloc(float seconds) {
        return new short[(int) (seconds * RATE)];
    }

    /** jump: airy noise whoosh with a rising pitch. */
    private static short[] synthJump() {
        short[] out = alloc(0.16f);
        double ph = 0;
        double avg = 0;
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double f = 420 + 900 * t / 0.16f;
            ph += 2 * Math.PI * f / RATE;
            double noise = Math.random() * 2 - 1;
            avg = avg * 0.82 + noise * 0.18;                 // low-pass
            double tone = Math.sin(ph) * 0.22;
            float e = env(t, 0.03f, 0.16f);
            double v = (avg * 0.55 + tone) * e * 0.5;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** land: soft thud. */
    private static short[] synthLand() {
        short[] out = alloc(0.14f);
        double ph = 0;
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            ph += 2 * Math.PI * (95 - 40 * t / 0.14f) / RATE;
            double noise = (Math.random() * 2 - 1) * Math.exp(-45 * t) * 0.25;
            float e = env(t, 0.004f, 0.14f);
            double v = (Math.sin(ph) * 0.8 + noise) * e;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** perfect: bright two-tone ding. */
    private static short[] synthPerfect() {
        short[] out = alloc(0.28f);
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            float e = env(t, 0.004f, 0.28f);
            double v = (Math.sin(2 * Math.PI * 1318 * t) * 0.55
                    + Math.sin(2 * Math.PI * 1976 * t) * 0.30
                    + Math.sin(2 * Math.PI * 2637 * t) * 0.12) * e * 0.8;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** fall: descending glissando. */
    private static short[] synthFall() {
        short[] out = alloc(0.42f);
        double ph = 0;
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double f = 620 * Math.pow(0.32, t / 0.42f);
            ph += 2 * Math.PI * f / RATE;
            float e = env(t, 0.02f, 0.42f);
            double v = Math.sin(ph) * 0.5 * e;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** game over: two low somber notes. */
    private static short[] synthGameOver() {
        short[] out = alloc(0.8f);
        double[] notes = {196, 147};
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double v = 0;
            for (int n = 0; n < notes.length; n++) {
                double start = n * 0.34;
                if (t >= start && t < start + 0.42) {
                    float lt = (float) (t - start);
                    v += Math.sin(2 * Math.PI * notes[n] * t) * env(lt, 0.01f, 0.42f) * 0.5;
                }
            }
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** manhole: water plip-plip. */
    private static short[] synthWater() {
        short[] out = alloc(0.5f);
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double v = 0;
            for (int k = 0; k < 2; k++) {
                double start = 0.03 + k * 0.16;
                if (t >= start && t < start + 0.13) {
                    float lt = (float) (t - start);
                    double f = 560 - 260 * lt / 0.13;
                    double trem = 0.6 + 0.4 * Math.sin(2 * Math.PI * 34 * lt);
                    v += Math.sin(2 * Math.PI * f * lt) * env(lt, 0.008f, 0.13f) * trem * 0.6;
                }
            }
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** rubik: three ratchet clicks. */
    private static short[] synthRatchet() {
        short[] out = alloc(0.45f);
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double v = 0;
            for (int k = 0; k < 3; k++) {
                double start = 0.05 + k * 0.13;
                if (t >= start && t < start + 0.05) {
                    float lt = (float) (t - start);
                    double noise = (Math.random() * 2 - 1);
                    v += noise * Math.exp(-80 * lt) * 0.8
                            + Math.sin(2 * Math.PI * 240 * lt) * Math.exp(-60 * lt) * 0.4;
                }
            }
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /** store: friendly door-chime arpeggio (C6 E6 G6). */
    private static short[] synthChime() {
        short[] out = alloc(0.55f);
        double[] notes = {1046.5, 1318.5, 1568.0};
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double v = 0;
            for (int n = 0; n < notes.length; n++) {
                double start = n * 0.09;
                if (t >= start && t < start + 0.4) {
                    float lt = (float) (t - start);
                    v += (Math.sin(2 * Math.PI * notes[n] * lt) * 0.5
                            + Math.sin(2 * Math.PI * notes[n] * 2 * lt) * 0.08)
                            * env(lt, 0.006f, 0.4f) * 0.55;
                }
            }
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    /**
     * music box: an original gentle 8-note lullaby (pentatonic, plucked
     * sines with a touch of second harmonic - evokes a music box without
     * copying any melody).
     */
    private static short[] synthMusicBox() {
        double[] notes = {659.26, 783.99, 880.00, 987.77, 1174.66, 987.77, 880.00, 783.99}; // E5 G5 A5 B5 D6 B5 A5 G5
        double noteLen = 0.30;
        short[] out = alloc((float) (notes.length * noteLen + 0.5));
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            double v = 0;
            for (int n = 0; n < notes.length; n++) {
                double start = n * noteLen;
                if (t >= start && t < start + noteLen + 0.4) {
                    float lt = (float) (t - start);
                    double decay = Math.exp(-3.2 * lt);
                    v += (Math.sin(2 * Math.PI * notes[n] * lt) * 0.5
                            + Math.sin(2 * Math.PI * notes[n] * 2 * lt) * 0.07
                            + Math.sin(2 * Math.PI * notes[n] * 3.01 * lt) * 0.02) * decay * 0.5;
                }
            }
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }
}
