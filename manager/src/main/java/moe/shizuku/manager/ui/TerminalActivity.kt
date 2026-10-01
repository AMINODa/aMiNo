package moe.shizuku.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.terminal.TermBackend
import moe.shizuku.manager.terminal.TerminalEngine
import moe.shizuku.manager.utils.Toasts

/**
 * aMiNo r1384 — the REAL terminal screen (spec E).
 *
 * Everything on this page is bound to TerminalEngine: live command output,
 * separate stderr coloring, real exit codes, cwd/env state, several
 * independent sessions, stop/clear/copy/paste/history. NOT a mock.
 * Auto-scroll is guarded (r1383 lesson: never scroll an empty list).
 */
class TerminalActivity : AppActivity() {

    companion object {
        /** r1395 — the session the caller (e.g. the Linux page) wants selected. */
        const val EXTRA_SESSION_ID = "amino.terminal.SESSION_ID"
    }

    private lateinit var binding: TerminalActivityBindingHolder
    private val adapter = TermAdapter()
    private var currentId: String? = null
    private var pendingSelectId: String? = null

    /** ViewBinding holder without the generated binding class name clash — simple manual holder. */
    class TerminalActivityBindingHolder(
        val toolbar: MaterialToolbar,
        val sessionChips: android.view.ViewGroup,
        val statusLine: TextView,
        val termList: RecyclerView,
        val emptyView: TextView,
        val btnHistory: TextView, val btnCopy: TextView, val btnPaste: TextView,
        val btnClear: TextView, val btnStop: TextView, val btnNew: TextView,
        val btnClose: TextView,
        val inputEdit: android.widget.EditText,
        val sendBtn: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        val b = TerminalActivityBindingHolder(
            toolbar = findViewById(R.id.toolbar),
            sessionChips = findViewById(R.id.sessionChips),
            statusLine = findViewById(R.id.statusLine),
            termList = findViewById(R.id.termList),
            emptyView = findViewById(R.id.emptyView),
            btnHistory = findViewById(R.id.btnHistory),
            btnCopy = findViewById(R.id.btnCopy),
            btnPaste = findViewById(R.id.btnPaste),
            btnClear = findViewById(R.id.btnClear),
            btnStop = findViewById(R.id.btnStop),
            btnNew = findViewById(R.id.btnNew),
            btnClose = findViewById(R.id.btnClose),
            inputEdit = findViewById(R.id.inputEdit),
            sendBtn = findViewById(R.id.sendBtn)
        )
        binding = b

        // r1395 — a caller that created a session (Linux page "Ouvrir le terminal",
        // agent tools) can pin the session this screen must show first; without it
        // the old fallback selected the OLDEST session and the freshly created
        // Linux session was never displayed — the feature looked dead.
        pendingSelectId = intent?.getStringExtra(EXTRA_SESSION_ID)

        b.termList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.termList.adapter = adapter

        // send a command (real execute — output streams live into the scrollback)
        b.sendBtn.setOnClickListener {
            val s = currentSession()
            val text = b.inputEdit.text?.toString().orEmpty()
            if (s == null) { toast(getString(R.string.terminal_empty)); return@setOnClickListener }
            if (text.isBlank()) return@setOnClickListener
            b.inputEdit.setText("")
            lifecycleScope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { s.execute(text, 600_000) }
                        .onFailure { toast(it.message ?: "error") }
                }
            }
        }

        b.btnStop.setOnClickListener { currentSession()?.stopProcess() }
        b.btnClear.setOnClickListener { currentSession()?.clearLines() }
        b.btnCopy.setOnClickListener {
            val s = currentSession() ?: return@setOnClickListener
            val text = s.snapshotLines().joinToString("\n") { it.text }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("amino-terminal", text))
            toast(getString(R.string.term_copied))
        }
        b.btnPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            if (t.isNotEmpty()) binding.inputEdit.append(t)
        }
        b.btnHistory.setOnClickListener {
            val s = currentSession() ?: return@setOnClickListener
            if (s.history.isEmpty()) return@setOnClickListener
            val pm = android.widget.PopupMenu(this, it)
            s.history.toList().takeLast(15).reversed().forEach { cmd -> pm.menu.add(cmd) }
            pm.setOnMenuItemClickListener { mi ->
                binding.inputEdit.setText(mi.title); true
            }
            pm.show()
        }
        b.btnNew.setOnClickListener { showNewSessionDialog() }
        b.btnClose.setOnClickListener {
            val id = currentId
            if (id != null) {
                TerminalEngine.close(id)
                currentId = TerminalEngine.listSessions().firstOrNull()?.id
                rebuildChips()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(250)
                }
            }
        }
    }

    private fun currentSession() = currentId?.let { TerminalEngine.getSession(it) }

    private fun refresh() {
        // r1395 — honor the caller's session selection once, before the fallback
        pendingSelectId?.let { pid ->
            pendingSelectId = null
            if (TerminalEngine.getSession(pid) != null) currentId = pid
        }
        val sessions = TerminalEngine.listSessions()
        if (currentId == null || TerminalEngine.getSession(currentId!!) == null) {
            currentId = sessions.firstOrNull()?.id
        }
        val s = currentSession()
        binding.emptyView.visibility = if (s == null) View.VISIBLE else View.GONE
        if (s == null) {
            adapter.submitList(emptyList())
            binding.statusLine.text = getString(R.string.terminal_empty)
            return
        }
        adapter.submitList(s.snapshotLines())
        val st = s.status()
        binding.statusLine.text =
            "env: ${st.backend.id}${if (st.agentSession) " (agent)" else ""} · cwd: ${st.cwd ?: "?"} · " +
                "rc: ${st.lastExitCode ?: "-"} · ${if (st.busy) "running (pid ${st.fgPid})" else if (st.ready) "ready" else "down"}"
        rebuildChips()
    }

    private fun rebuildChips() {
        val container = binding.sessionChips
        val sessions = TerminalEngine.listSessions()
        // cheap re-render (few chips); preserve selection state by tag
        if (container.tag == sessions.joinToString(",") { it.id + it.busy }) return
        container.tag = sessions.joinToString(",") { it.id + it.busy }
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (s in sessions) {
            val chip = inflater.inflate(R.layout.item_term_chip, container, false) as TextView
            chip.text = (if (s.isAgentSession) "🤖 " else "") + s.displayName + (if (s.busy) " •" else "")
            chip.setTextColor(if (s.id == currentId) 0xFFFF5252.toInt() else 0xFF8A8A94.toInt())
            chip.setOnClickListener {
                currentId = s.id
                container.tag = null
                refresh()
            }
            chip.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(s.displayName)
                    .setMessage(getString(R.string.term_close_confirm))
                    .setPositiveButton(getString(R.string.term_btn_close_label)) { _, _ ->
                        TerminalEngine.close(s.id)
                        if (currentId == s.id) currentId = null
                        container.tag = null
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            container.addView(chip)
        }
        // "+" chip — create a session
        val plus = inflater.inflate(R.layout.item_term_chip, container, false) as TextView
        plus.text = "➕"
        plus.setTextColor(0xFFFF5252.toInt())
        plus.setOnClickListener { showNewSessionDialog() }
        container.addView(plus)
    }

    private fun showNewSessionDialog() {
        val envs = TerminalEngine.discover(this).filter { it.available }
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (e in envs) {
            items.add("${e.backend.title} — ${e.identity}")
            actions.add {
                lifecycleScope.launch {
                    val s = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { TerminalEngine.create(this@TerminalActivity, e.backend, "", false) }
                            .onFailure { toast(it.message ?: "failed") }
                            .getOrNull()
                    }
                    if (s != null) {
                        currentId = s.id
                        binding.sessionChips.tag = null
                        refresh()
                    }
                }
            }
        }
        // r1385: always offer the Linux environment manager (install / manage)
        val linuxRow = getString(R.string.term_linux_manage)
        items.add(linuxRow)
        actions.add { startActivity(android.content.Intent(this, LinuxEnvActivity::class.java)) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.term_pick_env))
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // r1400 — crash-safe: the two onFailure sites above run inside
    // withContext(Dispatchers.IO) — a raw Toast there KILLED the whole app
    // on Android 13 ("Can't toast on a thread that has not called
    // Looper.prepare()") the moment a command execution failed.
    private fun toast(t: String) = Toasts.show(this, t)
}
