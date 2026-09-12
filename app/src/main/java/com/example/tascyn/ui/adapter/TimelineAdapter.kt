package com.example.tascyn.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.UrgencyLevel
import com.example.tascyn.domain.NotionFormulas
import java.text.SimpleDateFormat
import java.util.*

class TimelineAdapter(
    private val onTaskClicked: (Task) -> Unit
) : ListAdapter<Task, TimelineAdapter.TimelineViewHolder>(TimelineDiffCallback()) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_precision, parent, false)
        return TimelineViewHolder(view)
    }

    override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TimelineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtTimelineDate: TextView = itemView.findViewById(R.id.txtTimelineDate)
        private val txtTimelineTime: TextView = itemView.findViewById(R.id.txtTimelineTime)
        private val txtTimelineTaskTitle: TextView = itemView.findViewById(R.id.txtTimelineTaskTitle)
        private val txtTimelineQuadrant: TextView = itemView.findViewById(R.id.txtTimelineQuadrant)
        private val txtTimelineUrgency: TextView = itemView.findViewById(R.id.txtTimelineUrgency)

        fun bind(task: Task) {
            val context = itemView.context
            txtTimelineTaskTitle.text = task.title

            val now = System.currentTimeMillis()
            val reminderInfo = NotionFormulas.calculateNextReminderInfo(task, now)
            val targetDate = reminderInfo.triggerTime

            val cal = Calendar.getInstance()
            val todayDay = cal.get(Calendar.DAY_OF_YEAR)
            val todayYear = cal.get(Calendar.YEAR)

            cal.timeInMillis = targetDate
            val targetDay = cal.get(Calendar.DAY_OF_YEAR)
            val targetYear = cal.get(Calendar.YEAR)

            if (todayYear == targetYear && todayDay == targetDay) {
                txtTimelineDate.text = "TODAY"
            } else if (todayYear == targetYear && targetDay == todayDay + 1) {
                txtTimelineDate.text = "TMRW"
            } else {
                txtTimelineDate.text = dateFormat.format(Date(targetDate)).uppercase()
            }
            txtTimelineTime.text = timeFormat.format(Date(targetDate))

            // Quadrant
            val qResult = NotionFormulas.calculateQuadrant(task)
            if (qResult.isValid) {
                txtTimelineQuadrant.text = "Q${qResult.qNumber} · ${qResult.action}"
                txtTimelineQuadrant.visibility = View.VISIBLE
            } else {
                txtTimelineQuadrant.visibility = View.GONE
            }

            // Urgency
            val timeLeftResult = NotionFormulas.calculateTimeLeft(task)
            when (timeLeftResult.urgencyLevel) {
                UrgencyLevel.OVERDUE -> {
                    txtTimelineUrgency.text = "Overdue"
                    txtTimelineUrgency.setBackgroundResource(R.drawable.bg_urgency_red)
                    txtTimelineUrgency.setTextColor(ContextCompat.getColor(context, R.color.color_urgent_red))
                    txtTimelineUrgency.visibility = View.VISIBLE
                }
                UrgencyLevel.URGENT -> {
                    txtTimelineUrgency.text = "Urgent"
                    txtTimelineUrgency.setBackgroundResource(R.drawable.bg_urgency_red)
                    txtTimelineUrgency.setTextColor(ContextCompat.getColor(context, R.color.color_urgent_red))
                    txtTimelineUrgency.visibility = View.VISIBLE
                }
                UrgencyLevel.ATTENTION_NEEDED -> {
                    txtTimelineUrgency.text = "Attention"
                    txtTimelineUrgency.setBackgroundResource(R.drawable.bg_urgency_orange)
                    txtTimelineUrgency.setTextColor(ContextCompat.getColor(context, R.color.color_attention_orange))
                    txtTimelineUrgency.visibility = View.VISIBLE
                }
                UrgencyLevel.ON_TRACK -> {
                    txtTimelineUrgency.text = "On Track"
                    txtTimelineUrgency.setBackgroundResource(R.drawable.bg_urgency_green)
                    txtTimelineUrgency.setTextColor(ContextCompat.getColor(context, R.color.color_on_track_green))
                    txtTimelineUrgency.visibility = View.VISIBLE
                }
                else -> {
                    txtTimelineUrgency.visibility = View.GONE
                }
            }

            itemView.setOnClickListener {
                onTaskClicked(task)
            }
        }
    }

    class TimelineDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem == newItem
    }
}
