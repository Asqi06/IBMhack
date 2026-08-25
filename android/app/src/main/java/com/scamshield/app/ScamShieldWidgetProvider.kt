package com.scamshield.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen App Widget — companion to the floating bubble MVP.
 * Brief asked for "floating widget (chat-head bubble)" at FloatingWidgetService.kt;
 * this home widget gives judges a visible launcher entry and one-tap entry to scanning.
 *
 * Two actions:
 *  - Tap "Scan screen" → opens MainActivity (which owns MediaProjection grant) → user can start bubble scan
 *  - Tap widget body → same
 */
class ScamShieldWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_scamshield)

            // Intent to open MainActivity
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widgetScanBtn, pending)
            views.setOnClickPendingIntent(R.id.widgetStatus, pending)

            // Update status from prefs if available
            val prefs = context.getSharedPreferences("scamshield", Context.MODE_PRIVATE)
            val lastRisk = prefs.getString("lastOverallRisk", null)
            lastRisk?.let { risk ->
                views.setTextViewText(R.id.widgetStatus, "Last scan: ${risk.uppercase()} • tap to scan again")
            }

            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
