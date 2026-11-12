package com.egouvc.phone.ui;

/** One permission/open attempt per attachment; no dialog loops after a denial. */
public final class AutoConnectPolicy {
    public enum Action { NONE, CAMERA_PERMISSION, AUDIO_PERMISSION, USB_PERMISSION, OPEN }
    private String key;
    private boolean cameraAsked, audioAsked, usbAsked, opened;
    public void reset() { key = null; cameraAsked = audioAsked = usbAsked = opened = false; }
    public Action next(String deviceKey, boolean foreground, boolean surfaceReady,
                       boolean cameraGranted, boolean audioGranted, boolean usbGranted,
                       boolean usbPending, boolean canOpen, boolean jobBusy) {
        if (deviceKey == null) { reset(); return Action.NONE; }
        if (!deviceKey.equals(key)) { reset(); key = deviceKey; }
        if (!foreground || !surfaceReady) return Action.NONE;
        if (!cameraGranted) {
            if (cameraAsked) return Action.NONE;
            cameraAsked = true; return Action.CAMERA_PERMISSION;
        }
        if (!audioGranted && !audioAsked) {
            audioAsked = true; return Action.AUDIO_PERMISSION;
        }
        if (!usbGranted) {
            if (usbPending || usbAsked) return Action.NONE;
            usbAsked = true; return Action.USB_PERMISSION;
        }
        if (canOpen && !jobBusy && !opened) { opened = true; return Action.OPEN; }
        return Action.NONE;
    }
}
