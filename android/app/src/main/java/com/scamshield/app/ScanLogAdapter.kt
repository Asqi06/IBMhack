package com.scamshield.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ScanLogAdapter(
    private val onAction: (ScanLog, String) -> Unit
) : ListAdapter<ScanLog, ScanLogAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanLog>() {
            override fun areItemsTheSame(a: ScanLog, b: ScanLog) = a.id == b.id
            override fun areContentsTheSame(a: ScanLog, b: ScanLog) = a == b
        }
        private val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val risk: TextView = v.findViewById(R.id.logRisk)
        val cat: TextView = v.findViewById(R.id.logCategory)
        val reason: TextView = v.findViewById(R.id.logReason)
        val snippet: TextView = v.findViewById(R.id.logSnippet)
        val time: TextView = v.findViewById(R.id.logTime)
        val btnDelete: View = v.findViewById(R.id.logDelete)
        val btnBlock: View = v.findViewById(R.id.logBlock)
        val btnIgnore: View = v.findViewById(R.id.logIgnore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_scan_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.risk.text = item.overallRisk.uppercase()
        h.risk.setBackgroundColor(when(item.overallRisk) {
            "high" -> 0xFFFF3B30.toInt()
            "medium" -> 0xFFFF9500.toInt()
            else -> 0xFF34C759.toInt()
        })
        h.cat.text = item.category
        h.reason.text = item.reason
        h.snippet.text = item.snippet
        h.time.text = fmt.format(Date(item.timestamp)) + " • " + item.advisedAction
        // Color the card by risk
        (h.itemView as? com.google.android.material.card.MaterialCardView)?.strokeColor = when(item.overallRisk) {
            "high" -> 0xFFFF3B30.toInt()
            "medium" -> 0xFFFF9500.toInt()
            else -> 0xFF34C759.toInt()
        }
        h.btnDelete.setOnClickListener { onAction(item, "deleted") }
        h.btnBlock.setOnClickListener { onAction(item, "blocked") }
        h.btnIgnore.setOnClickListener { onAction(item, "ignored") }
        // If already acted, dim buttons
        val acted = item.advisedAction != "pending"
        h.btnDelete.alpha = if (acted) 0.4f else 1f
        h.btnBlock.alpha = if (acted) 0.4f else 1f
    }
}
