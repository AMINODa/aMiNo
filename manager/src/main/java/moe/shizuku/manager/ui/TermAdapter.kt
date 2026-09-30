package moe.shizuku.manager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.terminal.TermLine

/**
 * aMiNo r1384 — scrollback adapter: colors each line by kind.
 *   CMD = red bold ("❯"), OUT = light, ERR = salmon, SYS = grey italic.
 */
class TermAdapter : ListAdapter<TermLine, TermAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TermLine>() {
            override fun areItemsTheSame(a: TermLine, b: TermLine) = a.seq == b.seq
            override fun areContentsTheSame(a: TermLine, b: TermLine) = a == b
        }
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_term_line, parent, false) as TextView)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = getItem(position)
        val c = holder.text.context
        when (l.kind) {
            TermLine.Kind.CMD -> {
                holder.text.setTextColor(0xFFFF5252.toInt())
                holder.text.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.text.text = "❯ ${l.text}"
            }
            TermLine.Kind.OUT -> {
                holder.text.setTextColor(0xFFC6C6D0.toInt())
                holder.text.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.text.text = l.text
            }
            TermLine.Kind.ERR -> {
                holder.text.setTextColor(0xFFFF867C.toInt())
                holder.text.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.text.text = l.text
            }
            TermLine.Kind.SYS -> {
                holder.text.setTextColor(0xFF8A8A94.toInt())
                holder.text.setTypeface(null, android.graphics.Typeface.ITALIC)
                holder.text.text = l.text
            }
        }
    }
}
