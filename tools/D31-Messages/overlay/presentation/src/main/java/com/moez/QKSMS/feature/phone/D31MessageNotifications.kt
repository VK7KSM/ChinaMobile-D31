package dev.octoshrimpy.quik.feature.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.feature.compose.ComposeActivity
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class D31MessageNotifications @Inject constructor(private val context: Context, private val prefs: Preferences) {
    companion object {
        const val MESSAGE_CHANNEL = "notifications_default"
        private const val SIP_ID = 0

        @JvmStatic fun cancelSip(context: Context, peer: String) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel("sip:$peer", SIP_ID)
        }

        fun conversationIntent(context: Context, transport: String, conversation: String, messageId: Long): Intent =
            Intent(context, ComposeActivity::class.java)
                .setData(Uri.Builder().scheme("d31-message").authority(transport)
                    .appendPath(conversation).appendPath(messageId.toString()).build())
                .apply {
                    if (transport == "sip") putExtra("d31_transport", "sip").putExtra("d31_peer", conversation)
                    else putExtra("threadId", conversation.toLong())
                }
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val history = context.getSharedPreferences("d31_notification_history", Context.MODE_PRIVATE)

    fun isNew(key: String, messageId: Long, incoming: Boolean) = incoming && messageId > history.getLong(key, 0)

    fun posted(key: String, messageId: Long) {
        history.edit().putLong(key, maxOf(messageId, history.getLong(key, 0))).apply()
    }

    fun inCall(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.mode != AudioManager.MODE_NORMAL) return true
        return try {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) {
            // 无法确认电话状态时不冒险播放声音。
            true
        }
    }

    fun apply(builder: NotificationCompat.Builder, fresh: Boolean, threadId: Long = 0,
              quiet: Boolean = false, channelId: String = MESSAGE_CHANNEL): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(channelId) else null
        val allowed = fresh && !quiet && !inCall() && sound(prefs.ringtone(threadId).get()) != null &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0 &&
            !audio.isStreamMute(AudioManager.STREAM_NOTIFICATION) &&
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            (channel == null || (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null))
        builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!allowed)
            .addExtras(Bundle().apply {
                putBoolean("d31.message", true)
                putBoolean("d31.alert", allowed)
            })
        if (!allowed) builder.setSilent(true)
        return allowed
    }

    fun wake(fresh: Boolean, channelId: String, threadId: Long = 0) {
        if (!fresh || !prefs.wakeScreen(threadId).get() || inCall() ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) {
            @Suppress("DEPRECATION")
            power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "${context.packageName}:message").acquire(5000)
        }
    }

    private fun readable(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) { false }

    private fun actual(uri: Uri): Uri? = if (RingtoneManager.isDefault(uri))
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.getDefaultType(uri)) else uri

    fun sound(value: String): Uri? {
        if (value.isEmpty()) return null
        val requested = Uri.parse(value)
        val target = actual(requested) ?: return null // 系统默认“无”同样是用户静音选择。
        if (readable(target)) return requested
        val default = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION) ?: return null
        if (readable(default)) return default
        return try {
            val tones = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
            tones.cursor.use { cursor ->
                var fallback: Uri? = null
                while (cursor.moveToNext() && fallback == null) {
                    val uri = tones.getRingtoneUri(cursor.position)
                    if (uri != null && readable(uri)) fallback = uri
                }
                fallback
            }
        } catch (_: Exception) { null }
    }

    // 只修复不可读取的非空声音；不删除旧频道，不覆盖静音或重要性设置。
    fun channel(base: String): String {
        if (Build.VERSION.SDK_INT < 26) return base
        val old = manager.getNotificationChannel(base) ?: return base
        val uri = old.sound ?: return base
        val target = actual(uri) ?: return base
        if (readable(target)) return base
        val fallback = sound(uri.toString()) ?: return base
        val id = "${base}_d31_fallback"
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, old.name, old.importance).apply {
                description = old.description
                group = old.group
                lockscreenVisibility = old.lockscreenVisibility
                setShowBadge(old.canShowBadge())
                enableLights(old.shouldShowLights())
                lightColor = old.lightColor
                enableVibration(old.shouldVibrate())
                vibrationPattern = old.vibrationPattern
                setSound(fallback, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
        return id
    }

    @Synchronized fun postSip(store: SipMessageStore, peer: String, incoming: Boolean = true) {
        if (SipMessageStore.isReading(context, peer)) { store.markRead(peer); return }
        val messages = store.messages(peer).filter { it.direction == SipMessageStore.DIRECTION_IN && !it.read }
        if (messages.isEmpty()) { cancelSip(context, peer); return }
        if (!prefs.notifications().get() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            cancelSip(context, peer)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(MESSAGE_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "消息", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound(prefs.ringtone().get()), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                enableVibration(prefs.vibration().get())
            })
        }
        val latest = messages.maxByOrNull { it.id }!!
        val key = "sip:$peer"
        val fresh = isNew(key, latest.id, incoming)
        val channelId = channel(MESSAGE_CHANNEL)
        val intent = conversationIntent(context, "sip", peer, latest.id)
        val pending = PendingIntent.getActivity(context, SIP_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("SIP · $peer")
            .setContentText(latest.body).setNumber(messages.size).setWhen(latest.timestamp)
            .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(sound(prefs.ringtone().get()))
            .setVibrate(if (prefs.vibration().get()) longArrayOf(0, 200, 0, 200) else longArrayOf(0))
        when (prefs.notificationPreviews().get()) {
            Preferences.NOTIFICATION_PREVIEWS_ALL -> builder.setStyle(NotificationCompat.MessagingStyle("我").also { style ->
                messages.takeLast(25).forEach { style.addMessage(it.body, it.timestamp, peer) }
            })
            Preferences.NOTIFICATION_PREVIEWS_NAME -> builder.setContentText("${messages.size}条新消息")
            else -> builder.setContentTitle(context.getString(R.string.app_name)).setContentText("${messages.size}条新消息")
        }
        val alert = apply(builder, fresh, quiet = prefs.silentNotContact.get(), channelId = channelId)
        manager.notify(key, SIP_ID, builder.build())
        posted(key, latest.id)
        wake(alert, channelId)
    }
}
