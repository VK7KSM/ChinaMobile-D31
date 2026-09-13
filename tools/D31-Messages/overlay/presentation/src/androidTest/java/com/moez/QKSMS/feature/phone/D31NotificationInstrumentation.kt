package dev.octoshrimpy.quik.feature.phone

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import dev.octoshrimpy.quik.common.QKApplication
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import io.realm.Realm
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅编入测试 APK；不调用任何短信或 SIP 发送接口。 */
class D31NotificationInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private val applicationReady = CountDownLatch(1)
    @Volatile private var applicationFailure: Throwable? = null

    override fun callApplicationOnCreate(app: Application) {
        try {
            super.callApplicationOnCreate(app)
        } catch (error: Throwable) {
            applicationFailure = error
            throw error
        } finally {
            applicationReady.countDown()
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            // start()可先于目标Application.onCreate返回；必须等框架完成DI和Realm初始化。
            check(applicationReady.await(30, TimeUnit.SECONDS)) { "等待目标应用初始化超时，未执行消息操作" }
            applicationFailure?.let { throw IllegalStateException("目标应用初始化失败，未执行消息操作", it) }
            val run = requireNotNull(args.getString("run")) { "必须提供 run，后续清理使用相同值" }
            require(run.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "run 只允许字母、数字、下划线及连字符" }
            val action = args.getString("action", "seed")
            require(action in listOf("seed", "refresh", "read", "cleanup")) { "不支持的动作" }
            val transport = args.getString("transport", "both")
            require(transport in listOf("sim", "sip", "both")) { "不支持的通道" }
            val app = targetContext.applicationContext as QKApplication
            val journal = app.getSharedPreferences("d31_local_notification_tests", Context.MODE_PRIVATE)
            val rows = JSONArray(journal.getString(run, "[]"))
            val repo = appComponent.messageRepository()
            val conversations = appComponent.conversationRepository()
            val notifier = appComponent.notificationManager()
            fun save() { check(journal.edit().putString(run, rows.toString()).commit()) { "测试清单写入失败" } }
            fun snapshot(): String = app.contentResolver.query(D31UnreadProvider.countUri(app), null, null, null, null)!!.use {
                check(it.moveToFirst())
                "sim=${it.getLong(0)},sip=${it.getLong(1)},total=${it.getLong(2)}"
            }
            result.putString("before", snapshot())
            val delay = args.getString("delaySeconds", "0").toLong()
            val hold = args.getString("holdSeconds", "0").toLong()
            require(delay in 0..60 && hold in 0..60) { "等待时间必须为0至60秒" }
            SystemClock.sleep(delay * 1000)

            if (action == "seed") {
                for (channel in listOf("sim", "sip").filter { transport == "both" || it == transport }) {
                    if (channel == "sim") check(Telephony.Sms.getDefaultSmsPackage(app) == app.packageName) {
                        "目标应用必须已经是默认短信应用；测试不会替换默认应用"
                    }
                    val peer = "D31LOCAL${run}_$channel"
                    val body = "D31本地通知验证:$run:${UUID.randomUUID()}"
                    // 在接触消息库前持久化精确收件人及正文，异常中断后仍能选择性清理。
                    val row = JSONObject().put("transport", channel).put("peer", peer).put("body", body)
                    rows.put(row)
                    save()
                    if (channel == "sim") {
                        val message = repo.insertReceivedSms(-1, peer, body, System.currentTimeMillis())
                        row.put("id", message.id).put("thread", message.threadId).put("contentId", message.contentId)
                        save()
                        conversations.getOrCreateConversation(message.threadId)
                        conversations.updateConversations(listOf(message.threadId))
                        notifier.notifyIncoming(message.threadId)
                    } else {
                        onMain { app.startService(Intent(app, SipService::class.java).setAction(SipService.ACTION_START)) }
                        var service: SipService? = null
                        for (attempt in 0 until 50) {
                            onMain {
                                val field = SipEngine::class.java.getDeclaredField("listeners").apply { isAccessible = true }
                                service = (field.get(SipEngine.get()) as Iterable<*>).filterIsInstance<SipService>().firstOrNull()
                            }
                            if (service != null) break
                            SystemClock.sleep(100)
                        }
                        check(service != null) { "未找到真实 SIP 服务，保留清单供清理" }
                        onMain { service!!.onMessageReceived(peer, body) }
                        SipMessageStore(app).use { store ->
                            row.put("id", store.messages(peer).single { it.body == body }.id)
                        }
                        save()
                    }
                }
            } else {
                check(rows.length() > 0) { "未找到该 run 的清单" }
                val selected = (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { transport == "both" || it.getString("transport") == transport }
                for (group in selected.groupBy { it.getString("transport") + ":" + it.getString("peer") }.values) {
                    val peer = group.first().getString("peer")
                    val bodies = group.map { it.getString("body") }.toSet()
                    if (group.first().getString("transport") == "sip") {
                        onMain {
                            SipMessageStore(app).use { store ->
                                val own = store.messages(peer).filter { it.body in bodies && it.direction == 0 }
                                when (action) {
                                    "refresh" -> app.d31Notifications.postSip(store, peer, false)
                                    "read" -> {
                                        check(store.messages(peer).all { it.body in bodies }) { "该会话含非测试消息，拒绝整会话标记" }
                                        store.markRead(peer)
                                    }
                                    "cleanup" -> {
                                        own.forEach { store.writableDatabase.delete("messages", "id=? AND peer=? AND body=? AND direction=0",
                                            arrayOf(it.id.toString(), peer, it.body)) }
                                        app.d31Notifications.postSip(store, peer, false)
                                        D31UnreadProvider.notifyChanged(app)
                                        app.sendBroadcast(Intent(SipService.ACTION_MESSAGES_CHANGED).setPackage(app.packageName))
                                    }
                                }
                            }
                        }
                    } else {
                        Realm.getDefaultInstance().use { realm ->
                            realm.refresh()
                            val own = realm.where(Message::class.java).equalTo("address", peer)
                                .equalTo("type", Message.TYPE_SMS).equalTo("boxId", Telephony.Sms.MESSAGE_TYPE_INBOX)
                                .findAll().filter { it.body in bodies }
                            val ids = own.map { it.id }
                            val threads = own.map { it.threadId }.distinct()
                            when (action) {
                                "refresh" -> threads.forEach(notifier::update)
                                "read" -> {
                                    check(threads.all { thread -> realm.where(Message::class.java).equalTo("threadId", thread)
                                        .findAll().all { it.id in ids } }) { "该会话含非测试消息，拒绝整会话标记" }
                                    repo.markRead(threads)
                                    threads.forEach(notifier::update)
                                }
                                "cleanup" -> {
                                    repo.deleteMessages(ids)
                                    // 覆盖系统库写入成功、Realm 写入前异常的窗口；仍匹配精确正文和地址。
                                    bodies.forEach { body -> app.contentResolver.delete(Telephony.Sms.CONTENT_URI,
                                        "address=? AND body=? AND type=1", arrayOf(peer, body)) }
                                    conversations.updateConversations(threads)
                                    threads.forEach(notifier::update)
                                    realm.refresh()
                                    realm.executeTransaction {
                                        threads.filter { thread -> realm.where(Message::class.java).equalTo("threadId", thread).count() == 0L }
                                            .forEach { thread -> realm.where(Conversation::class.java).equalTo("id", thread).findAll().deleteAllFromRealm() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            waitForIdleSync()
            SystemClock.sleep(hold * 1000)
            result.putString("after", snapshot())
            result.putString("records", rows.toString())
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val testRows = (0 until rows.length()).map { rows.getJSONObject(it) }
            result.putString("notifications", manager.activeNotifications.filter { notification ->
                testRows.any { row -> if (row.getString("transport") == "sip")
                    notification.tag == "sip:${row.getString("peer")}" else
                    notification.tag == null && notification.id == row.optLong("thread", -1).toInt() }
            }.joinToString("\n") {
                val channel = if (android.os.Build.VERSION.SDK_INT >= 26) it.notification.channelId else "API23-25"
                "id=${it.id},tag=${it.tag},message=${it.notification.extras.getBoolean("d31.message")},alert=${it.notification.extras.getBoolean("d31.alert")},channel=$channel"
            })
            result.putString("restore", "Instrumentation结束会重启目标进程，请重新打开短信应用并核对SIP连接；清理使用相同run和action=cleanup")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.toString())
            result.putString("stack", android.util.Log.getStackTraceString(error))
            result.putString("restore", "保留测试清单；使用相同run执行cleanup，随后重新打开短信应用")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
