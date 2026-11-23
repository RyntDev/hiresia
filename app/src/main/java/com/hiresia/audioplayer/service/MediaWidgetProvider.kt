package com.hiresia.audioplayer.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.ui.MainActivity

class MediaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.notification_widget)

            val state = AudioPlayerController.playbackState.value
            state.currentTrack?.let {
                views.setTextViewText(R.id.widgetTrackTitle, it.title)
                views.setTextViewText(R.id.widgetTrackArtist, "${it.artist} • ${it.album}")
            } ?: run {
                views.setTextViewText(R.id.widgetTrackTitle, "Hiresia Audio")
                views.setTextViewText(R.id.widgetTrackArtist, "High-Resolution Engine")
            }

            views.setImageViewResource(
                R.id.widgetBtnPlay,
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            val openIntent = Intent(context, MainActivity::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            views.setOnClickPendingIntent(R.id.widgetAlbumArt, PendingIntent.getActivity(context, 0, openIntent, flags))

            // Play/Pause intent
            val playIntent = Intent(context, MediaPlaybackService::class.java).apply {
                action = if (state.isPlaying) MediaPlaybackService.ACTION_PAUSE else MediaPlaybackService.ACTION_PLAY
            }
            views.setOnClickPendingIntent(R.id.widgetBtnPlay, PendingIntent.getService(context, 1, playIntent, flags))

            // Next intent
            val nextIntent = Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_NEXT
            }
            views.setOnClickPendingIntent(R.id.widgetBtnNext, PendingIntent.getService(context, 2, nextIntent, flags))

            // Prev intent
            val prevIntent = Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_PREV
            }
            views.setOnClickPendingIntent(R.id.widgetBtnPrev, PendingIntent.getService(context, 3, prevIntent, flags))

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
