package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteManualUpdateTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static JSONObject apk(int v)throws Exception{return new JSONObject().put("versionCode",v)
            .put("sha256",RemoteProtocol.hash("apk-"+v)).put("remote_full",true);}
    static class Fake implements RemoteManualUpdate.Platform {
        JSONObject installed=apk(83),active=apk(82); boolean cloudBusy,healthy=true,lateCloud;
        int stops,restores,selections; String session="boot-a";
        Fake()throws Exception{}
        public JSONObject installed(){return installed;}
        public JSONObject active(){return active;}
        public JSONObject preserve(JSONObject a)throws Exception{return new JSONObject(a.toString());}
        public boolean cloudBusy(){return cloudBusy;}
        public void stop(){stops++;if(lateCloud)cloudBusy=true;}
        public void select(JSONObject a)throws Exception{if(!a.getString("sha256").equals(active.getString("sha256")))selections++;active=a;}
        public boolean healthy(JSONObject a)throws Exception{return healthy||a.getInt("versionCode")==82;}
        public void restore(JSONObject a){installed=a;restores++;}
        public String session(){return session;}
    }
    JSONObject state(File root,int v)throws Exception{return new JSONObject(RescueFiles.read(new File(root,apk(v).getString("sha256")+".json"),64000));}
    @Test public void offlineSuccessAndDuplicateDoNotReinstall()throws Exception{
        Fake p=new Fake();File root=temporary.newFolder();RemoteManualUpdate e=new RemoteManualUpdate(root,p);
        e.tick(1);e.tick(2);e.tick(3);assertFalse(e.tick(4));assertFalse(e.tick(5));
        assertEquals("success",state(root,83).getString("phase"));assertEquals(1,p.selections);assertEquals(0,p.restores);
        assertFalse(state(root,83).getBoolean("cloud_required"));
    }
    @Test public void existingCloudTaskDefersManual()throws Exception{
        Fake p=new Fake();p.cloudBusy=true;RemoteManualUpdate e=new RemoteManualUpdate(temporary.newFolder(),p);
        assertFalse(e.tick(1));assertEquals(0,p.stops);assertEquals(0,p.selections);
    }
    @Test public void lastOfferAtCoreExitIsNotOverridden()throws Exception{
        Fake p=new Fake();p.lateCloud=true;RemoteManualUpdate e=new RemoteManualUpdate(temporary.newFolder(),p);
        assertFalse(e.tick(1));assertEquals(0,p.selections);
    }
    @Test public void unhealthyRestoresRealPriorVersion()throws Exception{
        Fake p=new Fake();p.healthy=false;File root=temporary.newFolder();RemoteManualUpdate e=new RemoteManualUpdate(root,p);
        e.tick(1);e.tick(2);e.tick(150003);e.tick(150004);assertFalse(e.tick(150005));
        assertEquals("recovered",state(root,83).getString("phase"));assertEquals(82,p.installed.getInt("versionCode"));assertEquals(1,p.restores);
    }
    @Test public void restartResumesPreparedTransaction()throws Exception{
        Fake p=new Fake();File root=temporary.newFolder();new RemoteManualUpdate(root,p).tick(1);
        RemoteManualUpdate e=new RemoteManualUpdate(root,p);e.tick(2);e.tick(3);
        assertEquals("success",state(root,83).getString("phase"));
    }
    @Test public void laterManualInstallIsNotRolledBackOver()throws Exception{
        Fake p=new Fake();File root=temporary.newFolder();RemoteManualUpdate e=new RemoteManualUpdate(root,p);
        e.tick(1);e.tick(2);p.installed=apk(84);e.tick(3);e.tick(4);e.tick(5);e.tick(6);e.tick(7);
        assertEquals("superseded",state(root,83).getString("phase"));assertEquals("success",state(root,84).getString("phase"));assertEquals(0,p.restores);
    }
    @Test public void basicProbeIsNeverSelectedAsFullCore()throws Exception{
        Fake p=new Fake();p.installed.put("remote_full",false);RemoteManualUpdate e=new RemoteManualUpdate(temporary.newFolder(),p);
        assertFalse(e.tick(1));assertEquals(0,p.stops);
    }
    @Test public void healthWindowRestartsAfterSupervisorRestart()throws Exception{
        Fake p=new Fake();p.healthy=false;File root=temporary.newFolder();RemoteManualUpdate e=new RemoteManualUpdate(root,p);
        e.tick(1);e.tick(2);p.session="boot-b";e.tick(200000);
        assertEquals("health",state(root,83).getString("phase"));assertEquals(0,p.restores);
    }
}
