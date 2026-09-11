package net.elfradio.d31bootstrap;

final class RemoteDeployment {
    static boolean systemManaged() {
        return new java.io.File("/system/etc/d31-elfremote.system").isFile();
    }
    private RemoteDeployment() { }
}
