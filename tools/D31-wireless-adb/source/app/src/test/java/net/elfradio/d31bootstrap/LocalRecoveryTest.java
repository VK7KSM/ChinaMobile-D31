package net.elfradio.d31bootstrap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LocalRecoveryTest {
    @Test
    public void hashCheckUsesCommandExitInsteadOfCapturedOutput() {
        assertEquals(
                "/system/bin/busybox sha256sum /data/local/test.sh "
                        + "| /system/bin/busybox grep -qi '^ABC123  '",
                LocalRecovery.hashCheckCommand("/data/local/test.sh", "ABC123"));
    }

    @Test
    public void hashCheckAcceptsLowercaseDigestOutput() {
        assertEquals(
                "/system/bin/busybox sha256sum /data/local/test.sh "
                        + "| /system/bin/busybox grep -qi '^BA25EBF9  '",
                LocalRecovery.hashCheckCommand("/data/local/test.sh", "BA25EBF9"));
    }

    @Test
    public void differentDigestProducesDifferentHashCheck() {
        String verified = LocalRecovery.hashCheckCommand(
                "/data/local/test.sh", "BA25EBF9");
        String unknown = LocalRecovery.hashCheckCommand(
                "/data/local/test.sh", "8C3CD362");
        org.junit.Assert.assertNotEquals(verified, unknown);
    }

    @Test
    public void anyHashCheckAcceptsAllVerifiedStartupScripts() {
        assertEquals(
                "/system/bin/busybox sha256sum /data/local/test.sh "
                        + "| /system/bin/busybox grep -qi '^V7  ' || "
                        + "/system/bin/busybox sha256sum /data/local/test.sh "
                        + "| /system/bin/busybox grep -qi '^V8  ' || "
                        + "/system/bin/busybox sha256sum /data/local/test.sh "
                        + "| /system/bin/busybox grep -qi '^V9  '",
                LocalRecovery.anyHashCheckCommand(
                        "/data/local/test.sh", "V7", "V8", "V9"));
    }

    @Test
    public void staleLockCleanupConditionRefusesToDeleteLiveOwner() {
        assertEquals(
                "owner=$(cat /data/local/snSudoSerialize/held/owner 2>/dev/null); "
                        + "if [ -n \"$owner\" ] && [ -d \"/proc/$owner\" ]; "
                        + "then false; else rm -rf /data/local/snSudoSerialize/held "
                        + "&& [ ! -e /data/local/snSudoSerialize/held ]; fi",
                LocalRecovery.staleLockCleanupCondition());
    }

    @Test
    public void markedCheckWritesReadablePassOnlyAfterCondition() {
        assertEquals(
                "(test -f /data/local/test.sh) && echo PASS > /data/local/tmp/marker "
                        + "&& chmod 0644 /data/local/tmp/marker",
                LocalRecovery.markedCheckCommand(
                        "test -f /data/local/test.sh", "/data/local/tmp/marker"));
    }
}
