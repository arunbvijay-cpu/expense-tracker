package com.arun.smsanalyser

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The 9 PM screen: every spend is already categorised; change only what's wrong, add cash, save. */
class ReviewActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private val rows = mutableListOf<Pair<Txn, Spinner>>()
    private val cash = mutableListOf<JSONObject>()
    private lateinit var cashList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        render()
        refreshCategories()
    }

    private fun options(): List<String> = listOf(SmsParser.NEEDS) + store.categories + SmsParser.NOT_EXPENSE

    private fun render() {
        rows.clear()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        val pending = store.pending
        val spends = pending.filter { it.type == "debit" }
            .sortedWith(compareBy<Txn>({ it.category != SmsParser.NEEDS }, { it.time }))
        val returns = pending.filter { it.returnType.isNotEmpty() }

        title("Today's expenses")
        note(if (spends.isEmpty()) "No card or UPI spends since the last check." else
            "Each spend already has a category. Change only the wrong ones. Rows in orange need a category.")

        for (t in spends) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (t.category == SmsParser.NEEDS) Color.parseColor("#FCE4D6") else Color.parseColor("#F2F4F7"))
            }
            box.addView(TextView(this).apply {
                text = "₹${"%,.2f".format(t.amount)}  ·  ${t.payee.ifEmpty { t.channel }}"
                textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK)
            })
            box.addView(TextView(this).apply {
                text = "${t.day} · ${t.channel} ${t.account} · ${t.how}"
                textSize = 12f; setTextColor(Color.DKGRAY)
            })
            val sp = spinner(options(), t.category)
            box.addView(sp)
            root.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            rows += t to sp
        }

        title("Cash spends")
        val amt = EditText(this).apply { hint = "Amount"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val catSp = spinner(store.categories, store.categories.firstOrNull() ?: "")
        val noteIn = EditText(this).apply { hint = "Note (optional)" }
        root.addView(amt); root.addView(catSp); root.addView(noteIn)
        root.addView(Button(this).apply {
            text = "Add cash spend"
            setOnClickListener {
                val a = amt.text.toString().toDoubleOrNull()
                if (a == null || a <= 0) { amt.error = "Enter an amount"; return@setOnClickListener }
                cash += JSONObject().apply {
                    put("ts", iso(System.currentTimeMillis())); put("month", month(System.currentTimeMillis()))
                    put("category", catSp.selectedItem.toString()); put("amount", a)
                    put("note", noteIn.text.toString().trim()); put("additional", "Cash, added in SMS app")
                }
                amt.setText(""); noteIn.setText("")
                cashList.text = cash.joinToString("\n") { "₹${it.getDouble("amount")} · ${it.getString("category")} ${it.optString("note")}" }
            }
        })
        cashList = TextView(this).apply { textSize = 14f }
        root.addView(cashList)

        if (returns.isNotEmpty()) {
            title("Investment returns received")
            note(returns.joinToString("\n") { "${it.day} · ${it.returnType} ₹${"%,.2f".format(it.amount)} · ${it.payee.ifEmpty { it.account }}" })
        }

        root.addView(Button(this).apply {
            text = "Save to Expense sheet"
            setOnClickListener { save(this) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun save(button: Button) {
        val missing = rows.count { it.second.selectedItem.toString() == SmsParser.NEEDS }
        if (missing > 0) { toast("Pick a category for the $missing orange row(s), or choose \"Not an expense\"."); return }

        val items = JSONArray()
        val learned = store.learned.toMutableMap()
        for ((t, sp) in rows) {
            val chosen = sp.selectedItem.toString()
            if (chosen != t.category && t.payee.isNotEmpty()) learned[SmsParser.payeeKey(t.payee)] = chosen
            if (chosen == SmsParser.NOT_EXPENSE) continue
            items.put(JSONObject().apply {
                put("ts", iso(t.time)); put("month", t.month); put("category", chosen); put("amount", t.amount)
                put("note", listOf(t.payee, t.channel).filter { it.isNotEmpty() }.joinToString(" · "))
                put("additional", "Auto (SMS app)")
            })
        }
        cash.forEach { items.put(it) }
        val rets = JSONArray()
        store.pending.filter { it.returnType.isNotEmpty() }.forEach { t ->
            rets.put(JSONObject().apply {
                put("date", iso(t.time)); put("returnType", t.returnType); put("amount", t.amount)
                put("account", t.account); put("source", t.payee); put("seenIn", "SMS"); put("ref", t.id)
            })
        }

        button.isEnabled = false
        button.text = "Saving…"
        val url = store.apiUrl
        Thread {
            val error = try {
                if (items.length() > 0) {
                    val r = Api.post(url, JSONObject().put("type", "addExpense").put("items", items))
                    if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error", "not saved"))
                }
                if (rets.length() > 0) {
                    val r = Api.post(url, JSONObject().put("type", "addReturns").put("items", rets))
                    if (!r.optBoolean("ok")) throw IllegalStateException("Returns not saved. Update the sheet's script (see README).")
                }
                null
            } catch (e: Exception) { e.message ?: "network error" }
            runOnUiThread {
                if (error == null) {
                    store.learned = learned
                    store.pending = emptyList()
                    toast("Saved ${items.length()} expense(s) to your sheet.")
                    finish()
                } else {
                    button.isEnabled = true
                    button.text = "Save to Expense sheet"
                    toast("Not saved: $error. Your list is kept; try again.")
                }
            }
        }.start()
    }

    /** Pulls the sheet's Categories tab so the choices match the expense app. */
    private fun refreshCategories() {
        val url = store.apiUrl
        Thread {
            try {
                val a = Api.get("$url?action=categories").getJSONArray("categories")
                val cats = (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
                if (cats.isNotEmpty() && cats != store.categories) {
                    store.categories = cats
                    runOnUiThread { if (cash.isEmpty()) render() }
                }
            } catch (_: Exception) { /* offline: keep the saved list */ }
        }.start()
    }

    private fun spinner(items: List<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ReviewActivity, android.R.layout.simple_spinner_dropdown_item, items)
        val i = items.indexOfFirst { it.equals(selected, ignoreCase = true) }
        if (i >= 0) setSelection(i)
    }

    private fun title(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#1F4E78"))
        setPadding(0, dp(16), 0, dp(6))
    })

    private fun note(s: String) = root.addView(TextView(this).apply { text = s; textSize = 14f; setPadding(0, 0, 0, dp(8)) })

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(t))
        fun month(t: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(t))
    }
}
