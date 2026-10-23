package uz.azura.relay

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Yagona ekran: katta Yoqish/O'chirish tugmasi, holat va qurilma IP ro'yxati.
 * Holat har soniyada yangilab turiladi (Tailscale manzili keyin paydo bo'lishi mumkin).
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var toggleButton: Button
    private lateinit var tailscaleView: TextView
    private lateinit var ipListView: TextView

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "Azura Relay"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "Telefon internetini SOCKS5 proksi sifatida ulashadi (port ${RelayState.PORT})."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        root.addView(sub)

        statusView = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }
        root.addView(statusView)

        detailView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        root.addView(detailView)

        toggleButton = Button(this).apply {
            textSize = 22f
            setPadding(0, 36, 0, 36)
            setOnClickListener {
                if (RelayState.running) {
                    RelayService.stop(this@MainActivity)
                    Toast.makeText(this@MainActivity, "To'xtatildi", Toast.LENGTH_SHORT).show()
                } else {
                    RelayService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Ishga tushmoqda...", Toast.LENGTH_SHORT).show()
                }
                handler.postDelayed({ refresh() }, 600)
            }
        }
        root.addView(toggleButton)

        val tsTitle = TextView(this).apply {
            text = "Tailscale manzili"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 36, 0, 8)
        }
        root.addView(tsTitle)

        tailscaleView = TextView(this).apply { textSize = 17f }
        root.addView(tailscaleView)

        val ipTitle = TextView(this).apply {
            text = "Qurilma IP manzillari"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 28, 0, 8)
        }
        root.addView(ipTitle)

        ipListView = TextView(this).apply { textSize = 15f }
        root.addView(ipListView)

        val hint = TextView(this).apply {
            text = "Maslahat: Sozlamalar → Ilovalar → Azura Relay → Batareya → «Cheklanmagan» qiling, " +
                "shunda Android fon xizmatini o'ldirmaydi.\n\n" +
                "Ulanish: kompyuterda SOCKS5 proksi sifatida Tailscale manzili va ${RelayState.PORT} portni ko'rsating."
            textSize = 13f
            setPadding(0, 32, 0, 0)
        }
        root.addView(hint)

        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        if (RelayState.running) {
            statusView.text = "Ishlayapti"
            detailView.text = "SOCKS5: ${RelayState.bindAddress}:${RelayState.PORT} · " +
                "faol ulanishlar: ${RelayState.activeConnections}"
            toggleButton.text = "O'chirish"
        } else {
            statusView.text = "To'xtatilgan"
            val err = RelayState.lastError
            detailView.text = if (err.isNullOrBlank()) "Port: ${RelayState.PORT}" else "Xato: $err"
            toggleButton.text = "Yoqish"
        }

        val ips = NetInfo.allIpv4()
        val ts = ips.firstOrNull { NetInfo.isTailscaleAddress(it.second) }
        tailscaleView.text = if (ts != null) {
            "⭐ ${ts.second.hostAddress} (${ts.first})"
        } else {
            "Topilmadi — Tailscale ilovasini yoqing, manzil shu yerda chiqadi"
        }

        ipListView.text = if (ips.isEmpty()) {
            "Manzil topilmadi"
        } else {
            ips.joinToString("\n") { (name, addr) ->
                val mark = if (NetInfo.isTailscaleAddress(addr)) " ⭐ Tailscale" else ""
                "$name: ${addr.hostAddress}$mark"
            }
        }
    }
}
