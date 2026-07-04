package com.stevesarmy.client.model;

/**
 * Runtime-adjustable prone pose angles.
 * Defaults match SoldierModel's prone pose.
 * Use /stevesarmy_cover pose set <part> <value> to adjust live.
 */
public class PoseConfig {
    // RIGHT ARM
    public static float RA_X = -3.1400F;
    public static float RA_Y = 0.1000F;
    public static float RA_Z = 0.1000F;
    public static float RA_POS_X = 0.8F;
    public static float RA_POS_Y = 0.0F;
    public static float RA_POS_Z = -2.0F;

    // LEFT ARM
    public static float LA_X = -3.1400F;
    public static float LA_Y = 0.2000F;
    public static float LA_Z = -0.5000F;
    public static float LA_POS_X = -2.0F;
    public static float LA_POS_Y = -2.0F;
    public static float LA_POS_Z = -2.0F;

    // HEAD
    public static float H_X = -3.0000F;
    public static float H_CLAMP_MIN = -1.5F;
    public static float H_CLAMP_MAX = 0.3F;

    // BODY
    public static float B_X = -0.1000F;
    public static float B_Y = 0.0F;
    public static float B_Z = 0.0F;

    // RIGHT LEG
    public static float RL_X = 0.0F;
    public static float RL_Y = 0.0F;
    public static float RL_Z = 0.3000F;
    public static float RL_POS_Z = -1.5F;

    // LEFT LEG
    public static float LL_X = 0.0F;
    public static float LL_Y = 0.0F;
    public static float LL_Z = -0.3000F;
    public static float LL_POS_Z = -1.5F;

    public static String getAngleReport() {
        return String.format("""
            RIGHT ARM: xRot=%.4f yRot=%.4f zRot=%.4f  pos=[%.1f, %.1f, %.1f]
            LEFT ARM:  xRot=%.4f yRot=%.4f zRot=%.4f  pos=[%.1f, %.1f, %.1f]
            HEAD:      xRot=%.4f  clamp=[%.1f, %.1f]
            BODY:      xRot=%.4f yRot=%.4f zRot=%.4f
            RIGHT LEG: xRot=%.4f yRot=%.4f zRot=%.4f  posZ=%.1f
            LEFT LEG:  xRot=%.4f yRot=%.4f zRot=%.4f  posZ=%.1f""",
            RA_X, RA_Y, RA_Z, RA_POS_X, RA_POS_Y, RA_POS_Z,
            LA_X, LA_Y, LA_Z, LA_POS_X, LA_POS_Y, LA_POS_Z,
            H_X, H_CLAMP_MIN, H_CLAMP_MAX,
            B_X, B_Y, B_Z,
            RL_X, RL_Y, RL_Z, RL_POS_Z,
            LL_X, LL_Y, LL_Z, LL_POS_Z);
    }

    public static String getDegreesReport() {
        return String.format("""
            === PRONE POSE IN DEGREES (Blockbench) ===
            RIGHT ARM: [%.1f, %.1f, %.1f]  pos=[%.1f, %.1f, %.1f]
            LEFT ARM:  [%.1f, %.1f, %.1f]  pos=[%.1f, %.1f, %.1f]
            HEAD:      [%.1f, 0, 0]  clamp=[%.1f, %.1f]
            BODY:      [%.1f, %.1f, %.1f]
            RIGHT LEG: [%.1f, %.1f, %.1f]  posZ=%.1f
            LEFT LEG:  [%.1f, %.1f, %.1f]  posZ=%.1f""",
            rad2deg(RA_X), rad2deg(RA_Y), rad2deg(RA_Z), RA_POS_X, RA_POS_Y, RA_POS_Z,
            rad2deg(LA_X), rad2deg(LA_Y), rad2deg(LA_Z), LA_POS_X, LA_POS_Y, LA_POS_Z,
            rad2deg(H_X), rad2deg(H_CLAMP_MIN), rad2deg(H_CLAMP_MAX),
            rad2deg(B_X), rad2deg(B_Y), rad2deg(B_Z),
            rad2deg(RL_X), rad2deg(RL_Y), rad2deg(RL_Z), RL_POS_Z,
            rad2deg(LL_X), rad2deg(LL_Y), rad2deg(LL_Z), LL_POS_Z);
    }

    private static float rad2deg(float rad) {
        return rad * 180.0F / (float) Math.PI;
    }
}