package moe.shizuku.manager.keys

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.CloudflareProvider
import moe.shizuku.manager.agent.GeminiProvider
import moe.shizuku.manager.agent.LlmDecision
import moe.shizuku.manager.agent.OpenAiCompatProvider
import moe.shizuku.manager.agent.OpenRouterProvider
import moe.shizuku.manager.app.AppActivity

/**
 * API Keys page (r1376). The key is stored ONLY locally, encrypted with
 * Android Keystore (see SecureStore). "Test" performs a REAL request to the
 * configured provider and shows the real result. The saved key is never shown
 * fully again - only a masked hint.
 */
class KeysActivity : AppActivity() {

    private lateinit var binding: moe.shizuku.manager.databinding.ActivityKeysBinding
    private lateinit var providerSpinner: android.widget.Spinner
    private lateinit var modelEdit: android.widget.EditText
    private lateinit var baseUrlEdit: android.widget.EditText
    private lateinit var keyEdit: android.widget.EditText
    private lateinit var statusText: android.widget.TextView
    private var keyVisible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = moe.shizuku.manager.databinding.ActivityKeysBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        providerSpinner = b.providerSpinner
        modelEdit = b.modelEdit
        baseUrlEdit = b.baseUrlEdit
        keyEdit = b.keyEdit
        statusText = b.statusText
        val eyeBtn = b.eyeBtn
        val saveBtn = b.saveBtn
        val testBtn = b.testBtn
        val deleteBtn = b.deleteBtn
        val toolbar = b.toolbar

        toolbar.setNavigationOnClickListener { finish() }

        val labels = ApiKeysStore.PROVIDERS.map { it.label }
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        providerSpinner.setSelection(ApiKeysStore.PROVIDERS.indexOfFirst { it.id == ApiKeysStore.provider(this) }.coerceAtLeast(0))
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < 0 || pos >= ApiKeysStore.PROVIDERS.size) return
                val was = ApiKeysStore.provider(this@KeysActivity)
                val now = ApiKeysStore.PROVIDERS[pos].id
                if (was != now) ApiKeysStore.setProvider(this@KeysActivity, now)
                loadFields()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        eyeBtn.setOnClickListener {
            keyVisible = !keyVisible
            keyEdit.transformationMethod = if (keyVisible)
                HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            keyEdit.setSelection(keyEdit.text?.length ?: 0)
        }

        saveBtn.setOnClickListener { save() }
        testBtn.setOnClickListener { test() }
        deleteBtn.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keys_delete)
                .setMessage(R.string.keys_confirm_delete)
                .setPositiveButton(R.string.keys_delete) { _, _ ->
                    ApiKeysStore.deleteKey(this)
                    loadFields()
                    Toast.makeText(this, R.string.keys_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        loadFields()
    }

    private fun loadFields() {
        val info = ApiKeysStore.providerInfo(this)
        modelEdit.setText(ApiKeysStore.model(this))
        baseUrlEdit.setText(ApiKeysStore.baseUrl(this) ?: "")
        baseUrlEdit.visibility = View.VISIBLE
        keyEdit.setText("")
        // per-provider setup help + model hint (r1377)
        when (info.id) {
            ApiKeysStore.PROVIDER_CLOUDFLARE -> {
                binding.helpText.visibility = View.VISIBLE
                binding.helpText.text = getString(R.string.keys_help_cloudflare)
                modelEdit.hint = "@cf/meta/llama-3.1-8b-instruct | @cf/meta/llama-3.3-70b-instruct-fp8-fast | @cf/qwen/qwen2.5-32b-instruct"
            }
            ApiKeysStore.PROVIDER_OPENROUTER -> {
                binding.helpText.visibility = View.VISIBLE
                binding.helpText.text = getString(R.string.keys_help_openrouter)
                modelEdit.hint = "meta-llama/llama-3.3-70b-instruct | qwen/qwen3.8-27b:free | nvidia/nemotron-3.5-lightning:free"
            }
            ApiKeysStore.PROVIDER_GEMINI -> {
                binding.helpText.visibility = View.VISIBLE
                binding.helpText.text = getString(R.string.keys_help_gemini)
                modelEdit.hint = "gemini-flash-latest"
            }
            else -> {
                binding.helpText.visibility = View.GONE
                modelEdit.hint = info.defaultModel
            }
        }
        val masked = ApiKeysStore.maskedKey(this)
        keyEdit.hint = if (masked != null) getString(R.string.keys_saved_hint, masked)
        else getString(R.string.keys_api_key_hint)
        renderStatus()
    }

    private fun renderStatus() {
        statusText.text = when (ApiKeysStore.status(this)) {
            ApiKeysStore.STATUS_READY -> getString(R.string.keys_status_ready)
            ApiKeysStore.STATUS_FAILED -> getString(R.string.keys_status_failed)
            else -> getString(R.string.keys_status_not_configured)
        }
    }

    private fun save() {
        val key = keyEdit.text?.toString()?.trim()
        ApiKeysStore.setModel(this, modelEdit.text?.toString()?.trim().orEmpty())
        ApiKeysStore.setBaseUrl(this, baseUrlEdit.text?.toString()?.trim().takeIf { !it.isNullOrBlank() })
        if (!key.isNullOrEmpty()) {
            val ok = ApiKeysStore.setKey(this, key.toCharArray())
            if (!ok) {
                Toast.makeText(this, R.string.keys_save_failed_keystore, Toast.LENGTH_LONG).show()
                return
            }
            // clear the plaintext field immediately
            keyEdit.setText("")
            Toast.makeText(this, R.string.keys_saved, Toast.LENGTH_SHORT).show()
        }
        loadFields()
    }

    private fun test() {
        val key = keyEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toCharArray()
            ?: SecureStore.get(this, "apikey_" + ApiKeysStore.provider(this))
        if (key == null) {
            ApiKeysStore.setStatus(this, ApiKeysStore.STATUS_NOT_CONFIGURED)
            renderStatus()
            Toast.makeText(this, R.string.keys_status_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        // stash a just-typed key so test works before save too
        val provider = ApiKeysStore.provider(this)
        val model = modelEdit.text?.toString()?.trim().orEmpty().ifBlank { ApiKeysStore.providerInfo(this).defaultModel }
        val baseUrl = baseUrlEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        setTestBusy(true)
        scope.launch {
            val impl = when (provider) {
                ApiKeysStore.PROVIDER_CLOUDFLARE -> CloudflareProvider
                ApiKeysStore.PROVIDER_OPENROUTER -> OpenRouterProvider
                ApiKeysStore.PROVIDER_AGENTROUTER -> moe.shizuku.manager.agent.AgentRouterProvider
                ApiKeysStore.PROVIDER_OPENAI_COMPAT -> OpenAiCompatProvider
                else -> GeminiProvider
            }
            val decision = runCatching { impl.testConnection(key, model, baseUrl) }
                .getOrElse { LlmDecision.Error(it.message ?: it.javaClass.simpleName, 0) }
            withContext(Dispatchers.Main) {
                setTestBusy(false)
                when (decision) {
                    is LlmDecision.Text -> {
                        ApiKeysStore.setStatus(this@KeysActivity, ApiKeysStore.STATUS_READY)
                        Toast.makeText(this@KeysActivity,
                            getString(R.string.keys_test_ok, decision.text.take(40)), Toast.LENGTH_LONG).show()
                    }
                    is LlmDecision.Error -> {
                        ApiKeysStore.setStatus(this@KeysActivity, ApiKeysStore.STATUS_FAILED)
                        MaterialAlertDialogBuilder(this@KeysActivity)
                            .setTitle(R.string.keys_test_fail_title)
                            .setMessage(decision.message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    else -> {}
                }
                renderStatus()
            }
        }
    }

    private fun setTestBusy(busy: Boolean) {
        findViewById<MaterialButton>(R.id.testBtn)?.isEnabled = !busy
        findViewById<MaterialButton>(R.id.saveBtn)?.isEnabled = !busy
        statusText.text = if (busy) getString(R.string.keys_testing) else statusText.text
    }
}
