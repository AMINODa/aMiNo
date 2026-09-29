package moe.shizuku.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.ChatItem

/**
 * Chat adapter: user bubbles (right), agent bubbles (left), compact tool step rows
 * that expand on tap to show the REAL tool output.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = ArrayList<ChatItem>()
    private val expanded = HashSet<Long>()

    fun submit(list: List<ChatItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int = when {
        items[position].role == "user" -> TYPE_USER
        items[position].role == "tool" -> TYPE_TOOL
        items[position].role == "auto" -> TYPE_TOOL // r1379: autonomous loop shell rows
        items[position].toolName != null -> TYPE_TOOL
        else -> TYPE_AGENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> VH(inf.inflate(R.layout.item_chat_user, parent, false))
            TYPE_TOOL -> VH(inf.inflate(R.layout.item_chat_tool, parent, false))
            else -> VH(inf.inflate(R.layout.item_chat_agent, parent, false))
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        when (getItemViewType(position)) {
            TYPE_USER -> {
                val t = holder.root.findViewById<TextView>(R.id.text)
                t.text = item.text
            }
            TYPE_AGENT -> {
                val t = holder.root.findViewById<TextView>(R.id.text)
                t.text = item.text
            }
            TYPE_TOOL -> {
                val title = holder.root.findViewById<TextView>(R.id.toolTitle)
                val output = holder.root.findViewById<TextView>(R.id.toolOutput)
                if (item.role == "tool") {
                    val mark = when (item.toolOk) { true -> "✓"; false -> "✗"; else -> "•" }
                    title.text = "🔧 ${item.toolName} $mark"
                } else if (item.role == "auto") {
                    // r1379: real executed command of the autonomous loop (red/black identity)
                    val cmd = item.text.lineSequence().firstOrNull()?.removePrefix("$ ") ?: ""
                    val mark = when (item.toolOk) { true -> "✓"; false -> "✗"; else -> "•" }
                    title.text = "💻 $cmd $mark"
                    title.setTextColor(0xFFFF867C.toInt())
                } else {
                    title.text = "⚙️ ${item.toolName}"
                }
                val body = item.text
                val isOpen = expanded.contains(item.id) || item.role == "auto"
                output.text = body
                output.visibility = if (isOpen && body.isNotBlank()) View.VISIBLE else View.GONE
                holder.root.setOnClickListener {
                    if (expanded.contains(item.id)) expanded.remove(item.id) else expanded.add(item.id)
                    notifyItemChanged(position)
                }
            }
        }
    }

    class VH(val root: View) : RecyclerView.ViewHolder(root)

    companion object {
        const val TYPE_USER = 0
        const val TYPE_AGENT = 1
        const val TYPE_TOOL = 2
    }
}
