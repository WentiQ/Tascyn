package com.example.tascyn.ui.adapter

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.UrgencyLevel
import com.example.tascyn.domain.NotionFormulas

enum class TaskUrgencyCategory {
    NOW,
    NEXT,
    LATER
}

class TodaySectionTaskAdapter(
    private val category: TaskUrgencyCategory,
    private val onTaskClicked: (Task) -> Unit,
    private val onTaskCheckToggled: (Task) -> Unit,
    private val onStartSession: (Task) -> Unit
) : ListAdapter<Task, TodaySectionTaskAdapter.TodayTaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TodayTaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_today_task, parent, false)
        return TodayTaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TodayTaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TodayTaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutCardRoot: LinearLayout = itemView.findViewById(R.id.layoutTaskCardRoot)
        private val imgTaskCheckbox: ImageView = itemView.findViewById(R.id.imgTaskCheckbox)
        private val viewStatusDot: View = itemView.findViewById(R.id.viewStatusDot)
        private val txtTaskTitle: TextView = itemView.findViewById(R.id.txtTaskTitle)
        private val btnStartTask: LinearLayout = itemView.findViewById(R.id.btnStartTask)
        private val txtStartPlayIcon: TextView = itemView.findViewById(R.id.txtStartPlayIcon)
        private val txtStartLabel: TextView = itemView.findViewById(R.id.txtStartLabel)
        private val txtTaskFormulaMetadata: TextView = itemView.findViewById(R.id.txtTaskFormulaMetadata)

        fun bind(task: Task) {
            txtTaskTitle.text = task.title

            // Completion strikethrough & checkbox
            if (task.isCompleted) {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                txtTaskTitle.setTextColor(Color.parseColor("#9CA3AF"))
                imgTaskCheckbox.setImageResource(R.drawable.ic_precision_check)
                imgTaskCheckbox.setColorFilter(Color.parseColor("#10B981"))
            } else {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                txtTaskTitle.setTextColor(Color.parseColor("#0E0E10"))
                imgTaskCheckbox.setImageResource(R.drawable.ic_precision_circle)
                imgTaskCheckbox.setColorFilter(Color.parseColor("#9CA3AF"))
            }

            val qResult = NotionFormulas.calculateQuadrant(task)
            val tlResult = NotionFormulas.calculateTimeLeft(task)

            // Dynamic Styling based on Section Category
            when (category) {
                TaskUrgencyCategory.NOW -> {
                    layoutCardRoot.setBackgroundResource(R.drawable.bg_task_now_card)
                    setDotColor("#EF4444")
                    btnStartTask.setBackgroundResource(R.drawable.bg_btn_start_urgent)
                    txtStartPlayIcon.setTextColor(Color.parseColor("#DC2626"))
                    txtStartLabel.setTextColor(Color.parseColor("#DC2626"))
                    txtTaskFormulaMetadata.setTextColor(Color.parseColor("#EF4444"))

                    val statusLabel = if (tlResult.urgencyLevel == UrgencyLevel.OVERDUE) "Overdue · Overdue" else "Urgent"
                    val timeStr = if (tlResult.formattedTime.isNotBlank()) tlResult.formattedTime else "Due Soon"
                    val qLabel = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else ""
                    txtTaskFormulaMetadata.text = "$statusLabel · $timeStr · $qLabel"
                }

                TaskUrgencyCategory.NEXT -> {
                    layoutCardRoot.setBackgroundResource(R.drawable.bg_task_next_card)
                    setDotColor("#F59E0B")
                    btnStartTask.setBackgroundResource(R.drawable.bg_btn_start_neutral)
                    txtStartPlayIcon.setTextColor(Color.parseColor("#1F2937"))
                    txtStartLabel.setTextColor(Color.parseColor("#1F2937"))
                    txtTaskFormulaMetadata.setTextColor(Color.parseColor("#D97706"))

                    val statusLabel = "Attention Needed"
                    val timeStr = if (tlResult.formattedTime.isNotBlank()) "${tlResult.formattedTime} left" else "Tomorrow"
                    val qLabel = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else ""
                    txtTaskFormulaMetadata.text = "$statusLabel · $timeStr · $qLabel"
                }

                TaskUrgencyCategory.LATER -> {
                    layoutCardRoot.setBackgroundResource(R.drawable.bg_task_next_card)
                    setDotColor("#3B82F6")
                    btnStartTask.setBackgroundResource(R.drawable.bg_btn_start_neutral)
                    txtStartPlayIcon.setTextColor(Color.parseColor("#1F2937"))
                    txtStartLabel.setTextColor(Color.parseColor("#1F2937"))
                    txtTaskFormulaMetadata.setTextColor(Color.parseColor("#6B7280"))

                    val statusLabel = if (tlResult.urgencyLevel == UrgencyLevel.ON_TRACK) "On Track" else "Scheduled"
                    val timeStr = if (tlResult.formattedTime.isNotBlank()) tlResult.formattedTime else "Upcoming"
                    val qLabel = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else ""
                    txtTaskFormulaMetadata.text = "$statusLabel · $timeStr · $qLabel"
                }
            }

            imgTaskCheckbox.setOnClickListener {
                onTaskCheckToggled(task)
            }

            btnStartTask.setOnClickListener {
                onStartSession(task)
            }

            itemView.setOnClickListener {
                onTaskClicked(task)
            }
        }

        private fun setDotColor(colorHex: String) {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            viewStatusDot.background = drawable
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem == newItem
    }
}
