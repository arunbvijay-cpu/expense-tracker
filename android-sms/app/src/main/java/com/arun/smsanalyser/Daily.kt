package com.arun.smsanalyser

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Calendar

/**
 * One wake-up a day at 9:06 PM. No background service, no polling: the alarm reads the
 * day's bank SMS on the phone, parses them, and shows one notification. Nothing is sent
 * anywhere until you confirm in the review screen.
 */
object Daily {
    const val HOUR = 21
    const val MINUTE = 6
    private const val CHANNEL = "daily_review"

    fun nextRun(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 1, Intent(context, DailyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // A 10-minute window lets Android batch the wake-up with others, which saves battery.
        am.setWindow(AlarmManager.RTC_WAKEUP, nextRun(), 10 * 60 * 1000L, pi)
    }

    /** Reads new bank SMS since the last run and adds them to the review list. Returns how many were added. */
    fun collect(context: Context): Int {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val store = Store(context)
        val since = store.lastSmsTime
        val categories = store.categories
        val learned = store.learned
        val found = mutableListOf<Txn>()
        var newest = since
        context.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("_id", "address", "date", "body"),
            "date > ?", arrayOf(since.toString()), "date ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val address = cur.getString(1) ?: ""
                val date = cur.getLong(2)
                val body = cur.getString(3) ?: ""
                if (date > newest) newest = date
                if (!SmsParser.isBankSender(address)) continue   // skip personal messages
                SmsParser.parse(id, date, address, body, categories, learned)?.let { found += it }
            }
        }
        store.addPending(found)
        store.lastSmsTime = newest
        return found.size
    }

    fun notify(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily expense review", NotificationManager.IMPORTANCE_HIGH))
        val pending = Store(context).pending
        val spends = pending.filter { it.type == "debit" && it.category != SmsParser.NOT_EXPENSE }
        val needs = spends.count { it.category == SmsParser.NEEDS }
        val returns = pending.count { it.returnType.isNotEmpty() }
        val total = spends.sumOf { it.amount }
        val text = buildString {
            if (spends.isEmpty()) append("No card or UPI spends today. Add any cash spends.")
            else append("${spends.size} spends, ₹${"%,.0f".format(total)}, already categorised.")
            if (needs > 0) append(" $needs need a category.")
            if (returns > 0) append(" $returns investment return(s) received.")
        }
        val open = PendingIntent.getActivity(context, 2, Intent(context, ReviewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Confirm today's expenses")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        Thread {
            try {
                Daily.collect(app)
                Daily.notify(app)
            } finally {
                Daily.schedule(app)
                done.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Daily.schedule(context.applicationContext)
    }
}
