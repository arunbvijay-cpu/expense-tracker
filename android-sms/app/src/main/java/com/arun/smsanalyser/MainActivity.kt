package com.arun.smsanalyser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Daily.schedule(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad + pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "SMS Expenses"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#1F4E78"))
        })
        root.addView(TextView(this).apply {
            textSize = 15f
            text = "Every day at 9:06 PM this app reads the day's bank SMS on this phone, categorises each spend " +
                "from the UPI details, and asks you to confirm. Confirmed spends go to your Expense Tracker sheet " +
                "with \"Auto (SMS app)\" in the Additional column. OTP messages are ignored and the SMS text never " +
                "leaves the phone; only amount, date, payee and the last 4 digits of the account are saved.\n"
        })
        status = TextView(this).apply { textSize = 14f }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Allow SMS and notifications"
            setOnClickListener { askPermissions() }
        })
        root.addView(Button(this).apply {
            text = "2. Check today's SMS now"
            setOnClickListener {
                Thread {
                    val n = Daily.collect(applicationContext)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "$n new transaction(s) found", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@MainActivity, ReviewActivity::class.java))
                    }
                }.start()
            }
        })

        root.addView(TextView(this).apply { text = "\nExpense sheet link (already set to your Expense Tracker):"; textSize = 13f })
        val urlIn = EditText(this).apply { setText(store.apiUrl); textSize = 12f }
        root.addView(urlIn)
        root.addView(Button(this).apply {
            text = "Save link and test connection"
            setOnClickListener {
                store.apiUrl = urlIn.text.toString()
                test()
            }
        })
        refreshStatus()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val sms = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val next = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US).format(Date(Daily.nextRun()))
        status.text = "SMS access: ${if (sms) "allowed" else "NOT allowed"}\n" +
            "Notifications: ${if (notif) "allowed" else "NOT allowed"}\n" +
            "Waiting for review: ${store.pending.size} item(s)\n" +
            "Next check: $next\n"
    }

    private fun test() {
        val url = store.apiUrl
        Thread {
            val msg = try {
                val r = Api.get("$url?action=categories")
                val n = r.getJSONArray("categories").length()
                // Creates the Returns and Investments tabs if the updated script is deployed.
                val setup = try { Api.post(url, JSONObject().put("type", "setup")).optBoolean("ok") } catch (_: Exception) { false }
                "Connected: $n categories found." + if (setup) " Returns and Investments tabs are ready." else
                    " The sheet's script still needs the update for investment returns."
            } catch (e: Exception) { "Could not reach the sheet: ${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
