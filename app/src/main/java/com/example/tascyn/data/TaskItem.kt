package com.example.tascyn.data

data class TaskItem(
    val id: String,
    var title: String,
    var schedule: String,
    var project: String,
    var isCompleted: Boolean = false,
    var isCreatedByAi: Boolean = false,
    var priority: Priority = Priority.NORMAL
) {
    enum class Priority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }
}
