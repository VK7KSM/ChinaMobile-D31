package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

public class CellularConfigurationTest {
    static final String ROOT="/data/local/d31-startup-handover";
    static final String SCRIPT=ROOT+"/start.sh", JAR=ROOT+"/handover.jar", MARKER=ROOT+"/cellular-enabled";
    static final String FIELD="semantic.startup.cellular_enabled";
    static byte[] resource(String name) throws Exception {
        try (InputStream input=CellularConfigurationTest.class.getResourceAsStream("/diagnostics/"+name)) {
            if (input==null) throw new AssertionError("缺少冻结资源");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] b=new byte[1024]; int n;
            while ((n=input.read(b))!=-1) bytes.write(b,0,n);
            return bytes.toByteArray();
        }
    }
    static Access fixture(Clock clock,String variant,String kind) throws Exception {
        Access access=new Access(clock).add(ROOT,"directory","").add(SCRIPT,"file","").add(JAR,"file","");
        access.nodes.get(SCRIPT).bytes=resource("startup-handover.sh");
        access.nodes.get(JAR).bytes=resource("startup-handover-"+variant+".jar");
        if (!"ABSENT".equals(kind)) access.add(MARKER,kind,"");
        return access;
    }
    static JSONObject collect(CollectionAccess access,Clock clock,String scope,long bytes) throws Exception {
        return new ManifestCollector(access,clock).collect(identity(),scope,limits(40,bytes,4)).manifest().toJson();
    }
    static JSONObject semantic(JSONObject manifest) throws Exception { return field(manifest,SCRIPT,FIELD); }
    @Test public void bothExactJarsUseTheSameIsFileCondition() throws Exception {
        String[] hashes={"f56cb9586d08046848e02c321cb00916f595ee039f2b0e8e3e92028474c2da92","3e12fe7cdf595483d66b72a9a3ab371a17ee58b2c801155c0b24f4f5481d85ec"};
        int i=0;
        for (String variant:new String[]{"firmware","deployed"}) {
            byte[] jar=resource("startup-handover-"+variant+".jar");
            assertEquals(hashes[i++],CollectionSupport.hex(CollectionSupport.digest().digest(jar)));
            for (String kind:new String[]{"file","directory","block","ABSENT","symlink"}) {
                Clock clock=new Clock(); Access access=fixture(clock,variant,kind);
                JSONObject e=semantic(collect(access,clock,ROOT,200000));
                if (kind.equals("symlink")) { assertEquals("NOT_CHECKED",e.getString("state")); assertFalse(e.has("value")); }
                else { assertEquals("OBSERVED",e.getString("state")); assertEquals(kind.equals("file"),e.getBoolean("value")); }
            }
        }
    }
    @Test public void unknownJarCannotTurnMissingMarkerIntoFalse() throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,"deployed","ABSENT");
        access.nodes.get(JAR).bytes[100]^=1;
        JSONObject e=semantic(collect(access,clock,ROOT,200000));
        assertEquals("SWITCH_CONSUMER_JAR_NOT_RECOGNIZED",e.getString("reason")); assertFalse(e.has("value"));
    }
    @Test public void unknownLauncherRejectsEvenAKnownJar() throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,"deployed","file");
        access.nodes.get(SCRIPT).bytes="#!/system/bin/sh\nexit 0\n".getBytes("UTF-8");
        assertEquals("SWITCH_CONSUMER_TEMPLATE_NOT_RECOGNIZED",semantic(collect(access,clock,ROOT,200000)).getString("reason"));
    }
    @Test public void missingDeniedAndLinkedJarRemainInsufficient() throws Exception {
        for (String state:new String[]{"missing","denied","symlink"}) {
            Clock clock=new Clock(); Access access=fixture(clock,"deployed","file");
            if (state.equals("missing")) access.nodes.remove(JAR);
            if (state.equals("denied")) access.failingStat=JAR;
            if (state.equals("symlink")) access.nodes.get(JAR).type="symlink";
            JSONObject e=semantic(collect(access,clock,ROOT,200000));
            assertEquals(state.equals("symlink")?"NOT_CHECKED":"READ_FAILED",e.getString("state")); assertFalse(e.has("value"));
        }
    }
    @Test public void deniedMarkerIsNotDisabledAndContentsDoNotControlTheSwitch() throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,"deployed","file"); access.failingStat=MARKER;
        assertEquals("READ_FAILED",semantic(collect(access,clock,ROOT,200000)).getString("state"));
        access.failingStat=null; access.nodes.get(MARKER).bytes="false\n".getBytes("UTF-8");
        assertTrue(semantic(collect(access,clock,ROOT,200000)).getBoolean("value"));
    }
    @Test public void jarChangesDuringBindingCannotPass() throws Exception {
        Clock clock=new Clock(); final Access access=fixture(clock,"deployed","file");
        CollectionAccess changing=new CollectionAccess() {
            int opens;
            public Stat lstat(String path)throws IOException{return access.lstat(path);}
            public String readLink(String path)throws IOException{return access.readLink(path);}
            public Listing list(String p,Stat s,int n,long b,long t)throws IOException{return access.list(p,s,n,b,t);}
            public Handle openRegular(String path,Stat s)throws IOException{
                if (path.equals(JAR)&&++opens==4) access.nodes.get(JAR).bytes[100]^=1;
                return access.openRegular(path,s);
            }
        };
        assertEquals("UNSTABLE",semantic(collect(changing,clock,ROOT,200000)).getString("state"));
    }
    @Test public void callerBudgetAndScopeRemainBinding() throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,"deployed","file");
        assertEquals("NOT_CHECKED",semantic(collect(access,clock,ROOT,60000)).getString("state"));
        access=fixture(clock,"deployed","file");
        assertEquals("SWITCH_MARKER_OUTSIDE_SCOPE",semantic(collect(access,clock,SCRIPT,200000)).getString("reason"));
    }
    @Test public void fifthObservedItemLeavesFourGapsAndDetectsActualDifference() throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,"deployed","file").add("/data/local","directory","");
        for(String path:new String[]{ConfigurationSwitches.SUPPORT,ConfigurationSwitches.RECOVERY,ConfigurationSwitches.RESCUE})
            access.nodes.putAll(ConfigurationSwitchesTest.fixture(clock,path,ConfigurationSwitchesTest.script(path),"file").nodes);
        JSONObject observation=collect(access,clock,"/data/local",300000);
        JSONObject firmware=new JSONObject(observation.toString()).put("role","FIRMWARE");
        semantic(firmware).put("value",false);
        JSONObject report=new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observation),DiagnosticManifest.parse(firmware),11000);
        JSONObject coverage=report.getJSONObject("configurationCoverage");
        assertEquals(5,coverage.getInt("bothObservedItems")); assertEquals(4,coverage.getInt("gapItems"));
        assertEquals("DIFFERENT",coverage.getJSONArray("items").getJSONObject(3).getString("pair"));
        assertEquals("NOT_ASSESSED",report.getString("systemConsistency")); assertFalse(report.getBoolean("repairPlanGenerated"));
        field(firmware,SCRIPT,FIELD).put("value","false");
        report=new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observation),DiagnosticManifest.parse(firmware),11000);
        assertEquals(5,report.getJSONObject("configurationCoverage").getInt("gapItems"));
    }
    public static void main(String[] args)throws Exception {
        Clock clock=new Clock(); Access access=fixture(clock,args[0],args[1]);
        if(args.length==3&&args[2].equals("unknown"))access.nodes.get(JAR).bytes[100]^=1;
        System.out.println(collect(access,clock,ROOT,200000));
    }
}
