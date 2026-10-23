package uz.azura.relay

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Oddiy SOCKS5 serveri (RFC 1928):
 * - faqat no-auth (0x00) va CONNECT buyrug'i qo'llab-quvvatlanadi;
 * - manzil turlari: IPv4 (0x01), domen (0x03), IPv6 (0x04);
 * - BIND va UDP ASSOCIATE uchun REP=0x07 (command not supported);
 * - har bir ulanish alohida oqimda, xatolar serverni yiqitmaydi.
 */
class Socks5Server(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val onConnectionsChanged: (Int) -> Unit = {}
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeCount = AtomicInteger(0)
    private val clientSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    val isRunning: Boolean get() = running

    /** Server tinglayotgan haqiqiy manzil (startdan keyin). */
    val localAddress: InetAddress? get() = serverSocket?.inetAddress

    /** ServerSocket ochadi va accept oqimini ishga tushiradi. Xatoda exception otadi. */
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port), 50)
        serverSocket = ss
        running = true
        thread(name = "socks5-accept", isDaemon = true) { acceptLoop(ss) }
    }

    /** Serverni to'xtatadi va barcha ochiq ulanishlarni yopadi. */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
        val copy: List<Socket>
        synchronized(clientSockets) { copy = clientSockets.toList() }
        for (s in copy) {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                // stop() da socket yopilsa shu yerga tushamiz — normal chiqish
                break
            }
            clientSockets.add(client)
            val n = activeCount.incrementAndGet()
            try { onConnectionsChanged(n) } catch (e: Exception) { }
            thread(name = "socks5-conn", isDaemon = true) {
                try {
                    handle(client)
                } catch (e: Exception) {
                    // bitta ulanish xatosi serverga ta'sir qilmaydi
                } finally {
                    try { client.close() } catch (e: Exception) { }
                    clientSockets.remove(client)
                    val left = activeCount.decrementAndGet()
                    try { onConnectionsChanged(left) } catch (e: Exception) { }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        // Salomlashuv va so'rov o'qishda uzoq osilib qolmaslik uchun taym-aut
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        // ---- Salomlashuv: VER, NMETHODS, METHODS ----
        val ver = input.read()
        if (ver != SOCKS_VERSION) return
        val nMethods = input.read()
        if (nMethods < 1) return
        val methods = readExact(input, nMethods)
        if (!methods.contains(NO_AUTH)) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
        output.flush()

        // ---- So'rov: VER, CMD, RSV, ATYP ----
        val header = readExact(input, 4)
        if (header[0].toInt() != SOCKS_VERSION) return
        val cmd = header[1].toInt() and 0xFF
        if (cmd != CMD_CONNECT) {
            writeReply(output, REP_COMMAND_NOT_SUPPORTED, null, 0)
            return
        }
        val targetAddress: InetAddress
        val targetHost: String
        when (val atyp = header[3].toInt() and 0xFF) {
            ATYP_IPV4 -> {
                val raw = readExact(input, 4)
                targetAddress = InetAddress.getByAddress(raw)
                targetHost = targetAddress.hostAddress ?: ""
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                if (len < 1) throw EOFException("domain length")
                val raw = readExact(input, len)
                targetHost = String(raw, Charsets.UTF_8)
                // Domen shu yerda yechiladi — xato bo'lsa REP=0x04 qaytadi
                targetAddress = try {
                    InetAddress.getByName(targetHost)
                } catch (e: UnknownHostException) {
                    writeReply(output, REP_HOST_UNREACHABLE, null, 0)
                    return
                }
            }
            ATYP_IPV6 -> {
                val raw = readExact(input, 16)
                targetAddress = InetAddress.getByAddress(raw)
                targetHost = targetAddress.hostAddress ?: ""
            }
            else -> {
                writeReply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED, null, 0)
                return
            }
        }
        val portBytes = readExact(input, 2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // ---- Targetga ulanish ----
        val remote = Socket()
        clientSockets.add(remote)
        try {
            remote.connect(InetSocketAddress(targetAddress, targetPort), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            writeReply(output, replyCodeFor(e), null, 0)
            try { remote.close() } catch (e2: Exception) { }
            clientSockets.remove(remote)
            return
        }

        try {
            // Muvaffaqiyat: BND.ADDR/BND.PORT — targetga chiqgan lokal manzil
            writeReply(output, REP_SUCCEEDED, remote.localAddress, remote.localPort)
            // Relay bosqichida soTimeout — o'qish oqimini vaqti-vaqti bilan
            // uyg'otib, yopilgan soketlarni tekshirish uchun ishlatiladi
            client.soTimeout = RELAY_TIMEOUT_MS
            remote.soTimeout = RELAY_TIMEOUT_MS
            relay(client, input, remote)
        } finally {
            try { remote.close() } catch (e: Exception) { }
            clientSockets.remove(remote)
        }
    }

    /** Ikki tomonlama relay: bitta yo'nalish shu oqimda, ikkinchisi alohida oqimda. */
    private fun relay(client: Socket, clientInput: InputStream, remote: Socket) {
        val remoteInput = remote.getInputStream()
        val remoteOutput = remote.getOutputStream()
        val clientOutput = client.getOutputStream()
        val up = thread(name = "socks5-up", isDaemon = true) {
            pump(clientInput, remoteOutput, client, remote)
        }
        pump(remoteInput, clientOutput, remote, client)
        try { up.join() } catch (e: InterruptedException) { }
    }

    private fun pump(input: InputStream, output: OutputStream, from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = try {
                    input.read(buffer)
                } catch (e: SocketTimeoutException) {
                    if (from.isClosed || to.isClosed) break else continue
                }
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            // ulanish uzilishi — normal holat
        } finally {
            // Qarshi tomonga EOF signalini beramiz, lekin o'qishni davom ettiramiz
            try { to.shutdownOutput() } catch (e: Exception) { }
        }
    }

    private fun writeReply(output: OutputStream, rep: Int, bound: InetAddress?, boundPort: Int) {
        val addrBytes: ByteArray
        val atyp: Int
        when (bound) {
            is Inet6Address -> { atyp = ATYP_IPV6; addrBytes = bound.address }
            is Inet4Address -> { atyp = ATYP_IPV4; addrBytes = bound.address }
            else -> { atyp = ATYP_IPV4; addrBytes = byteArrayOf(0, 0, 0, 0) }
        }
        val msg = ByteArray(6 + addrBytes.size)
        msg[0] = SOCKS_VERSION.toByte()
        msg[1] = rep.toByte()
        msg[2] = 0
        msg[3] = atyp.toByte()
        System.arraycopy(addrBytes, 0, msg, 4, addrBytes.size)
        msg[4 + addrBytes.size] = ((boundPort shr 8) and 0xFF).toByte()
        msg[5 + addrBytes.size] = (boundPort and 0xFF).toByte()
        output.write(msg)
        output.flush()
    }

    private fun replyCodeFor(e: Exception): Int = when (e) {
        is UnknownHostException -> REP_HOST_UNREACHABLE
        is NoRouteToHostException -> REP_NETWORK_UNREACHABLE
        is ConnectException -> REP_CONNECTION_REFUSED
        is SocketTimeoutException -> REP_HOST_UNREACHABLE
        is IOException -> REP_GENERAL_FAILURE
        else -> REP_GENERAL_FAILURE
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) throw EOFException("expected $count bytes, got $offset")
            offset += n
        }
        return buf
    }

    companion object {
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0x00.toByte()
        private const val NO_ACCEPTABLE = 0xFF
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_NETWORK_UNREACHABLE = 0x03
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONNECTION_REFUSED = 0x05
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val RELAY_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 32 * 1024
    }
}
