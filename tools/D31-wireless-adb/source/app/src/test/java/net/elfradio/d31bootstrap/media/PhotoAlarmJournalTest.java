package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PhotoAlarmJournalTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject terminal(String state,String error)throws Exception {
        return new JSONObject().put("session_id","session-one").put("mode","photo").put("state",state)
                .put("closed",true).put("cleanup_complete",true).put("error",error);
    }
    @Test public void successfulEmptyErrorSurvivesServiceRecreationAndSameIdCannotRestart()throws Exception {
        File directory=temp.newFolder();PhotoAlarmJournal before=new PhotoAlarmJournal(directory);
        PhotoAlarmOffer offer=PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo"));
        assertNull(before.begin(offer));
        JSONObject completed=terminal("completed","").put("result",new JSONObject().put("type","result")
                .put("report_id",offer.id).put("camera","back").put("captured_at",100001));
        before.complete(completed);
        PhotoAlarmJournal rebuilt=new PhotoAlarmJournal(directory);
        assertEquals(completed.toString(),rebuilt.read(offer.id).toString());
        assertEquals(completed.toString(),rebuilt.begin(offer).toString());
        assertEquals("",PhotoAlarmContract.reply(rebuilt.read(offer.id)).getString("error"));
        assertEquals("back",rebuilt.read(offer.id).getJSONObject("result").getString("camera"));
    }
    @Test public void failureIsBusinessResultNotCommandFailureAfterRecreation()throws Exception {
        File directory=temp.newFolder();PhotoAlarmJournal before=new PhotoAlarmJournal(directory);
        before.begin(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")));
        JSONObject failed=terminal("failed","MEDIA_SILENT_SHUTTER_UNAVAILABLE");before.complete(failed);
        JSONObject read=PhotoAlarmContract.reply(new PhotoAlarmJournal(directory).read("session-one"));
        assertEquals("failed",read.getString("state"));assertEquals("MEDIA_SILENT_SHUTTER_UNAVAILABLE",read.getString("error"));
        assertTrue(read.getBoolean("cleanup_complete"));
    }
    @Test public void completionOriginalNeverOverwrittenByLaterStopOrFailure()throws Exception {
        File directory=temp.newFolder();PhotoAlarmJournal journal=new PhotoAlarmJournal(directory);
        journal.begin(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")));journal.complete(terminal("completed",""));
        File original=new File(directory,"session-one/completion.json");String hash=MediaFiles.hash(original);
        JSONObject saved=journal.complete(terminal("failed","LATER_FAILURE"));
        assertEquals("completed",saved.getString("state"));assertEquals(hash,MediaFiles.hash(original));
    }
    @Test public void processLostBeforeCompletionIsUnknownAndNeverReplayed()throws Exception {
        File directory=temp.newFolder();PhotoAlarmOffer offer=PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo"));
        new PhotoAlarmJournal(directory).begin(offer);
        PhotoAlarmJournal rebuilt=new PhotoAlarmJournal(directory);JSONObject prior=rebuilt.begin(offer);
        assertEquals("interrupted",prior.getString("state"));assertFalse(prior.getBoolean("cleanup_complete"));
        assertFalse(prior.getBoolean("replayed"));assertFalse(new File(directory,"session-one/completion.json").exists());
    }
    @Test public void pendingCleanupCannotCreateCompletion()throws Exception {
        File directory=temp.newFolder();PhotoAlarmJournal journal=new PhotoAlarmJournal(directory);
        journal.begin(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")));
        try{journal.complete(terminal("failed","VISUAL_CLEANUP_PENDING").put("cleanup_complete",false));fail();}
        catch(IOException expected){assertEquals("VISUAL_TERMINAL_CLEANUP_PENDING",expected.getMessage());}
        assertFalse(new File(directory,"session-one/completion.json").exists());
    }
    @Test public void originalSessionOnlyAndInvalidPathRejected()throws Exception {
        PhotoAlarmJournal journal=new PhotoAlarmJournal(temp.newFolder());
        journal.begin(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")));journal.complete(terminal("completed",""));
        assertNull(journal.read("other-session"));
        try{journal.read("../session-one");fail();}catch(IOException expected){}
    }
    @Test public void incompleteIntentDirectoryCannotRestartCapture()throws Exception {
        File directory=temp.newFolder();assertTrue(new File(directory,"session-one").mkdir());
        PhotoAlarmJournal journal=new PhotoAlarmJournal(directory);
        try{journal.begin(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")));fail();}
        catch(IOException expected){assertEquals("VISUAL_INTENT_INCOMPLETE_NO_REPLAY",expected.getMessage());}
    }
    @Test public void commandErrorsRemainExceptionsButSessionsAreIntact()throws Exception {
        for(JSONObject failure:new JSONObject[]{PhotoAlarmContract.error("VISUAL_APK_MISMATCH"),new JSONObject().put("error","VISUAL_OLD_COMMAND_ERROR")}) {
            try{PhotoAlarmContract.reply(failure);fail();}catch(IOException expected){assertTrue(expected.getMessage().startsWith("VISUAL_"));}
        }
        JSONObject active=new JSONObject().put("session_id","session-one").put("state","capturing").put("error","");
        assertSame(active,PhotoAlarmContract.reply(active));
        JSONObject businessFailure=terminal("failed","PHOTO_ACK_UNKNOWN_OR_MISMATCH");assertSame(businessFailure,PhotoAlarmContract.reply(businessFailure));
        try{PhotoAlarmContract.reply(null);fail();}catch(IOException expected){assertEquals("VISUAL_REPLY_UNKNOWN",expected.getMessage());}
    }
}
