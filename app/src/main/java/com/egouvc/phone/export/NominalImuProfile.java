package com.egouvc.phone.export;

/** 2UQ2 nominal USB units from supplied vendor SDK/manual; NOT per-unit calibration. */
public final class NominalImuProfile {
    public static final String ID = "2uq2_sc7u22_usb_nominal_8g_2000dps_v1";
    public static final double ACCEL_COUNTS_PER_G = 4096.0;
    public static final double GYRO_COUNTS_PER_DEGREE_PER_SECOND = 16.4;
    public static final double STANDARD_GRAVITY = 9.80665;
    public static final String EVIDENCE = "YLX SDK nominal coefficients 4096 count/g and 16.4 count/(degree/s); 2UQ2 V1.4 nominal +/-8g, +/-2000degree/s; no per-unit precision calibration";
    private NominalImuProfile() {}
    public static EgoRecordFormat.ImuScale scale() {
        return new EgoRecordFormat.ImuScale(STANDARD_GRAVITY / ACCEL_COUNTS_PER_G,
            Math.PI / 180.0 / GYRO_COUNTS_PER_DEGREE_PER_SECOND, EVIDENCE);
    }
}
