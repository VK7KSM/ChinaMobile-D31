package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class DownlinkRouteLeaseTest {
    @Test public void focusReleaseCanPrecedeFreshRestoreObservation()throws Exception{
        Port p=new Port();Journal j=new Journal();DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{});
        lease.openPtt();lease.releaseFocus();assertEquals(1,p.abandonCalls);assertNotNull(j.value);assertTrue(p.speaker);
        lease.close();assertEquals(1,p.abandonCalls);assertNull(j.value);assertFalse(p.speaker);
    }
    static class Port implements DownlinkRouteLease.Port{
        int mode,focusCalls,abandonCalls,writes;boolean speaker,denied,failAfterWrite,failRestore;
        public int mode(){return mode;}public boolean speaker(){return speaker;}
        public void speaker(boolean value)throws Exception{
            writes++;if(!value&&failRestore)throw new Exception("restore");speaker=value;
            if(value&&failAfterWrite)throw new Exception("apply");
        }
        public boolean focus(){focusCalls++;return !denied;}public void abandonFocus(){abandonCalls++;}
    }
    static class Journal implements DownlinkRouteLease.Journal{
        JSONObject value;boolean failWrite,failClear;
        public JSONObject read(){return value;}
        public void write(JSONObject v)throws Exception{if(failWrite)throw new Exception("disk");value=new JSONObject(v.toString());}
        public void clear()throws Exception{if(failClear)throw new Exception("disk");value=null;}
    }
    @Test public void restoresOnlyOwnSpeakerChangeNoVolumePortExists()throws Exception{
        Port p=new Port();Journal j=new Journal();DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{});
        lease.openPtt();assertTrue(p.speaker);assertNotNull(j.value);assertEquals(1,p.writes);
        lease.close();assertFalse(p.speaker);assertNull(j.value);assertEquals(1,p.abandonCalls);assertEquals(2,p.writes);
        lease.close();assertEquals(2,p.writes);
    }
    @Test public void unchangedRouteNeverWritten()throws Exception{
        Port p=new Port();p.speaker=true;Journal j=new Journal();DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{});
        lease.openPtt();lease.close();assertEquals(0,p.writes);assertTrue(p.speaker);
    }
    @Test public void journalFailurePreventsAllDeviceMutations()throws Exception{
        Port p=new Port();Journal j=new Journal();j.failWrite=true;
        try{new DownlinkRouteLease(p,j,()->{}).openPtt();fail();}catch(Exception expected){}
        assertEquals(0,p.writes);assertEquals(0,p.focusCalls);
    }
    @Test public void focusDeniedDoesNotChangeRoute()throws Exception{
        Port p=new Port();p.denied=true;Journal j=new Journal();
        try{new DownlinkRouteLease(p,j,()->{}).openPtt();fail();}catch(Exception expected){}
        assertFalse(p.speaker);assertEquals(0,p.writes);assertNull(j.value);
    }
    @Test public void exceptionAfterMutationStillRestoresOriginal()throws Exception{
        Port p=new Port();p.failAfterWrite=true;Journal j=new Journal();
        try{new DownlinkRouteLease(p,j,()->{}).openPtt();fail();}catch(Exception expected){}
        assertFalse(p.speaker);assertNull(j.value);assertEquals(1,p.abandonCalls);
    }
    @Test public void cellularTakeoverPreservesPendingJournalAndNeverResetsMode()throws Exception{
        Port p=new Port();Journal j=new Journal();boolean[] busy={false};
        DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{if(busy[0])throw new Exception("call");});
        lease.openPtt();busy[0]=true;p.mode=2;
        try{lease.close();fail();}catch(Exception expected){}
        assertEquals(2,p.mode);assertNotNull(j.value);assertEquals(1,p.writes);
        busy[0]=false;p.mode=0;lease.recover();assertFalse(p.speaker);assertNull(j.value);
    }
    @Test public void failedRestoreSurvivesNewComponentInstance()throws Exception{
        Port p=new Port();Journal j=new Journal();DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{});
        lease.openPtt();p.failRestore=true;
        try{lease.close();fail();}catch(Exception expected){}
        assertNotNull(j.value);p.failRestore=false;new DownlinkRouteLease(p,j,()->{}).recover();
        assertFalse(p.speaker);assertNull(j.value);
    }
    @Test public void pendingJournalAndBusyBaselineBlockNewPlayback()throws Exception{
        Port p=new Port();Journal j=new Journal();DownlinkRouteLease lease=new DownlinkRouteLease(p,j,()->{});
        lease.openPtt();int calls=p.focusCalls;
        try{new DownlinkRouteLease(p,j,()->{}).openPtt();fail();}catch(Exception expected){}
        assertEquals(calls,p.focusCalls);lease.close();p.mode=3;
        try{new DownlinkRouteLease(p,j,()->{}).openPtt();fail();}catch(Exception expected){}
        assertEquals(calls,p.focusCalls);
    }
}
