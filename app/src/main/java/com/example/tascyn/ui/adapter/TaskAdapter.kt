package com.example.tascyn.ui.adapter

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.*
import com.example.tascyn.domain.NotionFormulas
import com.example.tascyn.ui.components.InterlockingGeometryView

class TaskAdapter(
    private val onTaskClicked: (Task) -> Unit,
    private val onTaskCheckToggled: (Task) -> Unit,
    private val onQuickStartSession: (Task) -> Unit,
    private val onTaskOptions: (Task, View) -> Unit
) : ListAdapter<Task, TaskAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task_precision, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnTaskCheck: FrameLayout = itemView.findViewById(R.id.btnTaskCheck)
        private val imgCheckCircle: ImageView = itemView.findViewById(R.id.imgCheckCircle)
        private val viewInterlockingCheck: InterlockingGeometryView = itemView.findViewById(R.id.viewInterlockingCheck)
        private val txtTaskTitle: TextView = itemView.findViewById(R.id.txtTaskTitle)
        private val txtTaskComment: TextView = itemView.findViewById(R.id.txtTaskComment)
        private val txtQuadrantBadge: TextView = itemView.findViewById(R.id.txtQuadrantBadge)
        private val txtTimeLeftBadge: TextView = itemView.findViewById(R.id.txtTimeLeftBadge)
        private val txtPriorityBadge: TextView = itemView.findViewById(R.id.txtPriorityBadge)
        private val txtTotalDuration: TextView = itemView.findViewById(R.id.txtTotalDuration)
        private val txtSubtasksCount: TextView = itemView.findViewById(R.id.txtSubtasksCount)
        private val txtTaskTypes: TextView = itemView.findViewById(R.id.txtTaskTypes)
        private val btnQuickStartSession: ImageView = itemView.findViewById(R.id.btnQuickStartSession)
        private val btnTaskOptions: ImageView = itemView.findViewById(R.id.btnTaskOptions)

        fun bind(task: Task) {
            val context = itemView.context
            val repo = TaskManagerRepository.get()
            val allTasks = repo.getAllTasks()
            val allSessions = repo.getAllSessions()

            // Title & Strikethrough if done
            txtTaskTitle.text = task.title
            if (task.isCompleted) {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                txtTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.color_steel))
                imgCheckCircle.setImageResource(R.drawable.ic_precision_check)
                imgCheckCircle.setColorFilter(ContextCompat.getColor(context, R.color.color_success))
            } else {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                txtTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.color_void))
                imgCheckCircle.setImageResource(R.drawable.ic_precision_circle)
                imgCheckCircle.setColorFilter(ContextCompat.getColor(context, R.color.color_steel))
            }

            // Comment
            if (task.comment.isNotBlank()) {
                txtTaskComment.text = task.comment
                txtTaskComment.visibility = View.VISIBLE
            } else {
                txtTaskComment.visibility = View.GONE
            }

            // Formula 2.3: Quadrant Matrix
            val qResult = NotionFormulas.calculateQuadrant(task)
            if (qResult.isValid) {
                txtQuadrantBadge.text = "Q${qResult.qNumber} · ${qResult.action}"
                txtQuadrantBadge.visibility = View.VISIBLE
            } else {
                txtQuadrantBadge.visibility = View.GONE
            }

            // Formula 2.2: Time Left & Urgency Level
            val timeLeftResult = NotionFormulas.calculateTimeLeft(task)
            when (timeLeftResult.urgencyLevel) {
                UrgencyLevel.OVERDUE -> {
                    txtTimeLeftBadge.text = "Overdue"
                    txtTimeLeftBadge.setBackgroundResource(R.drawable.bg_urgency_red)
                    txtTimeLeftBadge.setTextColor(ContextCompat.getColor(context, R.color.color_urgent_red))
                    txtTimeLeftBadge.visibility = View.VISIBLE
                }
                UrgencyLevel.URGENT -> {
                    txtTimeLeftBadge.text = "Urgent · ${timeLeftResult.formattedTime}"
                    txtTimeLeftBadge.setBackgroundResource(R.drawable.bg_urgency_red)
                    txtTimeLeftBadge.setTextColor(ContextCompat.getColor(context, R.color.color_urgent_red))
                    txtTimeLeftBadge.visibility = View.VISIBLE
                }
                UrgencyLevel.ATTENTION_NEEDED -> {
                    txtTimeLeftBadge.text = "Attention · ${timeLeftResult.formattedTime}"
                    txtTimeLeftBadge.setBackgroundResource(R.drawable.bg_urgency_orange)
                    txtTimeLeftBadge.setTextColor(ContextCompat.getColor(context, R.color.color_attention_orange))
                    txtTimeLeftBadge.visibility = View.VISIBLE
                }
                UrgencyLevel.ON_TRACK -> {
                    txtTimeLeftBadge.text = "On Track · ${timeLeftResult.formattedTime}"
                    txtTimeLeftBadge.setBackgroundResource(R.drawable.bg_urgency_green)
                    txtTimeLeftBadge.setTextColor(ContextCompat.getColor(context, R.color.color_on_track_green))
                    txtTimeLeftBadge.visibility = View.VISIBLE
                }
                UrgencyLevel.NORMAL_TIME_LEFT -> {
                    txtTimeLeftBadge.text = timeLeftResult.formattedTime
                    txtTimeLeftBadge.setBackgroundResource(R.drawable.bg_urgency_neutral)
                    txtTimeLeftBadge.setTextColor(ContextCompat.getColor(context, R.color.color_carbon))
                    txtTimeLeftBadge.visibility = View.VISIBLE
                }
                UrgencyLevel.NO_DUE_DATE -> {
                    txtTimeLeftBadge.visibility = View.GONE
                }
            }

            // Priority Badge
            txtPriorityBadge.text = task.priority.name

            // Formula 2.1: Total Duration (Task + Subtasks)
            val durationText = NotionFormulas.calculateTotalDuration(task, allTasks, allSessions)
            txtTotalDuration.text = if (durationText == "No sessions") "0m total" else "$durationText total"

            // Subtasks Count
            val subTasks = repo.getSubTasks(task.id)
            if (subTasks.isNotEmpty()) {
                val doneCount = subTasks.count { it.isCompleted }
                txtSubtasksCount.text = "$doneCount/${subTasks.size} sub-tasks"
                txtSubtasksCount.visibility = View.VISIBLE
            } else {
                txtSubtasksCount.visibility = View.GONE
            }

            // Task Types
            if (task.taskTypes.isNotEmpty()) {
                txtTaskTypes.text = task.taskTypes.joinToString(" · ") { it.displayName }
                txtTaskTypes.visibility = View.VISIBLE
            } else {
                txtTaskTypes.visibility = View.GONE
            }

            // Interlocking completion click action
            btnTaskCheck.setOnClickListener {
                if (!task.isCompleted) {
                    imgCheckCircle.visibility = View.GONE
                    viewInterlockingCheck.visibility = View.VISIBLE
                    viewInterlockingCheck.startCompletionAnimation {
                        onTaskCheckToggled(task)
                    }
                } else {
                    viewInterlockingCheck.visibility = View.GONE
                    imgCheckCircle.visibility = View.VISIBLE
                    onTaskCheckToggled(task)
                }
            }

            // Card click -> Open detail modal
            itemView.setOnClickListener {
                onTaskClicked(task)
            }

            // Quick start session
            btnQuickStartSession.setOnClickListener {
                onQuickStartSession(task)
            }

            // More options
            btnTaskOptions.setOnClickListener {
                onTaskOptions(task, it)
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem == newItem
    }
}
