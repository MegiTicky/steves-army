package com.stevesarmy.debug;

import java.util.UUID;

/** Server-wide diagnostic log toggles. All default to false, reset on restart. */
public final class DiagnosticLogManager {
    private static boolean coverLoggingEnabled;
    private static boolean coverScoreLoggingEnabled;
    private static boolean coverPerformanceLoggingEnabled;
    private static boolean attackLoggingEnabled;
    private static boolean damageLoggingEnabled;
    private static boolean suppressionLoggingEnabled;
    private static boolean spacingLoggingEnabled;
    private static boolean holeRescueLoggingEnabled;
    private static UUID rotationTraceSoldierId;
    private static UUID peekTraceSoldierId;

    private DiagnosticLogManager() {}

    // Cover
    public static boolean isCoverLoggingEnabled() { return coverLoggingEnabled; }
    public static void setCoverLoggingEnabled(boolean v) { coverLoggingEnabled = v; }

    // Expensive per-candidate scoring and peek-ray traces are separate from cover events.
    public static boolean isCoverScoreLoggingEnabled() { return coverScoreLoggingEnabled; }
    public static void setCoverScoreLoggingEnabled(boolean v) { coverScoreLoggingEnabled = v; }

    // Cover search/path timing summaries without per-candidate traces.
    public static boolean isCoverPerformanceLoggingEnabled() { return coverPerformanceLoggingEnabled; }
    public static void setCoverPerformanceLoggingEnabled(boolean v) { coverPerformanceLoggingEnabled = v; }

    // Attack
    public static boolean isAttackLoggingEnabled() { return attackLoggingEnabled; }
    public static void setAttackLoggingEnabled(boolean v) { attackLoggingEnabled = v; }

    // Damage and gun integration
    public static boolean isDamageLoggingEnabled() { return damageLoggingEnabled; }
    public static void setDamageLoggingEnabled(boolean v) { damageLoggingEnabled = v; }

    // Suppression and incoming fire
    public static boolean isSuppressionLoggingEnabled() { return suppressionLoggingEnabled; }
    public static void setSuppressionLoggingEnabled(boolean v) { suppressionLoggingEnabled = v; }

    // Spacing
    public static boolean isSpacingLoggingEnabled() { return spacingLoggingEnabled; }
    public static void setSpacingLoggingEnabled(boolean v) { spacingLoggingEnabled = v; }

    // Hole rescue
    public static boolean isHoleRescueLoggingEnabled() { return holeRescueLoggingEnabled; }
    public static void setHoleRescueLoggingEnabled(boolean v) { holeRescueLoggingEnabled = v; }

    // Targeted rotation trace. A UUID is required to avoid per-tick squad-wide output.
    public static void setRotationTraceSoldierId(UUID soldierId) { rotationTraceSoldierId = soldierId; }
    public static void clearRotationTrace() { rotationTraceSoldierId = null; }
    public static UUID getRotationTraceSoldierId() { return rotationTraceSoldierId; }
    public static boolean isRotationTraceEnabledFor(UUID soldierId) {
        return rotationTraceSoldierId != null && rotationTraceSoldierId.equals(soldierId);
    }

    // Targeted peek/suppression trace. Kept separate from rotation because the two
    // diagnostics have different reproduction workflows and output volume.
    public static void setPeekTraceSoldierId(UUID soldierId) { peekTraceSoldierId = soldierId; }
    public static void clearPeekTrace() { peekTraceSoldierId = null; }
    public static UUID getPeekTraceSoldierId() { return peekTraceSoldierId; }
    public static boolean isPeekTraceEnabledFor(UUID soldierId) {
        return peekTraceSoldierId != null && peekTraceSoldierId.equals(soldierId);
    }

    /** Enable all diagnostic logging categories. */
    public static void enableAll() {
        coverLoggingEnabled = true;
        attackLoggingEnabled = true;
        damageLoggingEnabled = true;
        suppressionLoggingEnabled = true;
        spacingLoggingEnabled = true;
        holeRescueLoggingEnabled = true;
    }

    /** Disable all diagnostic logging categories. */
    public static void disableAll() {
        coverLoggingEnabled = false;
        coverScoreLoggingEnabled = false;
        coverPerformanceLoggingEnabled = false;
        attackLoggingEnabled = false;
        damageLoggingEnabled = false;
        suppressionLoggingEnabled = false;
        spacingLoggingEnabled = false;
        holeRescueLoggingEnabled = false;
        rotationTraceSoldierId = null;
        peekTraceSoldierId = null;
    }
}
