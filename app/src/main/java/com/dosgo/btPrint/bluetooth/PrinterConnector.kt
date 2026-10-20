package com.dosgo.btPrint.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一的打印机数据通道。经典蓝牙 SPP 与 BLE GATT 对外暴露同一套 write 接口，
 * 这样 MainActivity 不需要关心底层到底走的哪一种。
 */
interface PrinterTransport {
    /** 通道类型描述，用于日志排查 */
    val kind: String

    /**
     * 已成功写出的字节数。
     * 用它判断失败时"一个字节都没发出去"——那种情况换条通道整份重发是安全的，
     * 打印机没收到任何指令，不会多出一张纸。
     */
    val bytesSent: Long
        get() = 0L

    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** 发送原数组中的一段，避免大点阵分带时反复复制 ByteArray。 */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int, length: Int) {
        write(data.copyOfRange(offset, offset + length))
    }

    fun close()
}

private const val TAG = "BtPrintConnector"

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

// 常见打印机 BLE 模块的服务 UUID（按优先级排列）
private val PREFERRED_BLE_SERVICES = listOf(
    UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"), // ISSC，佳博/汉印等常用
    UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"), // TI CC254x / HM 系列模块
    UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
)

private val BluetoothGattCharacteristic.isWritable: Boolean
    get() = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE
            or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

object PrinterConnector {

    /** 每 512 字节块的默认节流时长（毫秒）。打印机 MCU 消化不了全速灌入的点阵。 */
    const val DEFAULT_THROTTLE_MS = 25
    private const val CHUNK_SIZE = 512
    private const val THROTTLE_THRESHOLD = 8192

    // ---- 单连接 + 连接复用 ----
    // 一台打印机同一时刻只能有一条通道：SPP 与 BLE 并存会让打印机把两路字节
    // 拼成乱码，第二路 PRINT 更会直接插进第一份点阵里，表现为打半张卡住或整机锁死。
    // 这里用占位 token 做 CAS，连"两个线程同时开始握手"这种竞态也挡掉。
    private val exclusiveSlot = AtomicReference<PrinterTransport?>(null)

    private object ConnectingToken : PrinterTransport {
        override val kind: String get() = "连接中"
        override fun write(data: ByteArray) = throw IOException("连接尚未建立")
        override fun close() = Unit
    }

    /**
     * 两份打印之间保留连接的时长。
     *
     * 打完立刻 socket.close() 是 broken pipe 的根源：Android 侧立刻释放了 RFCOMM，
     * 打印机那头的 channel 还在，下一次 connect() 会"成功"返回一个实际已死的 socket，
     * 第一次 write 就是 EPIPE(Broken pipe)。保留一段窗口直接复用同一条 socket 最省事。
     */
    const val IDLE_KEEP_MS = 60_000L

    /** 通道关闭后到下一次握手之间的最小间隔，给蓝牙栈留出拆 ACL 的时间。 */
    private const val RECONNECT_GAP_MS = 900L

    private val cacheLock = Any()
    private var cached: CachedConnection? = null
    @Volatile private var lastCloseAt = 0L
    private val idleHandler = Handler(Looper.getMainLooper())

    private data class CachedConnection(
        val address: String,
        val transport: ExclusiveTransport,
        var lastUsedAt: Long
    )

    private val idleClose = Runnable {
        synchronized(cacheLock) { closeCachedLocked("空闲超过 ${IDLE_KEEP_MS}ms") }
    }

    /** 当前占用的通道描述；无连接返回 null。 */
    fun activeKind(): String? =
        exclusiveSlot.get()?.takeIf { it !== ConnectingToken }?.kind

    /** 连接前必须停止搜索，否则 RFCOMM 握手会被拖慢甚至直接超时。 */
    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
            .onFailure { Log.w(TAG, "cancelDiscovery 失败", it) }
    }

    /**
     * 取通道：窗口内第二次打印直接复用上一条连接，否则新建。
     * 调用方用完必须调用 [release]（正常）或 [invalidate]（出错）。
     */
    @SuppressLint("MissingPermission")
    fun acquire(
        context: Context,
        device: BluetoothDevice,
        log: (String) -> Unit,
        throttleMs: Int = DEFAULT_THROTTLE_MS
    ): PrinterTransport {
        val now = SystemClock.elapsedRealtime()
        // 任务开始就停掉空闲计时：一份多页标签可能要打一分钟，
        // 计时器要是在这期间触发，就会在打印中途把 socket 关掉（自造 broken pipe）。
        idleHandler.removeCallbacks(idleClose)
        synchronized(cacheLock) {
            val c = cached
            if (c != null && c.address == device.address &&
                !c.transport.isClosed && now - c.lastUsedAt <= IDLE_KEEP_MS
            ) {
                val idle = now - c.lastUsedAt
                c.lastUsedAt = now
                Log.i(TAG, "复用已有连接（空闲 ${idle}ms）：${c.transport.kind}")
                return c.transport
            }
            if (c != null) closeCachedLocked("换设备或已超时")
        }

        if (!exclusiveSlot.compareAndSet(null, ConnectingToken)) {
            val holder = activeKind() ?: "另一路连接"
            Log.w(TAG, "拒绝重复连接 ${device.address}：已有通道占用（$holder）")
            throw IOException("蓝牙通道已被「$holder」占用，等它关闭后再连")
        }
        try {
            // 刚关过通道就立刻握手，蓝牙栈往往给回一个"已连接但对面已死"的 socket
            val sinceClose = SystemClock.elapsedRealtime() - lastCloseAt
            if (lastCloseAt > 0L && sinceClose < RECONNECT_GAP_MS) {
                val wait = RECONNECT_GAP_MS - sinceClose
                log("等待蓝牙栈拆链 ${wait}ms …")
                Thread.sleep(wait)
            }
            val transport = doConnect(context, device, log, throttleMs)
            val guarded = ExclusiveTransport(transport)
            exclusiveSlot.set(guarded)
            synchronized(cacheLock) {
                cached = CachedConnection(device.address, guarded, SystemClock.elapsedRealtime())
            }
            Log.i(TAG, "已占用唯一通道：${guarded.kind}")
            return guarded
        } catch (t: Throwable) {
            // 握手失败要把槽位还回去，否则后面所有打印都会被判定为"已有连接"
            exclusiveSlot.set(null)
            throw t
        }
    }

    /** 正常用完：保留连接，窗口期内下一份直接复用。 */
    fun release(transport: PrinterTransport) {
        synchronized(cacheLock) {
            val c = cached
            if (c != null && c.transport === transport) {
                c.lastUsedAt = SystemClock.elapsedRealtime()
                idleHandler.removeCallbacks(idleClose)
                idleHandler.postDelayed(idleClose, IDLE_KEEP_MS)
                Log.i(TAG, "任务结束，连接保留 ${IDLE_KEEP_MS}ms 供下一份复用")
                return
            }
        }
        runCatching { transport.close() }
    }

    /** 出错：立刻关闭，下一次必然重新握手。 */
    fun invalidate(transport: PrinterTransport) {
        synchronized(cacheLock) {
            if (cached?.transport === transport) {
                cached = null
                idleHandler.removeCallbacks(idleClose)
            }
        }
        runCatching { transport.close() }
    }

    private fun closeCachedLocked(reason: String) {
        val c = cached ?: return
        cached = null
        idleHandler.removeCallbacks(idleClose)
        Log.i(TAG, "关闭连接（$reason）：${c.transport.kind}")
        runCatching { c.transport.close() }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(
        context: Context,
        device: BluetoothDevice,
        log: (String) -> Unit,
        throttleMs: Int
    ): PrinterTransport {
        cancelDiscovery()

        var sppError: Throwable? = null
        try {
            log("尝试经典蓝牙 SPP …")
            val transport = connectSpp(device, log, throttleMs)
            log("已连接：${transport.kind}")
            return transport
        } catch (e: Throwable) {
            sppError = e
            Log.w(TAG, "SPP 连接失败", e)
            log("SPP 失败：${e.message}")
        }

        // N41BT 能在 iOS 上用，说明它至少有 BLE 通道；SPP 不通时改走 GATT
        try {
            log("尝试 BLE GATT …")
            val transport = BleTransport(context, device, log).also { it.open() }
            log("已连接：${transport.kind}")
            return transport
        } catch (e: Throwable) {
            sppError?.let { e.addSuppressed(it) }
            Log.w(TAG, "BLE 连接失败", e)
            throw e
        }
    }

    @SuppressLint("MissingPermission", "PrivateApi")
    private fun connectSpp(
        device: BluetoothDevice,
        log: (String) -> Unit,
        throttleMs: Int
    ): PrinterTransport {
        val candidates = mutableListOf<Pair<String, () -> BluetoothSocket>>()
        candidates += "安全 socket(SPP UUID)" to { device.createRfcommSocketToServiceRecord(SPP_UUID) }
        candidates += "非安全 socket(SPP UUID)" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        // 部分打印机 SDP 里不广播标准 SPP UUID，只能按 RFCOMM 通道直连（隐藏 API，失败就跳过）
        for (channel in 1..3) {
            candidates += "反射通道 $channel" to { createSocketByChannel(device, channel, false) }
            candidates += "反射非安全通道 $channel" to { createSocketByChannel(device, channel, true) }
        }

        var last: Throwable? = null
        for ((name, factory) in candidates) {
            var socket: BluetoothSocket? = null
            try {
                socket = factory()
                log("  尝试 $name …")
                connectWithTimeout(socket, 6000)
                return SppTransport(socket, "SPP($name)", throttleMs)
            } catch (e: Throwable) {
                last = e
                runCatching { socket?.close() }
            }
        }
        throw IOException("所有 SPP 通道均失败：${last?.message}").apply { initCause(last) }
    }

    @SuppressLint("PrivateApi")
    private fun createSocketByChannel(
        device: BluetoothDevice,
        channel: Int,
        insecure: Boolean
    ): BluetoothSocket {
        val name = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
        val method = device.javaClass.getMethod(name, Int::class.javaPrimitiveType)
        return method.invoke(device, channel) as BluetoothSocket
    }

    /** BluetoothSocket.connect() 无法被中断，只能靠另一个线程 close 掉它来打断。 */
    private fun connectWithTimeout(socket: BluetoothSocket, timeoutMs: Long) {
        val finished = AtomicBoolean(false)
        val watchdog = Thread({
            try {
                Thread.sleep(timeoutMs)
                if (!finished.get()) {
                    Log.w(TAG, "connect 超过 ${timeoutMs}ms，主动关闭 socket")
                    runCatching { socket.close() }
                }
            } catch (_: InterruptedException) {
            }
        }, "bt-connect-watchdog").apply { isDaemon = true }
        watchdog.start()
        try {
            socket.connect()
        } finally {
            finished.set(true)
            watchdog.interrupt()
        }
    }

    /**
     * 独占包装：close 时把全局槽位、复用缓存一起释放。
     * 无论上层是正常打完、异常抛出还是 Activity 销毁，只要走了 close 就不会把通道锁死。
     */
    private class ExclusiveTransport(
        private val inner: PrinterTransport
    ) : PrinterTransport {

        private val closed = AtomicBoolean(false)

        val isClosed: Boolean get() = closed.get()

        override val kind: String get() = inner.kind

        override val bytesSent: Long get() = inner.bytesSent

        override fun write(data: ByteArray) {
            if (closed.get()) throw IOException("通道已关闭")
            inner.write(data)
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            if (closed.get()) throw IOException("通道已关闭")
            inner.write(data, offset, length)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            exclusiveSlot.compareAndSet(this, null)
            synchronized(cacheLock) { if (cached?.transport === this) cached = null }
            lastCloseAt = SystemClock.elapsedRealtime()
            runCatching { inner.close() }
            Log.i(TAG, "通道已释放：${inner.kind}")
        }
    }

    private class SppTransport(
        private val socket: BluetoothSocket,
        override val kind: String,
        private val throttleMs: Int
    ) : PrinterTransport {

        @Volatile
        private var sent = 0L

        override val bytesSent: Long get() = sent

        override fun write(data: ByteArray) = write(data, 0, data.size)

        override fun write(data: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= data.size - length) {
                "无效数据范围 offset=$offset length=$length size=${data.size}"
            }
            val out = socket.outputStream
            // 两道约束：
            // 1) RFCOMM 实测 MTU 约 990，取 512 留余量，超限会被拆包导致数据错乱；
            // 2) 大点阵必须节流 —— 打印机 MCU 来不及消化全速灌入的字节会静默丢包，
            //    表现为小图能打、大图毫无反应。
            val needThrottle = throttleMs > 0 && length > THROTTLE_THRESHOLD
            val end = offset + length
            var cursor = offset
            while (cursor < end) {
                val len = minOf(CHUNK_SIZE, end - cursor)
                out.write(data, cursor, len)
                out.flush()
                cursor += len
                sent += len
                if (needThrottle && cursor < end) Thread.sleep(throttleMs.toLong())
            }
        }

        override fun close() {
            // 先关流再关 socket，让打印机收到规范的断链，而不是直接掉 ACL
            runCatching { socket.outputStream.flush() }
            runCatching { socket.outputStream.close() }
            runCatching { socket.inputStream.close() }
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private class BleTransport(
        private val context: Context,
        private val device: BluetoothDevice,
        private val log: (String) -> Unit
    ) : PrinterTransport {

        private var gatt: BluetoothGatt? = null
        private var writeChar: BluetoothGattCharacteristic? = null
        @Volatile private var mtu = 23
        private val writeLock = Any()
        private var writeLatch: CountDownLatch? = null
        @Volatile private var writeOk = false
        @Volatile private var sent = 0L

        override val bytesSent: Long get() = sent

        override val kind: String
            get() = "BLE GATT ${writeChar?.uuid ?: device.address}"

        fun open() {
            val connected = CountDownLatch(1)
            val servicesReady = CountDownLatch(1)
            var failure: String? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        log("  BLE 已连接，发现服务 …")
                        val ok = runCatching { g.discoverServices() }.isSuccess
                        if (!ok) {
                            failure = "discoverServices 调用失败"
                            connected.countDown()
                            servicesReady.countDown()
                        }
                    } else {
                        failure = "GATT 连接失败 status=$status state=$newState"
                        connected.countDown()
                        servicesReady.countDown()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failure = "服务发现失败 status=$status"
                        connected.countDown()
                        servicesReady.countDown()
                        return
                    }
                    val ch = pickWriteCharacteristic(g)
                    if (ch == null) {
                        failure = "未找到可写特征值（该设备可能不是 BLE 打印机）"
                        connected.countDown()
                        servicesReady.countDown()
                        return
                    }
                    writeChar = ch
                    connected.countDown()
                    // 抬 MTU 减少分包；回调不一定来，超时后按默认值继续
                    runCatching { g.requestMtu(517) }
                    Handler(Looper.getMainLooper()).postDelayed({ servicesReady.countDown() }, 1200)
                }

                override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu = m.coerceAtLeast(23)
                    servicesReady.countDown()
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    writeOk = status == BluetoothGatt.GATT_SUCCESS
                    writeLatch?.countDown()
                }
            }

            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val g = gatt ?: throw IOException("connectGatt 返回 null")

            if (!connected.await(12, TimeUnit.SECONDS)) {
                runCatching { g.close() }
                throw IOException("BLE 连接超时（12s），设备可能不在可连接范围或未开机")
            }
            failure?.let { runCatching { g.close() }; throw IOException(it) }
            if (!servicesReady.await(3, TimeUnit.SECONDS)) {
                log("  MTU 协商超时，按默认 23 字节分包继续")
            }
            failure?.let { runCatching { g.close() }; throw IOException(it) }
            log("  写特征值 ${writeChar?.uuid}，MTU=$mtu")
        }

        override fun write(data: ByteArray) {
            val g = gatt ?: throw IOException("BLE 未连接")
            val ch = writeChar ?: throw IOException("无可用写特征值")
            val chunk = (mtu - 3).coerceIn(20, 512)
            var offset = 0
            while (offset < data.size) {
                val len = minOf(chunk, data.size - offset)
                val part = data.copyOfRange(offset, offset + len)
                if (!writeChunk(g, ch, part)) {
                    throw IOException("BLE 写入失败（第 $offset 字节处）")
                }
                offset += len
                sent += len
            }
        }

        private fun writeChunk(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            part: ByteArray
        ): Boolean {
            synchronized(writeLock) {
                writeOk = false
                writeLatch = CountDownLatch(1)
                ch.value = part
                ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                val waitForCallback = ch.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!g.writeCharacteristic(ch)) return false
                if (!waitForCallback) {
                    // WRITE_NO_RESPONSE 不保证回调，靠小延时做流控
                    Thread.sleep(2)
                    return true
                }
                val arrived = writeLatch?.await(5, TimeUnit.SECONDS) == true
                return arrived && writeOk
            }
        }

        override fun close() {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }
    }

    private fun pickWriteCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val services = gatt.services.orEmpty()
        for (uuid in PREFERRED_BLE_SERVICES) {
            val svc = services.firstOrNull { it.uuid == uuid } ?: continue
            svc.characteristics.firstOrNull { it.isWritable }?.let { return it }
        }
        return services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .filter { it.isWritable }
            .sortedByDescending { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE }
            .firstOrNull()
    }
}
