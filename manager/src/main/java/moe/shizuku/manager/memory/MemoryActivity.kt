package moe.shizuku.manager.memory

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.AgentHomeActivity
import java.text.DateFormat
import java.util.Date

/**
 * Memory management page (r1376): review, search, edit, delete and export ALL local
 * agent memory. Nothing leaves the device except a user-initiated export share.
 */
class MemoryActivity : AppActivity() {

    private enum class Section { CONVERSATIONS, USER, KNOWLEDGE }

    private var section = Section.CONVERSATIONS
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter
    private lateinit var addBtn: MaterialButton
    private var searchQuery: String = ""

    // row model for the generic list
    private data class Row(val id: Long, val title: String, val sub: String, val content: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = RowAdapter()
        list.adapter = adapter
        addBtn = findViewById(R.id.addBtn)

        findViewById<Chip>(R.id.chipConversations).setOnClickListener { section = Section.CONVERSATIONS; reload() }
        findViewById<Chip>(R.id.chipUser).setOnClickListener { section = Section.USER; reload() }
        findViewById<Chip>(R.id.chipKnowledge).setOnClickListener { section = Section.KNOWLEDGE; reload() }

        findViewById<EditText>(R.id.searchEdit).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                reload()
            }
        })

        addBtn.setOnClickListener { onAdd() }
        reload()
    }

    private fun onAdd() = when (section) {
        Section.CONVERSATIONS -> {
            AgentOrchestrator_new()
        }
        Section.USER -> showEditDialog(null, "") { text ->
            MemoryRepository.addUserMemory(this, text)
            reload()
        }
        Section.KNOWLEDGE -> showKnowledgeDialog(null) { reload() }
    }

    private fun AgentOrchestrator_new() {
        moe.shizuku.manager.agent.AgentOrchestrator.newConversation(this)
        startActivity(Intent(this, AgentHomeActivity::class.java))
        reload()
    }

    private fun reload() {
        val rows = when (section) {
            Section.CONVERSATIONS -> MemoryRepository.listConversations(this, searchQuery).map {
                Row(it.id, it.title,
                    DateFormat.getDateTimeInstance().format(Date(it.updatedAt)), "")
            }
            Section.USER -> MemoryRepository.userMemory(this, searchQuery.takeIf { it.isNotBlank() }).map {
                Row(it.id, it.content.take(80), it.content.take(200), it.content)
            }
            Section.KNOWLEDGE -> MemoryRepository.knowledge(this, searchQuery.takeIf { it.isNotBlank() }).map {
                Row(it.id, it.title, it.content.take(200), it.content)
            }
        }
        adapter.submit(rows)
        addBtn.visibility = if (section == Section.CONVERSATIONS && rows.isEmpty()) View.VISIBLE else View.VISIBLE
    }

    private fun deleteRow(id: Long) {
        when (section) {
            Section.CONVERSATIONS -> MemoryRepository.deleteConversation(this, id)
            Section.USER -> MemoryRepository.deleteUserMemory(this, id)
            Section.KNOWLEDGE -> MemoryRepository.deleteKnowledge(this, id)
        }
        reload()
    }

    private fun confirmDelete(id: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.memory_delete)
            .setMessage(R.string.memory_delete_confirm)
            .setPositiveButton(R.string.memory_delete) { _, _ -> deleteRow(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onRowClick(row: Row) {
        when (section) {
            Section.CONVERSATIONS -> {
                val i = Intent(this, AgentHomeActivity::class.java)
                i.putExtra(AgentHomeActivity.EXTRA_CONVERSATION_ID, row.id)
                startActivity(i)
            }
            Section.USER -> showEditDialog(row.id, row.content) { text ->
                MemoryRepository.updateUserMemory(this, row.id, text)
                reload()
            }
            Section.KNOWLEDGE -> showKnowledgeDialog(row) { reload() }
        }
    }

    private fun onRowLongClick(row: Row): Boolean {
        if (section != Section.CONVERSATIONS) return false
        val text = MemoryRepository.exportConversation(this, row.id)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.memory_export)))
        return true
    }

    private fun showEditDialog(id: Long?, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSelection(text?.length ?: 0) }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (id == null) R.string.memory_add else R.string.memory_edit)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) onSave(t)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showKnowledgeDialog(existing: Row?, onDone: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_knowledge_edit, null, false)
        val title = view.findViewById<EditText>(R.id.kTitle)
        val content = view.findViewById<EditText>(R.id.kContent)
        existing?.let { title.setText(it.title); content.setText(it.content) }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.memory_add else R.string.memory_edit)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = title.text?.toString()?.trim().orEmpty()
                val c = content.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && c.isNotEmpty()) {
                    if (existing != null) {
                        MemoryRepository.deleteKnowledge(this, existing.id)
                    }
                    MemoryRepository.addKnowledge(this, t, c)
                    onDone()
                } else {
                    Toast.makeText(this, R.string.memory_empty_fields, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class RowAdapter : RecyclerView.Adapter<MemoryRowHolder>() {
        private val rows = ArrayList<Row>()
        fun submit(list: List<Row>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryRowHolder =
            MemoryRowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_memory_row, parent, false))
        override fun onBindViewHolder(holder: MemoryRowHolder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.sub.text = row.sub
            holder.itemView.setOnClickListener { onRowClick(row) }
            holder.itemView.setOnLongClickListener { onRowLongClick(row) }
            holder.delete.setOnClickListener { confirmDelete(row.id) }
        }
    }
}

private class MemoryRowHolder(v: View) : RecyclerView.ViewHolder(v) {
    val title: TextView = v.findViewById(R.id.titleText)
    val sub: TextView = v.findViewById(R.id.subText)
    val delete: View = v.findViewById(R.id.deleteBtn)
}
