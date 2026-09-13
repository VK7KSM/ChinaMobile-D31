package dev.octoshrimpy.quik.feature.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.octoshrimpy.quik.model.Message
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.RealmResults

class D31UnreadProvider : ContentProvider() {
    companion object {
        @Volatile private var ready = false
        private var observedRealm: Realm? = null
        private var observedMessages: RealmResults<Message>? = null

        @JvmStatic fun countUri(context: Context): Uri =
            Uri.parse("content://${context.packageName}.unread/count")

        @JvmStatic fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(countUri(context), null)
        }

        private fun unread(realm: Realm): RealmQuery<Message> = realm.where(Message::class.java)
            .equalTo("read", false)
            .`in`("type", arrayOf(Message.TYPE_SMS, Message.TYPE_MMS))
            .`in`("boxId", arrayOf<Int>(0, 1))

        // 应用初始化 Realm 后，在主线程长期观察；接收、阅读、删除、同步均经过真实消息库。
        fun observeSim(context: Context) {
            if (observedRealm != null) return
            val realm = Realm.getDefaultInstance()
            val messages = unread(realm).findAll().also { messages ->
                messages.addChangeListener { _: RealmResults<Message> -> notifyChanged(context) }
            }
            observedRealm = realm
            observedMessages = messages
            ready = true
            notifyChanged(context)
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val app = requireNotNull(context)
        require(uri == countUri(app)) { "不支持的查询地址" }
        require(selection == null && selectionArgs == null && sortOrder == null) { "只支持未读计数查询" }
        val columns = projection ?: arrayOf("sim", "sip", "total")
        require(columns.all { it in setOf("sim", "sip", "total") }) { "不支持的列" }
        // Provider可先于Application发布；未就绪返回未知，不伪造零计数或另行初始化Realm。
        if (!ready) return null
        val sim = Realm.getDefaultInstance().use { realm -> realm.refresh(); unread(realm).count() }
        val sip = SipMessageStore(app).use { it.unreadCount() }
        val values = mapOf("sim" to sim, "sip" to sip, "total" to sim + sip)
        return MatrixCursor(columns).apply {
            addRow(columns.map { values.getValue(it) })
            setNotificationUri(app.contentResolver, countUri(app))
        }
    }

    override fun getType(uri: Uri): String {
        require(uri == countUri(requireNotNull(context))) { "不支持的查询地址" }
        return "vnd.android.cursor.item/vnd.net.elfradio.d31.unread"
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("只读接口")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读接口")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读接口")
}
