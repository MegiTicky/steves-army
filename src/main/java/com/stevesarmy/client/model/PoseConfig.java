package com.stevesarmy.client.model;

public class PoseConfig {
    public static float RA_X = -3.1400F;
    public static float RA_Y = 0.1000F;
    public static float RA_Z = 0.1000F;
    public static float RA_POS_X = 0.8F;
    public static float RA_POS_Y = 0.0F;
    public static float RA_POS_Z = -2.0F;

    public static float LA_X = -3.1400F;
    public static float LA_Y = 0.2000F;
    public static float LA_Z = -0.5000F;
    public static float LA_POS_X = -2.0F;
    public static float LA_POS_Y = -2.0F;
    public static float LA_POS_Z = -2.0F;

    public static float H_X = -3.0000F;
    public static float H_CLAMP_MIN = -1.5F;
    public static float H_CLAMP_MAX = 0.3F;

    public static float B_X = -0.1000F;
    public static float B_Y = 0.0F;
    public static float B_Z = 0.0F;

    public static float RL_X = 0.0F;
    public static float RL_Y = 0.0F;
    public static float RL_Z = 0.3000F;
    public static float RL_POS_Z = -1.5F;

    public static float LL_X = 0.0F;
    public static float LL_Y = 0.0F;
    public static float LL_Z = -0.3000F;
    public static float LL_POS_Z = -1.5F;

    private static float rad2deg(float rad) {
        return rad * 180.0f / (float) Math.PI;
    }

    public static String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PRONE POSE CONFIG ===\n");
        sb.append(String.format("Right_Arm: [%.2f, %.2f, %.2f] pos=[%.1f, %.1f, %.1f]\n",
            RA_X, RA_Y, RA_Z, RA_POS_X, RA_POS_Y, RA_POS_Z));
        sb.append(String.format("Left_Arm:  [%.2f, %.2f, %.2f] pos=[%.1f, %.1f, %.1f]\n",
            LA_X, LA_Y, LA_Z, LA_POS_X, LA_POS_Y, LA_POS_Z));
        sb.append(String.format("Head:      [%.2f] clamp=[%.1f, %.1f]\n", H_X, H_CLAMP_MIN, H_CLAMP_MAX));
        sb.append(String.format("Body:      [%.2f, %.2f, %.2f]\n", B_X, B_Y, B_Z));
        sb.append(String.format("Right_Leg: [%.2f, %.2f, %.2f] posZ=%.1f\n", RL_X, RL_Y, RL_Z, RL_POS_Z));
        sb.append(String.format("Left_Leg:  [%.2f, %.2f, %.2f] posZ=%.1f\n", LL_X, LL_Y, LL_Z, LL_POS_Z));
        return sb.toString();
    }

    public static String getDegreesReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PRONE POSE IN DEGREES ===\n");
        sb.append(String.format("Right_Arm: [%.1f, %.1f, %.1f] pos=[%.1f, %.1f, %.1f]\n",
            rad2deg(RA_X), rad2deg(RA_Y), rad2deg(RA_Z), RA_POS_X, RA_POS_Y, RA_POS_Z));
        sb.append(String.format("Left_Arm:  [%.1f, %.1f, %.1f] pos=[%.1f, %.1f, %.1f]\n",
            rad2deg(LA_X), rad2deg(LA_Y), rad2deg(LA_Z), LA_POS_X, LA_POS_Y, LA_POS_Z));
        sb.append(String.format("Head:      [%.1f] clamp=[%.1f, %.1f]\n", rad2deg(H_X), rad2deg(H_CLAMP_MIN), rad2deg(H_CLAMP_MAX)));
        sb.append(String.format("Body:      [%.1f, %.1f, %.1f]\n", rad2deg(B_X), rad2deg(B_Y), rad2deg(B_Z)));
        sb.append(String.format("Right_Leg: [%.1f, %.1f, %.1f] posZ=%.1f\n", rad2deg(RL_X), rad2deg(RL_Y), rad2deg(RL_Z), RL_POS_Z));
        sb.append(String.format("Left_Leg:  [%.1f, %.1f, %.1f] posZ=%.1f\n", rad2deg(LL_X), rad2deg(LL_Y), rad2deg(LL_Z), LL_POS_Z));
        return sb.toString();
    }

    public static void reset() {
        RA_X = -3.1400F; RA_Y = 0.1000F; RA_Z = 0.1000F;
        RA_POS_X = 0.8F; RA_POS_Y = 0.0F; RA_POS_Z = -2.0F;
        LA_X = -3.1400F; LA_Y = 0.2000F; LA_Z = -0.500F;
        LA_POS_X = -2.0F; LA_POS_Y = -2.0F; LA_POS_Z = -2.0F;
        H_X = -3.0000F; H_CLAMP_MIN = -1.5F; H_CLAMP_MAX = 0.3F;
        B_X = -0.1000F; B_Y = 0.0F; B_Z = 0.0F;
        RL_X = 0.0F; RL_Y = 0.0F; RL_Z = 0.3000F; RL_POS_Z = -1.5F;
        LL_X = 0.0F; LL_Y = 0.0F; LL_Z = -0.3000F; LL_POS_Z = -1.5F;
    }
}