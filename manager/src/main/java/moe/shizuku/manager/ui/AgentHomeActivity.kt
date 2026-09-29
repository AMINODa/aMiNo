package moe.shizuku.manager.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.AgentOrchestrator
import moe.shizuku.manager.agent.AgentStatus
import moe.shizuku.manager.agent.auto.AutonomousEngine
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.databinding.ActivityAgentHomeBinding
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellActivity

/**
 * AMINO Agent chat - the new home screen (r1376). The conversation is the center of
 * the app; the old HomeActivity (service status cards) stays reachable from the menu.
 */
open class AgentHomeActivity : AppActivity() {

    protected lateinit var binding: ActivityAgentHomeBinding
    private val adapter = ChatAdapter()

    // r1379: autonomous task mode (architecture C) — toggled by the ⚡ Auto button
    private var autoMode = false
    private var confirmDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chatList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatList.adapter = adapter

        binding.toolbar.inflateMenu(R.menu.menu_agent_home)
        binding.toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_new_chat -> { AgentOrchestrator.newConversation(this); true }
                R.id.action_service_status -> { startActivity(Intent(this, HomeActivity::class.java)); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        binding.sendBtn.setOnClickListener {
            val text = binding.inputEdit.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            binding.inputEdit.setText("")
            if (autoMode) AutonomousEngine.run(this, text)
            else AgentOrchestrator.send(this, text)
        }
        binding.stopBtn.setOnClickListener {
            AgentOrchestrator.cancel()
            AutonomousEngine.cancel()
        }
        binding.autoBtn.setOnClickListener {
            autoMode = !autoMode
            binding.autoBtn.isChecked = autoMode
            // r1381: ChatGPT-style pill — dark grey idle, aMiNo red active
            binding.autoBtn.setTextColor(if (autoMode) 0xFFFFFFFF.toInt() else 0xFFFF867C.toInt())
            binding.autoBtn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(if (autoMode) 0xFFD32F2F.toInt() else 0xFF1A1A20.toInt())
            binding.inputEdit.hint = getString(if (autoMode) R.string.auto_hint else R.string.agent_hint)
        }

        // r1381: empty-state suggestion chips fill the composer
        val suggestions = mapOf(
            binding.suggest1 to R.string.agent_suggest_1,
            binding.suggest2 to R.string.agent_suggest_2,
            binding.suggest3 to R.string.agent_suggest_3,
            binding.suggest4 to R.string.agent_suggest_4
        )
        for ((view, res) in suggestions) {
            view.setOnClickListener {
                if (binding.inputEdit.isEnabled) {
                    binding.inputEdit.setText(getString(res))
                    binding.inputEdit.requestFocus()
                }
            }
        }
        binding.statusChip.setOnClickListener {
            if (AgentOrchestrator.state.value.status is AgentStatus.NotConfigured || !ApiKeysStore.hasKey(this)) {
                startActivity(Intent(this, moe.shizuku.manager.keys.KeysActivity::class.java))
            }
        }

        binding.navHome.setOnClickListener { startActivity(Intent(this, HomeActivity::class.java)) }
        binding.navChat.setOnClickListener { binding.chatList.smoothScrollToPosition(adapter.itemCount - 1) }
        binding.navKeys.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.keys.KeysActivity::class.java)) }
        binding.navMemory.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.memory.MemoryActivity::class.java)) }
        binding.navTools.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.tools.ToolsActivity::class.java)) }
        binding.navShell.setOnClickListener { startActivity(Intent(this, ShellActivity::class.java)) }

        // aMiNo red/black identity: the active nav item glows red
        binding.navChat.setTextColor(0xFFFF5252.toInt())
        binding.navChat.setTypeface(null, android.graphics.Typeface.BOLD)

        // open a specific conversation when coming from the Memory page
        val convId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        if (convId > 0) AgentOrchestrator.openConversation(this, convId) else AgentOrchestrator.loadLatestOrNew(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentOrchestrator.state.collect { render(it) }
            }
        }

        // r1379: autonomous engine state -> chip phase + approval dialog
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutonomousEngine.state.collect { auto ->
                    val busy = s_busy || auto.busy
                    binding.stopBtn.visibility =
                        if (busy) android.view.View.VISIBLE else android.view.View.GONE
                    // r1381: one circular slot — send swaps to stop while busy (ChatGPT style)
                    binding.sendBtn.visibility =
                        if (busy) android.view.View.GONE else android.view.View.VISIBLE
                    binding.sendBtn.isEnabled = !busy
                    binding.inputEdit.isEnabled = !busy
                    val pending = auto.awaiting
                    if (pending != null) showConfirmDialog(pending.command, pending.reason)
                    else confirmDialog?.dismiss()
                }
            }
        }
    }

    private fun showConfirmDialog(command: String, reason: String) {
        if (confirmDialog?.isShowing == true) return
        confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_confirm_title))
            .setMessage("$ ${command}\n\n${getString(R.string.auto_confirm_why, reason)}")
            .setPositiveButton(getString(R.string.auto_confirm_allow)) { _, _ -> AutonomousEngine.approve(true) }
            .setNegativeButton(getString(R.string.auto_confirm_deny)) { _, _ -> AutonomousEngine.approve(false) }
            .setOnCancelListener { AutonomousEngine.approve(false) }
            .show()
    }

    private val s_busy: Boolean get() = AgentOrchestrator.state.value.busy

    private fun render(s: moe.shizuku.manager.agent.AgentUiState) {
        val had = adapter.itemCount
        adapter.submit(s.items)
        if (s.items.size != had || s.busy) {
            binding.chatList.scrollToPosition((s.items.size - 1).coerceAtLeast(0))
        }

        // r1381: ChatGPT-like empty state (hidden once the conversation has rows or work is running)
        binding.emptyState.visibility =
            if (s.items.isEmpty() && !s.busy) android.view.View.VISIBLE else android.view.View.GONE

        binding.statusChip.text = when (val st = s.status) {
            is AgentStatus.Ready -> if (s.providerReady) getString(R.string.agent_status_ready)
            else getString(R.string.agent_status_not_configured)
            is AgentStatus.Thinking -> getString(R.string.agent_status_thinking)
            is AgentStatus.ExecutingTool -> getString(R.string.agent_status_tool, st.name)
            is AgentStatus.Verifying -> getString(R.string.agent_status_verify)
            is AgentStatus.NotConfigured -> getString(R.string.agent_status_not_configured)
        }
        // r1379: while the autonomous loop runs, the chip shows ITS phase instead
        val autoBusy = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.busy
        if (autoBusy) {
            binding.statusChip.text = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.phase
                ?: getString(R.string.agent_status_thinking)
        }
        val autoPending = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.awaiting != null
        val busyAll = s.busy || autoBusy
        binding.stopBtn.visibility = if (busyAll) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendBtn.visibility = if (busyAll) android.view.View.GONE else android.view.View.VISIBLE
        binding.sendBtn.isEnabled = !busyAll
        binding.inputEdit.isEnabled = !busyAll
        if (autoPending) return
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
