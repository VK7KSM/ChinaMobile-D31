package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutomaticPhotoNetworkTest {
    @Test public void wifiAndEthernetAreEligibleButCellularAlwaysPauses() {
        assertTrue(AutomaticPhotoNetwork.allowsTransports(true,false,false));
        assertTrue(AutomaticPhotoNetwork.allowsTransports(false,true,false));
        assertTrue(AutomaticPhotoNetwork.allowsTransports(true,true,false));
        assertFalse(AutomaticPhotoNetwork.allowsTransports(false,false,false));
        for(int mask=0;mask<4;mask++)
            assertFalse(AutomaticPhotoNetwork.allowsTransports((mask&1)!=0,(mask&2)!=0,true));
    }
}
