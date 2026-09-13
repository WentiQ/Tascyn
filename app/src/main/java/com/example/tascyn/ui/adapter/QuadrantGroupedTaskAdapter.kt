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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.UrgencyLevel
import com.example.tascyn.domain.NotionFormulas

abstract class QuadrantListItem {
    data class Header(val title: String, val colorHex: String) : QuadrantListItem()
    data class TaskCard(
        val task: Task,
        val category: TaskUrgencyCategory,
        val status: com.example.tascyn.data.TaskStatus = task.status
    ) : QuadrantListItem()
}

class QuadrantGroupedTaskAdapter(
    private val onTaskClicked: (Task) -> Unit,
    private val onTaskCheckToggled: (Task) -> Unit,
    private val onStartSession: (Task) -> Unit
) : ListAdapter<QuadrantListItem, RecyclerView.ViewHolder>(QuadrantDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is QuadrantListItem.Header -> TYPE_HEADER
            is QuadrantListItem.TaskCard -> TYPE_TASK
            else -> TYPE_TASK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_quadrant_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_today_task, parent, false)
            TaskCardViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is QuadrantListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is QuadrantListItem.TaskCard -> (holder as TaskCardViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewQuadrantDot: View = itemView.findViewById(R.id.viewQuadrantDot)
        private val txtQuadrantHeaderTitle: TextView = itemView.findViewById(R.id.txtQuadrantHeaderTitle)

        fun bind(header: QuadrantListItem.Header) {
            txtQuadrantHeaderTitle.text = header.title
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(header.colorHex))
            }
            viewQuadrantDot.background = drawable
        }
    }

    inner class TaskCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutCardRoot: LinearLayout = itemView.findViewById(R.id.layoutTaskCardRoot)
        val viewTaskCheckbox: com.example.tascyn.ui.components.TaskCheckboxPulseView = itemView.findViewById(R.id.viewTaskCheckbox)
        private val viewStatusDot: View = itemView.findViewById(R.id.viewStatusDot)
        val txtTaskTitle: TextView = itemView.findViewById(R.id.txtTaskTitle)
        private val btnStartTask: LinearLayout = itemView.findViewById(R.id.btnStartTask)
        private val txtStartPlayIcon: TextView = itemView.findViewById(R.id.txtStartPlayIcon)
        private val txtStartLabel: TextView = itemView.findViewById(R.id.txtStartLabel)
        private val txtTaskFormulaMetadata: TextView = itemView.findViewById(R.id.txtTaskFormulaMetadata)

        fun bind(taskItem: QuadrantListItem.TaskCard) {
            val task = taskItem.task
            val category = taskItem.category

            txtTaskTitle.text = task.title
            viewTaskCheckbox.setState(task)

            if (task.isCompleted) {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                txtTaskTitle.setTextColor(Color.parseColor("#9CA3AF"))
                layoutCardRoot.setBackgroundResource(R.drawable.bg_task_next_card)
                btnStartTask.visibility = View.GONE
                txtTaskFormulaMetadata.visibility = View.GONE
                viewStatusDot.visibility = View.GONE
            } else {
                txtTaskTitle.paintFlags = txtTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                txtTaskTitle.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.color_text_primary))
                btnStartTask.visibility = View.VISIBLE
                txtTaskFormulaMetadata.visibility = View.VISIBLE
                viewStatusDot.visibility = View.VISIBLE

                val qResult = NotionFormulas.calculateQuadrant(task)
                val tlResult = NotionFormulas.calculateTimeLeft(task)

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
                        txtStartPlayIcon.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.color_text_primary))
                        txtStartLabel.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.color_text_primary))
                        txtTaskFormulaMetadata.setTextColor(Color.parseColor("#D97706"))

                        val statusLabel = "Attention Needed"
                        val timeStr = if (tlResult.formattedTime.isNotBlank()) "${tlResult.formattedTime} left" else "Tomorrow"
                        val qLabel = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else ""
                        txtTaskFormulaMetadata.text = "$statusLabel · $timeStr · $qLabel"
                    }

                    TaskUrgencyCategory.LATER -> {
                        layoutCardRoot.setBackgroundResource(R.drawable.bg_task_next_card)
                        val dotColor = if (tlResult.urgencyLevel == UrgencyLevel.ON_TRACK) "#10B981" else "#3B82F6"
                        setDotColor(dotColor)
                        btnStartTask.setBackgroundResource(R.drawable.bg_btn_start_neutral)
                        txtStartPlayIcon.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.color_text_primary))
                        txtStartLabel.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.color_text_primary))
                        txtTaskFormulaMetadata.setTextColor(Color.parseColor("#059669"))

                        val statusLabel = if (tlResult.urgencyLevel == UrgencyLevel.ON_TRACK) "On Track" else "Scheduled"
                        val timeStr = if (tlResult.formattedTime.isNotBlank()) tlResult.formattedTime else "No deadline"
                        val qLabel = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else ""
                        txtTaskFormulaMetadata.text = "$statusLabel · $timeStr · $qLabel"
                    }
                }
            }

            viewTaskCheckbox.setOnClickListener {
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

    class QuadrantDiffCallback : DiffUtil.ItemCallback<QuadrantListItem>() {
        override fun areItemsTheSame(oldItem: QuadrantListItem, newItem: QuadrantListItem): Boolean {
            return when {
                oldItem is QuadrantListItem.Header && newItem is QuadrantListItem.Header -> oldItem.title == newItem.title
                oldItem is QuadrantListItem.TaskCard && newItem is QuadrantListItem.TaskCard -> oldItem.task.id == newItem.task.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: QuadrantListItem, newItem: QuadrantListItem): Boolean {
            return when {
                oldItem is QuadrantListItem.Header && newItem is QuadrantListItem.Header -> oldItem == newItem
                oldItem is QuadrantListItem.TaskCard && newItem is QuadrantListItem.TaskCard ->
                    oldItem.category == newItem.category &&
                    oldItem.status == newItem.status &&
                    oldItem.task.title == newItem.task.title &&
                    oldItem.task.dueDate == newItem.task.dueDate &&
                    oldItem.task.remainderDate == newItem.task.remainderDate &&
                    oldItem.task.taskTypes == newItem.task.taskTypes &&
                    oldItem.task.priority == newItem.task.priority
                else -> false
            }
        }
    }
}
