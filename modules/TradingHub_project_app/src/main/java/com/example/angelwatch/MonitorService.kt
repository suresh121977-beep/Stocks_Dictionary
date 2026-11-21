package com.example.angelwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Keeps quotes fresh in the background until the user presses Stop. */
class MonitorService : Service() {
    companion object {
        const val ACTION_STOP = "com.example.angelwatch.STOP"
        private const val CHANNEL = "monitor"

        fun start(ctx: Context) {
            Store.init(ctx)
            Store.stopped = false
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            Store.init(ctx)
            Store.stopped = true
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Alerts.init()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale("en", "IN"); ttsOk = true }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("alerts", "Portfolio alerts", NotificationManager.IMPORTANCE_HIGH))
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Market monitor", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.stopped = true
            job?.cancel()
            Repo.running.value = false
            Repo.status.value = "Stopped"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.shutdown()
        Repo.running.value = false
        super.onDestroy()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("AngelWatch is running")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop",
            PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun speak(t: String) {
        if (Store.voice && ttsOk) tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "a" + System.nanoTime())
    }

    private fun notifyAlert(a: AlertItem) {
        val n = NotificationCompat.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle((if (a.kind.startsWith("SIGNAL")) "Signal: " else if (a.kind == "INDEX") "Index caution: " else "Portfolio alert: ") + a.symbol)
            .setContentText(a.msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        getSystemService(NotificationManager::class.java).notify((a.time % 100000).toInt() + 100, n)
    }

    private fun updateNotification() {
        val nifty = Repo.indices.value.firstOrNull()
        val head = nifty?.let { "${it.symbol} ${f2(it.ltp)} (${String.format("%+.2f", it.pct)}%) • " } ?: ""
        getSystemService(NotificationManager::class.java).notify(1, notification(head + Repo.status.value))
    }

    private suspend fun loop() {
        Repo.running.value = true
        var n = 0
        try {
            try { Instruments.load(applicationContext) } catch (e: Exception) { Repo.status.value = "Stock list: ${e.message}" }
            while (currentCoroutineContext().isActive) {
                try {
                    if (n % 6 == 0) runCatching { Repo.refreshHoldings() }
                    Repo.tick()
                    runCatching { IndexWatch.tick() }
                    runCatching { IndexWatch.prepare() }
                    runCatching { IndexWatch.evaluate(::speak, ::notifyAlert) }
                    runCatching { Orb.refresh(3) }
                    runCatching { Orb.update(::speak, ::notifyAlert) }
                    Alerts.prepare(if (marketOpen()) 2 else 6)
                    Alerts.evaluate(::speak, ::notifyAlert)
                    Repo.status.value = "Updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Repo.status.value = e.message ?: "Error"
                }
                updateNotification()
                n++
                val mcxWatched = mcxOpen() && Store.lists.value.any { l -> l.symbols.any { Instruments.get(it)?.exch == "MCX" } }
                delay(if (marketOpen() || mcxWatched) 5_000 else 30_000)
            }
        } finally {
            Repo.running.value = false
        }
    }
}
