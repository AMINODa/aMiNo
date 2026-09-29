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
            binding.inputEdit.setText("")
            AgentOrchestrator.send(this, text)
        }
        binding.stopBtn.setOnClickListener { AgentOrchestrator.cancel() }
        binding.statusChip.setOnClickListener {
            if (AgentOrchestrator.state.value.status is AgentStatus.NotConfigured || !ApiKeysStore.hasKey(this)) {
                startActivity(Intent(this, moe.shizuku.manager.keys.KeysActivity::class.java))
            }
        }

        binding.navChat.setOnClickListener { binding.chatList.smoothScrollToPosition(adapter.itemCount - 1) }
        binding.navKeys.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.keys.KeysActivity::class.java)) }
        binding.navMemory.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.memory.MemoryActivity::class.java)) }
        binding.navTools.setOnClickListener { startActivity(Intent(this, moe.shizuku.manager.tools.ToolsActivity::class.java)) }
        binding.navShell.setOnClickListener { startActivity(Intent(this, ShellActivity::class.java)) }

        // open a specific conversation when coming from the Memory page
        val convId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        if (convId > 0) AgentOrchestrator.openConversation(this, convId) else AgentOrchestrator.loadLatestOrNew(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentOrchestrator.state.collect { render(it) }
            }
        }
    }

    private fun render(s: moe.shizuku.manager.agent.AgentUiState) {
        val had = adapter.itemCount
        adapter.submit(s.items)
        if (s.items.size != had || s.busy) {
            binding.chatList.scrollToPosition((s.items.size - 1).coerceAtLeast(0))
        }

        binding.statusChip.text = when (val st = s.status) {
            is AgentStatus.Ready -> if (s.providerReady) getString(R.string.agent_status_ready)
            else getString(R.string.agent_status_not_configured)
            is AgentStatus.Thinking -> getString(R.string.agent_status_thinking)
            is AgentStatus.ExecutingTool -> getString(R.string.agent_status_tool, st.name)
            is AgentStatus.Verifying -> getString(R.string.agent_status_verify)
            is AgentStatus.NotConfigured -> getString(R.string.agent_status_not_configured)
        }
        binding.stopBtn.visibility = if (s.busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendBtn.isEnabled = !s.busy
        binding.inputEdit.isEnabled = !s.busy
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
