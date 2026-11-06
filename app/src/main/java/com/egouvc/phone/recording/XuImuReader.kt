package com.egouvc.phone.recording

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.io.Closeable

/**
 * Performs UVC XU class-specific control transfers via Android USB Host EP0.
 * No force-claim of VideoControl interface: libuvc is streaming on that interface.
 * Parallel access is device/Android specific and MUST be verified on the Honor phone.
 */
class XuImuReader(
    context: Context,
    device: UsbDevice,
    /** Connection owned by the active UVCCamera clone; do not close it here. */
    streamingConnection: UsbDeviceConnection? = null,
    /** Original USBMonitor connection, retained only as a fallback diagnostic path. */
    borrowedConnection: UsbDeviceConnection? = null,
    /** Only the standalone probe retains the packet already read during verification. */
    retainVerificationPacket: Boolean = false,
) : Closeable {
    data class Packet(val raw: ByteArray, val beforeBootNs: Long, val afterBootNs: Long,
                      val hostUnixNs: Long, val samples: List<ImuPacketParser.Sample>)
    class ReadFailure(val stage: String, message: String, cause: Throwable? = null,
                      val beforeBootNs: Long = 0, val afterBootNs: Long = 0,
                      val returnedBytes: Int = -999) :
        Exception(message, cause)
    private var verificationPacket: Packet? = null
    fun verifiedProbePacket(): Packet = checkNotNull(verificationPacket) {
        "Verification packet retention was not enabled"
    }
    val transportPath: String
    private val connection: UsbDeviceConnection
    private val ownsConnection: Boolean
    private val vcInterface: Int

    init {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        require(manager.hasPermission(device)) { "USB permission missing for XU" }
        val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.interfaceSubclass == 1 }
            ?: error("UVC VideoControl interface not found")
        vcInterface = iface.id
        Log.i(TAG, "XU target: VID=0x${device.vendorId.toString(16)} " +
            "PID=0x${device.productId.toString(16)} VC interface=$vcInterface " +
            "bRequestType=0x${REQUEST_TYPE.toString(16)} " +
            "wValue=0x${(SELECTOR shl 8).toString(16)} " +
            "wIndex=0x${((UNIT shl 8) or vcInterface).toString(16)}")
        val attempts = ArrayList<String>()

        // UVCCamera.open(controlBlock) clones the block. Probe that clone first:
        // it is the connection whose file descriptor libuvc uses for streaming.
        // The monitor and a separate open are fallback diagnostics only.
        fun verify(conn: UsbDeviceConnection, path: String): Boolean {
            val problems = mutableListOf<String>()
            fun optionalControl(request: Int, expected: Int, name: String): ByteArray? =
                try {
                    readControl(conn, request, expected)
                } catch (e: Exception) {
                    problems.add("$name: ${e.message}")
                    Log.w(TAG, "$path $name failed: ${e.message}")
                    null
                }

            val len = optionalControl(GET_LEN, 2, "GET_LEN")
            val size = len?.let { (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8) }
            val info = optionalControl(GET_INFO, 1, "GET_INFO")?.get(0)?.toInt()?.and(255)
            Log.i(TAG, "$path: vcInterface=$vcInterface GET_LEN=${size ?: "failed"} " +
                "GET_INFO=${info?.let { "0x${it.toString(16)}" } ?: "failed"}")

            if (size != null && size != ImuPacketParser.PACKET_BYTES) {
                problems.add("firmware GET_LEN=$size, expected ${ImuPacketParser.PACKET_BYTES}")
            }
            if (info != null && (info and 0x01) == 0) {
                problems.add("GET_INFO says XU selector is not readable: 0x${info.toString(16)}")
            }
            if (problems.any { it.startsWith("firmware GET_LEN=") || it.startsWith("GET_INFO says") }) {
                attempts.add("$path: ${problems.joinToString("; ")}")
                return false
            }

            // Some Android USB stacks STALL GET_LEN/GET_INFO even when GET_CUR
            // works. Never accept a connection based on these query results alone.
            // A valid 252-byte packet with 14 headers/counters is mandatory.
            return try {
                val before = if (retainVerificationPacket) SystemClock.elapsedRealtimeNanos() else 0L
                val raw = readControl(conn, GET_CUR, ImuPacketParser.PACKET_BYTES)
                val after = if (retainVerificationPacket) SystemClock.elapsedRealtimeNanos() else 0L
                val parsed = ImuPacketParser.decode(raw)
                if (retainVerificationPacket) {
                    verificationPacket = Packet(raw, before, after,
                        System.currentTimeMillis() * 1_000_000L, parsed)
                }
                Log.i(TAG, "$path: XU GET_CUR verified: ${parsed.size} samples, " +
                    "counter=${parsed.first().counterRaw}..${parsed.last().counterRaw}")
                true
            } catch (e: Exception) {
                val failure = "$path: ${problems.joinToString("; ")}; GET_CUR: " +
                    (e.message ?: e.javaClass.simpleName)
                attempts.add(failure)
                Log.w(TAG, "XU probe failed: $failure")
                false
            }
        }

        var chosen: UsbDeviceConnection? = null
        var isOwned = false
        if (streamingConnection != null && verify(streamingConnection, "UVCCamera streaming")) {
            chosen = streamingConnection
        }
        if (chosen == null && borrowedConnection != null &&
            borrowedConnection !== streamingConnection && verify(borrowedConnection, "USBMonitor borrowed")) {
            chosen = borrowedConnection
        }
        if (chosen == null) {
            val separate = manager.openDevice(device)
            if (separate == null) {
                attempts.add("openDevice returned null")
            } else if (verify(separate, "separate openDevice")) {
                chosen = separate
                isOwned = true
            } else {
                separate.close()
            }
        }
        connection = chosen ?: error(
            "XU GET_CUR failed on all connections; unit=$UNIT selector=$SELECTOR " +
                "vcInterface=$vcInterface requestType=0x${REQUEST_TYPE.toString(16)} " +
                "wIndex=0x${((UNIT shl 8) or vcInterface).toString(16)}; " + attempts.joinToString("; ")
        )
        ownsConnection = isOwned
        transportPath = when (connection) {
            streamingConnection -> "UVCCamera streaming"
            borrowedConnection -> "USBMonitor borrowed"
            else -> "separate openDevice"
        }
        Log.i(TAG, "XU transport verified: $transportPath")
    }

    fun readPacket(): Packet {
        val data = ByteArray(ImuPacketParser.PACKET_BYTES)
        val before = SystemClock.elapsedRealtimeNanos()
        val n = try {
            connection.controlTransfer(REQUEST_TYPE, GET_CUR, SELECTOR shl 8,
                UNIT shl 8 or vcInterface, data, data.size, TRANSFER_TIMEOUT_MS)
        } catch (e: Exception) {
            throw ReadFailure("usb_transfer", "XU GET_CUR 请求异常：${e.message}", e,
                before, SystemClock.elapsedRealtimeNanos(), -999)
        }
        val after = SystemClock.elapsedRealtimeNanos()
        if (n != data.size) throw ReadFailure("usb_transfer", "XU GET_CUR 返回 $n 字节，期望 ${data.size}",
            beforeBootNs = before, afterBootNs = after, returnedBytes = n)
        val samples = try { ImuPacketParser.decode(data) } catch (e: Exception) {
            throw ReadFailure("packet_parse", "IMU 包解析失败：${e.message}", e, before, after, n)
        }
        return Packet(data, before, after, System.currentTimeMillis() * 1_000_000L, samples)
    }

    private fun readControl(conn: UsbDeviceConnection, request: Int, size: Int): ByteArray {
        val data = ByteArray(size)
        val result = conn.controlTransfer(REQUEST_TYPE, request, SELECTOR shl 8,
            UNIT shl 8 or vcInterface, data, size, TRANSFER_TIMEOUT_MS)
        require(result == size) {
            "request=0x${request.toString(16)} type=0x${REQUEST_TYPE.toString(16)} " +
                "value=0x${(SELECTOR shl 8).toString(16)} " +
                "index=0x${((UNIT shl 8) or vcInterface).toString(16)} " +
                "returned $result expected $size"
        }
        return data
    }

    override fun close() {
        if (ownsConnection) connection.close()
    }

    companion object {
        private const val TAG = "EgoUVC-XU"
        private const val UNIT = 3
        private const val SELECTOR = 1
        private const val GET_CUR = 0x81
        private const val GET_LEN = 0x85
        private const val GET_INFO = 0x86
        private const val TRANSFER_TIMEOUT_MS = 500
        private const val REQUEST_TYPE = 0xA1
    }
}
