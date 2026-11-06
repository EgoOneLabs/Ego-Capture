package com.egouvc.phone.export;
import org.junit.Test;
import static org.junit.Assert.*;
public final class NominalImuProfileTest {
    @Test public void knownUnitsSignsAndAxisOrder() {NominalImuProfileRegression.knownSamples();}
    @Test public void timestampsAndUnavailableOrientationAreRetained() {
        NominalImuProfileRegression.check(1793351091166671038L,123,-456,789,-123,456,-789);
    }
    @Test public void nominalSourceIsExplicit() {
        assertEquals(4096.0,NominalImuProfile.ACCEL_COUNTS_PER_G,0.0);
        assertEquals(16.4,NominalImuProfile.GYRO_COUNTS_PER_DEGREE_PER_SECOND,0.0);
        assertTrue(NominalImuProfile.scale().source.contains("no per-unit precision calibration"));
    }
    @Test public void signedSixteenBitRangeStillEnforced() {
        assertThrows(IllegalArgumentException.class,()->EgoRecordFormat.imuLine(1,32768,0,0,0,0,0,NominalImuProfile.scale()));
    }
}
