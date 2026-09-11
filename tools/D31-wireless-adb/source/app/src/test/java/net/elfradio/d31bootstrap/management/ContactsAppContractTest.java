package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContactsAppContractTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String BOOT = "00000000-0000-0000-0000-000000000002";
    private static final String HASH = new String(new char[64]).replace('\0', 'a');
    interface Attempt { void run() throws Exception; }
    private static void rejects(String code, Attempt attempt) throws Exception {
        try { attempt.run(); fail("应当拒绝"); } catch (IOException expected) { assertEquals(code, expected.getMessage()); }
    }
    @Test public void duplicateRequestDoesNotAcquireAnotherSlot() throws Exception {
        ContactsAppContract.Requests requests = new ContactsAppContract.Requests();
        requests.claim(ID, BOOT, 0, 0);
        rejects("CONTACTS_DUPLICATE_REQUEST", () -> requests.claim(ID, BOOT, 0, 1));
        rejects("CONTACTS_DUPLICATE_REQUEST", () -> requests.claim(ID, BOOT, 1, 1));
    }
    @Test public void concurrentDuplicateHasExactlyOneWinner() throws Exception {
        ContactsAppContract.Requests requests = new ContactsAppContract.Requests();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(16);
        AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
        for (int i = 0; i < 16; i++) new Thread(() -> {
            try { start.await(); requests.claim(ID, BOOT, 0, 0); accepted.incrementAndGet(); }
            catch (Exception expected) { rejected.incrementAndGet(); } finally { done.countDown(); }
        }).start();
        start.countDown(); assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, accepted.get()); assertEquals(15, rejected.get());
    }
    @Test public void capacityDoesNotEvictUnexpiredRequests() throws Exception {
        ContactsAppContract.Requests requests = new ContactsAppContract.Requests(); requests.claim(ID, BOOT, 0, 0);
        for (int i = 1; i < 64; i++) requests.claim(UUID.randomUUID().toString(), BOOT, 0, 0);
        rejects("CONTACTS_REQUEST_CAPACITY", () -> requests.claim(UUID.randomUUID().toString(), BOOT, 0, 1));
        rejects("CONTACTS_DUPLICATE_REQUEST", () -> requests.claim(ID, BOOT, 0, 1));
        requests.claim(UUID.randomUUID().toString(), BOOT, 12000, 12000);
        rejects("CONTACTS_REQUEST_EXPIRED_OR_INVALID", () -> requests.claim(ID, BOOT, 0, 12000));
    }
    @Test public void futureAndExpiredHandshakeRejected() throws Exception {
        rejects("CONTACTS_REQUEST_EXPIRED_OR_INVALID", () -> ContactsAppContract.request(ID, BOOT, 100, 99, 3000));
        rejects("CONTACTS_REQUEST_EXPIRED_OR_INVALID", () -> ContactsAppContract.request(ID, BOOT, 0, 3000, 3000));
    }
    @Test public void actualUidPackageAndFrameworkIdentityAllRequired() {
        String p = ContactsAppContract.PACKAGE;
        assertTrue(ContactsAppContract.applicationIdentity(10001, p, p, new String[]{p}));
        assertFalse(ContactsAppContract.applicationIdentity(0, p, p, new String[]{p}));
        assertFalse(ContactsAppContract.applicationIdentity(10001, p, "android", new String[]{p}));
        assertFalse(ContactsAppContract.applicationIdentity(10001, p, p, new String[]{"android"}));
        assertFalse(ContactsAppContract.applicationIdentity(110001, p, p, new String[]{p}));
    }
    @Test public void replyRequiresActualUidRequestBootAndTime() throws Exception {
        String good = ContactsAppContract.envelope(ID, BOOT, 0, ContactsAppContract.metadata("test")).toString();
        assertTrue(ContactsAppContract.reply(10001, 10001, ID, BOOT, 0, 1, good).has("result"));
        rejects("CONTACTS_REPLY_IDENTITY_OR_SIZE", () -> ContactsAppContract.reply(10002, 10001, ID, BOOT, 0, 1, good));
        rejects("CONTACTS_REPLY_MISMATCH", () -> ContactsAppContract.reply(10001, 10001, BOOT, ID, 0, 1, good));
        rejects("CONTACTS_REQUEST_EXPIRED_OR_INVALID", () -> ContactsAppContract.reply(10001, 10001, ID, BOOT, 0, 12000, good));
    }
    @Test public void oversizedReplyRejectedBeforeParsing() throws Exception {
        String large = new String(new char[8193]).replace('\0', 'x');
        rejects("CONTACTS_REPLY_IDENTITY_OR_SIZE", () -> ContactsAppContract.reply(10001, 10001, ID, BOOT, 0, 1, large));
    }
    @Test public void cliOnlyPermitsMetadataAndExplicitDigest() throws Exception {
        assertEquals(HASH, ContactsAppCommand.validate(new String[]{"metadata", HASH}));
        rejects("CONTACTS_ARGUMENTS_INVALID", () -> ContactsAppCommand.validate(new String[]{"list", HASH}));
        rejects("CONTACTS_ARGUMENTS_INVALID", () -> ContactsAppCommand.validate(new String[]{"metadata", HASH, "LOCAL"}));
        rejects("CONTACTS_APK_HASH_INVALID", () -> ContactsAppCommand.validate(new String[]{"metadata", ""}));
    }
    @Test public void noRecordOrMessagingFieldsInMetadata() throws Exception {
        JSONObject value = ContactsAppContract.metadata("CONTACTS_BIND_REJECTED");
        assertFalse(value.getBoolean("contacts_requested")); assertFalse(value.getBoolean("vendorServiceStartRequested"));
        assertFalse(value.has("items")); assertFalse(value.has("record_count")); assertFalse(value.getBoolean("listComplete"));
    }
}
