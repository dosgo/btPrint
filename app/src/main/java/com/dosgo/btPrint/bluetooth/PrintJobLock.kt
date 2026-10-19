package com.dosgo.btPrint.bluetooth

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级打印锁。
 *
 * 为什么不做成 Activity 的成员变量：MainActivity 没有指定 launchMode，adb 连续两次
 * `am start` 会开出两个实例，各自的字段互不可见，锁就形同虚设。锁必须比界面活得久，
 * 因为真正在跑的是工作线程，界面只是发起方。
 *
 * 语义上这是"一次任务只允许一个"，而不是"一把可重入锁"：打印机是一条独占的串行通道，
 * 第二份数据插进来只会打乱第一份的点阵。
 */
object PrintJobLock {

    private const val TAG = "BtPrintLock"

    private val busy = AtomicBoolean(false)

    /** 当前持有者（任务描述），没人在用时为 null */
    @Volatile
    var currentJob: String? = null
        private set

    val isBusy: Boolean
        get() = busy.get()

    /**
     * 尝试抢占。成功返回 null；失败返回当前占用者的描述。
     */
    fun tryAcquire(job: String): String? {
        if (!busy.compareAndSet(false, true)) {
            val holder = currentJob ?: "打印任务"
            Log.w(TAG, "拒绝[$job]：[$holder] 仍在执行")
            return holder
        }
        currentJob = job
        Log.i(TAG, "已获取打印锁：$job")
        return null
    }

    /** 释放锁。重复释放是安全的，避免异常路径把整个 App 的打印功能永久锁死。 */
    fun release() {
        val job = currentJob
        currentJob = null
        busy.set(false)
        Log.i(TAG, "已释放打印锁：$job")
    }
}
