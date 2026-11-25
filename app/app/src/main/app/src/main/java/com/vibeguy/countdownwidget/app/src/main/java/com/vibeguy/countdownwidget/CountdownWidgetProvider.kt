package com.vibeguy.countdownwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CountdownWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleNextTick(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK_UPDATE || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            updateAllWidgets(context)
            scheduleNextTick(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelTick(context)
    }

    companion object {
        const val PREFS_NAME = "countdown_widget_prefs"
        const val KEY_CONFIG_JSON = "countdown_config_json"
        const val ACTION_TICK_UPDATE = "com.vibeguy.countdownwidget.ACTION_TICK_UPDATE"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CountdownWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                updateSingleWidget(context, manager, id)
            }
        }

        fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawJson = prefs.getString(KEY_CONFIG_JSON, null)
            val cfg = parseConfig(rawJson)

            val now = System.currentTimeMillis()
            val diffMillis = cfg.targetMillis - now
            val isPast = diffMillis <= 0L
            val absDiff = kotlin.math.abs(diffMillis)

            val days = TimeUnit.MILLISECONDS.toDays(absDiff)
            val hours = TimeUnit.MILLISECONDS.toHours(absDiff) % 24
            val minutes = TimeUnit.MILLISECONDS.toMinutes(absDiff) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(absDiff) % 60

            val timeText = when (cfg.formatMode) {
                "dhms" -> String.format(Locale.getDefault(), "%02dd %02dh %02dm %02ds", days, hours, minutes, seconds)
                "hms" -> {
                    val totalHours = TimeUnit.MILLISECONDS.toHours(absDiff)
                    String.format(Locale.getDefault(), "%02d:%02d:%02d", totalHours, minutes, seconds)
                }
                else -> "${days}d ${hours}h ${minutes}m"
            }

            val statusSuffix = if (isPast) " (Done)" else " left"
            val formattedTarget = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(cfg.targetMillis))

            val bgBitmap = renderWidgetBackground(context, cfg)

            val views = RemoteViews(context.packageName, R.layout.widget_countdown)
            views.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)

            val textColor = safeParseColor(cfg.textColor, Color.WHITE)
            val accentColor = safeParseColor(cfg.accentColor, Color.parseColor("#38BDF8"))

            views.setTextViewText(R.id.widget_title, cfg.title)
            views.setTextColor(R.id.widget_title, textColor)

            views.setTextViewText(R.id.widget_countdown, "$timeText$statusSuffix")
            views.setTextColor(R.id.widget_countdown, accentColor)

            views.setTextViewText(R.id.widget_subtitle, cfg.subtitle.ifBlank { "Target: $formattedTarget" })
            views.setTextColor(R.id.widget_subtitle, textColor)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun renderWidgetBackground(context: Context, cfg: WidgetConfig): Bitmap {
            val width = 900
            val height = 420
            val radius = 48f

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val clipPath = android.graphics.Path().apply {
                addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            val bgFile = File(context.filesDir, "widget_bg.jpg")
            if (cfg.useWallpaper && bgFile.exists()) {
                val rawPhoto = BitmapFactory.decodeFile(bgFile.absolutePath)
                if (rawPhoto != null) {
                    val scaledPhoto = centerCropBitmap(rawPhoto, width, height)
                    canvas.drawBitmap(scaledPhoto, 0f, 0f, null)
                }
            } else {
                val startCol = safeParseColor(cfg.bgStartColor, Color.parseColor("#0F172A"))
                val endCol = safeParseColor(cfg.bgEndColor, Color.parseColor("#1E293B"))
                val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        startCol, endCol, Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradPaint)
            }

            val overlayAlpha = (cfg.overlayOpacity.coerceIn(0f, 0.85f) * 255f).toInt()
            val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(overlayAlpha, 0, 0, 0)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.argb(55, 255, 255, 255)
            }
            canvas.drawRoundRect(
                RectF(1.5f, 1.5f, width - 1.5f, height - 1.5f),
                radius, radius, borderPaint
            )

            canvas.restore()
            return output
        }

        private fun centerCropBitmap(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
            val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val srcRatio = src.width.toFloat() / src.height.coerceAtLeast(1)
            val dstRatio = targetW.toFloat() / targetH.toFloat()

            val drawRect = if (srcRatio > dstRatio) {
                val scaledW = (src.height * dstRatio).toInt()
                val left = ((src.width - scaledW) / 2).coerceAtLeast(0)
                Rect(left, 0, left + scaledW, src.height)
            } else {
                val scaledH = (src.width / dstRatio).toInt()
                val top = ((src.height - scaledH) / 2).coerceAtLeast(0)
                Rect(0, top, src.width, top + scaledH)
            }

            val dstRect = Rect(0, 0, targetW, targetH)
            canvas.drawBitmap(src, drawRect, dstRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        }

        private fun safeParseColor(hex: String, fallback: Int): Int {
            return try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                fallback
            }
        }

        private fun parseConfig(rawJson: String?): WidgetConfig {
            val defaultTarget = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
            if (rawJson.isNullOrBlank()) {
                return WidgetConfig(
                    title = "My Countdown",
                    subtitle = "",
                    targetMillis = defaultTarget,
                    formatMode = "dhm",
                    bgStartColor = "#0F172A",
                    bgEndColor = "#1E293B",
                    textColor = "#FFFFFF",
                    accentColor = "#38BDF8",
                    overlayOpacity = 0.35f,
                    useWallpaper = false
                )
            }
            return try {
                val obj = JSONObject(rawJson)
                WidgetConfig(
                    title = obj.optString("title", "My Countdown").ifBlank { "My Countdown" },
                    subtitle = obj.optString("subtitle", ""),
                    targetMillis = obj.optLong("targetMillis", defaultTarget),
                    formatMode = obj.optString("formatMode", "dhm"),
                    bgStartColor = obj.optString("bgStartColor", "#0F172A"),
                    bgEndColor = obj.optString("bgEndColor", "#1E293B"),
                    textColor = obj.optString("textColor", "#FFFFFF"),
                    accentColor = obj.optString("accentColor", "#38BDF8"),
                    overlayOpacity = obj.optDouble("overlayOpacity", 0.35).toFloat(),
                    useWallpaper = obj.optBoolean("useWallpaper", false)
                )
            } catch (_: Exception) {
                WidgetConfig(
                    title = "My Countdown",
                    subtitle = "",
                    targetMillis = defaultTarget,
                    formatMode = "dhm",
                    bgStartColor = "#0F172A",
                    bgEndColor = "#1E293B",
                    textColor = "#FFFFFF",
                    accentColor = "#38BDF8",
                    overlayOpacity = 0.35f,
                    useWallpaper = false
                )
            }
        }

        fun scheduleNextTick(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, CountdownWidgetProvider::class.java).apply {
                action = ACTION_TICK_UPDATE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + 60_000L
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (_: Exception) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }

        fun cancelTick(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, CountdownWidgetProvider::class.java).apply {
                action = ACTION_TICK_UPDATE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    private data class WidgetConfig(
        val title: String,
        val subtitle: String,
        val targetMillis: Long,
        val formatMode: String,
        val bgStartColor: String,
        val bgEndColor: String,
        val textColor: String,
        val accentColor: String,
        val overlayOpacity: Float,
        val useWallpaper: Boolean
    )
}
