package uz.azura.relay

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Qurilmaning tarmoq manzillari haqida yordamchi funksiyalar. */
object NetInfo {

    /** Tailscale manzillari 100.64.0.0/10 (CGNAT) diapazonida bo'ladi. */
    fun isTailscaleAddress(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        val b = addr.address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    /** Faol interfeyslardagi barcha IPv4 manzillar: (interfeys nomi, manzil). */
    fun allIpv4(): List<Pair<String, Inet4Address>> {
        val out = mutableListOf<Pair<String, Inet4Address>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in ifaces) {
                try {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            out.add(iface.name to addr)
                        }
                    }
                } catch (e: Exception) {
                    // bitta interfeys xatosi qolganlarini to'xtatmasin
                }
            }
        } catch (e: Exception) {
            // manzillar topilmasa bo'sh ro'yxat qaytadi
        }
        return out
    }

    /** Tailscale IPv4 manzili (bo'lsa); bo'lmasa null. */
    fun tailscaleAddress(): Inet4Address? =
        allIpv4().firstOrNull { isTailscaleAddress(it.second) }?.second
}
