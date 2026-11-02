package io.github.maharjanronish.jumper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Typeface;
import android.opengl.GLES20;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import io.github.maharjanronish.jumper.core.Cfg;
import io.github.maharjanronish.jumper.core.Level;

/**
 * ALL textures in one place - and, unlike the original, ALL SPRITES SHARE ONE
 * ATLAS TEXTURE (10 block sprites + the chess piece + the soft disc + a ring),
 * so the whole world renders in a single draw call. The two font strips stay
 * separate (they are rebuilt per family).
 *
 * Sub-textures carry their own UV rect. Block sprites keep the original
 * anchors: topCy = pixel row of the TOP-FACE (landing) CENTER, squash =
 * top-face diamond h/w, so art and landing zone coincide exactly.
 *
 * Everything is uploaded with STRAIGHT alpha (manual RGBA byte upload) so
 * blending behaves identically on every GPU.
 */
final class Art {

    /** One sub-texture of the atlas. */
    static final class Tex {
        int atlas = -1;
        float u0, v0, u1, v1;   // UV rect (v0 = top row)
        int w, h;               // pixel size
        /** Block sprites only: pixel row of the top-face center (unscaled). */
        float topCy;
        /** Block sprites only: top-face diamond h/w of this art. */
        float squash = 0.5f;
    }

    /** Asset files in fixed order; index = sprite id. */
    private static final String[] FILES = {
            "01_plain_stone_slab.png",    // 0 plain
            "02_grass_layer_platform.png",// 1 plain
            "03_wooden_platform.png",     // 2 plain
            "04_dark_striped_platform.png",// 3 plain
            "10_face_tile_platform.png",  // 4 plain
            "05_round_cylinder.png",      // 5 cylinder
            "06_manhole_platform.png",    // 6 manhole  +5
            "07_rubiks_cube.png",         // 7 rubik    +10
            "09_small_shop_platform.png", // 8 store    +15
            "08_record_player_platform.png", // 9 music box +30
    };

    /** Measured anchors: pixel row of the top-face center at 256 px width. */
    private static final float[] TOP_CY = {
            84.05f, 69.39f, 62.42f, 63.53f, 64.90f,
            62.71f, 67.62f, 64.70f, 72.00f, 60.13f,
    };

    /** Measured top-face squash (diamond h/w) of each art. */
    private static final float[] TOP_SQUASH = {
            0.662f, 0.564f, 0.499f, 0.513f, 0.542f,
            0.498f, 0.518f, 0.555f, 0.590f, 0.486f,
    };

    /** Sprite id for a block kind + plain style. */
    static int idOf(Level.Kind kind, int style) {
        switch (kind) {
            case CYLINDER: return 5;
            case MANHOLE:  return 6;
            case RUBIK:    return 7;
            case STORE:    return 8;
            case MUSICBOX: return 9;
            default:       return style % 5;   // plain styles 0..4
        }
    }

    final Tex[] blocks = new Tex[FILES.length];
    Tex piece, disc, ring;
    /** The single atlas texture object. */
    int atlasTex = -1;

    /** Loads / builds / packs everything; call once on the GL thread. */
    void create(Context ctx) {
        Bitmap[] imgs = new Bitmap[FILES.length + 3];
        for (int i = 0; i < FILES.length; i++) imgs[i] = decode(ctx, "blocks/" + FILES[i]);
        imgs[FILES.length]     = createPieceBitmap();
        imgs[FILES.length + 1] = createSoftDiscBitmap();
        imgs[FILES.length + 2] = createRingBitmap();

        // ---- pack: two 256-px columns, greedy into the shorter one.
        // Atlas stays well under the 2048-px minimum GLES2 device limit.
        final int cell = 256;
        int n = imgs.length;
        int[] x = new int[n], y = new int[n];
        int[][] size = new int[n][2];
        int colH0 = 0, colH1 = 0, atlasW = 0, atlasH = 0;
        for (int i = 0; i < n; i++) {
            Bitmap b = imgs[i];
            if (b == null) { size[i][0] = 0; size[i][1] = 0; continue; }
            boolean col0 = colH0 <= colH1;
            x[i] = col0 ? 0 : cell;
            y[i] = col0 ? colH0 : colH1;
            size[i][0] = b.getWidth();
            size[i][1] = b.getHeight();
            if (col0) colH0 += b.getHeight() + 2;
            else colH1 += b.getHeight() + 2;
            atlasW = Math.max(atlasW, x[i] + size[i][0]);
            atlasH = Math.max(atlasH, y[i] + size[i][1]);
        }
        if (atlasW <= 0 || atlasH <= 0) return;   // no art at all; renderer skips

        // ---- copy pixels int-by-int (no Canvas premultiply roundtrip)
        int[] atlasPix = new int[atlasW * atlasH];
        for (int i = 0; i < n; i++) {
            Bitmap b = imgs[i];
            if (b == null) continue;
            int w = size[i][0], h = size[i][1];
            int[] src = new int[w * h];
            b.getPixels(src, 0, w, 0, 0, w, h);
            for (int row = 0; row < h; row++) {
                System.arraycopy(src, row * w, atlasPix, (y[i] + row) * atlasW + x[i], w);
            }
            b.recycle();
        }

        atlasTex = uploadPixels(atlasPix, atlasW, atlasH);

        // ---- record sub-texture rects + anchors
        for (int i = 0; i < n; i++) {
            Tex t = new Tex();
            t.atlas = atlasTex;
            t.w = size[i][0];
            t.h = size[i][1];
            if (t.w > 0 && t.h > 0) {
                t.u0 = x[i] / (float) atlasW;
                t.v0 = y[i] / (float) atlasH;
                t.u1 = (x[i] + t.w) / (float) atlasW;
                t.v1 = (y[i] + t.h) / (float) atlasH;
            }
            if (i < FILES.length) {
                if (t.w > 0) {
                    t.topCy = TOP_CY[i] * (t.w / 256f);
                    t.squash = TOP_SQUASH[i];
                }
                blocks[i] = t;
            } else if (i == FILES.length) {
                piece = t;
            } else if (i == FILES.length + 1) {
                disc = t;
            } else {
                ring = t;
            }
        }
    }

    // ---------------------------------------------------------------- bitmaps

    private static Bitmap decode(Context ctx, String assetPath) {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The black-and-ivory chess piece, drawn with Canvas (no asset file).
     * 220 x 370 px at 200 px per world unit: 1.10 x 1.85 units, feet at the
     * bottom edge, anchor = bottom-center. Same proportions as the original.
     */
    private static Bitmap createPieceBitmap() {
        float u = 200f;
        int w = Math.round(Cfg.PIECE_W_UNITS * u);
        int h = Math.round(Cfg.PIECE_H_UNITS * u);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float cx = w * 0.5f;
        float baseR = 0.42f * u;      // base half-width
        float neckR = 0.17f * u;      // shoulder half-width
        float headR = 0.29f * u;      // ivory head radius
        float headCy = 0.33f * u;     // head center
        float bodyTop = 0.62f * u;    // shoulder line
        float bodyLen = h - bodyTop;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // body: tapered silhouette with a soft waist, feet at the bottom
        Path body = new Path();
        body.moveTo(cx - baseR, h);
        body.quadTo(cx - baseR * 0.96f, h - bodyLen * 0.42f,
                cx - 0.30f * u, bodyTop + bodyLen * 0.16f);
        body.quadTo(cx - neckR * 1.25f, bodyTop + bodyLen * 0.05f,
                cx - neckR, bodyTop);
        body.lineTo(cx + neckR, bodyTop);
        body.quadTo(cx + neckR * 1.25f, bodyTop + bodyLen * 0.05f,
                cx + 0.30f * u, bodyTop + bodyLen * 0.16f);
        body.quadTo(cx + baseR * 0.96f, h - bodyLen * 0.42f,
                cx + baseR, h);
        body.close();
        p.setColor(0xFF343238);
        c.drawPath(body, p);

        // left-side shade for volume
        Path shade = new Path();
        shade.moveTo(cx - baseR * 0.9f, h);
        shade.quadTo(cx - baseR * 0.85f, h - bodyLen * 0.42f,
                cx - 0.24f * u, bodyTop + bodyLen * 0.18f);
        shade.lineTo(cx - 0.10f * u, bodyTop + bodyLen * 0.18f);
        shade.quadTo(cx - 0.14f * u, h - bodyLen * 0.42f,
                cx - 0.14f * u, h);
        shade.close();
        p.setColor(0xFF26242A);
        c.drawPath(shade, p);

        // collar ring below the head (drawn before the head so it peeks out)
        p.setColor(0xFF3C3A40);
        c.drawOval(cx - 0.34f * u, bodyTop - 0.07f * u,
                cx + 0.34f * u, bodyTop + 0.09f * u, p);

        // ivory head with a soft highlight
        p.setColor(0xFFF6F1E4);
        c.drawOval(cx - headR, headCy - headR, cx + headR, headCy + headR, p);
        p.setColor(0xFFFFFCF4);
        c.drawOval(cx - headR * 0.62f, headCy - headR * 0.62f,
                cx + headR * 0.10f, headCy + headR * 0.10f, p);
        return bmp;
    }

    /** 64 x 64 white radial disc (opaque core, transparent edge). */
    private static Bitmap createSoftDiscBitmap() {
        int n = 64;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(n / 2f, n / 2f, n / 2f,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.64f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(n / 2f, n / 2f, n / 2f, p);
        return bmp;
    }

    /**
     * 96 x 96 white ring sprite: stroke thickness is 14% of the radius, the
     * same ratio the original drew its 20-segment annulus with, so scaled
     * quads reproduce the marker / bonus rings 1:1.
     */
    private static Bitmap createRingBitmap() {
        int n = 96;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        float outer = n / 2f - 1f;
        float inner = outer * 0.86f;
        p.setStrokeWidth(outer - inner);
        c.drawCircle(n / 2f, n / 2f, (outer + inner) / 2f, p);
        return bmp;
    }

    // ---------------------------------------------------------------- fonts

    /**
     * Runtime bitmap font - zero asset files. Glyphs are drawn WHITE on a
     * transparent strip (uploaded straight-alpha RGBA); the renderer tints
     * per vertex, so shadow pass + crisp pass + combo batch in ONE draw call.
     */
    static final class Font {
        static final String GLYPHS = "0123456789+x";

        final String family;
        final int style;
        int texture = -1;
        private final float[] uvs = new float[GLYPHS.length() * 4];
        private final float[] widths = new float[GLYPHS.length()];
        private float rowH;

        Font(String family, int style) {
            this.family = family;
            this.style = style;
        }

        void create() {
            int size = 128;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.create(family, style));
            paint.setTextSize(size * 0.72f);
            paint.setColor(0xFFFFFFFF);

            float maxW = 0;
            for (int i = 0; i < GLYPHS.length(); i++) {
                float w = paint.measureText(String.valueOf(GLYPHS.charAt(i)));
                widths[i] = w;
                maxW = Math.max(maxW, w);
            }
            rowH = size;

            int stripW = (int) Math.ceil(maxW * GLYPHS.length()) + GLYPHS.length() * 8;
            Bitmap bmp = Bitmap.createBitmap(stripW, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float y = -fm.ascent;
            float x = 0;
            for (int i = 0; i < GLYPHS.length(); i++) {
                String g = String.valueOf(GLYPHS.charAt(i));
                c.drawText(g, x + 4, y, paint);
                float w = widths[i];
                uvs[i * 4] = (x + 4) / stripW;
                uvs[i * 4 + 1] = 0f;
                uvs[i * 4 + 2] = (x + 4 + w) / stripW;
                uvs[i * 4 + 3] = 1f;
                x += w + 8;
            }
            texture = uploadBitmap(bmp);
            bmp.recycle();
        }

        /**
         * Appends quads (NDC pos + UV + per-vertex RGBA) for text centered
         * horizontally at (cx, cy), pixel height hPx. Returns vertices added.
         */
        int emit(FloatBuffer buf, String s, float cxNdc, float cyNdc, float hPx,
                 float viewW, float viewH, float r, float g, float b, float a) {
            float hNdc = 2f * hPx / viewH;
            float wScale = hPx / rowH;    // px per glyph-height unit
            float totalW = 0;
            for (int i = 0; i < s.length(); i++) {
                int idx = GLYPHS.indexOf(s.charAt(i));
                if (idx >= 0) totalW += widths[idx];
            }
            float x = cxNdc - (totalW * wScale) / viewW;
            int quads = 0;
            for (int i = 0; i < s.length(); i++) {
                int idx = GLYPHS.indexOf(s.charAt(i));
                if (idx < 0) continue;
                float wPx = widths[idx];
                float x0 = x, x1 = x + 2f * wPx * wScale / viewW;
                float y0 = cyNdc - hNdc * 0.5f, y1 = cyNdc + hNdc * 0.5f;
                float u0 = uvs[idx * 4], v0 = uvs[idx * 4 + 1];
                float u1 = uvs[idx * 4 + 2], v1 = uvs[idx * 4 + 3];
                buf.put(x0); buf.put(y0); buf.put(u0); buf.put(v1); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                buf.put(x1); buf.put(y0); buf.put(u1); buf.put(v1); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                buf.put(x1); buf.put(y1); buf.put(u1); buf.put(v0); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                buf.put(x0); buf.put(y0); buf.put(u0); buf.put(v1); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                buf.put(x1); buf.put(y1); buf.put(u1); buf.put(v0); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                buf.put(x0); buf.put(y1); buf.put(u0); buf.put(v0); buf.put(r); buf.put(g); buf.put(b); buf.put(a);
                x = x1;
                quads++;
            }
            return quads * 6;
        }
    }

    // ---------------------------------------------------------------- upload

    /** Uploads an int[] as GL_RGBA with STRAIGHT alpha (non-premultiplied). */
    private static int uploadPixels(int[] pixels, int w, int h) {
        ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            bb.put((byte) ((c >> 16) & 0xFF));   // R
            bb.put((byte) ((c >> 8) & 0xFF));    // G
            bb.put((byte) (c & 0xFF));           // B
            bb.put((byte) ((c >> 24) & 0xFF));   // A
        }
        bb.flip();
        return uploadRgba(bb, w, h);
    }

    /** getPixels -> straight RGBA upload (same path the original proved safe). */
    private static int uploadBitmap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        return uploadPixels(pixels, w, h);
    }

    private static int uploadRgba(ByteBuffer bb, int w, int h) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return tex;
    }
}
