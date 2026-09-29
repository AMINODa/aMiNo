package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeAgentBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.ui.AgentHomeActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * aMiNo r1378: "Agent" home card, placed right below the Shell card.
 * Always enabled - the agent works even while the service is stopped
 * (it is a chat with Cloudflare Workers AI, not tied to the Shizuku server).
 */
class AgentViewHolder(private val binding: HomeAgentBinding, root: View) :
    BaseViewHolder<Any>(root),
    View.OnClickListener {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeAgentBinding.inflate(inflater, outer.root, true)
            AgentViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    override fun onBind() {
        binding.text2.text = itemView.context.getString(R.string.home_agent_description)
    }

    override fun onClick(v: View) {
        v.context.startActivity(Intent(v.context, AgentHomeActivity::class.java))
    }
}
