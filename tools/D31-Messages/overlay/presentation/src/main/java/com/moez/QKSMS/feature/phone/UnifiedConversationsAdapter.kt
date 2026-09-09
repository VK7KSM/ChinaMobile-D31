package dev.octoshrimpy.quik.feature.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.databinding.ConversationListItemBinding
import dev.octoshrimpy.quik.feature.compose.ComposeActivity
import dev.octoshrimpy.quik.feature.conversations.ConversationsAdapter
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.SearchResult
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class UnifiedConversationsAdapter(
    private val context: Context,
    private val sim: ConversationsAdapter,
    private val onEmpty: (Boolean) -> Unit
) : RecyclerView.Adapter<QkBindingViewHolder<ConversationListItemBinding>>() {
    private data class Row(val sim: Conversation? = null, val sip: SipMessageStore.Conversation? = null) {
        val timestamp get() = sim?.date ?: sip!!.timestamp
    }
    private val store = SipMessageStore(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var sipRows = emptyList<SipMessageStore.Conversation>()
    private var rows = emptyList<Row>()
    private var search: List<SearchResult>? = null
    private var query = ""
    private val sipIds = mutableMapOf<String, Long>()
    private val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = rebuild()
        override fun onItemRangeChanged(start: Int, count: Int) = rebuild()
        override fun onItemRangeInserted(start: Int, count: Int) = rebuild()
        override fun onItemRangeRemoved(start: Int, count: Int) = rebuild()
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = rebuild()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshSip()
    }
    init {
        setHasStableIds(true)
        sim.registerAdapterDataObserver(observer)
        context.registerReceiver(receiver, IntentFilter(SipService.ACTION_MESSAGES_CHANGED))
        refreshSip()
        SipService.ensureStarted(context)
    }
    fun showInbox() {
        search = null
        if (query.isNotEmpty()) { query = ""; sipRows = emptyList(); refreshSip() }
        rebuild()
    }
    fun showSearch(value: List<SearchResult>, text: String) {
        search = value
        if (query != text.trim()) { query = text.trim(); sipRows = emptyList(); refreshSip() }
        rebuild()
    }
    fun refreshSip() {
        if (closed) return
        val requestedQuery = query
        worker.execute {
            val values = store.conversations(requestedQuery)
            main.post { if (!closed && query == requestedQuery) { sipRows = values; rebuild() } }
        }
    }
    private fun rebuild() {
        if (closed) return
        val cellular = search?.map { it.conversation }?.distinctBy { it.id }
            ?: sim.data?.takeIf { it.isValid && it.isLoaded }?.toList().orEmpty()
        rows = (cellular.map { Row(sim = it) } + sipRows.map { Row(sip = it) }).sortedWith(compareByDescending<Row> { it.sim?.pinned == true }
            .thenByDescending { it.timestamp }.thenBy { it.sim?.id?.toString() ?: it.sip!!.peer })
        notifyDataSetChanged()
        onEmpty(rows.isEmpty())
    }
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int): Long = rows[position].let {
        it.sim?.id ?: sipIds.getOrPut(it.sip!!.peer) { -2L - sipIds.size }
    }
    override fun getItemViewType(position: Int) = rows[position].let { if (it.sip != null) 2 else if (it.sim!!.unread) 1 else 0 }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = sim.onCreateViewHolder(parent, type)
    override fun onBindViewHolder(holder: QkBindingViewHolder<ConversationListItemBinding>, position: Int) {
        val item = rows[position]
        item.sim?.let { conversation ->
            sim.bindConversation(holder, conversation)
            if (search != null) holder.itemView.setOnClickListener {
                context.startActivity(Intent(context, ComposeActivity::class.java)
                    .putExtra("threadId", conversation.id).putExtra("query", query))
            }
            return
        }
        val message = item.sip!!
        holder.binding.apply {
            root.setBackgroundResource(R.drawable.d31_conversation_sip)
            root.isActivated = false
            avatars.visibility = android.view.View.INVISIBLE
            sipAvatar.isVisible = true
            title.text = message.peer
            title.setTypeface(null, Typeface.NORMAL)
            snippet.text = message.body.replace('\n', ' ')
            snippet.maxLines = 1
            date.text = "SIP · " + dates.format(Date(message.timestamp))
            unread.isVisible = false
            scheduled.isVisible = false
            pinned.isVisible = false
            root.setOnLongClickListener(null)
            root.setOnClickListener { openSip(context, message.peer) }
        }
    }
    fun dispose() {
        closed = true
        context.unregisterReceiver(receiver)
        sim.unregisterAdapterDataObserver(observer)
        worker.execute { store.close() }
        worker.shutdown()
    }
    companion object {
        fun openSip(context: Context, peer: String = "") {
            context.startActivity(Intent(context, ComposeActivity::class.java)
                .putExtra("d31_transport", "sip").putExtra("d31_peer", peer))
        }
    }
}
