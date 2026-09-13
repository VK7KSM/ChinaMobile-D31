package dev.octoshrimpy.quik.feature.phone

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.feature.compose.ComposeActivity
import dev.octoshrimpy.quik.util.Preferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class D31NotificationsTest {
    private class ToneProvider(private val file: java.io.File) : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun openAssetFile(uri: Uri, mode: String) = android.content.res.AssetFileDescriptor(
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?) = null
        override fun getType(uri: Uri) = "audio/ogg"
        override fun insert(uri: Uri, values: android.content.ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    private lateinit var app: Application
    private lateinit var prefs: Preferences
    private lateinit var notifications: D31MessageNotifications
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val shared = app.getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs = Preferences(app, RxSharedPreferences.create(shared), shared)
        notifications = D31MessageNotifications(app, prefs)
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val tone = java.io.File(app.cacheDir, "test-tone.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val toneProvider = ToneProvider(tone)
        toneProvider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "d31-test-tones" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("d31-test-tones", toneProvider)
        prefs.ringtone().set("content://d31-test-tones/tone")
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        SipMessageStore.setActivePeer(null)
        shadowOf(app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .setCallState(TelephonyManager.CALL_STATE_IDLE)
    }

    @Test fun migrationKeepsEveryOldMessageAndMarksItRead() {
        val db = app.openOrCreateDatabase("d31_sip_messages.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, body TEXT NOT NULL, direction INTEGER NOT NULL, state INTEGER NOT NULL, timestamp INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO messages VALUES(41,'old','saved',0,0,123,'retained')")
        db.execSQL("INSERT INTO messages VALUES(42,'old','outgoing',1,3,124,'TLS error')")
        db.version = 2
        db.close()
        SipMessageStore(app).use { store ->
            assertEquals(0L, store.unreadCount())
            assertEquals(3, store.readableDatabase.version)
            val old = store.messages("old")
            assertEquals(2, old.size)
            assertTrue(old.all { it.read })
            assertEquals("retained", old[0].detail)
            assertEquals("TLS error", old[1].detail)
            assertEquals(41L, old[0].id)
            assertTrue(store.add("new", "incoming", 0, 0) > 42)
            assertEquals(1L, store.unreadCount())
        }
    }

    @Test fun readStatePersistsAndOnlySelectedConversationClears() {
        SipMessageStore(app).use { store ->
            store.add("a", "one", 0, 0)
            store.add("a", "two", 0, 0)
            store.add("b", "three", 0, 0)
            store.add("a", "sent", 1, 2)
            assertEquals(3L, store.unreadCount())
            store.markRead("a")
        }
        SipMessageStore(app).use { store ->
            assertEquals(1L, store.unreadCount())
            assertTrue(store.messages("a").all { it.read })
            assertFalse(store.messages("b")[0].read)
            assertEquals(1, store.conversations().first { it.peer == "b" }.unread)
        }
    }

    @Test fun readingOnlySuppressesSameSipPeer() {
        SipMessageStore.setActivePeer("a")
        SipMessageStore(app).use { store ->
            store.add("a", "visible", 0, 0)
            store.add("b", "background", 0, 0)
            notifications.postSip(store, "a")
            notifications.postSip(store, "b")
            assertEquals(1L, store.unreadCount())
            assertNull(shadowOf(manager).getNotification("sip:a", 0))
            assertNotNull(shadowOf(manager).getNotification("sip:b", 0))
        }
    }

    @Test fun newMessagesAlertAgainButRefreshAndDuplicateDoNot() {
        SipMessageStore(app).use { store ->
            store.add("a", "one", 0, 0)
            notifications.postSip(store, "a")
            var notification = shadowOf(manager).getNotification("sip:a", 0)
            assertTrue(notification.extras.getBoolean("d31.message"))
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            assertTrue("sound=${notifications.sound(prefs.ringtone().get())},call=${notifications.inCall()},ringer=${audio.ringerMode},volume=${audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)},mute=${audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)},dnd=${manager.currentInterruptionFilter},quiet=${prefs.silentNotContact.get()},channel=${manager.getNotificationChannel(D31MessageNotifications.MESSAGE_CHANNEL).sound}", notification.extras.getBoolean("d31.alert"))
            assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
            assertNull(notification.fullScreenIntent)
            notifications.postSip(store, "a")
            assertFalse(shadowOf(manager).getNotification("sip:a", 0).extras.getBoolean("d31.alert"))
            store.add("a", "two", 0, 0)
            notifications.postSip(store, "a")
            notification = shadowOf(manager).getNotification("sip:a", 0)
            assertTrue(notification.extras.getBoolean("d31.alert"))
            assertEquals(2, notification.number)
            assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertEquals(1, shadowOf(manager).size())
            notifications.postSip(store, "a", false)
            assertFalse(shadowOf(manager).getNotification("sip:a", 0).extras.getBoolean("d31.alert"))
        }
    }

    @Test fun cancellingReadSipLeavesOtherPeerAndService() {
        manager.notify(-3108, NotificationCompat.Builder(app, "service").setSmallIcon(1).build())
        SipMessageStore(app).use { store ->
            listOf("a", "b").forEach { store.add(it, "body", 0, 0); notifications.postSip(store, it) }
            store.markRead("a")
            assertNull(shadowOf(manager).getNotification("sip:a", 0))
            assertNotNull(shadowOf(manager).getNotification("sip:b", 0))
            assertNotNull(shadowOf(manager).getNotification(-3108))
        }
    }

    @Test fun pendingIntentsKeepTransportPeerAndMessageIdentity() {
        val sip = D31MessageNotifications.conversationIntent(app, "sip", "a/b@local", 10)
        val sim = D31MessageNotifications.conversationIntent(app, "sim", "10", 10)
        assertEquals("a/b@local", sip.getStringExtra("d31_peer"))
        assertEquals("sip", sip.getStringExtra("d31_transport"))
        assertEquals(10L, sim.getLongExtra("threadId", 0))
        assertEquals(ComposeActivity::class.java.name, sip.component!!.className)
        val first = PendingIntent.getActivity(app, 0, sip, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val second = PendingIntent.getActivity(app, 0,
            D31MessageNotifications.conversationIntent(app, "sip", "b", 10), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        assertNotEquals(first, second)
        assertEquals("a/b@local", shadowOf(first).savedIntent.getStringExtra("d31_peer"))
        assertNotEquals(sip.data, D31MessageNotifications.conversationIntent(app, "sip", "a/b@local", 11).data)
    }

    @Test fun historySurvivesManagerRecreationAndNeverAlertsSilentRefresh() {
        assertTrue(notifications.isNew("sim:1", 10, true))
        notifications.posted("sim:1", 10)
        val restarted = D31MessageNotifications(app, prefs)
        assertFalse(restarted.isNew("sim:1", 10, true))
        assertFalse(restarted.isNew("sim:1", 9, true))
        assertFalse(restarted.isNew("sim:1", 11, false))
        assertTrue(restarted.isNew("sim:2", 10, true))
    }

    @Test fun telephonyAndVoipCallsSilenceIncoming() {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val phone = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        for (state in listOf(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK)) {
            shadowOf(phone).setCallState(state)
            assertTrue(notifications.inCall())
            val builder = NotificationCompat.Builder(app, "test").setSmallIcon(1).setSound(Uri.parse("content://test/sound"))
            notifications.apply(builder, true)
            assertNull(builder.build().sound)
            assertFalse(builder.build().extras.getBoolean("d31.alert"))
        }
        shadowOf(phone).setCallState(TelephonyManager.CALL_STATE_IDLE)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertTrue(notifications.inCall())
        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(notifications.inCall())
    }

    @Test fun explicitSilenceAndMutedChannelsAreNotRepaired() {
        assertNull(notifications.sound(""))
        Settings.System.putString(app.contentResolver, Settings.System.NOTIFICATION_SOUND, null)
        assertNull(notifications.sound(Settings.System.DEFAULT_NOTIFICATION_URI.toString()))
        manager.createNotificationChannel(NotificationChannel("muted", "静音", NotificationManager.IMPORTANCE_NONE).apply { setSound(null, null) })
        assertEquals("muted", notifications.channel("muted"))
        assertEquals(1, manager.notificationChannels.size)
    }

    @Test fun quietAndEmptyRingtoneSuppressDesktopAlertToo() {
        val builder = NotificationCompat.Builder(app, "test").setSmallIcon(1)
        assertFalse(notifications.apply(builder, true, quiet = true))
        assertFalse(builder.build().extras.getBoolean("d31.alert"))
        prefs.ringtone().set("")
        val silent = NotificationCompat.Builder(app, "test").setSmallIcon(1)
        assertFalse(notifications.apply(silent, true))
        assertFalse(silent.build().extras.getBoolean("d31.alert"))
    }

    @Test fun disabledWakeAndRefreshDoNotAcquireWakeLock() {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(false)
        notifications.wake(true, "test")
        assertNull(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock())
        prefs.wakeScreen().set(true)
        notifications.wake(false, "test")
        assertNull(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun screenOffAndKeyguardNeverConsumeUnreadDespiteActiveConversation() {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val sim = dev.octoshrimpy.quik.manager.ActiveConversationManagerImpl(app)
        sim.setActiveConversation(42)
        SipMessageStore.setActivePeer("a")
        shadowOf(power).setIsInteractive(false)
        SipMessageStore(app).use { store ->
            store.add("a", "screen off", 0, 0)
            assertNull(sim.getActiveConversation())
            assertFalse(SipMessageStore.isReading(app, "a"))
            shadowOf(power).setIsInteractive(true)
            shadowOf(keyguard).setKeyguardLocked(true)
            store.add("a", "locked", 0, 0)
            assertNull(sim.getActiveConversation())
            assertEquals(2L, store.unreadCount())
            shadowOf(keyguard).setKeyguardLocked(false)
            assertEquals(42L, sim.getActiveConversation())
            assertTrue(SipMessageStore.isReading(app, "a"))
            SipMessageStore.clearActivePeer("a")
            assertFalse(SipMessageStore.isReading(app, "a"))
        }
    }

    @Test fun sipChangesInvalidateCountCursorForIncomingAndRead() {
        var changes = 0
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { changes++ }
        }
        val uri = D31UnreadProvider.countUri(app)
        app.contentResolver.registerContentObserver(uri, false, observer)
        SipMessageStore(app).use { store ->
            store.add("a", "unread", 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            val afterInsert = changes
            assertTrue(afterInsert > 0)
            store.markRead("a")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(changes > afterInsert)
        }
        app.contentResolver.unregisterContentObserver(observer)
    }
}
