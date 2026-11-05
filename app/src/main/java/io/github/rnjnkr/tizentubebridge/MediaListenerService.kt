package io.github.rnjnkr.tizentubebridge

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty notification listener. Being an enabled listener is what lets
 * [YouTubeInterceptorService] see and control other apps' media sessions (to stop official
 * YouTube's playback after a redirect); no notifications are read. Enable via adb:
 *   adb shell cmd notification allow_listener io.github.rnjnkr.tizentubebridge/io.github.rnjnkr.tizentubebridge.MediaListenerService
 */
class MediaListenerService : NotificationListenerService()
