package com.myown.dialer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val perms = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        if (perms.any { !granted(it) }) requestPermissions(perms, 1)
        // Open tel: links (e.g. from other apps) straight into the keypad
        intent?.data?.schemeSpecificPart?.let { n ->
            web.postDelayed({ web.evaluateJavascript("window.prefill&&prefill('" + n.filter { it.isDigit() || it == '+' } + "')", null) }, 600)
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun refresh() { web.evaluateJavascript("window.refreshData&&refreshData()", null) }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) refresh()
    }

    private fun last10(s: String) = s.filter { it.isDigit() }.takeLast(10)
    private fun dialable(s: String) = s.filter { it.isDigit() || it == '+' }

    inner class Bridge {
        @JavascriptInterface
        fun call(n: String) {
            val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            startActivity(Intent(action, Uri.fromParts("tel", n, null)))
        }

        @JavascriptInterface
        fun addContact(n: String) {
            val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            i.putExtra(ContactsContract.Intents.Insert.PHONE, n)
            startActivity(i)
        }

        @JavascriptInterface
        fun getContacts(): String {
            val out = JSONArray()
            if (!granted(Manifest.permission.READ_CONTACTS)) return out.toString()
            val seen = HashSet<String>()
            val P = ContactsContract.CommonDataKinds.Phone
            contentResolver.query(
                P.CONTENT_URI,
                arrayOf(P.DISPLAY_NAME, P.NUMBER, P.STARRED),
                null, null, P.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(1) ?: continue
                    val key = last10(raw)
                    if (key.isEmpty() || !seen.add(key)) continue
                    out.put(JSONObject()
                        .put("n", c.getString(0) ?: key)
                        .put("p", key)
                        .put("d", dialable(raw))
                        .put("f", if (c.getInt(2) == 1) 1 else 0))
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun getCalls(): String {
            val out = JSONArray()
            if (!granted(Manifest.permission.READ_CALL_LOG)) return out.toString()
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext() && out.length() < 60) {
                    val raw = c.getString(0) ?: ""
                    if (last10(raw).isEmpty()) continue
                    val type = c.getInt(1)
                    out.put(JSONObject()
                        .put("p", last10(raw))
                        .put("d", dialable(raw))
                        .put("t", fmt.format(Date(c.getLong(2))))
                        .put("m", if (type == CallLog.Calls.MISSED_TYPE) 1 else 0)
                        .put("o", if (type == CallLog.Calls.OUTGOING_TYPE) 1 else 0))
                }
            }
            return out.toString()
        }
    }
}
