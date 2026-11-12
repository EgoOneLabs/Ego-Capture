package com.egouvc.phone.export;

import org.junit.Test;
import java.time.ZoneId;
import java.util.Locale;
import static org.junit.Assert.*;

public class EgoRecordFormatTest {
    @Test public void timestampsRetainNanosecondsAndUseSavedIndex() {
        assertEquals("0 1785494722.166671038\n", EgoRecordFormat.timestampLine(0, 1785494722166671038L));
        assertEquals("1785494722.000000001", EgoRecordFormat.seconds(1785494722000000001L));
        assertEquals(1793008655798000001L,
            EgoRecordFormat.hostUnixNs(1000001, 0, 1793008655797L));
    }
    @Test public void rowMatchesTargetColumnOrderWithExplicitSyntheticScale() {
        EgoRecordFormat.ImuScale scale = new EgoRecordFormat.ImuScale(0.5, 0.25, "synthetic test only");
        assertEquals("0.000000001,0.0,0.0,0.0,0.0,1.0,-2.0,3.0,1.0,-2.0,3.0\n",
            EgoRecordFormat.imuLine(1, 2, -4, 6, 4, -8, 12, scale));
        assertEquals(11, EgoRecordFormat.IMU_HEADER.split(",").length);
    }
    @Test public void rejectsMissingOrInvalidScaleAndTraversal() {
        assertThrows(IllegalArgumentException.class, () -> EgoRecordFormat.imuLine(1, 0,0,0,0,0,0,null));
        assertThrows(IllegalArgumentException.class, () -> new EgoRecordFormat.ImuScale(Double.NaN,1,"x"));
        assertThrows(IllegalArgumentException.class, () -> new EgoRecordFormat.ImuScale(1,1," "));
        for (String name : new String[]{"..", "../escape", "a/b", "a\\b", "a\nb"})
            assertThrows(IllegalArgumentException.class, () -> EgoRecordFormat.component(name));
        assertEquals("任务一", EgoRecordFormat.component(" 任务一 "));
    }
    @Test public void sessionNamingUsesExplicitZoneAndDeviceId() {
        assertEquals("device01_session_20261026_175735", EgoRecordFormat.sessionName(
            "device01",1793008655797L,ZoneId.of("Asia/Shanghai")));
    }
    @Test public void numericFormattingDoesNotFollowPhoneLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.000000001", EgoRecordFormat.seconds(1000000001L));
        } finally { Locale.setDefault(before); }
    }
}
