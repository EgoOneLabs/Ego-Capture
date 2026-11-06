package com.egouvc.phone.export;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** File-level compatibility only; never upgrades host timestamps into hardware sampling times. */
public final class EgoRecordFormat {
    private EgoRecordFormat() {}
    public static final String IMU_HEADER =
        "timestamp,orientation_x,orientation_y,orientation_z,orientation_w," +
        "angular_velocity_x,angular_velocity_y,angular_velocity_z," +
        "linear_acceleration_x,linear_acceleration_y,linear_acceleration_z";

    /** Unix nanoseconds rendered exactly as seconds with nine fractional digits. */
    public static String seconds(long unixNs) {
        if (unixNs < 0) throw new IllegalArgumentException("Negative Unix timestamp");
        return (unixNs / 1_000_000_000L) + "." +
            String.format(Locale.ROOT, "%09d", unixNs % 1_000_000_000L);
    }

    /** Mapping anchored once at session start; result is host time, not exposure/sample time. */
    public static long hostUnixNs(long bootNs, long anchorBootNs, long anchorUnixMs) {
        if (bootNs < 0 || anchorBootNs < 0 || anchorUnixMs < 0)
            throw new IllegalArgumentException("Negative clock input");
        return Math.addExact(Math.multiplyExact(anchorUnixMs, 1_000_000L),
            Math.subtractExact(bootNs, anchorBootNs));
    }

    public static String timestampLine(long savedFrameIndex, long unixNs) {
        if (savedFrameIndex < 0) throw new IllegalArgumentException("Negative frame index");
        return savedFrameIndex + " " + seconds(unixNs) + "\n";
    }

    /** Require an explicit conversion profile for THIS sensor; never guess ICM20602 coefficients. */
    public static final class ImuScale {
        public final double accelMetresPerSecondSquaredPerCount;
        public final double gyroRadiansPerSecondPerCount;
        public final String source;
        public ImuScale(double accel, double gyro, String source) {
            if (!Double.isFinite(accel) || accel <= 0 || !Double.isFinite(gyro) || gyro <= 0 ||
                source == null || source.trim().isEmpty())
                throw new IllegalArgumentException("Verified sensor scales and source required");
            this.accelMetresPerSecondSquaredPerCount = accel;
            this.gyroRadiansPerSecondPerCount = gyro;
            this.source = source.trim();
        }
    }

    /** Legacy orientation fields are all zero (unavailable), NOT a valid estimated quaternion.
     * Axis identity must be confirmed separately; this helper preserves the input axis order.
     */
    public static String imuLine(long hostUnixNs, int ax, int ay, int az,
                                 int gx, int gy, int gz, ImuScale scale) {
        if (scale == null) throw new IllegalArgumentException("No verified sensor scale");
        int[] raw = {ax, ay, az, gx, gy, gz};
        for (int value : raw) if (value < -32768 || value > 32767)
            throw new IllegalArgumentException("IMU value is not signed 16-bit");
        return seconds(hostUnixNs) + ",0.0,0.0,0.0,0.0," +
            number(gx * scale.gyroRadiansPerSecondPerCount) + "," +
            number(gy * scale.gyroRadiansPerSecondPerCount) + "," +
            number(gz * scale.gyroRadiansPerSecondPerCount) + "," +
            number(ax * scale.accelMetresPerSecondSquaredPerCount) + "," +
            number(ay * scale.accelMetresPerSecondSquaredPerCount) + "," +
            number(az * scale.accelMetresPerSecondSquaredPerCount) + "\n";
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite physical value");
        return Double.toString(value);
    }

    /** Reject path separators/control characters; allow human task names without silent renaming. */
    public static String component(String value) {
        if (value == null) throw new IllegalArgumentException("Empty path component");
        String text = value.trim();
        if (text.isEmpty() || text.equals(".") || text.equals("..") || text.length() > 80)
            throw new IllegalArgumentException("Invalid path component");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c))
                throw new IllegalArgumentException("Unsafe path component");
        }
        return text;
    }

    /** Matches actual EgoRecord session.py naming. Callers must refuse existing directories. */
    public static String sessionName(String deviceId, long startedUnixMs, ZoneId zone) {
        if (startedUnixMs < 0 || zone == null) throw new IllegalArgumentException("Invalid start time");
        return component(deviceId) + "_session_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
            .withZone(zone).format(Instant.ofEpochMilli(startedUnixMs));
    }
}
