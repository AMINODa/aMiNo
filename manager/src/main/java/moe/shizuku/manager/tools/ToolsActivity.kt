package moe.shizuku.manager.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.PermissionManager
import moe.shizuku.manager.agent.ToolRegistry
import moe.shizuku.manager.app.AppActivity

/**
 * Tools page (r1376): every registered tool with its REAL availability, plus the
 * real permission/capability state of the phone.
 */
class ToolsActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
    }

    private fun statusLine(tool: ToolRegistry.RegisteredTool): String {
        val s = ToolRegistry.availability(this, tool)
        val res = when (s) {
            "available" -> R.string.tools_status_available
            "needs_shell" -> R.string.tools_status_needs_shell
            "accessibility_only" -> R.string.tools_status_accessibility_only
            "no_internet" -> R.string.tools_status_no_internet
            else -> R.string.tools_status_needs_shell
        }
        return getString(res)
    }

    private inner class Adapter : RecyclerView.Adapter<ToolViewHolder>() {
        override fun getItemCount() = ToolRegistry.tools.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder =
            ToolViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_tool_row, parent, false))
        override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
            val tool = ToolRegistry.tools[position]
            holder.name.text = tool.spec.name
            holder.desc.text = tool.spec.description
            holder.status.text = statusLine(tool)
        }
    }
}

private class ToolViewHolder(v: View) : RecyclerView.ViewHolder(v) {
    val name: TextView = v.findViewById(R.id.nameText)
    val desc: TextView = v.findViewById(R.id.descText)
    val status: TextView = v.findViewById(R.id.statusText)
}
