package uz.azura.relay

/** Service va UI orasidagi jonli holat (oddiy volatile maydonlar). */
object RelayState {
    const val PORT = 11080

    @Volatile var running: Boolean = false
    @Volatile var bindAddress: String? = null
    @Volatile var activeConnections: Int = 0
    @Volatile var lastError: String? = null
}
