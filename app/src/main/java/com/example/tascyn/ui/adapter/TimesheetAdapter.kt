package com.example.tascyn.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.data.TimesheetSession
import com.example.tascyn.data.TimesheetStatus
import com.example.tascyn.domain.NotionFormulas
import java.text.SimpleDateFormat
import java.util.*

class TimesheetAdapter(
    private val onSessionClicked: (TimesheetSession) -> Unit
) : ListAdapter<TimesheetSession, TimesheetAdapter.TimesheetViewHolder>(TimesheetDiffCallback()) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimesheetViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timesheet_precision, parent, false)
        return TimesheetViewHolder(view)
    }

    override fun onBindViewHolder(holder: TimesheetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TimesheetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgSessionStatusIcon: ImageView = itemView.findViewById(R.id.imgSessionStatusIcon)
        private val txtSessionTitle: TextView = itemView.findViewById(R.id.txtSessionTitle)
        private val txtSessionDurationFormula: TextView = itemView.findViewById(R.id.txtSessionDurationFormula)
        private val txtSessionLinkedTask: TextView = itemView.findViewById(R.id.txtSessionLinkedTask)
        private val txtSessionTimeRange: TextView = itemView.findViewById(R.id.txtSessionTimeRange)

        fun bind(session: TimesheetSession) {
            val context = itemView.context
            txtSessionTitle.text = session.title

            // Status icon
            if (session.status == TimesheetStatus.IN_PROGRESS) {
                imgSessionStatusIcon.setImageResource(R.drawable.ic_play_precision)
                imgSessionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.color_success))
            } else {
                imgSessionStatusIcon.setImageResource(R.drawable.ic_timer_precision)
                imgSessionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.color_icon_secondary))
            }

            // Formula 4.1: Timesheet Duration
            val durationText = NotionFormulas.calculateTimesheetDuration(session)
            txtSessionDurationFormula.text = durationText.removePrefix("Duration: ")

            // Linked Task Name
            val linkedTask = session.taskId?.let { TaskManagerRepository.get().getTaskById(it) }
            if (linkedTask != null) {
                txtSessionLinkedTask.text = "Linked: ${linkedTask.title}"
                txtSessionLinkedTask.visibility = View.VISIBLE
            } else {
                txtSessionLinkedTask.visibility = View.GONE
            }

            // Time Range
            if (session.startTime != null) {
                val startStr = timeFormat.format(Date(session.startTime!!))
                val endStr = if (session.endTime != null) timeFormat.format(Date(session.endTime!!)) else "Now"
                txtSessionTimeRange.text = "$startStr - $endStr"
            } else {
                txtSessionTimeRange.text = "--"
            }

            itemView.setOnClickListener {
                onSessionClicked(session)
            }
        }
    }

    class TimesheetDiffCallback : DiffUtil.ItemCallback<TimesheetSession>() {
        override fun areItemsTheSame(oldItem: TimesheetSession, newItem: TimesheetSession): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TimesheetSession, newItem: TimesheetSession): Boolean = oldItem == newItem
    }
}
