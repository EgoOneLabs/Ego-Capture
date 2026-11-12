package com.egouvc.phone.ui;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.egouvc.phone.ui.AutoConnectPolicy.Action.*;

public class AutoConnectPolicyTest {
    @Test public void permissionsThenOpenOnce() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(CAMERA_PERMISSION,p.next("a",true,true,false,false,false,false,true,false));
        assertEquals(NONE,p.next("a",true,true,false,false,false,false,true,false));
        assertEquals(AUDIO_PERMISSION,p.next("a",true,true,true,false,false,false,true,false));
        assertEquals(USB_PERMISSION,p.next("a",true,true,true,true,false,false,true,false));
        assertEquals(NONE,p.next("a",true,true,true,true,false,true,true,false));
        assertEquals(OPEN,p.next("a",true,true,true,true,true,false,true,false));
        assertEquals(NONE,p.next("a",true,true,true,true,true,false,true,false));
    }
    @Test public void denialDoesNotLoopAndExplicitRetryResets() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(USB_PERMISSION,p.next("a",true,true,true,true,false,false,true,false));
        assertEquals(NONE,p.next("a",true,true,true,true,false,false,true,false));
        p.reset();
        assertEquals(USB_PERMISSION,p.next("a",true,true,true,true,false,false,true,false));
    }
    @Test public void exportBlocksOpeningUntilFinished() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(NONE,p.next("a",true,true,true,true,true,false,true,true));
        assertEquals(OPEN,p.next("a",true,true,true,true,true,false,true,false));
    }
    @Test public void waitsForForegroundSurfaceAndIdleCamera() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(NONE,p.next("a",false,true,true,true,true,false,true,false));
        assertEquals(NONE,p.next("a",true,false,true,true,true,false,true,false));
        assertEquals(NONE,p.next("a",true,true,true,true,true,false,false,false));
        assertEquals(OPEN,p.next("a",true,true,true,true,true,false,true,false));
    }
    @Test public void detachAndReplacementPermitNewOpen() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(OPEN,p.next("a",true,true,true,true,true,false,true,false));
        assertEquals(NONE,p.next(null,true,true,true,true,true,false,true,false));
        assertEquals(OPEN,p.next("a",true,true,true,true,true,false,true,false));
        assertEquals(OPEN,p.next("b",true,true,true,true,true,false,true,false));
    }
    @Test public void microphoneDenialAllowsPreviewWithoutDialogLoop() {
        AutoConnectPolicy p = new AutoConnectPolicy();
        assertEquals(AUDIO_PERMISSION,p.next("a",true,true,true,false,true,false,true,false));
        assertEquals(OPEN,p.next("a",true,true,true,false,true,false,true,false));
        assertEquals(NONE,p.next("a",true,true,true,false,true,false,true,false));
    }
}
