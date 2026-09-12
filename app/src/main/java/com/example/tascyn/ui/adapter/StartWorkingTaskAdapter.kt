package com.example.tascyn.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.domain.NotionFormulas

class StartWorkingTaskAdapter(
    private val onTaskClicked: (Task) -> Unit,
    private val onStartWorking: (Task) -> Unit
) : ListAdapter<Task, StartWorkingTaskAdapter.StartTaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StartTaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session_start_task, parent, false)
        return StartTaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: StartTaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StartTaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtTitle: TextView = itemView.findViewById(R.id.txtStartTaskTitle)
        private val txtSubtitle: TextView = itemView.findViewById(R.id.txtStartTaskSubtitle)
        private val btnStart: View = itemView.findViewById(R.id.btnStartTaskAction)

        fun bind(task: Task) {
            txtTitle.text = task.title

            val qResult = NotionFormulas.calculateQuadrant(task)
            val minMinutes = NotionFormulas.parseMinimumTimeToMinutes(task.minimumTimeRequired)
            val minStr = if (minMinutes >= 60) {
                val h = minMinutes / 60
                val m = minMinutes % 60
                if (m > 0) "${h}h ${m}m" else "${h}h"
            } else {
                "${minMinutes}m"
            }

            val qPart = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else "Unscheduled"
            txtSubtitle.text = "$qPart · Min: $minStr"

            btnStart.setOnClickListener {
                onStartWorking(task)
            }

            itemView.setOnClickListener {
                onTaskClicked(task)
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean = oldItem == newItem
    }
}
