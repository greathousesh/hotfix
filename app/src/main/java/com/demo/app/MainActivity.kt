package com.demo.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.demo.hotfix.Hotfix
import com.demo.hotfix.NativeHook
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Demo 主界面：演示三种热修复（代码 / 资源 / Native）。
 *
 * 补丁通过网络从本机 server 下载为**数据流**，直接交给 SDK 的 install* 接口——SDK 自己落盘(内部沙盒)、
 * 立即应用、并持久化。下次冷启动 [Hotfix.init] 会自动重新应用已安装的补丁，无需再点按钮。
 *   server: 本机 `./serve_patches.sh`(root=patch-src/build/patch) + `adb reverse tcp:8080 tcp:8080`
 */
class MainActivity : Activity() {

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tv = findViewById(R.id.tv_result)

        // 初始化：若此前安装过补丁(已持久化在内部沙盒)，启动时自动重新应用。
        Hotfix.init(application, "1.0.0")

        findViewById<android.widget.Button>(R.id.btn_java).setOnClickListener {
            log("Java  Calculator.add(2,3) = ${Calculator().add(2, 3)}  (期望5)")
        }
        findViewById<android.widget.Button>(R.id.btn_res).setOnClickListener {
            log("Res   string/patch_text = ${getString(R.string.patch_text)}")
        }
        findViewById<android.widget.Button>(R.id.btn_native).setOnClickListener {
            log("Native nativeAdd(2,3) = ${NativeBug().nativeAdd(2, 3)}  (期望5)")
        }
        findViewById<android.widget.Button>(R.id.btn_patch).setOnClickListener { downloadAndInstall() }
    }

    /** 从本机 server 下载三类补丁的数据流，交给 SDK 安装(落盘+立即应用+持久化)。 */
    private fun downloadAndInstall() {
        val variant = BuildConfig.BUILD_TYPE                 // "debug" / "release"
        val base = "$PATCH_SERVER/$variant"                  // 按变体取对应补丁(混淆名 vs 原始名)
        log("从 $base 下载并安装补丁中…")
        Thread {
            try {
                val j = Hotfix.installJavaPatch(open("$base/patch.dex"))
                val r = Hotfix.installResourcePatch(open("$base/patch_res.apk"))
                val n = Hotfix.installNativePatch(
                    open("$PATCH_SERVER/libpatch.so"),       // native 补丁与变体无关，共用
                    listOf(NativeHook("native_bug", "compute_add", "patched_add")),
                )
                runOnUiThread { log("安装补丁($variant): java=$j res=$r native=$n\n再点上面三个按钮看结果。") }
            } catch (e: Exception) {
                runOnUiThread { log("下载/安装失败: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    /** 打开 url 的响应流（由 SDK 读取并关闭）。 */
    private fun open(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            error("HTTP ${conn.responseCode} @ $url")
        }
        return conn.inputStream
    }

    private fun log(s: String) {
        tv.text = "$s\n\n${tv.text}"
    }

    companion object {
        // adb reverse 让设备的 127.0.0.1:8080 指向本机补丁 server（USB 真机 + 模拟器都适用）。
        private const val PATCH_SERVER = "http://127.0.0.1:8080"
    }
}
