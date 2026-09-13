package net.elfradio.d31system;

import org.junit.Test;
import static org.junit.Assert.*;

public class NoticePolicyTest {
    @Test public void backgroundServicesAndSummaryDoNotBecomeMessages() {
        assertFalse(NoticePolicy.message(NoticePolicy.SMS,true,false,true,"msg",true));
        assertFalse(NoticePolicy.message("org.telegram.messenger.web",false,true,false,"msg",true));
        assertFalse(NoticePolicy.message(NoticePolicy.SMS,false,false,false,null,true));
    }
    @Test public void onlySupportedMessageAppsAreAccepted() {
        assertTrue(NoticePolicy.message(NoticePolicy.SMS,false,false,true,"msg",true));
        assertTrue(NoticePolicy.message("org.telegram.messenger.web",false,false,false,"msg",true));
        assertFalse(NoticePolicy.message("com.loudtalks",false,false,false,"msg",true));
        assertFalse(NoticePolicy.message("org.telegram.messenger",false,false,false,"call",true));
    }
    @Test public void callsSilentModeAndDndSuppressBanners() {
        assertTrue(NoticePolicy.canShowBanner(true,false,false,false));
        assertFalse(NoticePolicy.canShowBanner(true,true,false,false));
        assertFalse(NoticePolicy.canShowBanner(true,false,true,false));
        assertFalse(NoticePolicy.canShowBanner(true,false,false,true));
        assertFalse(NoticePolicy.canShowBanner(false,false,false,false));
    }
    @Test public void notificationTextIsBounded() {
        assertEquals("",NoticePolicy.bounded(null,5));
        assertEquals("a b",NoticePolicy.bounded("a\nb",5));
        assertEquals("abc…",NoticePolicy.bounded("abcdef",3));
    }
}
