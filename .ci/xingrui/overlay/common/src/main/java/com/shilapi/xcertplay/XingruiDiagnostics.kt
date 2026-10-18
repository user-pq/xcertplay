package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** User-selected SAF destination. Exports only read-only system SDK files and our status,
 * never our APK, assets/offline-mfi, private keys, pairing databases or other app data. */
internal object XingruiDiagnostics {
    const val REQUEST = 7904
    fun show(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("星瑞适配诊断")
            .setMessage("版本：${activity.packageManager.getPackageInfo(activity.packageName, 0).versionName}\n\n" +
                XingruiRuntimeStatus.summary())
            .setPositiveButton("导出诊断") { _, _ ->
                val name = "xingrui-e02-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"
                activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, name)
                }, REQUEST)
            }.setNegativeButton("关闭", null).show()
    }
    fun export(activity: Activity, uri: Uri) {
        val app = activity.applicationContext
        Toast.makeText(activity, "正在导出适配诊断…", Toast.LENGTH_SHORT).show()
        Thread({
            val outcome = runCatching {
                val notes = StringBuilder()
                val files = linkedMapOf<String, File>()
                File("/system/framework").listFiles()?.filter {
                    it.isFile && it.extension == "jar" && it.name.contains("ecarx", ignoreCase = true)
                }?.sortedBy { it.name }?.forEach { files["system-sdk/" + it.name] = it }
                runCatching { app.packageManager.getApplicationInfo("com.ecarx.sdk.openapi", 0).sourceDir }
                    .onSuccess { files["system-sdk/ecarx-openapi.apk"] = File(it) }
                    .onFailure { notes.appendLine("OpenAPI APK lookup: ${it.message}") }
                ZipOutputStream(checkNotNull(app.contentResolver.openOutputStream(uri, "w"))).use { zip ->
                    fun text(name: String, value: String) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                    }
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    text("runtime-status.txt", "package=${app.packageName}\nversion=${info.versionName}\n" +
                        "api=${android.os.Build.VERSION.SDK_INT}\nmodel=${android.os.Build.MODEL}\n" + XingruiRuntimeStatus.report())
                    val contracts = listOf("com.ecarx.xui.adaptapi.input.Input",
                        "com.ecarx.xui.adaptapi.input.IKeyCallback", "com.ecarx.xui.adaptapi.diminteraction.DimInteraction",
                        "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction", "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction")
                    text("sdk-contracts.txt", contracts.joinToString("\n\n") { name ->
                        runCatching {
                            val cls = Class.forName(name)
                            name + "\n" + cls.methods.map { it.toGenericString() }.sorted().joinToString("\n")
                        }.getOrElse { "$name: ${it.javaClass.simpleName} ${it.message}" }
                    })
                    var total = 0L
                    for ((name, file) in files) {
                        val size = file.length()
                        if (!file.canRead() || size <= 0 || size > 50L * 1024 * 1024 || total + size > 100L * 1024 * 1024) {
                            notes.appendLine("Skipped $name readable=${file.canRead()} bytes=$size"); continue
                        }
                        // Open before creating the ZIP entry; a denied file must not break the archive.
                        val input = try { file.inputStream() } catch (error: Exception) {
                            notes.appendLine("Cannot open $name: ${error.message}"); continue
                        }
                        input.use { source ->
                            zip.putNextEntry(ZipEntry(name)); source.copyTo(zip); zip.closeEntry()
                        }
                        total += size
                        notes.appendLine("Included $name bytes=$size")
                    }
                    text("export-manifest.txt", notes.toString())
                }
            }
            activity.runOnUiThread {
                val message = if (outcome.isSuccess) "诊断文件已保存，请发送该 ZIP 文件。" else
                    "导出失败：${outcome.exceptionOrNull()?.message}"
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }, "xingrui-export").start()
    }
}
