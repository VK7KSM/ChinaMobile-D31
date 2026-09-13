package net.elfradio.d31bootstrap.management;

import java.io.File;
import java.io.RandomAccessFile;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.management.NetworkChangeTransactionTest.*;

public class NetworkChangeTransactionJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private NetworkChangeTransaction engine(File dir, Device device, Time time) throws Exception {
        return new NetworkChangeTransaction(new NetworkChangeTransactionJournal(dir), device, time);
    }
    @Test public void realDiskReopenPreservesIdempotenceAndDeadline() throws Exception {
        File dir = temporary.newFolder(); Device d = new Device(); Time t = new Time();
        engine(dir,d,t).begin("disk-1",false,1000);
        state("AWAITING_CONFIRM",engine(dir,d,t).begin("disk-1",false,1000)); assertEquals(1,d.writes);
        t.now=1100; state("ROLLED_BACK",engine(dir,d,t).recover("disk-1"));
        assertTrue(engine(dir,d,t).query("disk-1").getBoolean("restored"));
    }
    @Test public void tornTailIsUnknownAndNeverFallsBackToOlderIntent() throws Exception {
        File dir=temporary.newFolder(); Device d=new Device(); Time t=new Time();
        engine(dir,d,t).begin("disk-1",false,1000);
        try(RandomAccessFile file=new RandomAccessFile(new File(dir,"wifi-enabled.journal"),"rw")) {
            file.setLength(file.length()-1);
        }
        state("UNKNOWN",engine(dir,d,t).query("disk-1"));
        rejected(() -> engine(dir,d,t).recover("disk-1")); assertEquals(1,d.writes);
    }
    @Test public void digestMismatchBlocksNewTaskAndRecovery() throws Exception {
        File dir=temporary.newFolder(); Device d=new Device(); Time t=new Time();
        engine(dir,d,t).begin("disk-1",false,1000);
        try(RandomAccessFile file=new RandomAccessFile(new File(dir,"wifi-enabled.journal"),"rw")) {
            file.seek(12); file.writeByte(0);
        }
        state("UNKNOWN",engine(dir,d,t).query("disk-1"));
        rejected(() -> engine(dir,d,t).begin("disk-2",true,1000)); assertEquals(1,d.writes);
    }
    @Test public void simultaneousStoreOwnersRejectedAndThenReleased() throws Exception {
        File dir=temporary.newFolder(); NetworkChangeTransactionJournal first=new NetworkChangeTransactionJournal(dir);
        try(NetworkChangeTransaction.Store.Session owner=first.lock()) {
            rejected(() -> new NetworkChangeTransactionJournal(dir).lock());
        }
        try(NetworkChangeTransaction.Store.Session next=new NetworkChangeTransactionJournal(dir).lock()) {
            assertEquals(0,next.read().getJSONObject("records").length());
        }
    }
    @Test public void corruptSchemaDoesNotBecomeAbsent() throws Exception {
        File dir=temporary.newFolder(); NetworkChangeTransactionJournal journal=new NetworkChangeTransactionJournal(dir);
        try(NetworkChangeTransaction.Store.Session s=journal.lock()) {
            s.save(new JSONObject().put("a",new JSONObject().put("state","CONFIRMED")));
        }
        state("UNKNOWN",engine(dir,new Device(),new Time()).query("a"));
    }
    @Test public void absentTaskDoesNotReadDevice() throws Exception {
        Device d=new Device(); state("ABSENT",engine(temporary.newFolder(),d,new Time()).query("absent"));
        assertEquals(0,d.reads); assertEquals(0,d.writes);
    }
}
