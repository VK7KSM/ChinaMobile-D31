package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmWaveformTest {
    @Test public void fourAudibleSegmentsHaveDistinctFrequenciesAndQuietBoundaries(){
        short[] samples=AlarmWaveform.samples();assertEquals(22400,samples.length);
        for(int segment=0;segment<4;segment++){
            int start=segment*5600,crossings=0;long energy=0;
            assertEquals(0,samples[start]);assertEquals(0,samples[start+5599]);
            for(int i=start+1;i<start+5600;i++){
                if(samples[i-1]<0&&samples[i]>=0)crossings++;
                energy+=(long)samples[i]*samples[i];assertTrue(Math.abs((int)samples[i])<=22000);
            }
            assertEquals(segment%2==0?308:462,crossings,2);
            assertTrue(Math.sqrt(energy/5600.0)>14000);
        }
    }
}
