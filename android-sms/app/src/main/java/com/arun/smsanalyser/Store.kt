package com.arun.smsanalyser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** Small on-phone store. Holds parsed transactions (never SMS text), learned payees and settings. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("sms_analyser", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = p.getString("apiUrl", DEFAULT_API) ?: DEFAULT_API
        set(v) { p.edit().putString("apiUrl", v.trim()).apply() }

    /** Last SMS timestamp already read; first run starts at the beginning of today. */
    var lastSmsTime: Long
        get() = p.getLong("lastSmsTime", startOfToday())
        set(v) { p.edit().putLong("lastSmsTime", v).apply() }

    var categories: List<String>
        get() = JSONArray(p.getString("categories", JSONArray(DEFAULT_CATEGORIES).toString())).let { a -> (0 until a.length()).map { a.getString(it) } }
        set(v) { p.edit().putString("categories", JSONArray(v).toString()).apply() }

    var learned: Map<String, String>
        get() = JSONObject(p.getString("learned", "{}") ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        set(v) { p.edit().putString("learned", JSONObject(v).toString()).apply() }

    var pending: List<Txn>
        get() = JSONArray(p.getString("pending", "[]") ?: "[]").let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        set(v) { p.edit().putString("pending", JSONArray(v.map { toJson(it) }).toString()).apply() }

    fun addPending(new: List<Txn>) {
        val cur = pending
        val ids = cur.map { it.id }.toHashSet()
        pending = cur + new.filter { it.id !in ids }
    }

    companion object {
        const val DEFAULT_API = "https://script.google.com/macros/s/AKfycbzubi4Xko4h_6-sKDWCm51n1KJpxUvP-ZsQzQlPBrG1csj1qmaNyazi_Ugc1ob-MT_WYQ/exec"
        val DEFAULT_CATEGORIES = listOf("Phone", "Supermarket", "Food", "Entertainment", "Online", "Dress", "Car",
            "Hospital", "gift", "Home", "Children", "Beer and Wine", "Beauty", "Loan", "Trading Expenses", "Fuel",
            "KSEB", "Travel Expense", "Pet", "Miscellaneous")

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun toJson(t: Txn) = JSONObject().apply {
            put("id", t.id); put("time", t.time); put("type", t.type); put("amount", t.amount)
            put("channel", t.channel); put("payee", t.payee); put("account", t.account)
            put("category", t.category); put("returnType", t.returnType); put("how", t.how)
        }

        fun fromJson(o: JSONObject) = Txn(
            o.getString("id"), o.getLong("time"), o.getString("type"), o.getDouble("amount"),
            o.optString("channel"), o.optString("payee"), o.optString("account"),
            o.optString("category"), o.optString("returnType"), o.optString("how")
        )
    }
}

/** Talks to the Apps Script behind the user's Expense Tracker sheet. Call off the main thread. */
object Api {
    fun get(url: String): JSONObject = JSONObject(request(URL(url), null))

    fun post(url: String, body: JSONObject): JSONObject = JSONObject(request(URL(url), body.toString()))

    // Apps Script answers a POST with a redirect to a one-time GET URL, so redirects are followed by hand.
    private fun request(url: URL, body: String?): String {
        var target = url
        var payload = body
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            if (payload != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                c.outputStream.use { it.write(payload!!.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                c.disconnect()
                target = URL(target, loc)
                payload = null
                return@repeat
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            c.disconnect()
            if (code !in 200..299) throw IllegalStateException("Sheet returned $code")
            return text
        }
        throw IllegalStateException("Too many redirects")
    }
}
