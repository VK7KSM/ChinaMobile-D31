package net.elfradio.d31bootstrap.media;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppMediaMuteTest {
    @Test public void repeatedFramesOnlyCallSetterAtTransitions(){
        List<Boolean> calls=new ArrayList<>();AppMediaMute mute=new AppMediaMute();
        for(int i=0;i<1000;i++)mute.apply(true,calls::add);
        for(int i=0;i<1000;i++)mute.apply(false,calls::add);
        mute.close(calls::add);mute.close(calls::add);
        assertEquals(Arrays.asList(true,false,true),calls);
    }
    @Test public void delayedCallbackCannotUnmuteAfterClose(){
        List<Boolean> calls=new ArrayList<>();AppMediaMute mute=new AppMediaMute();
        mute.apply(true,calls::add);mute.close(calls::add);mute.apply(false,calls::add);
        assertEquals(Collections.singletonList(true),calls);
    }
    @Test public void failedSetterIsNotCachedAsApplied(){
        AppMediaMute mute=new AppMediaMute();try{mute.apply(true,value->{throw new IllegalStateException();});fail();}catch(IllegalStateException expected){}
        List<Boolean> calls=new ArrayList<>();mute.apply(true,calls::add);assertEquals(Collections.singletonList(true),calls);
    }
}
