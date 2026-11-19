package com.vpnworld6.app

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val servers = mutableListOf<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun loadServers() {
            Thread {
                try {
                    val connection = URL("https://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "VPN-WORLD-6-Android")
                    val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    connection.disconnect()
                    val rows = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("*") }.toList()
                    if (rows.size < 2) throw Exception("No server data")
                    val headers = parseCsv(rows.first().removePrefix("#"))
                    val indexes = headers.withIndex().associate { it.value to it.index }
                    val result = JSONArray()
                    servers.clear()
                    for (line in rows.drop(1)) {
                        val cells = parseCsv(line)
                        fun value(key: String) = cells.getOrNull(indexes[key] ?: -1).orEmpty()
                        val config = value("OpenVPN_ConfigData_Base64")
                        if (config.isBlank()) continue
                        val item = JSONObject()
                            .put("host", value("HostName"))
                            .put("ip", value("IP"))
                            .put("country", value("CountryLong"))
                            .put("countryCode", value("CountryShort"))
                            .put("ping", value("Ping"))
                            .put("score", value("Score"))
                            .put("config", config)
                        servers.add(item)
                        result.put(item)
                    }
                    runOnUiThread { web.evaluateJavascript("window.onServersLoaded($result)", null) }
                } catch (e: Exception) {
                    runOnUiThread {
                        val message = JSONObject.quote(e.message ?: "تعذر جلب الخوادم")
                        web.evaluateJavascript("window.onServersError($message)", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun saveConfig(index: Int) {
            runOnUiThread {
                try {
                    val item = servers.getOrNull(index) ?: throw Exception("الخادم غير متاح")
                    val bytes = Base64.decode(item.getString("config"), Base64.DEFAULT)
                    val code = item.optString("countryCode", "server")
                    val file = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "VPN-WORLD-6-" + code + ".ovpn")
                    file.writeBytes(bytes)
                    Toast.makeText(this@MainActivity, "تم حفظ ملف الإعداد داخل مجلد تنزيلات التطبيق: " + file.name, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "تعذر حفظ الملف", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(ch)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }
}
