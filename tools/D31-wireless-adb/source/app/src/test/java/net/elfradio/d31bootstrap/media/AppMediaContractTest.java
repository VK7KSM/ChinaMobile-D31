package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppMediaContractTest {
    static final String ID="11111111-1111-1111-1111-111111111111",BOOT="22222222-2222-2222-2222-222222222222";
    static final String HASH=new String(new char[64]).replace('\0','a');
    public interface ThreadStub{}
    public static final class IntentStub{}
    public static final class Ams {
        boolean called;
        public Object startService(ThreadStub thread,IntentStub intent,String type,String callingPackage,int user)throws IOException {
            assertNull(thread);assertNotNull(intent);assertNull(type);assertEquals("",callingPackage);assertEquals(0,user);called=true;return intent;
        }
    }
    @Test public void usesExactVerifiedAmsSignature()throws Exception{
        Ams manager=new Ams();IntentStub intent=new IntentStub();
        assertSame(intent,AppMediaContract.start(Ams.class,manager,ThreadStub.class,IntentStub.class,intent));assertTrue(manager.called);
    }
    @Test public void commandsRequireActualRootCaller()throws Exception{
        AppMediaContract.caller(0);
        for(final int uid:new int[]{1000,2000,10042,-1})MediaCaptureTest.rejects("ROOT_REQUIRED",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.caller(uid);}});
    }
    @Test public void queryEmptyIsAllowedButStopEmptyIsNot()throws Exception{
        assertEquals("query",AppMediaContract.command(new JSONObject().put("operation","query").put("session_id","").toString()).getString("operation"));
        MediaCaptureTest.rejects("SESSION_ID",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.command(new JSONObject().put("operation","stop").put("session_id","").toString());}});
    }
    @Test public void malformedAndOtherWebModesAreRejected()throws Exception{
        for(final String op:new String[]{"record_audio","play_alarm","wipe_data"})MediaCaptureTest.rejects("OPERATION",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.command(new JSONObject().put("operation",op).toString());}});
        MediaCaptureTest.rejects("APK_HASH",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.command("{\"operation\":\"prepare\"}");}});
        MediaCaptureTest.rejects("MODE",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.command(new JSONObject().put("operation","start").put("apk_sha256",HASH).put("offer",new JSONObject().put("mode","call")).toString());}});
    }
    @Test public void lateReplySpoofedUidAndNonceAreRejected()throws Exception{
        final String body=new JSONObject().put("request_id",ID).put("boot_id",BOOT).put("started_elapsed_ms",100).toString();
        AppMediaContract.reply(10042,10042,ID,BOOT,100,200,body);
        MediaCaptureTest.rejects("IDENTITY",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.reply(0,10042,ID,BOOT,100,200,body);}});
        MediaCaptureTest.rejects("EXPIRED",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.reply(10042,10042,ID,BOOT,100,6101,body);}});
        MediaCaptureTest.rejects("MISMATCH",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.reply(10042,10042,BOOT,BOOT,100,200,body);}});
    }
    @Test public void utf8ReplySizeAndTimestampTypeAreStrict()throws Exception{
        final String text=new JSONObject().put("request_id",ID).put("boot_id",BOOT).put("started_elapsed_ms","100").toString();
        MediaCaptureTest.rejects("MISMATCH",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.reply(10042,10042,ID,BOOT,100,200,text);}});
        final String large=new String(new char[6000]).replace('\0','中');
        MediaCaptureTest.rejects("SIZE",new MediaCaptureTest.Operation(){public void run()throws Exception{AppMediaContract.reply(10042,10042,ID,BOOT,100,200,large);}});
    }
}
