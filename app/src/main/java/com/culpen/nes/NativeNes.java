package com.culpen.nes;

/** JNI entry points. All calls belong to the single emulation thread. */
public final class NativeNes {
    static { System.loadLibrary("nescore"); }
    private NativeNes() {}
    public static native String load(String romPath, String systemDir, String saveDir);
    public static native void unload();
    public static native void reset();
    public static native double fps();
    public static native int sampleRate();
    public static native int width();
    public static native int height();
    public static native void runFrame(int buttons);
    public static native void copyVideo(int[] argb);
    public static native int drainAudio(short[] stereoPcm);
    public static native byte[] saveState();
    public static native boolean loadState(byte[] state);
    public static native byte[] saveRam();
    public static native void loadRam(byte[] ram);
}
