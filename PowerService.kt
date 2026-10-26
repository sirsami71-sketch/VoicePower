package com.voicepower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class PowerService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_EVENTS = "events_v3"
        const val ACTION_REPLAY = "com.voicepower.REPLAY"
        const val EXTRA_EVENT = "event_key"
        const val FG_ID = 1
    }

    private var lastLevel = -1
    private val fired = mutableSetOf<PowerEvent>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    fired.clear()
                    // আগে থেকেই ৮০%/১০০% হলে আলাদা করে আবার ঘোষণা করবে না
                    if (lastLevel >= 80) fired += PowerEvent.LEVEL_80
                    if (lastLevel >= 100) fired += PowerEvent.FULL
                    trigger(PowerEvent.CONNECTED)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    fired.clear()
                    trigger(PowerEvent.DISCONNECTED)
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, FG_ID, buildForegroundNotification(), type)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // নোটিফিকেশনের "▶ আবার শুনুন" বাটন — অ্যাপ না খুলেই ভয়েস বাজবে
        if (intent?.action == ACTION_REPLAY) {
            val key = intent.getStringExtra(EXTRA_EVENT)
            PowerEvent.values().firstOrNull { it.key == key }?.let { VoicePlayer.play(this, it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        VoicePlayer.stop()
        super.onDestroy()
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val prev = lastLevel
        lastLevel = pct

        // সার্ভিস চালু হওয়ার প্রথম রিডিং: শুধু অবস্থা মনে রাখো, কিছু বাজিও না
        if (prev == -1) {
            if (plugged) {
                if (pct >= 80) fired += PowerEvent.LEVEL_80
                if (pct >= 100) fired += PowerEvent.FULL
            } else if (pct <= 20) {
                fired += PowerEvent.LOW
            }
            return
        }

        if (plugged) {
            if (prev < 80 && pct >= 80 && PowerEvent.LEVEL_80 !in fired) {
                fired += PowerEvent.LEVEL_80
                trigger(PowerEvent.LEVEL_80)
            }
            if (pct >= 100 && PowerEvent.FULL !in fired) {
                fired += PowerEvent.FULL
                trigger(PowerEvent.FULL)
            }
        } else {
            if (pct <= 20 && prev > 20 && PowerEvent.LOW !in fired) {
                fired += PowerEvent.LOW
                trigger(PowerEvent.LOW)
            }
            if (pct > 20) fired -= PowerEvent.LOW
        }
    }

    private fun trigger(e: PowerEvent) {
        if (!VoiceStore.isEnabled(this, e)) return
        val played = VoicePlayer.play(this, e)
        showEventNotification(e, played)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "ব্যাকগ্রাউন্ড সার্ভিস", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.deleteNotificationChannel("events_v2")
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "চার্জ ও ব্যাটারি ইভেন্ট", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // শব্দ বাজবে আপনার নিজের ভয়েসে
                // ছোট কম্পন: স্মার্টওয়াচ অ্যাপ সাধারণত এমন "আসল" নোটিফিকেশনই ঘড়িতে পাঠায়
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 120, 200)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // লক স্ক্রিনে পুরো দেখাবে
            }
        )
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("ভয়েস পাওয়ার চালু আছে")
            .setContentText("চার্জার ও ব্যাটারির দিকে নজর রাখছে")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun showEventNotification(e: PowerEvent, played: Boolean) {
        val battery = if (lastLevel >= 0) " • ব্যাটারি $lastLevel%" else ""
        val b = NotificationCompat.Builder(this, CH_EVENTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(e.title)
            .setContentText((if (played) "🔊 আপনার ভয়েস বাজানো হয়েছে" else "এই ইভেন্টের জন্য কোনো ভয়েস সেট করা নেই") + battery)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLocalOnly(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        if (played) {
            val replay = PendingIntent.getService(
                this, 200 + e.ordinal,
                Intent(this, PowerService::class.java).setAction(ACTION_REPLAY).putExtra(EXTRA_EVENT, e.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(android.R.drawable.ic_media_play, "▶ আবার শুনুন", replay)
        }
        val n = b.build()
        getSystemService(NotificationManager::class.java).notify(100 + e.ordinal, n)
    }
}
