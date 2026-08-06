package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = """
        Query calendar events on the user's device within a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
        Returns a list of events with title, description, location, start/end times, calendar info,
        recurrence rule ('rrule'), and reminders (minutes before start) when present.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                            add("month")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 20.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasCalendarReadPermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.HAS_ALARM,
        )

        val selection = if (query != null) {
            "${CalendarContract.Instances.TITLE} LIKE ?"
        } else null
        val selectionArgs = if (query != null) {
            arrayOf("%$query%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val eventId = cursor.getLong(0)
                    val hasAlarm = cursor.getInt(9) == 1
                    add(buildJsonObject {
                        put("id", eventId)
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        val dtStart = cursor.getLong(4)
                        val dtEnd = cursor.getLong(5)
                        val allDay = cursor.getInt(6) == 1
                        if (allDay) {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                } else {
                                    ""
                                }
                            )
                        } else {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                } else {
                                    ""
                                }
                            )
                        }
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7) ?: "")
                        put("rrule", cursor.getString(8) ?: "")
                        put("has_reminder", hasAlarm)
                        if (hasAlarm) {
                            put("reminders", queryReminders(context, eventId))
                        }
                    })
                    count++
                }
            }
        }

        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("events", events)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildCalendarCreateTool(context: Context): Tool = Tool(
    name = "calendar_create",
    description = """
        Create a new calendar event on the user's device.
        Requires title and start time at minimum. End time defaults to 1 hour after start.
        A system reminder is added by default (10 minutes before); pass 'reminder'=false to skip,
        'reminder_minutes' to adjust the lead time, or 'reminders' for multiple lead times. Recurring
        events are supported via the 'recurrence' preset (daily/weekly/monthly/yearly) or a raw
        'rrule' (RFC 5545) string.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Event title.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Event description or notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Event location.")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time, same formats as 'start'. Defaults to 1 hour after start."
                    )
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether this is an all-day event. Default false.")
                })
                put("reminder", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Whether to add a system reminder. Default true. Set to false to create " +
                            "the event without any reminder."
                    )
                })
                put("reminder_minutes", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Minutes before the event start to trigger a single reminder. Default 10. " +
                            "Common values: 0 (at event start), 5, 10, 15, 30, 60, 1440 (1 day). Range 0-10080. " +
                            "Ignored when 'reminders' (multiple) is provided."
                    )
                })
                put("reminders", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Multiple reminders (minutes before start). Takes precedence over 'reminder_minutes'. " +
                            "Example: [10, 1440] = 10 minutes before and 1 day before. Each value range 0-10080."
                    )
                    put("items", buildJsonObject {
                        put("type", "integer")
                    })
                })
                put("recurrence", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "Recurrence preset. Ignored when a raw 'rrule' is provided. " +
                            "Example: {\"freq\": \"WEEKLY\", \"interval\": 1, \"count\": 5}."
                    )
                    put("properties", buildJsonObject {
                        put("freq", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("DAILY")
                                add("WEEKLY")
                                add("MONTHLY")
                                add("YEARLY")
                            })
                            put("description", "Repeat frequency. Required inside 'recurrence'.")
                        })
                        put("interval", buildJsonObject {
                            put("type", "integer")
                            put("description", "Repeat every N units (e.g. 2 = every 2 weeks). Default 1.")
                        })
                        put("count", buildJsonObject {
                            put("type", "integer")
                            put("description", "Stop after this many occurrences. Omit to repeat indefinitely.")
                        })
                        put("until", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Repeat until this date (ISO-8601 'yyyy-MM-dd'), inclusive. " +
                                    "Mutually exclusive with 'count'."
                            )
                        })
                    })
                })
                put("rrule", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Raw RFC 5545 recurrence rule, e.g. 'FREQ=WEEKLY;COUNT=10' or " +
                            "'FREQ=MONTHLY;BYDAY=1MO'. Takes precedence over 'recurrence'. " +
                            "Must start with 'FREQ='."
                    )
                })
            },
            required = listOf("title", "start")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar write permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val allDay = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val reminderEnabled = params["reminder"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        // 多条提醒优先，否则用单条 reminder_minutes（默认 10）
        val reminderMinutesList = parseReminderMinutesList(params)
        val reminderMinutes = reminderMinutesList.firstOrNull() ?: 10

        if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "Both 'title' and 'start' are required.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseCalendarTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "end must be later than start.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""

        // 重复规则：优先用自定义 rrule，否则按 recurrence 预设组装。无效时返回空（不重复）。
        val rrule = parseRrule(params)

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                val payload = buildJsonObject {
                    put("error", "INVALID_RANGE")
                    put("message", "all-day event end date must be later than start date.")
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId(context)
        if (calendarId == null) {
            val payload = buildJsonObject {
                put("error", "NO_CALENDAR")
                put("message", "No calendar account found on this device. Please add a calendar account first.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
            if (rrule != null) {
                // 重复事件按 Android 规范用 RRULE + DURATION（ISO8601 时长），不写 DTEND
                put(CalendarContract.Events.RRULE, rrule)
                put(
                    CalendarContract.Events.DURATION,
                    if (allDay) {
                        val days = java.time.Duration.between(
                            java.time.Instant.ofEpochMilli(eventStartMillis),
                            java.time.Instant.ofEpochMilli(eventEndMillis)
                        ).toDays().coerceAtLeast(1)
                        "P${days}D"
                    } else {
                        val seconds = java.time.Duration.between(
                            java.time.Instant.ofEpochMilli(eventStartMillis),
                            java.time.Instant.ofEpochMilli(eventEndMillis)
                        ).seconds.coerceAtLeast(0)
                        "PT${seconds}S"
                    }
                )
            } else {
                put(CalendarContract.Events.DTEND, eventEndMillis)
            }
            if (reminderEnabled) {
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        if (uri == null) {
            val payload = buildJsonObject {
                put("error", "INSERT_FAILED")
                put("message", "Failed to insert calendar event.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val eventId = ContentUris.parseId(uri)

        // 写入系统提醒（默认开启）。失败不回滚事件，仅在响应里标注提醒状态。
        val remindersCreated = if (reminderEnabled) {
            insertReminders(context, eventId, reminderMinutesList)
        } else {
            emptyList()
        }

        val payload = buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("title", title)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("all_day", allDay)
            put("location", location)
            put("reminder", reminderEnabled)
            if (reminderEnabled) {
                put("reminder_minutes", reminderMinutes)
                put("reminders_created", buildJsonArray {
                    remindersCreated.forEach { add(it) }
                })
            }
            if (rrule != null) {
                put("rrule", rrule)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildCalendarUpdateTool(context: Context): Tool = Tool(
    name = "calendar_update",
    description = """
        Update an existing calendar event on the user's device by 'event_id'.
        Only the provided fields are changed: title, description, location, start, end, all_day,
        recurrence/'rrule'. Reminders are rebuilt from scratch when any of 'reminder'/'reminders'/
        'reminder_minutes' is provided (pass 'reminder'=false to remove all reminders).
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "ID of the event to update (from calendar_query).")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "New event title.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "New event description or notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "New event location.")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "New start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "New end time, same formats as 'start'. If only 'start' is given, end " +
                            "defaults to 1 hour after start (or 1 day for all-day events)."
                    )
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether this is an all-day event.")
                })
                put("recurrence", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "New recurrence preset. Ignored when a raw 'rrule' is provided. " +
                            "Pass empty object {\"freq\":\"NONE\"} to clear recurrence. " +
                            "Example: {\"freq\": \"WEEKLY\", \"interval\": 1, \"count\": 5}."
                    )
                    put("properties", buildJsonObject {
                        put("freq", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("DAILY")
                                add("WEEKLY")
                                add("MONTHLY")
                                add("YEARLY")
                                add("NONE")
                            })
                            put(
                                "description",
                                "Repeat frequency. 'NONE' clears recurrence. Required inside 'recurrence'."
                            )
                        })
                        put("interval", buildJsonObject {
                            put("type", "integer")
                            put("description", "Repeat every N units. Default 1.")
                        })
                        put("count", buildJsonObject {
                            put("type", "integer")
                            put("description", "Stop after this many occurrences.")
                        })
                        put("until", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Repeat until this date (ISO-8601 'yyyy-MM-dd'), inclusive."
                            )
                        })
                    })
                })
                put("rrule", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "New raw RFC 5545 recurrence rule. Pass empty string '' to clear recurrence."
                    )
                })
                put("reminder", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Whether to keep reminders. When false, all reminders are removed. " +
                            "When provided together with reminder fields, rebuilds the reminder set."
                    )
                })
                put("reminder_minutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minutes before start for a single reminder. Range 0-10080.")
                })
                put("reminders", buildJsonObject {
                    put("type", "array")
                    put("description", "Multiple reminders (minutes before start). Example: [10, 1440].")
                    put("items", buildJsonObject {
                        put("type", "integer")
                    })
                })
            },
            required = listOf("event_id")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar write permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (eventId == null) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "'event_id' is required.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val values = ContentValues().apply {
            params["title"]?.jsonPrimitive?.contentOrNull?.let { put(CalendarContract.Events.TITLE, it) }
            params["description"]?.jsonPrimitive?.contentOrNull?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            params["location"]?.jsonPrimitive?.contentOrNull?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        // 时间/全天/重复涉及 start/end 的联动计算，需要原事件的 DTSTART/ALL_DAY 做基准
        val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val allDayRaw = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        if (startRaw != null || endRaw != null || allDayRaw != null) {
            val existing = queryEventMeta(context, eventId)
            val zone = ZoneId.systemDefault()
            val baseStart = existing?.dtStart?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val baseAllDay = existing?.allDay ?: false
            val allDay = allDayRaw ?: baseAllDay
            try {
                val startTime = startRaw?.let { parseCalendarTime(it, zone) }
                    ?: baseStart ?: error("Existing event start time unavailable.")
                val endTime = when {
                    endRaw != null -> parseCalendarTime(endRaw, zone)
                    startRaw != null && allDay -> startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
                    startRaw != null -> startTime.plusHours(1)
                    existing?.dtEnd != null && existing.dtEnd > 0 -> Instant.ofEpochMilli(existing.dtEnd).atZone(zone)
                    else -> startTime.plusHours(1)
                }
                if (allDay) {
                    val startDate = startTime.toLocalDate()
                    val endDate = endTime.toLocalDate()
                    if (!startDate.isBefore(endDate)) {
                        error("all-day event end date must be later than start date.")
                    }
                    values.put(CalendarContract.Events.ALL_DAY, 1)
                    values.put(CalendarContract.Events.DTSTART, startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    values.put(CalendarContract.Events.DTEND, endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                } else {
                    values.put(CalendarContract.Events.ALL_DAY, 0)
                    values.put(CalendarContract.Events.DTSTART, startTime.toInstant().toEpochMilli())
                    values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    values.put(CalendarContract.Events.DTEND, endTime.toInstant().toEpochMilli())
                }
            } catch (e: Exception) {
                val payload = buildJsonObject {
                    put("error", "INVALID_TIME")
                    put("message", e.message ?: "Invalid time format.")
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }
        }

        // 重复规则：新增/修改/清除
        val hasRruleParam = params["rrule"] != null
        val hasRecurrenceParam = params["recurrence"] != null
        if (hasRruleParam || hasRecurrenceParam) {
            val newRrule = parseRrule(params)
            if (newRrule != null) {
                val existing = queryEventMeta(context, eventId)
                val zone = ZoneId.systemDefault()
                val startTime = existing?.dtStart?.let { Instant.ofEpochMilli(it).atZone(zone) }
                val allDay = existing?.allDay ?: false
                if (startTime != null) {
                    val endTime = when {
                        existing.dtEnd != null && existing.dtEnd > 0 ->
                            Instant.ofEpochMilli(existing.dtEnd).atZone(zone)
                        allDay -> startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
                        else -> startTime.plusHours(1)
                    }
                    val startMs = if (allDay) {
                        startTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    } else startTime.toInstant().toEpochMilli()
                    val endMs = if (allDay) {
                        endTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    } else endTime.toInstant().toEpochMilli()
                    // 重复事件按 Android 规范用 RRULE + DURATION，不写 DTEND
                    values.put(CalendarContract.Events.RRULE, newRrule)
                    if (allDay) {
                        values.put(CalendarContract.Events.DURATION, "P${java.time.Duration.between(java.time.Instant.ofEpochMilli(startMs), java.time.Instant.ofEpochMilli(endMs)).toDays().coerceAtLeast(1)}D")
                    } else {
                        values.put(CalendarContract.Events.DURATION, "PT${java.time.Duration.between(java.time.Instant.ofEpochMilli(startMs), java.time.Instant.ofEpochMilli(endMs)).seconds.coerceAtLeast(0)}S")
                    }
                    values.remove(CalendarContract.Events.DTEND)
                } else {
                    // 拿不到原 start，直接写 RRULE（保留原 DTEND；provider 会以 DTEND 推导 duration）
                    values.put(CalendarContract.Events.RRULE, newRrule)
                }
            } else {
                // 显式传了 rrule/recurrence 但解析为空 → 清除重复
                values.put(CalendarContract.Events.RRULE, "")
                values.remove(CalendarContract.Events.DURATION)
            }
        }

        val updated = runCatching {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
        }.getOrDefault(0)
        if (updated <= 0) {
            val payload = buildJsonObject {
                put("error", "UPDATE_FAILED")
                put("message", "Event not found or update failed (event_id: $eventId).")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        // 提醒重建：仅当调用方提供了任一提醒相关参数时
        val hasReminderParam =
            params["reminder"] != null || params["reminders"] != null || params["reminder_minutes"] != null
        if (hasReminderParam) {
            deleteReminders(context, eventId)
            val reminderEnabled = params["reminder"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            if (reminderEnabled) {
                val minutesList = parseReminderMinutesList(params)
                val values = ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 1) }
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI, values,
                    "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString())
                )
                insertReminders(context, eventId, minutesList)
            } else {
                val values = ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 0) }
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI, values,
                    "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString())
                )
            }
        }

        val payload = buildJsonObject {
            put("success", true)
            put("event_id", eventId)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildCalendarDeleteTool(context: Context): Tool = Tool(
    name = "calendar_delete",
    description = """
        Delete a calendar event on the user's device by 'event_id'. Removing the master event
        also removes its recurring occurrences and reminders.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "ID of the event to delete (from calendar_query).")
                })
                put("delete_future", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Only meaningful for recurring events. When true, only this and future " +
                            "occurrences are removed (creates an exception); when false (default), " +
                            "the whole series is deleted."
                    )
                })
            },
            required = listOf("event_id")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar write permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (eventId == null) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "'event_id' is required.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val deleteFuture = params["delete_future"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val deleted = runCatching {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
        }.getOrDefault(0)

        if (deleted > 0) {
            val payload = buildJsonObject {
                put("success", true)
                put("deleted_event_id", eventId)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            val payload = buildJsonObject {
                put("error", "DELETE_FAILED")
                put("message", "Event not found or delete failed (event_id: $eventId).")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)

/**
 * 解析重复规则参数：
 * - 'rrule' 字符串非空且以 'FREQ=' 开头时原样使用（优先级最高）；显式传空串表示清除重复
 * - 否则读 'recurrence' 预设对象，按 {freq, interval, count, until} 组装 RRULE
 * - 解析失败、freq 非法、或 freq = 'NONE'（清除）→ 返回 null（不重复/清除）
 */
private fun parseRrule(params: JsonObject): String? {
    val rawRrule = params["rrule"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!rawRrule.isNullOrEmpty()) {
        return if (rawRrule.startsWith("FREQ=", ignoreCase = true)) rawRrule.uppercase() else null
    }

    val recurrence = params["recurrence"]?.jsonObject ?: return null
    val freq = recurrence["freq"]?.jsonPrimitive?.contentOrNull?.uppercase()
    if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null

    val sb = StringBuilder("FREQ=$freq")
    val interval = recurrence["interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
    if (interval > 1) {
        sb.append(";INTERVAL=$interval")
    }
    val count = recurrence["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    if (count != null && count > 0) {
        sb.append(";COUNT=$count")
    }
    val until = recurrence["until"]?.jsonPrimitive?.contentOrNull
    if (until != null) {
        // 统一转为 UTC 日期（YYYYMMDD），满足 RRULE UNTIL 格式要求
        val untilDate = runCatching { LocalDate.parse(until.trim()) }.getOrNull()
        if (untilDate != null) {
            sb.append(";UNTIL=${untilDate.toString().replace("-", "")}T235959Z")
        }
    }
    return sb.toString()
}

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * 解析提醒分钟数列表：'reminders' 数组优先，否则用单条 'reminder_minutes'（默认 10）。
 * 过滤非法值、去重、排序，最多 5 条。
 */
private fun parseReminderMinutesList(params: JsonObject): List<Int> {
    val fromArray = params["reminders"]?.jsonArray?.mapNotNull {
        it.jsonPrimitive.contentOrNull?.toIntOrNull()
    }?.filter { it in 0..10080 }?.distinct()?.take(5)?.sorted()
    if (fromArray != null) return fromArray
    return listOf(
        params["reminder_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(0, 10080) ?: 10
    )
}

/** 为事件插入多条提醒记录，返回实际写入成功的分钟数列表。 */
private fun insertReminders(context: Context, eventId: Long, minutesList: List<Int>): List<Int> {
    val created = mutableListOf<Int>()
    minutesList.forEach { minutes ->
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        val ok = runCatching {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues) != null
        }.getOrDefault(false)
        if (ok) created += minutes
    }
    return created
}

/** 删除事件的所有提醒记录。 */
private fun deleteReminders(context: Context, eventId: Long) {
    runCatching {
        context.contentResolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
    }
}

/** 查询事件的提醒分钟数列表（按提前分钟数升序）。 */
private fun queryReminders(context: Context, eventId: Long): JsonElement {
    val reminders = buildJsonArray {
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                add(cursor.getInt(0))
            }
        }
    }
    return reminders
}

/** 事件元数据（DTSTART/DTEND/ALL_DAY），供 update 联动计算 start/end/时长。 */
private data class EventMeta(
    val dtStart: Long?,
    val dtEnd: Long?,
    val allDay: Boolean,
)

private fun queryEventMeta(context: Context, eventId: Long): EventMeta? {
    context.contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        arrayOf(
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
        ),
        "${CalendarContract.Events._ID} = ?",
        arrayOf(eventId.toString()),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return EventMeta(
                dtStart = cursor.getLong(0).takeIf { !cursor.isNull(0) },
                dtEnd = cursor.getLong(1).takeIf { !cursor.isNull(1) },
                allDay = cursor.getInt(2) == 1,
            )
        }
    }
    return null
}

private fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun getDefaultCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val writableSelection =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
    val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
        writableArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        writableSelection,
        writableArgs,
        "${CalendarContract.Calendars.VISIBLE} DESC"
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
}

private fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
