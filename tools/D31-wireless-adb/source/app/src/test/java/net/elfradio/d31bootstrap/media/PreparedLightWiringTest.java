package net.elfradio.d31bootstrap.media;

import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreparedLightWiringTest {
    @Test public void initialAdmissionWaitsForTheExistingFirstSample()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        AndroidAudioOccupancy occupancy=new AndroidAudioOccupancy(()->{entered.countDown();finish.await();return D31OutputFixtures.idle(0);},()->System.nanoTime()/1000000);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try{occupancy.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
            Future<?> ready=caller.submit(()->{PreparedSessionAndroid.awaitInitialIdle(occupancy);return null;});
            try{ready.get(30,TimeUnit.MILLISECONDS);fail();}catch(TimeoutException expected){}
            finish.countDown();ready.get(1,TimeUnit.SECONDS);
        }finally{finish.countDown();occupancy.close();caller.shutdownNow();}
    }
    @Test public void completedFirstSampleDoesNotOverrideBusyOrUnknown()throws Exception {
        for(boolean unknown:new boolean[]{false,true}){
            AndroidAudioOccupancy occupancy=new AndroidAudioOccupancy(()->{
                JSONObject raw=D31OutputFixtures.idle(0);
                if(unknown)raw.remove("cellular");else raw.getJSONObject("cellular").put("call_state",1);return raw;
            },()->System.nanoTime()/1000000);
            try{occupancy.start();try{PreparedSessionAndroid.awaitInitialIdle(occupancy);fail();}catch(java.io.IOException expected){}}
            finally{occupancy.close();}
        }
    }
}
