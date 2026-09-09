package org.pjsip.pjsua2;

/** Bulk access to SWIG's std::vector<unsigned char> audio buffers. */
public final class ByteVectorNative {
    static {
        System.loadLibrary("gsm_audio");
    }

    private ByteVectorNative() {
    }

    public static int copyFrom(ByteVector destination, byte[] source, int length) {
        return copyFromJava(ByteVector.getCPtr(destination), source, length);
    }

    public static int copyTo(ByteVector source, byte[] destination, int length) {
        return copyToJava(ByteVector.getCPtr(source), destination, length);
    }

    private static native int copyFromJava(long vectorPtr, byte[] source, int length);
    private static native int copyToJava(long vectorPtr, byte[] destination, int length);
}
