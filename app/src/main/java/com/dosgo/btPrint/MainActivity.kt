package com.dosgo.btPrint;

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerDevices: Spinner
    private lateinit var spinnerLabelSize: Spinner
    private lateinit var tvPdfStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPreviewLabel: TextView
    private lateinit var ivPrintPreview: ImageView

    private var selectedPdfUri: Uri? = null
    private var preparedPrintData: PreparedPrintData? = null
    private val pairedDevices = mutableListOf<BluetoothDevice>()

    private data class LabelSize(val widthMm: Int, val heightMm: Int) {
        val widthDots: Int get() = widthMm * DOTS_PER_MM
        val heightDots: Int get() = heightMm * DOTS_PER_MM
        override fun toString() = "${widthMm} × ${heightMm} mm（${widthDots} × ${heightDots} 点）"
    }

    private val labelSizes = listOf(
        LabelSize(100, 150),
        LabelSize(100, 100),
        LabelSize(80, 100),
        LabelSize(70, 100),
        LabelSize(60, 40),
        LabelSize(50, 30),
        LabelSize(108, 150)
    )

    private data class PreparedPrintData(
        val sourceUri: Uri,
        val labelSize: LabelSize,
        val bitmapData: ByteArray,
        val widthInBytes: Int,
        val heightDots: Int,
        val previewBitmap: Bitmap
    )

    // 经典蓝牙 SPP 标准 UUID
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // 文件选择器
    private val selectPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPdfUri = uri
            preparedPrintData = null
            ivPrintPreview.setImageDrawable(null)
            ivPrintPreview.visibility = View.GONE
            tvPreviewLabel.visibility = View.GONE
            tvPdfStatus.text = "正在生成打印预览: ${uri.lastPathSegment}"
            tvLog.text = "正在按 203 DPI 渲染第一页..."
            preparePreview(uri, selectedLabelSize())
        }
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

        spinnerLabelSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labelSizes
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

        findViewById<Button>(R.id.btnSelectPdf).setOnClickListener {
            selectPdfLauncher.launch("application/pdf")
        }

        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            startPrintFlow()
        }

        checkAndRequestPermissions()
    }

    private fun selectedLabelSize(): LabelSize =
        labelSizes.getOrElse(spinnerLabelSize.selectedItemPosition) { labelSizes.first() }

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

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                return
            }
        }
        loadPairedDevices()
    }

    // 读取手机系统设置里已配对的蓝牙设备
    private fun loadPairedDevices() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return

        try {
            val devices = adapter.bondedDevices
            pairedDevices.clear()
            val deviceNames = mutableListOf<String>()

            for (dev in devices) {
                pairedDevices.add(dev)
                deviceNames.add("${dev.name ?: "未知设备"} (${dev.address})")
            }

            val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceNames)
            spinnerDevices.adapter = spinnerAdapter
        } catch (e: SecurityException) {
            tvLog.text = "缺少蓝牙权限"
        }
    }

    private fun startPrintFlow() {
        val uri = selectedPdfUri
        if (uri == null) {
            tvLog.text = "请先选择 PDF 文件！"
            return
        }
        if (pairedDevices.isEmpty() || spinnerDevices.selectedItemPosition < 0) {
            tvLog.text = "未发现已配对的蓝牙打印机"
            return
        }

        val targetDevice = pairedDevices[spinnerDevices.selectedItemPosition]
        val labelSize = selectedLabelSize()
        tvLog.text = "正在渲染并准备打印..."

        thread {
            try {
                // 优先复用屏幕中显示的同一份点阵，确保预览和实际打印完全一致。
                val prepared = preparedPrintData
                    ?.takeIf { it.sourceUri == uri && it.labelSize == labelSize }
                    ?: preparePrintData(uri, labelSize)

                sendToPrinter(
                    targetDevice,
                    prepared.bitmapData,
                    prepared.widthInBytes,
                    prepared.heightDots,
                    labelSize
                )

                runOnUiThread { tvLog.text = "打印完成！" }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvLog.text = "打印失败: ${e.message}" }
            }
        }
    }

    private fun preparePrintData(uri: Uri, labelSize: LabelSize): PreparedPrintData {
        val bitmap = renderPdfToBitmap(uri, labelSize.widthDots, labelSize.heightDots)
        val (bitmapData, widthInBytes) = convertBitmapTo1BitTspl(bitmap)
        val preview = createPreviewFromTspl(bitmapData, bitmap.width, bitmap.height, widthInBytes)
        bitmap.recycle()
        return PreparedPrintData(uri, labelSize, bitmapData, widthInBytes, preview.height, preview)
    }

    // 核心渲染：按物理点阵分辨率 1:1 输出，保证不模糊且字体完整
    private fun renderPdfToBitmap(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw Exception("无法读取 PDF")
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(0)
                try {
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    val scale = minOf(
                        targetWidth.toFloat() / page.width,
                        targetHeight.toFloat() / page.height
                    )
                    val offsetX = (targetWidth - page.width * scale) / 2f
                    val offsetY = (targetHeight - page.height * scale) / 2f
                    val transform = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(offsetX, offsetY)
                    }
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bitmap
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
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

                        // 硬阈值切分：< 180 判定为黑色，否则为白色
                        // TSPL 规范中：0 代表打点(黑)，1 代表不打点(白)
                        if (gray >= 180) {
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

    // 蓝牙 SPP 发送
    private fun sendToPrinter(
        device: BluetoothDevice,
        bitmapData: ByteArray,
        widthBytes: Int,
        heightDots: Int,
        labelSize: LabelSize
    ) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()

            val out: OutputStream = socket.outputStream

            // 1. 纸张尺寸与点阵画布使用同一规格。
            val initCmd = "SIZE ${labelSize.widthMm} mm, ${labelSize.heightMm} mm\r\n" +
                "GAP 2 mm, 0 mm\r\nCLS\r\n"
            out.write(initCmd.toByteArray(Charsets.US_ASCII))

            // 2. 写入 BITMAP 指令头: BITMAP X, Y, width_in_bytes, height_in_dots, mode, [data]
            // mode 0: OVERWRITE
            val bitmapCmdHeader = "BITMAP 0,0,$widthBytes,$heightDots,0,"
            out.write(bitmapCmdHeader.toByteArray(Charsets.US_ASCII))

            // 3. 写入纯黑白点阵数据 (约 100KB+，蓝牙 2 秒内发完)
            out.write(bitmapData)

            // 4. 发送换行与出纸打印命令
            val endCmd = "\r\nPRINT 1,1\r\n"
            out.write(endCmd.toByteArray(Charsets.US_ASCII))
            out.flush()

            Thread.sleep(1000) // 稍作缓冲保证发完
        } finally {
            socket?.close()
        }
    }

    companion object {
        private const val DOTS_PER_MM = 8
    }
}
