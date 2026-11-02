package io.github.maharjanronish.jumper.core;

/**
 * Every tunable constant of the game in one place. Values are IDENTICAL to the
 * original Jump Jump tuning so the feel is unchanged; see rea/02-gameplay.md.
 */
public final class Cfg {
    private Cfg() {}

    // projection (2.5D iso sprite world)
    public static final float RHO           = 0.50f;  // ground squash (2:1 iso)
    public static final float RISE_K        = 0.80f;  // screen rise per height unit
    public static final float SCALE_W       = 0.152f; // px/unit: fraction of width
    public static final float SCALE_H_CAP   = 0.068f; // px/unit: cap by height
    public static final float CAM_Y_FRAC    = 0.58f;  // focus row on screen
    public static final float FOCUS_AHEAD   = 0.30f;  // focus rides toward next block
    public static final float CAM_LERP_RATE = 7.0f;   // exponential focus lerp /s

    // lanes
    public static final float LANE_ANGLE_BASE   = 18f;
    public static final float LANE_ANGLE_JITTER = 5f;
    public static final float LANE_COS = (float) Math.cos(Math.toRadians(LANE_ANGLE_BASE));
    public static final int   RUN_LEN_MIN = 3;
    public static final int   RUN_LEN_MAX = 6;

    // jump physics (charge time maps LINEARLY to distance)
    public static final float CHARGE_MAX_T  = 1.15f;
    public static final float CHARGE_MIN_T  = 0.05f;
    public static final float JUMP_MIN_DIST = 1.10f;
    public static final float JUMP_MAX_DIST = 8.20f;
    public static final float FLIGHT_T_BASE = 0.46f;
    public static final float FLIGHT_T_PER_D = 0.030f;
    public static final float ARC_H_BASE    = 1.75f;
    public static final float ARC_H_PER_D   = 0.150f;
    public static final float GRAVITY       = 26f;
    public static final float FALL_KILL_Y   = -6.5f;
    public static final float OVER_DELAY    = 0.45f;

    // landing zones
    public static final float EDGE_INSET     = 0.06f;
    public static final float EDGE_TOLERANCE = 0.18f;
    public static final float WALL_HIT_RANGE = 0.60f;
    public static final float PERFECT_R_ABS  = 0.30f;
    public static final float PERFECT_R_FRAC = 0.16f;
    public static final float WOBBLE_FRAC    = 0.62f;
    public static final float WOBBLE_TIME    = 0.55f;

    // scoring
    public static final int   SCORE_NORMAL    = 1;
    public static final int   COMBO_CAP       = 32;
    public static final float SPECIAL_STAY_T  = 1.9f;
    public static final float SPECIAL_ANIM_T  = 0.85f;

    // level generation
    public static final int   KEEP_BEHIND  = 1;
    public static final int   KEEP_AHEAD   = 1;
    public static final int   INTRO_BLOCKS = 2;
    public static final float SPAWN_DROP_H = 4.5f;
    public static final float SPAWN_T      = 0.30f;
    public static final float SETTLE_T     = 0.18f;   // single source of truth

    // special block spawn (after intro, never twice in a row)
    public static final float P_SPECIAL  = 0.10f;
    public static final float W_MANHOLE  = 0.34f;
    public static final float W_RUBIK    = 0.26f;
    public static final float W_STORE    = 0.21f;
    public static final float W_MUSICBOX = 0.19f;

    // cylinders ramp in with score
    public static final float P_CYL_START      = 0.10f;
    public static final float P_CYL_MAX        = 0.34f;
    public static final float CYL_SCORE_AT_MAX = 60f;

    // charging + piece visuals
    public static final float BLOCK_CHARGE_SQUASH  = 0.15f;
    public static final float PIECE_W_UNITS       = 1.10f;
    public static final float PIECE_H_UNITS       = 1.85f;
    public static final float PIECE_CHARGE_SQUASH = 0.30f;
    public static final float PIECE_CHARGE_WIDEN  = 0.20f;
    public static final float PIECE_LAND_SQUASH   = 0.24f;
    public static final float PIECE_LAND_T        = 0.16f;
    public static final float PIECE_TILT_MAX      = 24f;

    // palette: 6 warm background phases {topRGB, bottomRGB}, glide on switch
    public static final float[][] BG_PHASES = {
            {0.984f, 0.968f, 0.910f,  0.996f, 0.988f, 0.957f},  // cream
            {0.988f, 0.930f, 0.905f,  0.996f, 0.973f, 0.952f},  // rose blush
            {0.984f, 0.965f, 0.835f,  0.996f, 0.988f, 0.933f},  // butter yellow
            {0.936f, 0.953f, 0.875f,  0.975f, 0.984f, 0.939f},  // sage green
            {0.985f, 0.935f, 0.862f,  0.996f, 0.975f, 0.943f},  // apricot peach
            {0.952f, 0.929f, 0.933f,  0.980f, 0.973f, 0.976f},  // warm lilac
    };
    public static final float BG_LERP_RATE = 2.8f;

    public static final float[] SCORE_INK  = {0.29f, 0.26f, 0.22f};  // #4A4238
    public static final float[] MARKER_INK = {0.33f, 0.28f, 0.22f};
    public static final float MARKER_ALPHA = 0.55f;
    public static final float[] SHADOW_RGB = {0.28f, 0.25f, 0.30f};
    public static final float SHADOW_ALPHA = 0.30f;
    public static final float[] GOLD        = {0.92f, 0.72f, 0.25f};

    // particles / popups / rings
    public static final float POPUP_T         = 0.85f;
    public static final float RING_T          = 0.45f;
    public static final float RING_R0         = 0.15f;
    public static final float RING_R1         = 0.85f;
    public static final float PARTICLE_T      = 0.85f;
    public static final float PARTICLE_G      = 11.0f;
    public static final float PARTICLE_BOUNCE = 0.38f;
    public static final int   DUST_LAND       = 7;
    public static final int   DUST_MINIHOP    = 3;
    public static final int   DUST_PERFECT    = 9;
    public static final int   SPARK_PERFECT   = 6;
    public static final int   DUST_SPECIAL    = 12;

    // charge absorb + flight trail
    public static final float ABSORB_RATE_MIN = 6f;
    public static final float ABSORB_RATE_MAX = 32f;
    public static final float ABSORB_R_MIN    = 0.65f;
    public static final float ABSORB_R_MAX    = 1.35f;
    public static final float ABSORB_HOMING   = 4.5f;
    public static final float ABSORB_T_MAX    = 1.4f;
    public static final int   ABSORB_CAP      = 42;
    public static final float TRAIL_PERIOD    = 0.03f;
    public static final float TRAIL_T         = 0.50f;
    public static final float TRAIL_R0        = 0.085f;
    public static final float TRAIL_R1        = 0.030f;
    public static final float TRAIL_ALPHA     = 0.55f;
    public static final float TRAIL_Y_OFF     = 0.55f;
}
