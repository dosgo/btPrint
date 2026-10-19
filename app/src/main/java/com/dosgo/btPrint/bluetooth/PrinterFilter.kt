package com.dosgo.btPrint.bluetooth

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice

/**
 * 设备列表只做"排除法"：能确定不是打印机的才剔除，其余一律保留。
 *
 * 为什么不做正向识别：蓝牙里没有可靠的"我是打印机"标记，国产热敏模块的
 * Class of Device 经常乱填、名字也五花八门，正向判断必然漏机器。
 * 反过来排除掉电脑/手机/耳机/手表/键鼠这些明确不可能出纸的类别更安全。
 *
 * 注意：**不能用标准 SPP UUID(00001101) 判断**——手机、车机、手表、PC 全都有 SPP。
 */
object PrinterFilter {

    /**
     * Class of Device 主类里明确不可能打标签的类别。
     * MISC(0x00) 与 UNCATEGORIZED(0x1F) 要保留——大量热敏打印机就是报这两个。
     */
    private val NOT_PRINTER_MAJORS = setOf(
        BluetoothClass.Device.Major.COMPUTER,
        BluetoothClass.Device.Major.PHONE,
        BluetoothClass.Device.Major.NETWORKING,
        BluetoothClass.Device.Major.AUDIO_VIDEO,
        BluetoothClass.Device.Major.PERIPHERAL,
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.TOY,
        BluetoothClass.Device.Major.HEALTH
    )

    /** CoD 不规范时的名字兜底，只放 unambiguous 的消费电子品类 */
    private val NOT_PRINTER_KEYWORDS = listOf(
        "watch", "buds", "airpod", "headphone", "earphone", "speaker", "soundbar",
        "keyboard", "mouse", "gamepad", "controller",
        "手环", "手表", "耳机", "音箱", "键盘", "鼠标", "平板", "手机"
    )

    fun isDefinitelyNotPrinter(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major != null && major in NOT_PRINTER_MAJORS) return true

        val name = runCatching { device.name }.getOrNull()?.lowercase().orEmpty()
        return name.isNotEmpty() && NOT_PRINTER_KEYWORDS.any { name.contains(it) }
    }
}
