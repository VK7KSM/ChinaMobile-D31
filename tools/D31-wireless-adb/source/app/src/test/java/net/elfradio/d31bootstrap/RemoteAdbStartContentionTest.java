package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteAdbStartContentionTest {
    private static final String SESSION="offline-start-fixture";
    interface Response { JSONObject reply(Fixture f)throws Exception; }
    static final class Step {
        final String method;final Response response;
        Step(String method,Response response){this.method=method;this.response=response;}
    }
    static class Fixture implements RemoteAdbMaintenance.Access {
        final String id;
        Fixture()throws Exception{id=RemoteProtocol.hash("adb-start:"+SESSION);}
        final Deque<Step> steps=new ArrayDeque<>();
        final List<String> calls=new ArrayList<>(),events=new ArrayList<>();
        long now,releaseAt=-1;
        boolean listening,occupied,dynamicBusy,accepted,interrupt;
        int posts,acceptedPosts,queries,occupiedChecks;
        public int port(){return 5654;}
        public boolean listening(int port){assertEquals(5654,port);return listening;}
        public boolean occupied(int port){assertEquals(5654,port);occupiedChecks++;return occupied;}
        public long now(){return now;}
        public void pause(long milliseconds)throws InterruptedException {
            assertTrue(milliseconds>0&&milliseconds<=RemoteAdbMaintenance.POLL_MS);
            if(interrupt)throw new InterruptedException("合成取消");
            now+=milliseconds;
        }
        public void event(String stage,int status){events.add(stage+" http_status="+status);}
        public JSONObject local(String path,JSONObject body)throws Exception {
            assertTrue("期限后仍发请求",now<RemoteAdbMaintenance.START_WAIT_MS);
            String method=body==null?"Q":"P";calls.add(method+"@"+now);
            if(body==null){queries++;assertEquals("/jobs/"+id,path);}
            else {
                posts++;assertEquals("/exec",path);assertEquals(id,body.getString("id"));
                assertEquals(RemoteAdbMaintenance.START_IF_STOPPED,body.getString("command"));
                assertEquals(10,body.getInt("timeout"));assertEquals(3,body.length());
                assertFalse(body.getString("command").contains("stop adbd"));
            }
            if(dynamicBusy){
                if(body==null)return accepted?state("completed",0):null;
                if(releaseAt<0||now<releaseAt)throw new RemoteHttp.Rejected(409,"不得泄漏的合成响应正文");
                assertFalse("启动动作重复执行",accepted);accepted=true;acceptedPosts++;listening=true;
                return state("completed",0);
            }
            assertFalse("出现未登记请求："+method,steps.isEmpty());
            Step step=steps.remove();assertEquals(step.method,method);return step.response.reply(this);
        }
        JSONObject state(String name,int code)throws Exception {
            JSONObject data=new JSONObject().put("id",id).put("state",name);
            if(!"running".equals(name))data.put("exit_code",code);
            return data;
        }
        Fixture q(Response response){steps.add(new Step("Q",response));return this;}
        Fixture p(Response response){steps.add(new Step("P",response));return this;}
        int run()throws Exception{return RemoteAdbMaintenance.ensureListening(SESSION,this);}
    }
    private static Response missing(){return f->null;}
    private static Response running(){return f->f.state("running",0);}
    private static Response completed(){return f->{f.listening=true;return f.state("completed",0);};}
    private static Response rejected(int code){return f->{throw new RemoteHttp.Rejected(code,"不得泄漏的合成响应正文");};}
    private static RemoteAdbMaintenance.StartFailure failed(Fixture f,String stage,int status)throws Exception {
        try{f.run();fail("应失败关闭");return null;}
        catch(RemoteAdbMaintenance.StartFailure failure){
            assertEquals(stage,failure.stage);assertEquals(status,failure.httpStatus);
            assertTrue(f.events.toString(),f.events.contains(stage+" http_status="+status));
            assertFalse(failure.getMessage().contains("不得泄漏"));
            if(status!=0)assertTrue(failure.getMessage().contains("HTTP "+status));
            return failure;
        }
    }
    @Test public void existingListenerSkipsAllCommandsAndPreservesPort()throws Exception {
        Fixture f=new Fixture();f.listening=true;assertEquals(5654,f.run());
        assertTrue(f.calls.isEmpty());assertEquals(1,f.occupiedChecks);
    }
    @Test public void occupiedExistingListenerIsNeverKicked()throws Exception {
        Fixture f=new Fixture();f.listening=true;f.occupied=true;
        failed(f,"ADB_START_CLIENT_OCCUPIED",0);assertTrue(f.calls.isEmpty());
    }
    @Test public void eightSecondBusyReleaseStartsOnceWithSameId()throws Exception {
        Fixture f=new Fixture();f.dynamicBusy=true;f.releaseAt=8000;
        assertEquals(5654,f.run());assertEquals(8000,f.now);assertEquals(33,f.posts);
        assertEquals(1,f.acceptedPosts);assertEquals(33,f.queries);
        for(int i=0;i<f.calls.size()-1;i++)if(f.calls.get(i).startsWith("P@")&&i<f.calls.size()-1)
            assertTrue("409后必须先查原号",f.calls.get(i+1).startsWith("Q@"));
        assertTrue(f.events.contains("ADB_START_SUBMIT_REJECTED http_status=409"));
        assertFalse(f.events.toString().contains(SESSION));assertFalse(f.events.toString().contains(f.id));
    }
    @Test public void perpetualBusyFailsAtFifteenSecondsWithoutTakingOver()throws Exception {
        Fixture f=new Fixture();f.dynamicBusy=true;
        failed(f,"ADB_START_TIMEOUT",409);assertEquals(15000,f.now);
        assertEquals(60,f.posts);assertEquals(61,f.queries);assertEquals(0,f.acceptedPosts);
        assertEquals(0,f.occupiedChecks);
    }
    @Test public void conflictWithExistingRunningReceiptOnlyPolls()throws Exception {
        Fixture f=new Fixture().q(missing()).p(rejected(409)).q(running()).q(running()).q(completed());
        assertEquals(5654,f.run());assertEquals(1,f.posts);assertTrue(f.steps.isEmpty());
    }
    @Test public void lostPostResponseQueriesSubmittedJobWithoutSecondPost()throws Exception {
        Fixture f=new Fixture().q(missing()).p(x->{x.acceptedPosts++;throw new IOException("合成回执丢失");})
                .q(running()).q(completed());
        assertEquals(5654,f.run());assertEquals(1,f.posts);assertEquals(1,f.acceptedPosts);
        assertTrue(f.events.contains("ADB_START_SUBMIT_UNCERTAIN http_status=0"));
    }
    @Test public void lostPostResponseWithMissingReceiptNeverReposts()throws Exception {
        Fixture f=new Fixture().q(missing()).p(x->{throw new IOException("合成回执丢失");}).q(missing());
        failed(f,"ADB_START_RECEIPT_MISSING",404);assertEquals(1,f.posts);
    }
    @Test public void alreadyRunningAtFirstQueryIsNeverSubmittedAgain()throws Exception {
        Fixture f=new Fixture().q(running()).q(running()).q(completed());
        assertEquals(5654,f.run());assertEquals(0,f.posts);
    }
    @Test public void runningReceiptThen404FailsWithoutReposting()throws Exception {
        Fixture f=new Fixture().q(missing()).p(running()).q(missing());
        failed(f,"ADB_START_RECEIPT_MISSING",404);assertEquals(1,f.posts);
    }
    @Test public void non409RejectionIsImmediateAndRetainsStatus()throws Exception {
        for(int status:new int[]{400,401,403,429,500,503}){
            Fixture f=new Fixture().q(missing()).p(rejected(status));
            RemoteAdbMaintenance.StartFailure failure=failed(f,"ADB_START_REJECTED",status);
            assertTrue(failure.getCause() instanceof RemoteHttp.Rejected);
            assertEquals(1,f.posts);assertEquals(1,f.queries);assertEquals(0,f.now);
        }
    }
    @Test public void conflictQueryRejectionNeverAuthorizesResubmit()throws Exception {
        for(int status:new int[]{409,403,500}){
            Fixture f=new Fixture().q(missing()).p(rejected(409)).q(rejected(status));
            failed(f,"ADB_START_QUERY_REJECTED",status);assertEquals(1,f.posts);
        }
    }
    @Test public void conflictQueryTransportFailureNeverAuthorizesResubmit()throws Exception {
        Fixture f=new Fixture().q(missing()).p(rejected(409)).q(x->{throw new IOException("合成查询失败");});
        failed(f,"ADB_START_QUERY_FAILED",0);assertEquals(1,f.posts);
    }
    @Test public void slow404AtDeadlineCannotAuthorizeAnotherPost()throws Exception {
        Fixture f=new Fixture().q(missing()).p(rejected(409)).q(x->{x.now=15000;return null;});
        failed(f,"ADB_START_TIMEOUT",409);assertEquals(1,f.posts);assertEquals(2,f.queries);
    }
    @Test public void initialQueryConsumesTheSameDeadlineBudget()throws Exception {
        Fixture f=new Fixture().q(x->{x.now=15000;return null;});
        failed(f,"ADB_START_TIMEOUT",0);assertEquals(0,f.posts);
    }
    @Test public void busyNearDeadlineCannotBeExtendedByRetryWait()throws Exception {
        Fixture f=new Fixture().q(missing()).p(rejected(409)).q(x->{x.now=14999;return null;});
        failed(f,"ADB_START_TIMEOUT",409);assertEquals(15000,f.now);assertEquals(1,f.posts);
    }
    @Test public void completionDoesNotClaimReadyUntilListenerAppears()throws Exception {
        Fixture f=new Fixture(){
            public boolean listening(int port){return now>=1000;}
        };
        f.q(x->x.state("completed",0));assertEquals(5654,f.run());assertEquals(1000,f.now);assertEquals(0,f.posts);
    }
    @Test public void successfulReceiptWithoutListenerFailsWithinBudget()throws Exception {
        Fixture f=new Fixture().q(x->x.state("completed",0));
        failed(f,"ADB_START_LISTENER_TIMEOUT",0);assertEquals(15000,f.now);assertEquals(0,f.posts);
    }
    @Test public void newlyStartedListenerStillChecksForAnotherClient()throws Exception {
        Fixture f=new Fixture().q(missing()).p(completed());f.occupied=true;
        failed(f,"ADB_START_CLIENT_OCCUPIED",0);assertEquals(1,f.posts);assertEquals(1,f.occupiedChecks);
    }
    @Test public void failedOrMalformedReceiptDoesNotRetryOrClaimReady()throws Exception {
        for(Response response:new Response[]{x->x.state("completed",1),x->x.state("failed",0),
                x->x.state("interrupted",0),x->x.state("timed_out",0)}){
            Fixture f=new Fixture().q(response);failed(f,"ADB_START_COMMAND_FAILED",200);assertEquals(0,f.posts);
        }
        Fixture wrong=new Fixture().q(x->x.state("completed",0).put("id","wrong"));
        failed(wrong,"ADB_START_RECEIPT_INVALID",200);assertEquals(0,wrong.posts);
    }
    @Test public void interruptStopsContentionWithoutFurtherHttp()throws Exception {
        Fixture f=new Fixture().q(missing()).p(rejected(409)).q(missing());f.interrupt=true;
        try{f.run();fail();}catch(InterruptedException expected){assertEquals("合成取消",expected.getMessage());}
        assertEquals(1,f.posts);assertEquals(2,f.queries);
    }
    @Test public void completedZeroWithTruncatedOrTimedOutFlagIsNotSuccess()throws Exception {
        for(String flag:new String[]{"truncated","timed_out"}){
            Fixture f=new Fixture().q(missing()).p(x->{x.listening=true;return x.state("completed",0).put(flag,true);});
            failed(f,"ADB_START_COMMAND_FAILED",200);assertEquals(1,f.posts);
            assertEquals(0,f.occupiedChecks);assertFalse(f.events.contains("ADB_START_LISTENER_READY http_status=0"));
        }
        Fixture good=new Fixture().q(missing()).p(x->{x.listening=true;return x.state("completed",0).put("truncated",false).put("timed_out",false);});
        assertEquals(5654,good.run());
    }
}
