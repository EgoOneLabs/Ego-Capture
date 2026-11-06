package com.egouvc.phone.export;

/** Coefficient-domain crop only. No pixel decoding or JPEG requantization. */
public final class LosslessJpegSplit {
    static { System.loadLibrary("ego_jpeg_split"); }
    private LosslessJpegSplit() {}
    /** Strict JPEG data validation failed; never a disk, library-loading, or geometry error. */
    public static final class CorruptFrameException extends java.io.IOException {
        public CorruptFrameException(String message) { super(message); }
    }
    public static native byte[][] split(byte[] jpeg, int width, int height) throws java.io.IOException;
}
