package uz.azura.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Foreground service: SOCKS5 serverni foydalanuvchi to'xtatguncha ushlab turadi.
 * Start paytida Tailscale (100.64.0.0/10) manzili topilsa — o'shanga bind bo'ladi,
 * topilmasa 0.0.0.0 (barcha interfeyslar). Service ishlayotganda partial wake lock ushlanadi.
 */
class RelayService : Service() {

    private var server: Socks5Server? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Foregroundga tez o'tish shart — bildirishnomani darhol ko'rsatamiz
        startForegroundSafe("Ishga tushmoqda...")
        if (server != null || starting) return START_STICKY
        starting = true
        acquireWakeLock()
        thread(name = "relay-start") {
            try {
                val ts = NetInfo.tailscaleAddress()
                val bind: InetAddress = ts ?: InetAddress.getByName("0.0.0.0")
                val srv = Socks5Server(bind, RelayState.PORT) { count ->
                    RelayState.activeConnections = count
                }
                srv.start()
                server = srv
                RelayState.bindAddress = srv.localAddress?.hostAddress ?: bind.hostAddress
                RelayState.lastError = null
                RelayState.running = true
                updateNotification("Ishlayapti: ${RelayState.bindAddress}:${RelayState.PORT}")
            } catch (e: Exception) {
                RelayState.lastError = e.message ?: e.javaClass.simpleName
                RelayState.running = false
                releaseWakeLock()
                stopSelf()
            } finally {
                starting = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { server?.stop() } catch (e: Exception) { }
        server = null
        RelayState.running = false
        RelayState.bindAddress = null
        RelayState.activeConnections = 0
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzuraRelay::SocksWakeLock")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
            // wake lock bo'lmasa ham service ishlayveradi
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Azura Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
        builder.addAction(android.R.drawable.ic_media_pause, "O'chirish", stopIntent)
        return builder.build()
    }

    private fun startForegroundSafe(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Azura Relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        try {
            startForeground(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            // foreground bo'lmasa ham server oqimi o'z ishini qiladi
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) { }
    }

    companion object {
        private const val CHANNEL_ID = "azura_relay"
        private const val NOTIF_ID = 11
        private const val ACTION_STOP = "uz.azura.relay.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, RelayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                try { ctx.startService(i) } catch (e2: Exception) { }
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, RelayService::class.java)) } catch (e: Exception) { }
        }
    }
}
