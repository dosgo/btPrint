package com.dosgo.btPrint;

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dosgo.btPrint.bluetooth.PrintJobLock
import com.dosgo.btPrint.bluetooth.PrinterConnector
import com.dosgo.btPrint.bluetooth.PrinterTransport
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerDevices: Spinner
    private lateinit var spinnerLabelSize: Spinner
    private lateinit var tvPdfStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPreviewLabel: TextView
    private lateinit var ivPrintPreview: ImageView
    private lateinit var tvBtState: TextView
    private lateinit var etPaperLength: EditText
    private lateinit var btnSelectPdf: Button
    private lateinit var btnPrint: Button
    private lateinit var btnTestPrint: Button
    private lateinit var btnRefreshDevices: Button

    // 打印锁：整条链路（渲染 → 连接 → 分幅下发 → 走纸）是串行的，
    // 重复点击会让第二份指令插进蓝牙栈，打印机要么丢数据要么锁死。
    // 锁放在进程级单例里，adb 开出第二个 Activity 实例时同样有效。
    /** 是否正有打印任务在跑 */
    private val isPrinting: Boolean
        get() = PrintJobLock.isBusy

    private var selectedPdfUri: Uri? = null
    private var preparedPrintData: PreparedPrintData? = null
    // 用 LinkedHashMap 去重并保持发现顺序，同时容纳已配对设备和扫描到的新设备
    private val foundDevices = LinkedHashMap<String, BluetoothDevice>()
    private var discoveryReceiver: BroadcastReceiver? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val deviceList: List<BluetoothDevice>
        get() = foundDevices.values.toList()

    // adb 调试入口：绕过界面直接给指定 MAC 发指令
    // adb shell am start -n com.dosgo.btPrint/.MainActivity --es test_mac "3B:B3:19:04:3F:C0" --es test_cmd "..."
    private var pendingDebugMac: String? = null
    private var pendingDebugCmd: String? = null
    private var pendingDebugBmpW = 0
    private var pendingDebugBmpH = 0
    private var pendingThrottleMs = PrinterConnector.DEFAULT_THROTTLE_MS
    private var pendingBandDelayMs = DEFAULT_BAND_DELAY_MS
    private var pendingSegmentMm = DEFAULT_SEGMENT_HEIGHT_MM
    // 批量测试：一次连接连打多个尺寸（逗号分隔，如 "50x30,100x15,100x30"），
    // 打印机收到超限指令会锁死，靠这个把二分压缩到一次重启内完成。
    private var pendingDebugBatch: List<Pair<Int, Int>> = emptyList()
    private var pendingGapMm = DEFAULT_GAP_MM
    private var pendingPaperMm = DEFAULT_PAPER_LENGTH_MM
    // 单条 BITMAP 的字节上限（可调，用于二分固件到底限制的是条带数还是总字节）
    private var pendingBandBytes = MAX_BAND_BYTES
    // 单幅点阵的字节上限（同样可调，默认值来自实测的 24KB）
    private var pendingSegmentBytes = MAX_SEGMENT_BYTES
    // adb 直连打印指定 PDF：--es test_pdf /data/local/tmp/x.pdf --es test_pdf_size 100x150
    private var pendingPdfPath: String? = null
    private var pendingPdfW = 100
    private var pendingPdfH = 150
    // 渲染引擎：pdfium（内置，缺字体时靠补字） / system（系统 PdfRenderer，带字体回退）
    private var pendingRenderEngine = "pdfium"
    // 只渲染不打印，调渲染效果时避免浪费纸
    private var pendingRenderOnly = false

    private data class LabelSize(val widthMm: Int, val heightMm: Int) {
        val widthDots: Int get() = widthMm * DOTS_PER_MM
        val heightDots: Int get() = heightMm * DOTS_PER_MM
        // 203dpi 下每行字节数正好等于纸宽毫米数（8 dot/mm ÷ 8 bit/byte）
        val widthBytes: Int get() = widthMm
        // 单幅能装下的行数受纸长与缓冲双重限制，超出就要拆成多幅依次打印
        val segments: Int
            get() {
                val rowLimit = minOf(
                    MAX_SEGMENT_HEIGHT_DOTS,
                    (MAX_SEGMENT_BYTES / widthBytes).coerceAtLeast(1)
                )
                return (heightDots + rowLimit - 1) / rowLimit
            }
        override fun toString() = buildString {
            append("${widthMm} × ${heightMm} mm（${widthDots} × ${heightDots} 点）")
            if (segments > 1) append(" · 自动分 $segments 幅")
        }
    }

    private val labelSizes = listOf(
        LabelSize(100, 150), // 4×6 面单原始比例：按纸长自动分页，每页一张纸
        LabelSize(102, 152), // 4×6 英寸精确值
        LabelSize(100, 99),  // N41 单幅纸长上限
        LabelSize(100, 50),
        LabelSize(100, 30),
        LabelSize(60, 40),
        LabelSize(50, 30)    // 已实测能正常出纸的规格
    )

    private data class PreparedPrintData(
        val sourceUri: Uri,
        val labelSize: LabelSize,
        val bitmapData: ByteArray,
        val widthInBytes: Int,
        val heightDots: Int,
        val previewBitmap: Bitmap
    )

    // 文件选择器
    private val selectPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) openPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerDevices = findViewById(R.id.spinnerDevices)
        spinnerLabelSize = findViewById(R.id.spinnerLabelSize)
        tvPdfStatus = findViewById(R.id.tvPdfStatus)
        tvLog = findViewById(R.id.tvLog)
        tvPreviewLabel = findViewById(R.id.tvPreviewLabel)
        ivPrintPreview = findViewById(R.id.ivPrintPreview)
        tvBtState = findViewById(R.id.tvBtState)
        etPaperLength = findViewById(R.id.etPaperLength)

        spinnerLabelSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labelSizes
        )
        // 默认 100×150：面单是 4×6（108×153mm）的竖长比例，按满宽 100mm 打，
        // 会按纸长自动分成 99mm + 51mm 两张纸，内容占满纸宽、字最大。
        // 若只想用一张纸，手动选「100 × 99」即可，但内容会被等比压到约 70mm 宽，字更小。
        spinnerLabelSize.setSelection(
            labelSizes.indexOfFirst { it.widthMm == 100 && it.heightMm == 150 }
                .coerceAtLeast(0)
        )
        spinnerLabelSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val uri = selectedPdfUri ?: return
                preparedPrintData = null
                ivPrintPreview.setImageDrawable(null)
                ivPrintPreview.visibility = View.GONE
                tvPreviewLabel.visibility = View.GONE
                tvPdfStatus.text = "纸张规格已改变，正在重新生成预览..."
                preparePreview(uri, labelSizes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnSelectPdf = findViewById(R.id.btnSelectPdf)
        btnPrint = findViewById(R.id.btnPrint)
        btnTestPrint = findViewById(R.id.btnTestPrint)
        btnRefreshDevices = findViewById(R.id.btnRefreshDevices)

        btnSelectPdf.setOnClickListener {
            selectPdfLauncher.launch("application/pdf")
        }

        btnPrint.setOnClickListener {
            startPrintFlow()
        }

        btnRefreshDevices.setOnClickListener {
            if (isPrinting) {
                tvBtState.text = "打印中，暂不能重新搜索设备"
            } else if (hasBluetoothPermissions()) {
                refreshDeviceList()
            } else {
                checkAndRequestPermissions()
            }
        }

        btnTestPrint.setOnClickListener {
            startTestPrint()
        }

        readDebugExtras(intent)

        if (pendingDebugMac != null) {
            Log.i(
                "BtPrint",
                "收到调试参数 mac=$pendingDebugMac bmp=${pendingDebugBmpW}x${pendingDebugBmpH} " +
                    "throttle=$pendingThrottleMs cmd=${pendingDebugCmd?.take(60)}"
            )
        }

        checkAndRequestPermissions()

        intent?.data?.let(::openPdf)
    }

    private fun readDebugExtras(source: Intent?) {
        pendingDebugMac = source?.getStringExtra("test_mac")
        pendingDebugBmpW = source?.getIntExtra("test_bmp_w", 0) ?: 0
        pendingDebugBmpH = source?.getIntExtra("test_bmp_h", 0) ?: 0
        pendingThrottleMs = source?.getIntExtra("test_throttle", PrinterConnector.DEFAULT_THROTTLE_MS)
            ?: PrinterConnector.DEFAULT_THROTTLE_MS
        pendingBandDelayMs = source?.getLongExtra("test_band_delay", DEFAULT_BAND_DELAY_MS)
            ?: DEFAULT_BAND_DELAY_MS
        // 单幅高度（mm）。N41 上限 99，调小可二分定位打印缓冲上限。
        pendingSegmentMm = (source?.getIntExtra("test_seg_mm", DEFAULT_SEGMENT_HEIGHT_MM)
            ?: DEFAULT_SEGMENT_HEIGHT_MM).coerceIn(10, MAX_SEGMENT_HEIGHT_MM)
        pendingGapMm = source?.getIntExtra("test_gap_mm", DEFAULT_GAP_MM) ?: DEFAULT_GAP_MM
        pendingPaperMm = source?.getIntExtra("test_paper_mm", DEFAULT_PAPER_LENGTH_MM)
            ?: DEFAULT_PAPER_LENGTH_MM
        pendingBandBytes = (source?.getIntExtra("test_band_bytes", MAX_BAND_BYTES) ?: MAX_BAND_BYTES)
            .coerceIn(512, 65536)
        pendingSegmentBytes = (source?.getIntExtra("test_seg_bytes", MAX_SEGMENT_BYTES)
            ?: MAX_SEGMENT_BYTES).coerceIn(1024, 200000)
        pendingPdfPath = source?.getStringExtra("test_pdf")
        // 默认 MuPDF：字体回退完整，渲染效果与 Chrome / WPS 一致
        pendingRenderEngine = source?.getStringExtra("test_engine") ?: "mupdf"
        pendingRenderOnly = source?.getBooleanExtra("test_render_only", false) ?: false
        source?.getStringExtra("test_pdf_size")?.let { raw ->
            val nums = raw.split("x")
            pendingPdfW = nums.getOrNull(0)?.trim()?.toIntOrNull() ?: 100
            pendingPdfH = nums.getOrNull(1)?.trim()?.toIntOrNull() ?: 150
        }
        pendingDebugBatch = source?.getStringExtra("test_batch")?.let { raw ->
            raw.split(",").mapNotNull { part ->
                val nums = part.split("x")
                val w = nums.getOrNull(0)?.trim()?.toIntOrNull()
                val h = nums.getOrNull(1)?.trim()?.toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) w to h else null
            }
        } ?: emptyList()
        // 带空格的指令走 base64，避免被 adb shell 按空格拆参数
        val b64 = source?.getStringExtra("test_b64")
        pendingDebugCmd = source?.getStringExtra("test_cmd") ?: b64?.let {
            runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }
    }

    override fun onResume() {
        super.onResume()
        // 打印在工作线程里跑，界面可能被重建（转屏 / 从选择器返回 / 又开了一个实例），
        // 回到前台时先把按钮状态和进程级锁对齐，避免出现"锁被占着但按钮可点"。
        setBusyUi(isPrinting)
        if (isPrinting) {
            tvBtState.text =
                "打印中：「${PrintJobLock.currentJob ?: "打印任务"}」，请勿重复点击"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDebugExtras(intent)
        if (pendingDebugMac != null) {
            Log.i("BtPrint", "onNewIntent 收到调试参数 mac=$pendingDebugMac bmp=${pendingDebugBmpW}x${pendingDebugBmpH}")
            val w = pendingDebugBmpW
            val h = pendingDebugBmpH
            val throttle = pendingThrottleMs
            pendingDebugMac = null
            runDebugSend(intent.getStringExtra("test_mac") ?: return, pendingDebugCmd, w to h, throttle)
        }
    }

    private fun openPdf(uri: Uri) {
        selectedPdfUri = uri
        preparedPrintData = null
        ivPrintPreview.setImageDrawable(null)
        ivPrintPreview.visibility = View.GONE
        tvPreviewLabel.visibility = View.GONE
        tvPdfStatus.text = "正在生成打印预览: ${uri.lastPathSegment}"
        tvLog.text = "正在按 203 DPI 渲染第一页..."
        preparePreview(uri, selectedLabelSize())
    }

    private fun selectedLabelSize(): LabelSize =
        labelSizes.getOrElse(spinnerLabelSize.selectedItemPosition) { labelSizes.first() }

    /** 物理纸张长度（mm），决定内容跨纸时在哪里分页对齐。 */
    private fun paperLengthMm(): Int =
        etPaperLength.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(20, MAX_SEGMENT_HEIGHT_MM) ?: DEFAULT_PAPER_LENGTH_MM

    private fun preparePreview(uri: Uri, labelSize: LabelSize) {
        thread {
            try {
                val prepared = preparePrintData(uri, labelSize)
                runOnUiThread {
                    if (selectedPdfUri != uri || selectedLabelSize() != labelSize) return@runOnUiThread
                    preparedPrintData = prepared
                    ivPrintPreview.setImageBitmap(prepared.previewBitmap)
                    ivPrintPreview.visibility = View.VISIBLE
                    tvPreviewLabel.visibility = View.VISIBLE
                    tvPdfStatus.text = "已选文件: ${uri.lastPathSegment}"
                    tvLog.text = "预览已按 ${labelSize.widthDots} × ${labelSize.heightDots} 点生成"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (selectedPdfUri != uri || selectedLabelSize() != labelSize) return@runOnUiThread
                    tvPdfStatus.text = "PDF 预览生成失败"
                    tvLog.text = "无法读取 PDF: ${e.message}"
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBluetoothPermissions(): Boolean =
        requiredPermissions().all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkAndRequestPermissions() {
        if (hasBluetoothPermissions()) {
            afterPermissionGranted()
        } else {
            requestPermissions(requiredPermissions(), REQ_BLUETOOTH)
        }
    }

    // 之前这里没有回调：用户在 Android 12+ 上点了"允许"之后代码什么都不做，
    // 结果设备列表永远为空，看起来就像"连不上打印机"。
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        val detail = permissions.indices.joinToString { "${permissions[it]}=${grantResults.getOrNull(it)}" }
        Log.i("BtPrint", "权限回调: $detail")
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            afterPermissionGranted()
        } else {
            tvBtState.text = "蓝牙权限被拒绝，无法搜索打印机"
        }
    }

    private fun afterPermissionGranted() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            tvBtState.text = "此设备不支持蓝牙"
            return
        }
        Log.i("BtPrint", "蓝牙开关状态 isEnabled=${adapter.isEnabled}")
        if (!adapter.isEnabled) {
            tvBtState.text = "蓝牙未开启，请在系统设置里打开蓝牙后点刷新"
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return
        }
        val debugMac = pendingDebugMac
        if (debugMac != null) {
            val w = pendingDebugBmpW
            val h = pendingDebugBmpH
            val throttle = pendingThrottleMs
            pendingDebugMac = null
            runDebugSend(debugMac, pendingDebugCmd, w to h, throttle)
            return
        }
        refreshDeviceList()
    }

    /** adb 直连发指令，用于脱离界面二分定位问题。 */
    private fun runDebugSend(
        mac: String,
        rawCmd: String?,
        bitmapMm: Pair<Int, Int>,
        throttleMs: Int
    ) {
        val renderOnlyPdf = pendingPdfPath
        if (pendingRenderOnly && renderOnlyPdf != null) {
            tvLog.text = "仅渲染中..."
            thread {
                try {
                    val prepared = preparePrintData(
                        Uri.fromFile(File(renderOnlyPdf)),
                        LabelSize(pendingPdfW, pendingPdfH)
                    )
                    Log.i("BtPrint", "仅渲染完成：${prepared.bitmapData.size} 字节（未发送）")
                    runOnUiThread {
                        tvLog.text = "仅渲染完成（${prepared.bitmapData.size} 字节，未发送）"
                    }
                } catch (error: Exception) {
                    Log.e("BtPrint", "仅渲染失败", error)
                    runOnUiThread { tvLog.text = "仅渲染失败：${error.message}" }
                }
            }
            return
        }
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            tvLog.text = "MAC 无效: $mac"
            Log.w("BtPrint", "无法构造 BluetoothDevice: $mac")
            return
        }
        Log.i("BtPrint", "调试直连 $mac，指令=${rawCmd ?: "<测试页>"}，节流=${throttleMs}ms/512B")
        // adb 连发两条 am start 也是并发来源，同样要走打印锁
        if (!acquirePrintLock("调试下发")) return
        tvLog.text = "调试发送中..."
        thread {
            try {
                connectAndRun(device, throttleMs) { transport ->
                    val pdfPath = pendingPdfPath
                    if (pdfPath != null) {
                        val file = File(pdfPath)
                        if (!file.exists()) return@connectAndRun "PDF 不存在：$pdfPath"
                        val size = LabelSize(pendingPdfW, pendingPdfH)
                        val prepared = preparePrintData(Uri.fromFile(file), size)
                        val pages = sendToPrinter(
                            transport, prepared.bitmapData, prepared.widthInBytes,
                            prepared.heightDots, size, pendingSegmentMm, pendingBandDelayMs,
                            pendingGapMm, pendingPaperMm, pendingBandBytes, pendingSegmentBytes
                        )
                        return@connectAndRun "PDF 打印完成，共 $pages 幅（${prepared.bitmapData.size} 字节）"
                    }
                    if (pendingDebugBatch.isNotEmpty()) {
                        val total = pendingDebugBatch.size
                        pendingDebugBatch.forEachIndexed { i, wh ->
                            Log.i("BtPrint", "批量 ${i + 1}/$total：${wh.first}x${wh.second}mm")
                            sendDebugBitmap(transport, wh.first, wh.second, pendingBandDelayMs)
                            if (i < total - 1) Thread.sleep(BATCH_GAP_MS)
                        }
                        return@connectAndRun "批量测试完成，共 $total 张"
                    }
                    if (bitmapMm.first > 0 && bitmapMm.second > 0) {
                        sendDebugBitmap(transport, bitmapMm.first, bitmapMm.second, pendingBandDelayMs)
                    } else {
                        val data = rawCmd?.let { (it.replace("\\n", "\r\n") + "\r\n").toByteArray(Charsets.US_ASCII) }
                            ?: buildTestPageCommand()
                        Log.i(
                            "BtPrint",
                            "调试发送 ${data.size} 字节 via ${transport.kind}: ${asciiPreview(data, 300)}"
                        )
                        transport.write(data)
                        Thread.sleep(1500)
                        "已发送 ${data.size} 字节（${transport.kind}）"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvLog.text = "调试发送失败: ${e.message}" }
            } finally {
                releasePrintLock()
            }
        }
    }

    /** 已配对设备 + 主动扫描，BLE 打印机不需要配对，只做 bondedDevices 会漏掉它。 */
    private fun refreshDeviceList() {
        val adapter = bluetoothAdapter ?: return
        foundDevices.clear()
        runCatching {
            adapter.bondedDevices?.forEach { foundDevices[it.address] = it }
        }
        Log.i("BtPrint", "已配对设备 ${foundDevices.size} 个: ${foundDevices.values.joinToString { "${safeDeviceName(it)}(${it.address})" }}")
        updateDeviceSpinner()
        registerDiscoveryReceiver()
        startDiscovery(adapter)
    }

    private fun startDiscovery(adapter: BluetoothAdapter) {
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) {
                tvBtState.text = "扫描启动失败，请检查蓝牙开关与定位权限"
                return
            }
            tvBtState.text = "正在搜索蓝牙设备…（已配对 ${foundDevices.size} 个）"
        } catch (e: SecurityException) {
            tvBtState.text = "缺少扫描权限：${e.message}"
        }
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        } ?: return
                        foundDevices[dev.address] = dev
                        updateDeviceSpinner()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        tvBtState.text = "搜索结束，共发现 ${foundDevices.size} 个设备"
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        discoveryReceiver = receiver
    }

    private fun updateDeviceSpinner() {
        val previousAddress = spinnerDevices.selectedItemPosition
            .takeIf { it >= 0 }
            ?.let { deviceList.getOrNull(it)?.address }

        val names = deviceList.map { dev ->
            val kind = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 -> ""
                dev.type == BluetoothDevice.DEVICE_TYPE_LE -> " [LE]"
                dev.type == BluetoothDevice.DEVICE_TYPE_DUAL -> " [双模]"
                dev.type == BluetoothDevice.DEVICE_TYPE_CLASSIC -> " [经典]"
                else -> ""
            }
            "${safeDeviceName(dev)} (${dev.address})$kind"
        }

        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        previousAddress?.let { addr ->
            deviceList.indexOfFirst { it.address == addr }
                .takeIf { it >= 0 }
                ?.let { spinnerDevices.setSelection(it) }
        }
    }

    private fun safeDeviceName(dev: BluetoothDevice): String =
        runCatching { dev.name }.getOrNull() ?: "未知设备"

    // ---- 打印锁 ----

    /**
     * 抢占打印锁。同一时刻只允许一份任务在跑：打印中再次点击「打印 / 测试页 /
     * 调试下发」都会被直接拒绝，不会产生第二条连接，也不会往同一条通道里插指令。
     * 必须在主线程调用（按钮回调本来就在主线程）。
     */
    private fun acquirePrintLock(job: String): Boolean {
        val holder = PrintJobLock.tryAcquire(job)
        if (holder != null) {
            val msg = "「$holder」还没打完，等它结束再点（打印过程中不允许重复下发）"
            tvLog.text = msg
            tvBtState.text = "打印中：「$holder」"
            Log.w("BtPrint", "拒绝并发请求[$job]，当前正在执行[$holder]")
            return false
        }
        setBusyUi(true)
        tvBtState.text = "打印中：「$job」，请勿重复点击"
        return true
    }

    /** 释放打印锁。可能在工作线程上调用，UI 状态切回主线程。 */
    private fun releasePrintLock() {
        PrintJobLock.release()
        runOnUiThread {
            setBusyUi(false)
            tvBtState.text = "空闲，可以打印下一份"
        }
    }

    /**
     * 打印期间锁住会破坏当前任务的操作：重复打印、换设备、换纸规、重新选文件。
     * 换纸规会触发重新渲染，换设备/搜索会打断 RFCOMM 握手。
     */
    private fun setBusyUi(busy: Boolean) {
        btnPrint.isEnabled = !busy
        btnTestPrint.isEnabled = !busy
        btnRefreshDevices.isEnabled = !busy
        btnSelectPdf.isEnabled = !busy
        spinnerDevices.isEnabled = !busy
        spinnerLabelSize.isEnabled = !busy
        etPaperLength.isEnabled = !busy
        btnPrint.text = if (busy) "打印中…（请等待）" else "4. 开始高清打印"
    }

    private fun startPrintFlow() {
        // 先抢锁再校验：打印中点击应该提示"还没打完"，而不是被当成参数错误
        if (!acquirePrintLock("打印 PDF")) return
        val uri = selectedPdfUri
        if (uri == null) {
            tvLog.text = "请先选择 PDF 文件！"
            releasePrintLock()
            return
        }
        val targetDevice = deviceList.getOrNull(spinnerDevices.selectedItemPosition)
        if (targetDevice == null) {
            tvLog.text = "请先选择一个蓝牙打印机（点上方刷新重新搜索）"
            Log.w("BtPrint", "设备列表为空，无法打印。当前列表大小=${deviceList.size}")
            releasePrintLock()
            return
        }
        Log.i(
            "BtPrint",
            "目标设备 ${safeDeviceName(targetDevice)} (${targetDevice.address}) " +
                "type=${runCatching { targetDevice.type }.getOrDefault(-1)} " +
                "bond=${runCatching { targetDevice.bondState }.getOrDefault(-1)}"
        )
        val labelSize = selectedLabelSize()
        tvLog.text = "正在渲染并准备打印..."

        thread {
            try {
                // 优先复用屏幕中显示的同一份点阵，确保预览和实际打印完全一致。
                val prepared = preparedPrintData
                    ?.takeIf { it.sourceUri == uri && it.labelSize == labelSize }
                    ?: preparePrintData(uri, labelSize)

                connectAndRun(targetDevice) { transport ->
                    Log.i(
                        "BtPrint",
                        "下发 BITMAP ${prepared.widthInBytes} 字节/行 × ${prepared.heightDots} 行，" +
                            "点阵共 ${prepared.bitmapData.size} 字节，通道=${transport.kind}"
                    )
                    val pages = sendToPrinter(
                        transport,
                        prepared.bitmapData,
                        prepared.widthInBytes,
                        prepared.heightDots,
                        labelSize,
                        paperMm = paperLengthMm()
                    )
                    if (pages > 1) {
                        "打印完成！共 $pages 张纸，每张都从纸头起打（${transport.kind}，${prepared.bitmapData.size} 字节）"
                    } else {
                        "打印完成！（${transport.kind}，${prepared.bitmapData.size} 字节）"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvLog.text = "打印失败: ${e.message}" }
            } finally {
                // 失败也要解锁，否则一次异常就把整个 App 的打印功能锁死
                releasePrintLock()
            }
        }
    }

    /** 不依赖 PDF 的最小测试页。能打出来说明通道和指令都对，问题在点阵数据本身。 */
    private fun startTestPrint() {
        if (!acquirePrintLock("发送测试页")) return
        val targetDevice = deviceList.getOrNull(spinnerDevices.selectedItemPosition)
        if (targetDevice == null) {
            tvLog.text = "请先选择一个蓝牙打印机（点上方刷新重新搜索）"
            Log.w("BtPrint", "设备列表为空，无法发测试页")
            releasePrintLock()
            return
        }
        Log.i("BtPrint", "发送测试页到 ${safeDeviceName(targetDevice)} (${targetDevice.address})")
        tvLog.text = "正在发送测试页..."

        thread {
            try {
                connectAndRun(targetDevice) { transport ->
                    val cmd = buildTestPageCommand()
                    Log.i("BtPrint", "测试页 ${cmd.size} 字节，通道=${transport.kind}")
                    transport.write(cmd)
                    Thread.sleep(1500)
                    "测试页已发送（${transport.kind}）"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvLog.text = "测试页失败: ${e.message}" }
            } finally {
                releasePrintLock()
            }
        }
    }

    /** 取连接 → 探活 → 执行 → 归还/作废，统一处理日志与重试。 */
    private fun connectAndRun(
        device: BluetoothDevice,
        throttleMs: Int = PrinterConnector.DEFAULT_THROTTLE_MS,
        job: (PrinterTransport) -> String
    ) {
        var lastError: IOException? = null
        var attempt = 0
        var retryDelay = 0L
        while (attempt < SEND_ATTEMPTS) {
            attempt++
            if (retryDelay > 0L) {
                Log.w("BtPrint", "等 ${retryDelay}ms 让打印机复位后重连")
                runCatching { Thread.sleep(retryDelay) }
            }
            val transport = PrinterConnector.acquire(this, device, { msg ->
                Log.i("BtPrint", msg)
                runOnUiThread { tvLog.text = msg }
            }, throttleMs)
            var handedOver = false
            try {
                // 探活：TSPL 下空行是空操作。上一份打完对面把 socket 关掉时，
                // 这里立刻抛 EPIPE(Broken pipe)，此时还没发任何真实数据，重建连接最安全。
                transport.write(PROBE_LINE)
                val result = job(transport)
                // 正常打完：交还连接，窗口期内下一份直接复用，不重连就不会 broken pipe
                PrinterConnector.release(transport)
                handedOver = true
                runOnUiThread { tvLog.text = result }
                return
            } catch (e: IOException) {
                lastError = e
                val sent = transport.bytesSent
                val realSent = (sent - PROBE_LINE.size).coerceAtLeast(0L)
                // 只有"真实数据一个字节都没发出去"才重发：那时打印机没收到指令，
                // 整份重发不会多出一张纸；已经发过一半（可能已发过 PRINT）就绝不重试。
                val retryable = attempt < SEND_ATTEMPTS && realSent == 0L
                Log.w(
                    "BtPrint",
                    "第 $attempt 次下发失败（实发 $realSent 字节）：${e.message}" +
                        if (retryable) "，重建连接后整份重发" else "，不再重试"
                )
                if (!retryable) throw e
                runOnUiThread {
                    tvLog.text = "连接已失效（${e.message}），正在重连重发…"
                }
                // 打印机刚断链时往往还在走纸/复位，给个递增退避再握手，避免又拿到死通道。
                // 注意：这里只记延迟，真正 sleep 在下一轮开头——必须先把这条通道关掉。
                retryDelay = 800L * attempt
            } finally {
                // 成功时已经 release（连接保留），其余情况一律作废，下次重新握手
                if (!handedOver) PrinterConnector.invalidate(transport)
            }
        }
        throw lastError
            ?: IOException("重试 $SEND_ATTEMPTS 次仍未发出数据，打印机可能已关机或不在范围")
    }

    private fun buildTestPageCommand(): ByteArray {
        // 必须 <=99mm：N41 学习到的标签长度只有 99mm，超过会整页拒打并锁死
        val widthMm = 100
        val heightMm = 60
        val sb = StringBuilder()
        sb.append("SIZE $widthMm mm, $heightMm mm\r\n")
        sb.append("GAP 2 mm, 0 mm\r\n")
        sb.append("CLS\r\n")
        // 显式设定打印参数，避免沿用上一次会话的浓度/速度导致出纸异常
        sb.append("DENSITY 8\r\n")
        sb.append("SPEED 4\r\n")
        sb.append("DIRECTION 0\r\n")
        sb.append("TEXT 60,60,\"3\",0,1,1,\"BT PRINT OK\"\r\n")
        sb.append("BAR 60,220,700,8\r\n")
        sb.append("TEXT 60,300,\"2\",0,1,1,\"TSPL TEST PAGE\"\r\n")
        sb.append("PRINT 1,1\r\n")
        val data = sb.toString().toByteArray(Charsets.US_ASCII)
        Log.d("BtPrint", "测试页指令: ${sb.toString().replace("\r\n", " | ")}")
        return data
    }

    /** 点阵是二进制，直接进 logcat 会把缓冲区打爆，这里只取头部的可打印部分。 */
    private fun asciiPreview(data: ByteArray, limit: Int): String {
        val end = minOf(limit, data.size)
        val sb = StringBuilder()
        for (i in 0 until end) {
            val b = data[i].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        return sb.toString().replace("\r\n", " | ")
    }

    /** 生成指定尺寸的条纹点阵并走与正式打印完全相同的条带流程，否则测试没有参考价值。 */
    private fun sendDebugBitmap(
        transport: PrinterTransport,
        widthMm: Int,
        heightMm: Int,
        bandDelayMs: Long
    ): String {
        val wDots = widthMm * DOTS_PER_MM
        val hDots = heightMm * DOTS_PER_MM
        val bitmap = Bitmap.createBitmap(wDots, hDots, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }
        // 每 10mm 一条黑带，带内写上连续编号。编号连贯即说明分幅没有跳纸/重叠。
        for (y in 0 until hDots step 80) {
            canvas.drawRect(0f, y.toFloat(), wDots.toFloat(), minOf(y + 40, hDots).toFloat(), paint)
            canvas.drawText(
                "${y / 80}",
                16f,
                minOf(y + 36, hDots - 6).toFloat(),
                labelPaint
            )
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawRect(0f, 0f, wDots.toFloat(), hDots.toFloat(), paint)
        // 右上角反白标注整张尺寸
        canvas.drawText(
            "${widthMm}x${heightMm}",
            wDots - 220f,
            minOf(hDots - 8f, 700f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
        )

        val (data, widthBytes) = convertBitmapTo1BitTspl(bitmap)
        bitmap.recycle()

        // 走与正式打印完全相同的分幅流程，否则测试结果没有参考价值
        val pages = sendToPrinter(
            transport, data, widthBytes, hDots,
            LabelSize(widthMm, heightMm), pendingSegmentMm, bandDelayMs, pendingGapMm,
            pendingPaperMm, pendingBandBytes, pendingSegmentBytes
        )
        return "调试点阵 ${widthMm}x${heightMm}mm（${data.size} 字节，分 $pages 幅，" +
            "每幅<=${pendingSegmentMm}mm）已发送（${transport.kind}）"
    }

    private fun preparePrintData(uri: Uri, labelSize: LabelSize): PreparedPrintData {
        // 把源 PDF 留一份到 cache，便于排查渲染问题（不影响打印）
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                File(cacheDir, "print-source.pdf").outputStream().use { input.copyTo(it) }
            }
        }
        val bitmap = renderPdfToBitmap(uri, labelSize.widthDots, labelSize.heightDots)
        FileOutputStream(File(cacheDir, "render-debug.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val (bitmapData, widthInBytes) = convertBitmapTo1BitTspl(bitmap)
        val preview = createPreviewFromTspl(bitmapData, bitmap.width, bitmap.height, widthInBytes)
        bitmap.recycle()
        return PreparedPrintData(uri, labelSize, bitmapData, widthInBytes, preview.height, preview)
    }

    // 渲染入口。三种引擎：
    //   mupdf  —— 默认。自带 CJK 字体回退，面单这类不嵌字体 + Tm 水平缩放的 PDF 也能还原；
    //   system —— Android 自带 PdfRenderer，只在 MuPDF 异常时兜底（不出中文）。
    private fun renderPdfToBitmap(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        when (pendingRenderEngine) {
            "system" -> return runCatching {
                renderWithSystemRenderer(uri, targetWidth, targetHeight)
            }.getOrElse { error ->
                Log.w("BtPrintRenderer", "系统渲染失败，回退 MuPDF：${error.message}")
                renderWithMuPdf(uri, targetWidth, targetHeight)
            }
        }
        return runCatching { renderWithMuPdf(uri, targetWidth, targetHeight) }
            .getOrElse { error ->
                Log.w("BtPrintRenderer", "MuPDF 渲染失败，回退系统渲染器：${error.message}")
                renderWithSystemRenderer(uri, targetWidth, targetHeight)
            }
    }

    // MuPDF（WPS 同类引擎）。它内置 CJK 字体回退，遇到只写字体名不嵌字体的
    // 中文文本时能按 PDF 声明的字宽排版，不会出现缺字形或英文挤在一起的问题。
    private fun renderWithMuPdf(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val source = File(cacheDir, "mupdf-render-source.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().use { input.copyTo(it) }
        } ?: throw Exception("无法读取 PDF")

        val document = com.artifex.mupdf.fitz.Document.openDocument(source.absolutePath)
        try {
            if (document.countPages() <= 0) throw Exception("PDF 没有页面")
            val page = document.loadPage(0)
            try {
                val bounds = page.bounds
                val pageWidth = (bounds.x1 - bounds.x0).coerceAtLeast(1f)
                val pageHeight = (bounds.y1 - bounds.y0).coerceAtLeast(1f)
                val scale = minOf(targetWidth / pageWidth, targetHeight / pageHeight)
                val pixmap = page.toPixmap(
                    com.artifex.mupdf.fitz.Matrix(scale, scale),
                    com.artifex.mupdf.fitz.ColorSpace.DeviceRGB,
                    false,
                    true
                )
                try {
                    val width = pixmap.width
                    val height = pixmap.height
                    val stride = pixmap.stride
                    val components = pixmap.numberOfComponents.coerceAtLeast(3)
                    val samples = pixmap.samples
                    val pixels = IntArray(width * height)
                    var cursor = 0
                    for (y in 0 until height) {
                        val row = y * stride
                        for (x in 0 until width) {
                            val at = row + x * components
                            val r = samples[at].toInt() and 0xFF
                            val g = samples[at + 1].toInt() and 0xFF
                            val b = samples[at + 2].toInt() and 0xFF
                            pixels[cursor++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                        }
                    }
                    val pageBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    Canvas(bitmap).drawBitmap(
                        pageBitmap,
                        ((targetWidth - width) / 2).toFloat(),
                        ((targetHeight - height) / 2).toFloat(),
                        null
                    )
                    pageBitmap.recycle()
                    Log.i(
                        "BtPrintRenderer",
                        "MuPDF 渲染完成 ${width}x$height scale=${String.format("%.2f", scale)}"
                    )
                    return bitmap
                } finally {
                    pixmap.destroy()
                }
            } finally {
                page.destroy()
            }
        } finally {
            document.destroy()
        }
    }

    // Android 自带的 PdfRenderer 由系统 PDFium + 系统字体提供字形，
    // 对未嵌入字体的面单 PDF 通常比自建 PDFium 更准（WPS 走的是同类路径）。
    private fun renderWithSystemRenderer(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        descriptor.use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) throw Exception("PDF 没有可渲染的页面")
                renderer.openPage(0).use { page ->
                    val pageWidth = page.width.coerceAtLeast(1)
                    val pageHeight = page.height.coerceAtLeast(1)
                    val scale = minOf(
                        targetWidth.toFloat() / pageWidth,
                        targetHeight.toFloat() / pageHeight
                    )
                    val drawWidth = (pageWidth * scale).roundToInt().coerceAtLeast(1)
                    val drawHeight = (pageHeight * scale).roundToInt().coerceAtLeast(1)

                    // 同样先高分辨率渲染再降采样，减轻二值化后的锯齿
                    val pageBitmap = Bitmap.createBitmap(drawWidth, drawHeight, Bitmap.Config.ARGB_8888)
                    pageBitmap.eraseColor(Color.WHITE)
                    page.render(
                        pageBitmap,
                        null,
                        null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    )

                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    Canvas(bitmap).drawBitmap(
                        pageBitmap,
                        ((targetWidth - drawWidth) / 2).toFloat(),
                        ((targetHeight - drawHeight) / 2).toFloat(),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    pageBitmap.recycle()
                    // 系统渲染器的英文/数字字宽是准的，但完全不出中文字形，
                    Log.i("BtPrintRenderer", "系统 PdfRenderer 渲染完成 ${drawWidth}x$drawHeight")
                    return bitmap
                }
            }
        }
    }

    // 核心算法：1-bit 硬二值化点阵编码（TSPL BITMAP 规范）
    // TSPL 规则：1 个 Byte 管横向 8 个像素，0 为黑（出墨），1 为白（空白）
    private fun convertBitmapTo1BitTspl(bitmap: Bitmap): Pair<ByteArray, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val widthInBytes = (width + 7) / 8
        val buffer = ByteArray(widthInBytes * height)

        var byteIndex = 0
        for (y in 0 until height) {
            for (byteX in 0 until widthInBytes) {
                var currentByte = 0
                for (bit in 0 until 8) {
                    val x = byteX * 8 + bit
                    if (x < width) {
                        val pixel = bitmap.getPixel(x, y)
                        // 计算灰度值
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val gray = (r * 30 + g * 59 + b * 11) / 100

                        // 硬阈值切分：覆盖率 ≥ 50% 出墨。此前用 180 会让
                        // 抗锯齿边缘的浅灰全部丢掉，小字笔画断裂、发虚。
                        // TSPL 规范中：0 代表打点(黑)，1 代表不打点(白)
                        if (gray >= 128) {
                            currentByte = currentByte or (0x80 shr bit)
                        }
                    } else {
                        // 超出部分补白
                        currentByte = currentByte or (0x80 shr bit)
                    }
                }
                buffer[byteIndex++] = currentByte.toByte()
            }
        }
        return Pair(buffer, widthInBytes)
    }

    // 从最终发送给打印机的 1-bit 数据反解预览，保证屏幕显示与发送内容逐点一致。
    private fun createPreviewFromTspl(
        bitmapData: ByteArray,
        width: Int,
        height: Int,
        widthInBytes: Int
    ): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = bitmapData[y * widthInBytes + x / 8].toInt() and 0xFF
                val isWhite = value and (0x80 shr (x % 8)) != 0
                pixels[y * width + x] = if (isWhite) Color.WHITE else Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // TSPL 指令发送。连接与分包由 PrinterTransport 负责，这里只管协议内容。
    //
    // 关键约束：N41 自检页显示 Length*Width = 99mm*108mm，即它学习到的标签长度只有
    // 99mm。下发 SIZE 高度超过 99mm 时，打印机不报错但整张拒打，并会进入锁死状态。
    // 因此这里把点阵按 <=99mm 切成多幅，每幅单独 SIZE/CLS/BITMAP/PRINT，
    // 这样 100×150 这类超高标签也能完整打出（代价是要走多张纸）。
    private fun sendToPrinter(
        transport: PrinterTransport,
        bitmapData: ByteArray,
        widthBytes: Int,
        heightDots: Int,
        labelSize: LabelSize,
        segmentMm: Int = DEFAULT_SEGMENT_HEIGHT_MM,
        bandDelayMs: Long = DEFAULT_BAND_DELAY_MS,
        gapMm: Int = DEFAULT_GAP_MM,
        paperMm: Int = DEFAULT_PAPER_LENGTH_MM,
        bandBytes: Int = MAX_BAND_BYTES,
        segBytes: Int = MAX_SEGMENT_BYTES
    ): Int {
        // 三重约束取小：调用方指定高度、99mm 纸长上限、以及实测出来的缓冲字节上限。
        // 实测数据：50×30(12KB) 可、100×15(12KB) 可、50×60(24KB) 可、100×60(48KB) 不可。
        val rowLimitByHeight = MAX_SEGMENT_HEIGHT_MM * DOTS_PER_MM
        val rowLimitByBytes = (segBytes / widthBytes).coerceAtLeast(1)
        val segRows = minOf(
            segmentMm.coerceIn(10, MAX_SEGMENT_HEIGHT_MM) * DOTS_PER_MM,
            rowLimitByHeight,
            rowLimitByBytes
        ).coerceAtLeast(1)
        // 间隙纸模式下一次 PRINT 就是一张纸，PRINT 后打印机自己走到下一张纸头，
        // 所以每幅高度只需不超过「纸长」和「99mm 固件上限」即可，不用手工算纸内偏移。
        val paperDots = paperMm.coerceAtLeast(20) * DOTS_PER_MM
        var y = 0
        var page = 0
        while (y < heightDots) {
            val rows = minOf(segRows, paperDots, heightDots - y)
            // 尾巴只剩一点点时不值得再占一张纸
            if (heightDots - y < MIN_SEGMENT_DOTS && page > 0) break
            val pageHeightMm = (rows + DOTS_PER_MM - 1) / DOTS_PER_MM
            page++

            // 1. 每幅重新声明纸张尺寸并清画布，BITMAP 的 Y 坐标按幅内从 0 计。
            transport.write(
                ("SIZE ${labelSize.widthMm} mm, $pageHeightMm mm\r\n" +
                    "GAP $gapMm mm, 0 mm\r\nCLS\r\n" +
                    "DENSITY 8\r\nSPEED 4\r\nDIRECTION 0\r\n").toByteArray(Charsets.US_ASCII)
            )
            // 让打印机消化完设置指令再灌点阵，部分机型连着收会丢掉 BITMAP 头
            Thread.sleep(150)

            // 2. 按条带下发本幅点阵（mode 0 = OVERWRITE）
            val from = y * widthBytes
            writeBitmapBands(
                transport, bitmapData.copyOfRange(from, from + rows * widthBytes),
                widthBytes, rows, DEFAULT_BAND_ROWS, bandDelayMs, bandBytes
            )

            // 3. 出纸打印命令
            transport.write("PRINT 1,1\r\n".toByteArray(Charsets.US_ASCII))
            Log.i(
                "BtPrint",
                "第 $page 幅 = 第 $page 张纸：SIZE ${labelSize.widthMm}x${pageHeightMm}mm，" +
                    "内容行 $y..${y + rows}"
            )
            y += rows
            // 幅与幅之间留足时间，让上一张真正走完，否则下一幅会覆盖缓冲区。
            // 最后一幅要等更久：socket 一关，还在蓝牙栈缓冲里的 PRINT 就没了，
            // 实测会表现为最后一段（约 30mm）打不出来。
            Thread.sleep(if (y < heightDots) SEGMENT_GAP_MS else FINAL_SEGMENT_WAIT_MS)
        }
        return page
    }

    override fun onDestroy() {
        discoveryReceiver?.let { runCatching { unregisterReceiver(it) } }
        discoveryReceiver = null
        PrinterConnector.cancelDiscovery()
        super.onDestroy()
    }

    /**
     * 大点阵必须分条带下发。N41 对单条 BITMAP 能吃到的数据量有固件上限：
     * 实测 5000 字节/带（50mm 宽 × 100 行）正常，10000 字节/带（100mm 宽 × 100 行）
     * 则整页不动作且不报错。因此条带大小按「字节」而不是固定行数来切，
     * 保证任何纸宽下单条 BITMAP 都不超过 MAX_BAND_BYTES。
     */
    private fun writeBitmapBands(
        transport: PrinterTransport,
        data: ByteArray,
        widthBytes: Int,
        heightDots: Int,
        rowsPerBand: Int,
        bandDelayMs: Long,
        bandBytes: Int = MAX_BAND_BYTES
    ) {
        // 单条 BITMAP 的字节上限换算成行数，再和调用方给的行数上限取小
        val rowLimitByBytes = (bandBytes / widthBytes).coerceAtLeast(1)
        val bandRows = minOf(rowsPerBand, rowLimitByBytes)
        var y = 0
        var index = 0
        while (y < heightDots) {
            val rows = minOf(bandRows, heightDots - y)
            val from = y * widthBytes
            val to = from + rows * widthBytes
            transport.write("BITMAP 0,$y,$widthBytes,$rows,0,".toByteArray(Charsets.US_ASCII))
            transport.write(data.copyOfRange(from, to))
            transport.write("\r\n".toByteArray(Charsets.US_ASCII))
            y += rows
            index++
            // 关键：条带之间必须停顿。连续 write 时打印机还在处理上一条，
            // 后续字节会冲垮它的接收缓冲并静默丢包，表现为整张不打印。
            if (y < heightDots) Thread.sleep(bandDelayMs)
        }
        Log.i(
            "BtPrint",
            "点阵分 $index 条带下发（每条 $bandRows 行 / ${bandRows * widthBytes} 字节，间隔 ${bandDelayMs}ms）"
        )
    }

    companion object {
        private const val DOTS_PER_MM = 8
        private const val REQ_BLUETOOTH = 101
        private const val DEFAULT_BAND_ROWS = 100
        private const val DEFAULT_BAND_DELAY_MS = 250L
        // 单条 BITMAP 的字节上限。真正的瓶颈是「条带条数」而不是总字节：
        // 实测 8 条带 × 10000 字节（共 79KB）能打满整张纸，12 条带 × 4000（48KB）反而不打。
        private const val MAX_BAND_BYTES = 10240
        // 单幅点阵的字节上限（79KB 实测可行，留余量取 90KB）
        private const val MAX_SEGMENT_BYTES = 90000
        // 必须用真实的纸缝值让打印机走间隙纸模式：PRINT 后它会自己定位到下一张纸头，
        // 这样每幅都从纸头起打。设为 0 会变成连续纸模式，走纸靠 SIZE 累加，误差会累积。
        private const val DEFAULT_GAP_MM = 2
        // 尾部不足这个行数就直接丢弃，避免为最后 1mm 白占一整张纸
        private const val MIN_SEGMENT_DOTS = 40
        // 物理纸张长度（mm）。内容比它长时要按它分页，保证每张纸都从纸头起打。
        private const val DEFAULT_PAPER_LENGTH_MM = 100

        // N41 自检页实测：Length*Width = 99mm*108mm。标签长度只有 99mm，
        // SIZE 高度超过它就会被拒打并锁死，所以单幅高度硬上限就是 99mm。
        private const val MAX_SEGMENT_HEIGHT_MM = 99
        private const val MAX_SEGMENT_HEIGHT_DOTS = MAX_SEGMENT_HEIGHT_MM * DOTS_PER_MM
        private const val DEFAULT_SEGMENT_HEIGHT_MM = MAX_SEGMENT_HEIGHT_MM
        // 间隙纸模式下 PRINT 后打印机要自己走纸找下一个纸缝并定位，
        // 这段时间内下发下一幅会被丢弃（实测 1.2s 不够，第 2 张直接不出）。
        private const val SEGMENT_GAP_MS = 3000L
        // 最后一幅打完后的收尾等待，确保 PRINT 真正离开蓝牙栈再关 socket
        private const val FINAL_SEGMENT_WAIT_MS = 3000L
        // 批量测试里两张之间的间隔
        private const val BATCH_GAP_MS = 6000L
        // 一份任务最多下发几次。只在"一个字节都没发出去"时重试（换新通道整份重发），
        // 覆盖上一份打完立刻再打时 socket 已被对面关闭、首写即 EPIPE 的情况。
        private const val SEND_ATTEMPTS = 3

        // 探活用。TSPL 里空行是空操作，不会出纸、不影响画布，
        // 但足以把一个"对面已断"的 socket 的 EPIPE 提前暴露出来。
        private val PROBE_LINE = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
