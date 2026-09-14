package com.example.tascyn.domain

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.tascyn.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class ParsedTaskGroup(
    val task: Task,
    val subtasks: List<Task> = emptyList()
)

data class TaskParseResult(
    val success: Boolean,
    val taskGroups: List<ParsedTaskGroup> = emptyList(),
    val error: String? = null
) {
    // Backwards-compatible properties
    val task: Task?
        get() = taskGroups.firstOrNull()?.task

    val subtasks: List<Task>
        get() = taskGroups.flatMap { it.subtasks }

    val allTasks: List<Task>
        get() = taskGroups.flatMap { listOf(it.task) + it.subtasks }
}

object GeminiAiService {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun testApiKey(apiKey: String, model: String = "gemini-2.5-flash", onResult: (success: Boolean, message: String) -> Unit) {
        val cleanKey = apiKey.trim()
        val cleanModel = model.trim().ifBlank { "gemini-2.5-flash" }
        if (cleanKey.isBlank()) {
            onResult(false, "API Key cannot be empty.")
            return
        }

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$cleanKey")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-goog-api-key", cleanKey)
                    useCaches = false
                    instanceFollowRedirects = true
                    doOutput = true
                    connectTimeout = 25000
                    readTimeout = 25000
                }

                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray().apply {
                        val contentObj = JSONObject().apply {
                            val partsArray = JSONArray().apply {
                                put(JSONObject().put("text", "Respond with exact word: OK"))
                            }
                            put("parts", partsArray)
                        }
                        put(contentObj)
                    }
                    put("contents", contentsArray)
                }

                val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bodyBytes.size)
                conn.setRequestProperty("Content-Length", bodyBytes.size.toString())

                conn.outputStream.use { os ->
                    os.write(bodyBytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    mainHandler.post { onResult(true, "Gemini API Key is valid and model '$cleanModel' is working!") }
                } else {
                    val errorStream = conn.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream)).readText()
                    } else "HTTP Error $responseCode"
                    
                    val parsedMsg = try {
                        val errObj = JSONObject(errorMsg)
                        errObj.optJSONObject("error")?.optString("message") ?: errorMsg
                    } catch (e: Exception) {
                        errorMsg
                    }

                    val userFriendlyMsg = when {
                        parsedMsg.contains("API_KEY_INVALID", ignoreCase = true) || parsedMsg.contains("API key not valid", ignoreCase = true) ->
                            "Invalid API Key: Please verify your Gemini API key."
                        parsedMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || responseCode == 429 ->
                            "API Quota Exceeded (429): Free tier quota reached for '$cleanModel'. Try gemini-1.5-flash."
                        parsedMsg.contains("not found", ignoreCase = true) || responseCode == 404 ->
                            "Model '$cleanModel' not found (404). Please choose gemini-1.5-flash or gemini-2.0-flash."
                        else -> "Test failed ($responseCode): $parsedMsg"
                    }

                    mainHandler.post { onResult(false, userFriendlyMsg) }
                }
            } catch (e: Exception) {
                val errorMsg = when (e) {
                    is java.net.SocketTimeoutException ->
                        "Connection timed out. Please check your internet connection or try model gemini-1.5-flash."
                    is java.net.UnknownHostException ->
                        "Network error: Unable to reach Google Gemini API. Please check your internet/Wi-Fi connection."
                    else -> "Connection error: ${e.localizedMessage ?: "Unknown error"}"
                }
                mainHandler.post { onResult(false, errorMsg) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun parseTask(prompt: String, context: Context, onResult: (TaskParseResult) -> Unit) {
        val settings = AppSettingsManager.getInstance(context)
        val apiKey = settings.geminiApiKey.trim()

        if (!settings.hasValidGeminiKey()) {
            mainHandler.post {
                onResult(
                    TaskParseResult(
                        success = false,
                        taskGroups = emptyList(),
                        error = "Gemini API key not found. Please enter your Gemini API key in Settings."
                    )
                )
            }
            return
        }

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val model = settings.selectedAiModel.ifBlank { "gemini-2.5-flash" }
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                    useCaches = false
                    instanceFollowRedirects = true
                    doOutput = true
                    connectTimeout = 30000
                    readTimeout = 40000
                }

                val nowCal = Calendar.getInstance()
                val dateContext = SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm", Locale.getDefault()).format(nowCal.time)
                val currentYear = nowCal.get(Calendar.YEAR)
                val currentMonth = nowCal.get(Calendar.MONTH) + 1
                val currentDay = nowCal.get(Calendar.DAY_OF_MONTH)

                val systemPrompt = """
You are an expert AI task parser for Tascyn (a Notion Task Manager system).
Current Time & Date Reference: $dateContext (Year: $currentYear, Month: $currentMonth, Day: $currentDay)

Your job: Read the user's natural language input, understand the intent, and extract ALL tasks and their subtasks into PURE JSON.
The user's input may contain ONE task, or MULTIPLE distinct tasks (e.g. a list of things to do, numbered items, separate goals, or multiple projects).
Each task may also contain one or more SUBTASKS (actionable breakdown steps, checklist items, sub-components) if mentioned or implied.

CRITICAL INSTRUCTIONS:
1. MULTIPLE TASKS:
   - If the user describes multiple things to do, create a separate task object in the "tasks" array for EACH independent task.
   - If only one task is described, return a "tasks" array with that 1 task.
   - Never merge unrelated tasks into one title.

2. SUBTASKS PER TASK:
   - If steps, sub-items, or checklist breakdown items are mentioned for a task, put them in that task's "subtasks" array.
   - If the user explicitly asks to break down a task (or mentions subtasks), generate concise actionable subtasks.
   - If a task has no subtasks, set "subtasks": [].

3. TASK PROPERTIES:
   - "title": (STRING, 3 to 7 WORDS MAXIMUM). Clean, crisp, action-oriented title summarizing what needs to be done (e.g. "Prepare Kinematics Quiz", "Submit Operating Systems Project", "Buy Weekly Groceries"). DO NOT copy entire paragraphs into the title!
   - "priority": (STRING) "HIGH", "MEDIUM", or "LOW" (Use HIGH if urgent, critical, exam, or deadline is near; otherwise MEDIUM or LOW).
   - "status": (STRING) "Not started" (or "In progress", "Procrastinated", "Left", "Done").
   - "taskTypes": (ARRAY OF STRINGS) Choose matching categories from: ["Work", "Personal", "Academic", "Project", "Skill", "Book Reading", "Spiritual", "Health", "Travel", "Lab", "Assignment", "Exam"].
   - "dueYear": (INTEGER) year e.g. $currentYear.
   - "dueMonth": (INTEGER) 1-12.
   - "dueDay": (INTEGER) 1-31.
   - "dueHour": (INTEGER 0-23) e.g. 17 for 5 PM, 18 for 6 PM, 21 for 9 PM (default 18).
   - "dueMinute": (INTEGER 0-59, default 0).
   - "minimumTimeRequired": (STRING in Notion duration format "Xd Yh Zm" e.g. "0d 1h 30m", "0d 2h 0m", "0d 0h 45m", "1d 0h 0m").
   - "comment": (STRING) Relevant background details, instructions, references, or context from the user prompt.
   - "subtasks": (ARRAY OF OBJECTS) Subtasks breakdown:
     [
       {
         "title": "Concise subtask title (string, 3-7 words)",
         "minimumTimeRequired": "0d 0h 30m"
       }
     ]

OUTPUT SCHEMA (Return ONLY valid JSON matching this schema):
{
  "tasks": [
    {
      "title": "Prepare Kinematics Quiz",
      "priority": "HIGH",
      "status": "Not started",
      "taskTypes": ["Academic", "Exam"],
      "dueYear": $currentYear,
      "dueMonth": $currentMonth,
      "dueDay": $currentDay,
      "dueHour": 18,
      "dueMinute": 0,
      "minimumTimeRequired": "0d 2h 0m",
      "comment": "Focus on 2D motion and projectile equations",
      "subtasks": [
        {
          "title": "Review lecture slides 1 to 4",
          "minimumTimeRequired": "0d 0h 45m"
        },
        {
          "title": "Solve 5 kinematics practice problems",
          "minimumTimeRequired": "0d 1h 0m"
        }
      ]
    }
  ]
}
""".trimIndent()

                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray().apply {
                        val contentObj = JSONObject().apply {
                            val partsArray = JSONArray().apply {
                                put(JSONObject().put("text", "$systemPrompt\n\nUser Task Description: \"$prompt\""))
                            }
                            put("parts", partsArray)
                        }
                        put(contentObj)
                    }
                    put("contents", contentsArray)

                    val genConfig = JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("response_mime_type", "application/json")
                        put("temperature", 0.1)
                    }
                    put("generationConfig", genConfig)
                }

                val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bodyBytes.size)
                conn.setRequestProperty("Content-Length", bodyBytes.size.toString())

                conn.outputStream.use { os ->
                    os.write(bodyBytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val responseJson = JSONObject(respText)
                    val candidates = responseJson.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")

                    // Extract text parts, ignoring internal thought/reasoning parts
                    val textBuilder = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val partObj = parts.optJSONObject(i) ?: continue
                            val isThought = partObj.optBoolean("thought", false)
                            if (!isThought) {
                                val pText = partObj.optString("text", "")
                                if (pText.isNotEmpty()) {
                                    textBuilder.append(pText)
                                }
                            }
                        }
                    }

                    var text = textBuilder.toString().trim()
                    if (text.isEmpty() && parts != null && parts.length() > 0) {
                        for (i in 0 until parts.length()) {
                            val cand = parts.optJSONObject(i)?.optString("text", "") ?: ""
                            if (cand.contains("{") || cand.contains("[")) {
                                text = cand
                                break
                            }
                        }
                        if (text.isEmpty()) {
                            text = parts.optJSONObject(0)?.optString("text", "") ?: ""
                        }
                    }

                    val cleanJsonStr = extractJsonSubstring(text)
                    val parsedResult = parseTasksFromJsonString(cleanJsonStr, prompt)
                    mainHandler.post { onResult(parsedResult) }
                } else {
                    val errStream = conn.errorStream
                    val errMsg = if (errStream != null) BufferedReader(InputStreamReader(errStream)).readText() else "HTTP $responseCode"
                    val parsedMsg = try {
                        val errObj = JSONObject(errMsg)
                        errObj.optJSONObject("error")?.optString("message") ?: errMsg
                    } catch (e: Exception) {
                        errMsg
                    }
                    val userFriendlyMsg = when {
                        parsedMsg.contains("API_KEY_INVALID", ignoreCase = true) || parsedMsg.contains("API key not valid", ignoreCase = true) ->
                            "Invalid Gemini API Key. Please verify your key in Settings."
                        parsedMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || responseCode == 429 ->
                            "Gemini API Quota Exceeded (429). Please try model gemini-1.5-flash or wait a moment."
                        parsedMsg.contains("not found", ignoreCase = true) || responseCode == 404 ->
                            "Gemini Model '$model' not found (404). Please select gemini-1.5-flash in Settings."
                        else -> "Gemini LLM error ($responseCode: $parsedMsg). No task was created."
                    }
                    mainHandler.post {
                        onResult(
                            TaskParseResult(
                                success = false,
                                taskGroups = emptyList(),
                                error = userFriendlyMsg
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMsg = when (e) {
                    is java.net.SocketTimeoutException ->
                        "Gemini request timed out. Please check your internet connection or try model gemini-1.5-flash."
                    is java.net.UnknownHostException ->
                        "Network error: Unable to reach Google Gemini API. Please check your internet connection."
                    else -> "LLM request failed: ${e.localizedMessage ?: "Network error"}. No task was created."
                }
                mainHandler.post {
                    onResult(
                        TaskParseResult(
                            success = false,
                            taskGroups = emptyList(),
                            error = errorMsg
                        )
                    )
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun extractJsonSubstring(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```json", ignoreCase = true)) {
            clean = clean.substring(7)
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3)
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length - 3)
        }
        clean = clean.trim()

        val firstBrace = clean.indexOf('{')
        val firstBracket = clean.indexOf('[')

        if (firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace)) {
            val lastBracket = clean.lastIndexOf(']')
            if (lastBracket != -1 && lastBracket > firstBracket) {
                return clean.substring(firstBracket, lastBracket + 1)
            }
        } else if (firstBrace != -1) {
            val lastBrace = clean.lastIndexOf('}')
            if (lastBrace != -1 && lastBrace > firstBrace) {
                return clean.substring(firstBrace, lastBrace + 1)
            }
        }
        return clean
    }

    fun parseTasksFromJsonString(cleanJsonStr: String, rawPrompt: String): TaskParseResult {
        val trimmed = cleanJsonStr.trim()
        val taskJsonList = mutableListOf<JSONObject>()

        try {
            if (trimmed.startsWith("[")) {
                val jsonArray = JSONArray(trimmed)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i)
                    if (obj != null) taskJsonList.add(obj)
                }
            } else {
                val rootObj = JSONObject(trimmed)
                if (rootObj.has("tasks")) {
                    val tasksArray = rootObj.optJSONArray("tasks")
                    if (tasksArray != null) {
                        for (i in 0 until tasksArray.length()) {
                            val obj = tasksArray.optJSONObject(i)
                            if (obj != null) taskJsonList.add(obj)
                        }
                    }
                } else {
                    // Single task object
                    taskJsonList.add(rootObj)
                }
            }
        } catch (e: Exception) {
            return TaskParseResult(
                success = false,
                taskGroups = emptyList(),
                error = "Failed to parse LLM JSON: ${e.localizedMessage}"
            )
        }

        if (taskJsonList.isEmpty()) {
            return TaskParseResult(
                success = false,
                taskGroups = emptyList(),
                error = "No valid tasks found in LLM response."
            )
        }

        val groups = mutableListOf<ParsedTaskGroup>()
        val baseTime = System.currentTimeMillis()

        for ((taskIndex, taskJson) in taskJsonList.withIndex()) {
            val group = parseSingleTaskGroup(taskJson, rawPrompt, baseTime, taskIndex)
            groups.add(group)
        }

        return TaskParseResult(
            success = true,
            taskGroups = groups
        )
    }

    private fun parseSingleTaskGroup(json: JSONObject, rawPrompt: String, baseTime: Long, taskIndex: Int): ParsedTaskGroup {
        var title = json.optString("title", "").trim()
        if (title.isBlank()) {
            title = generateConciseTitleFromPrompt(rawPrompt)
        }

        val priorityStr = json.optString("priority", "MEDIUM")
        val priority = TaskPriority.fromString(priorityStr)

        val statusStr = json.optString("status", "Not started")
        val status = TaskStatus.fromString(statusStr)

        val taskTypes = mutableSetOf<TaskType>()
        val typesArray = json.optJSONArray("taskTypes")
        if (typesArray != null) {
            for (i in 0 until typesArray.length()) {
                val tStr = typesArray.optString(i)
                val typeEnum = TaskType.fromString(tStr) ?: TaskType.values().find { it.name.equals(tStr, ignoreCase = true) }
                if (typeEnum != null) taskTypes.add(typeEnum)
            }
        }
        if (taskTypes.isEmpty()) taskTypes.add(TaskType.WORK)

        val nowCal = Calendar.getInstance()
        val dueYear = json.optInt("dueYear", nowCal.get(Calendar.YEAR))
        val dueMonth = json.optInt("dueMonth", nowCal.get(Calendar.MONTH) + 1)
        val dueDay = json.optInt("dueDay", nowCal.get(Calendar.DAY_OF_MONTH) + 1)
        val dueHour = json.optInt("dueHour", 18)
        val dueMinute = json.optInt("dueMinute", 0)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, dueYear)
            set(Calendar.MONTH, dueMonth - 1)
            set(Calendar.DAY_OF_MONTH, dueDay)
            set(Calendar.HOUR_OF_DAY, dueHour)
            set(Calendar.MINUTE, dueMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dueDateMillis = cal.timeInMillis

        val minTime = json.optString("minimumTimeRequired", "0d 1h 0m").ifBlank { "0d 1h 0m" }
        val comment = json.optString("comment", "").trim()

        val mainTaskId = "task_ai_${baseTime}_${taskIndex}_${UUID.randomUUID().toString().take(6)}"
        val mainTask = Task(
            id = mainTaskId,
            title = title,
            status = status,
            priority = priority,
            taskTypes = taskTypes,
            minimumTimeRequired = minTime,
            dueDate = dueDateMillis,
            remainderDate = dueDateMillis,
            comment = comment,
            parentTaskId = null
        )

        val subtasks = mutableListOf<Task>()
        val subtasksArray = json.optJSONArray("subtasks")
        if (subtasksArray != null) {
            for (subIndex in 0 until subtasksArray.length()) {
                val subObj = subtasksArray.optJSONObject(subIndex)
                val subTitle: String
                val subMinTime: String

                if (subObj != null) {
                    subTitle = subObj.optString("title", "").trim()
                    subMinTime = subObj.optString("minimumTimeRequired", "0d 0h 30m").ifBlank { "0d 0h 30m" }
                } else {
                    // LLM might have returned a raw string array: ["Subtask 1", "Subtask 2"]
                    subTitle = subtasksArray.optString(subIndex, "").trim()
                    subMinTime = "0d 0h 30m"
                }

                if (subTitle.isNotBlank()) {
                    val subId = "sub_ai_${baseTime}_${taskIndex}_${subIndex}_${UUID.randomUUID().toString().take(6)}"
                    subtasks.add(
                        Task(
                            id = subId,
                            title = subTitle,
                            status = TaskStatus.NOT_STARTED,
                            priority = priority,
                            taskTypes = taskTypes,
                            minimumTimeRequired = subMinTime,
                            dueDate = dueDateMillis,
                            remainderDate = dueDateMillis,
                            parentTaskId = mainTaskId
                        )
                    )
                }
            }
        }

        return ParsedTaskGroup(
            task = mainTask,
            subtasks = subtasks
        )
    }

    fun parseWithLocalHeuristics(prompt: String): TaskParseResult {
        val lines = prompt.split("\n", ";").map { it.trim() }.filter { it.isNotBlank() }
        val baseTime = System.currentTimeMillis()

        // Check if user entered multiple numbered/bulleted items
        val candidateItems = mutableListOf<String>()
        val numberRegex = Regex("^(\\d+[.)]|[-*•])\\s*(.+)$")

        for (line in lines) {
            val match = numberRegex.find(line)
            if (match != null) {
                candidateItems.add(match.groupValues[2].trim())
            } else if (candidateItems.isNotEmpty()) {
                candidateItems[candidateItems.size - 1] += " " + line
            } else {
                candidateItems.add(line)
            }
        }

        val itemsToProcess = if (candidateItems.size > 1) candidateItems else listOf(prompt)
        val groups = mutableListOf<ParsedTaskGroup>()

        for ((idx, itemText) in itemsToProcess.withIndex()) {
            val lower = itemText.lowercase()

            val priority = when {
                lower.contains("high priority") || lower.contains("urgent") || lower.contains("critical") || lower.contains("asap") -> TaskPriority.HIGH
                lower.contains("low priority") || lower.contains("trivial") || lower.contains("optional") -> TaskPriority.LOW
                else -> TaskPriority.MEDIUM
            }

            val cal = Calendar.getInstance()
            when {
                lower.contains("tomorrow") -> cal.add(Calendar.DAY_OF_YEAR, 1)
                lower.contains("today") || lower.contains("tonight") -> { /* today */ }
                lower.contains("monday") -> setNextDayOfWeek(cal, Calendar.MONDAY)
                lower.contains("tuesday") -> setNextDayOfWeek(cal, Calendar.TUESDAY)
                lower.contains("wednesday") -> setNextDayOfWeek(cal, Calendar.WEDNESDAY)
                lower.contains("thursday") -> setNextDayOfWeek(cal, Calendar.THURSDAY)
                lower.contains("friday") -> setNextDayOfWeek(cal, Calendar.FRIDAY)
                lower.contains("saturday") -> setNextDayOfWeek(cal, Calendar.SATURDAY)
                lower.contains("sunday") -> setNextDayOfWeek(cal, Calendar.SUNDAY)
                lower.contains("next week") -> cal.add(Calendar.DAY_OF_YEAR, 7)
                else -> cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            when {
                lower.contains("9am") || lower.contains("9:00 am") -> { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0) }
                lower.contains("10am") || lower.contains("10:00 am") -> { cal.set(Calendar.HOUR_OF_DAY, 10); cal.set(Calendar.MINUTE, 0) }
                lower.contains("11am") || lower.contains("11:00 am") -> { cal.set(Calendar.HOUR_OF_DAY, 11); cal.set(Calendar.MINUTE, 0) }
                lower.contains("12pm") || lower.contains("noon") -> { cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0) }
                lower.contains("1pm") || lower.contains("13:00") -> { cal.set(Calendar.HOUR_OF_DAY, 13); cal.set(Calendar.MINUTE, 0) }
                lower.contains("2pm") || lower.contains("14:00") -> { cal.set(Calendar.HOUR_OF_DAY, 14); cal.set(Calendar.MINUTE, 0) }
                lower.contains("3pm") || lower.contains("15:00") -> { cal.set(Calendar.HOUR_OF_DAY, 15); cal.set(Calendar.MINUTE, 0) }
                lower.contains("4pm") || lower.contains("16:00") -> { cal.set(Calendar.HOUR_OF_DAY, 16); cal.set(Calendar.MINUTE, 0) }
                lower.contains("5pm") || lower.contains("5 pm") || lower.contains("17:00") -> { cal.set(Calendar.HOUR_OF_DAY, 17); cal.set(Calendar.MINUTE, 0) }
                lower.contains("6pm") || lower.contains("6 pm") || lower.contains("18:00") -> { cal.set(Calendar.HOUR_OF_DAY, 18); cal.set(Calendar.MINUTE, 0) }
                lower.contains("7pm") || lower.contains("7 pm") || lower.contains("19:00") -> { cal.set(Calendar.HOUR_OF_DAY, 19); cal.set(Calendar.MINUTE, 0) }
                lower.contains("8pm") || lower.contains("8 pm") || lower.contains("20:00") -> { cal.set(Calendar.HOUR_OF_DAY, 20); cal.set(Calendar.MINUTE, 0) }
                lower.contains("9pm") || lower.contains("9 pm") || lower.contains("21:00") -> { cal.set(Calendar.HOUR_OF_DAY, 21); cal.set(Calendar.MINUTE, 0) }
                lower.contains("11:59pm") || lower.contains("midnight") -> { cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59) }
                else -> { cal.set(Calendar.HOUR_OF_DAY, 18); cal.set(Calendar.MINUTE, 0) }
            }
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dueDate = cal.timeInMillis

            val minTime = when {
                lower.contains("4h") || lower.contains("4 hours") -> "0d 4h 0m"
                lower.contains("3h") || lower.contains("3 hours") -> "0d 3h 0m"
                lower.contains("2h") || lower.contains("2 hours") -> "0d 2h 0m"
                lower.contains("1.5h") || lower.contains("90m") || lower.contains("90 min") -> "0d 1h 30m"
                lower.contains("1h") || lower.contains("1 hour") -> "0d 1h 0m"
                lower.contains("45m") || lower.contains("45 min") -> "0d 0h 45m"
                lower.contains("30m") || lower.contains("30 min") -> "0d 0h 30m"
                else -> "0d 1h 0m"
            }

            val types = mutableSetOf<TaskType>()
            if (lower.contains("academic") || lower.contains("thesis") || lower.contains("paper") || lower.contains("study")) types.add(TaskType.ACADEMIC)
            if (lower.contains("exam") || lower.contains("quiz") || lower.contains("midterm") || lower.contains("final")) types.add(TaskType.EXAM)
            if (lower.contains("lab") || lower.contains("experiment")) types.add(TaskType.LAB)
            if (lower.contains("assignment") || lower.contains("homework") || lower.contains("hw")) types.add(TaskType.ASSIGNMENT)
            if (lower.contains("reading") || lower.contains("book") || lower.contains("chapter")) types.add(TaskType.BOOK_READING)
            if (lower.contains("project") || lower.contains("code") || lower.contains("build") || lower.contains("develop")) types.add(TaskType.PROJECT)
            if (lower.contains("health") || lower.contains("gym") || lower.contains("workout") || lower.contains("med")) types.add(TaskType.HEALTH)
            if (lower.contains("skill") || lower.contains("practice") || lower.contains("learn")) types.add(TaskType.SKILL)
            if (lower.contains("personal")) types.add(TaskType.PERSONAL)
            if (types.isEmpty()) types.add(TaskType.WORK)

            val title = generateConciseTitleFromPrompt(itemText)

            val mainTaskId = "task_heuristic_${baseTime}_${idx}_${UUID.randomUUID().toString().take(6)}"
            val mainTask = Task(
                id = mainTaskId,
                title = title,
                status = TaskStatus.NOT_STARTED,
                priority = priority,
                taskTypes = types,
                minimumTimeRequired = minTime,
                dueDate = dueDate,
                remainderDate = dueDate,
                comment = itemText.trim(),
                parentTaskId = null
            )

            // Extract heuristic subtasks if user wrote "subtasks:" or "steps:"
            val subtasks = mutableListOf<Task>()
            val subtaskMarker = when {
                lower.contains("subtasks:") -> "subtasks:"
                lower.contains("steps:") -> "steps:"
                else -> null
            }

            if (subtaskMarker != null) {
                val subPart = itemText.substring(itemText.indexOf(subtaskMarker, ignoreCase = true) + subtaskMarker.length)
                val subItems = subPart.split(",", " and ", ";").map { it.trim() }.filter { it.isNotBlank() }
                for ((sIdx, sTitle) in subItems.withIndex()) {
                    val cleanSubTitle = sTitle.replace(Regex("^[-*•\\d+.)\\s]+"), "").trim()
                    if (cleanSubTitle.isNotBlank()) {
                        subtasks.add(
                            Task(
                                id = "sub_heuristic_${baseTime}_${idx}_${sIdx}_${UUID.randomUUID().toString().take(6)}",
                                title = cleanSubTitle.capitalizeFirstLetter(),
                                status = TaskStatus.NOT_STARTED,
                                priority = priority,
                                taskTypes = types,
                                minimumTimeRequired = "0d 0h 30m",
                                dueDate = dueDate,
                                remainderDate = dueDate,
                                parentTaskId = mainTaskId
                            )
                        )
                    }
                }
            }

            groups.add(ParsedTaskGroup(task = mainTask, subtasks = subtasks))
        }

        return TaskParseResult(
            success = true,
            taskGroups = groups
        )
    }

    private fun generateConciseTitleFromPrompt(prompt: String): String {
        var cleaned = prompt
            .replace(Regex("(?i)\\b(tomorrow|today|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week|yesterday)\\b"), "")
            .replace(Regex("(?i)\\b(by|at|before|due)\\s+\\d+(:\\d+)?\\s*(am|pm)?"), "")
            .replace(Regex("(?i)\\b(high priority|low priority|medium priority|urgent|critical|asap)\\b"), "")
            .replace(Regex("(?i)\\d+(\\.\\d+)?\\s*(h|hours|mins?|m)\\s*(min time|minimum time)?"), "")
            .replace(Regex("(?i)\\b(i need to|i have to|please|i want to|task is to|reminder to)\\b"), "")
            .replace(Regex("[,:;\\-\\.\"]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) cleaned = prompt.trim()

        val firstSentence = cleaned.split(Regex("[.!?\n]"))[0].trim()
        val words = firstSentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        val conciseWords = if (words.size > 6) words.take(6).joinToString(" ") else words.joinToString(" ")
        
        return conciseWords.ifBlank { "New Task" }.capitalizeFirstLetter()
    }

    private fun String.capitalizeFirstLetter(): String {
        if (this.isEmpty()) return this
        return this.substring(0, 1).uppercase() + this.substring(1)
    }

    private fun setNextDayOfWeek(cal: Calendar, targetDayOfWeek: Int) {
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)
        var daysToAdd = (targetDayOfWeek - currentDay + 7) % 7
        if (daysToAdd == 0) daysToAdd = 7
        cal.add(Calendar.DAY_OF_YEAR, daysToAdd)
    }
}
