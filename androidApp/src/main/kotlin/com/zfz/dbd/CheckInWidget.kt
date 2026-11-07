package com.zfz.dbd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

private val widgetItemOrder = compareBy(CheckIn::timeLabel, CheckIn::id)

class CheckInWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        refresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = pagePreferences(context).edit()
        appWidgetIds.forEach { editor.remove(dayKeyForWidget(it)).remove(pageKey(it)) }
        editor.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != CHANGE_PAGE) return

        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        if (id !in manager.getAppWidgetIds(component(context))) return
        val day = dayKey()
        if (intent.getStringExtra("day") == day) {
            pagePreferences(context).edit()
                .putString(dayKeyForWidget(id), day)
                .putInt(pageKey(id), intent.getIntExtra("page", 0))
                .apply()
        }
        refresh(context)
    }

    companion object {
        private const val CHANGE_PAGE = "com.zfz.dbd.CHANGE_WIDGET_PAGE"
        private const val MAX_PAGE_SIZE = 3
        private const val HEADER_AND_PADDING_DP = 48
        private const val ROW_HEIGHT_DP = 32

        private fun component(context: Context) = ComponentName(context, CheckInWidget::class.java)
        private fun pagePreferences(context: Context) = context.getSharedPreferences("checkInWidgetPages", Context.MODE_PRIVATE)
        private fun dayKeyForWidget(id: Int) = "$id.day"
        private fun pageKey(id: Int) = "$id.page"

        internal fun requestPin(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(component(context), null, null)
        }

        internal fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(component(context))
            if (ids.isEmpty()) return

            val items = ReminderStore(context).read().sortedWith(widgetItemOrder)
            val today = dayKey()
            val completed = items.count { it.completedDay == today }
            val pages = pagePreferences(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )

            for (id in ids) {
                val pageSize = pageSize(context, manager.getAppWidgetOptions(id))
                val pageCount = maxOf(1, (items.size + pageSize - 1) / pageSize)
                val page = if (pages.getString(dayKeyForWidget(id), null) == today) {
                    pages.getInt(pageKey(id), 0).coerceIn(0, pageCount - 1)
                } else {
                    0
                }
                val views = RemoteViews(context.packageName, R.layout.check_in_widget)
                views.setOnClickPendingIntent(R.id.widget_progress, open)
                views.setOnClickPendingIntent(R.id.widget_empty, open)
                views.setTextViewText(R.id.widget_progress, "今日打卡  $completed / ${items.size}")
                views.setProgressBar(R.id.widget_progress_bar, maxOf(items.size, 1), completed, false)
                views.setViewVisibility(R.id.widget_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_pager, if (pageCount > 1) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_page, "${page + 1} / $pageCount")
                views.setBoolean(R.id.widget_previous, "setEnabled", page > 0)
                views.setBoolean(R.id.widget_next, "setEnabled", page < pageCount - 1)
                if (page > 0) {
                    views.setOnClickPendingIntent(R.id.widget_previous, pageAction(context, id, page - 1, today))
                }
                if (page < pageCount - 1) {
                    views.setOnClickPendingIntent(R.id.widget_next, pageAction(context, id, page + 1, today))
                }

                views.removeAllViews(R.id.widget_items)
                items.drop(page * pageSize).take(pageSize).forEach { item ->
                    views.addView(R.id.widget_items, widgetRow(context, item, today))
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun pageSize(context: Context, options: Bundle): Int {
            val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            } else {
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            }
            val height = options.getInt(heightKey)
            return if (height > 0) {
                ((height - HEADER_AND_PADDING_DP) / ROW_HEIGHT_DP).coerceIn(1, MAX_PAGE_SIZE)
            } else {
                MAX_PAGE_SIZE
            }
        }

        private fun pageAction(context: Context, id: Int, page: Int, day: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, CheckInWidget::class.java)
                    .setAction(CHANGE_PAGE)
                    .setData(Uri.parse("daybyday://widget-page/$id/$day/$page"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("day", day)
                    .putExtra("page", page),
                PendingIntent.FLAG_IMMUTABLE
            )

        private fun widgetRow(context: Context, item: CheckIn, day: String): RemoteViews {
            val done = item.completedDay == day
            return RemoteViews(context.packageName, R.layout.check_in_widget_row).apply {
                setTextViewText(R.id.widget_item, item.title)
                setTextViewText(R.id.widget_done, context.getString(if (done) R.string.widget_completed else R.string.widget_done))
                setContentDescription(R.id.widget_done, context.getString(
                    if (done) R.string.widget_completed_description else R.string.widget_done_description, item.title
                ))
                setBoolean(R.id.widget_done, "setEnabled", !done)
                if (!done) {
                    val action = Intent(context, ReminderReceiver::class.java)
                        .setAction(Reminders.DONE)
                        .setData(Uri.parse("daybyday://widget/${item.id}?day=$day"))
                        .putExtra("day", day)
                    setOnClickPendingIntent(
                        R.id.widget_done, PendingIntent.getBroadcast(context, 0, action, PendingIntent.FLAG_IMMUTABLE)
                    )
                }
            }
        }
    }
}
