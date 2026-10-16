package com.arun.smsanalyser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One bank transaction parsed from an SMS. The SMS text itself is never kept. */
data class Txn(
    val id: String,
    val time: Long,
    val type: String,          // "debit" or "credit"
    val amount: Double,
    val channel: String,       // UPI, Card, ATM, NEFT/IMPS, Bank
    val payee: String,         // masked merchant / VPA / source
    val account: String,       // XX1234
    var category: String,      // one of the sheet's categories, or NOT_EXPENSE
    val returnType: String,    // non-empty for investment returns
    var how: String            // Learned from you / From UPI details / Needs category
) {
    val month: String get() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(time))
    val day: String get() = SimpleDateFormat("dd MMM", Locale.US).format(Date(time))
}

object SmsParser {
    const val NOT_EXPENSE = "Not an expense"
    const val NEEDS = "Needs category"

    private val I = setOf(RegexOption.IGNORE_CASE)
    private val OTP = Regex("""\b(otp|one[- ]time password|verification code|passcode|cvv|pin|password)\b""", I)
    private val PROMO = Regex("""\b(offer|cashback upto|pre-approved|pre approved|apply now|eligible for|win|congratulations|click here|loan of)\b""", I)
    private val NOTICE = Regex("""\b(due on|payment due|min(imum)? amount due|total amount due|will be debited|is due|request(ed)? money|collect request)\b""", I)
    private val AMT = Regex("""(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)""", I)
    private val DEBIT = Regex("""\b(debited|spent|paid|withdrawn|purchase|sent|deducted|dr)\b""", I)
    private val CREDIT = Regex("""\b(credited|received|deposited|refund|cr)\b""", I)
    private val UPI = Regex("""\bupi\b|vpa""", I)
    private val CARD = Regex("""\b(credit card|debit card|card)\b""", I)
    private val ACCT = Regex("""(?:a/c|acct|account|card|ac)\s*(?:no\.?)?\s*(?:ending\s*(?:with)?\s*)?[:\s]*([Xx*]*\d{3,4})\b""", I)

    private val MERCHANT = listOf(
        Regex("""\b(?:to|at|towards)\s+(?:vpa\s+)?([A-Za-z0-9@._&' -]{2,40}?)(?=\s+(?:on|via|ref|upi|for|avl|bal|info)\b|[.,]|$)""", I),
        Regex("""\b(?:from|by)\s+(?:vpa\s+)?([A-Za-z0-9@._&' -]{2,40}?)(?=\s+(?:on|via|ref|upi|avl|bal|info)\b|[.,]|$)""", I),
        Regex("""\binfo[:\s]+([A-Za-z0-9@._&/' -]{2,40}?)(?=\s+(?:avl|bal)\b|[.,]|$)""", I),
        Regex("""(?:;|\s)([A-Za-z][A-Za-z0-9 .&'-]{2,40}?)\s+credited\b""", I)
    )
    private val GENERIC = Regex("""(your|a/c|ac|acct|account|card|interest|int|neft|imps|upi|bank)\b.*""", I)

    private val RETURNS = listOf(
        "FD interest" to Regex("""\b(fd|fixed deposit|term deposit|td)\b.*\binterest\b|\binterest\b.*\b(fd|fixed deposit|term deposit)\b""", I),
        "NCD return" to Regex("""\bncd\b|non[- ]convertible|debenture""", I),
        "Bond return" to Regex("""\bbonds?\b|\bsgb\b|coupon""", I),
        "Dividend" to Regex("""\bdividend\b|\bdiv\b|\bach\b.*\bcr\b|nach.*dividend""", I),
        "FD maturity" to Regex("""\b(fd|fixed deposit|term deposit)\b.*\bmatur""", I),
        "MF redemption" to Regex("""\bredemption\b|mutual fund""", I),
        "Savings interest" to Regex("""\b(int\.?|interest)\b.*\b(credited|paid)|\b(credited|paid)\b.*\b(int\.?|interest)\b|\bint\.?\s*pd\b|\bsb\s*int""", I)
    )

    /** Rules that map a payee to one of the user's own sheet categories. */
    private val RULES = listOf(
        "KSEB" to "kseb|electricity|kerala state electricity",
        "Phone" to "airtel|jio|\\bvi\\b|vodafone|bsnl|recharge|broadband|act fibernet",
        "Fuel" to "petrol|fuel|hpcl|bpcl|iocl|indian oil|hindustan petroleum|bharat petroleum|shell|filling station|\\bev\\b|charg|statiq|tata power",
        "Supermarket" to "bigbasket|blinkit|zepto|dmart|grocery|supermarket|more retail|reliance fresh|reliance smart|instamart|lulu|vegetable|stores|traders|milma|\\bmart\\b|hypermarket",
        "Food" to "swiggy|zomato|restaurant|cafe|hotel|bakery|eats|dominos|kfc|mcdonald|biryani|juice|food|kitchen|mess|canteen|tea stall",
        "Online" to "amazon|flipkart|meesho|nykaa|snapdeal|tatacliq|jiomart",
        "Dress" to "myntra|ajio|trends|textiles|silks|fashion|garments|max retail|pantaloons|zudio|westside",
        "Entertainment" to "bookmyshow|pvr|inox|cinema|theatre|netflix|hotstar|spotify|prime video|sony liv",
        "Travel Expense" to "irctc|redbus|makemytrip|goibibo|indigo|air india|akasa|ksrtc|uber|ola|rapido|metro|fastag|yatra|cleartrip|toll",
        "Hospital" to "hospital|clinic|pharma|medical|medicals|apollo|medplus|diagnostic|\\blab\\b|dental|aster|kims",
        "Car" to "service center|service centre|motors|automobiles|tyres|car wash|maruti|hyundai|tata motors",
        "Beer and Wine" to "bevco|beverages corporation|wines|\\bbar\\b",
        "Loan" to "\\bemi\\b|loan|bajaj finance|home first|hdfc ltd|lic housing",
        "Trading Expenses" to "zerodha|groww|upstox|dhan|kotak securities|angel one|5paisa|icici direct|indmoney",
        "Children" to "school|tuition|academy|kids|toys|firstcry",
        "Home" to "furniture|hardware|electricals|pepperfry|ikea|urban company|plumb",
        "Beauty" to "salon|parlour|parlor|beauty|spa\\b|naturals",
        "Pet" to "\\bpets?\\b|\\bvet\\b|veterinary",
        "gift" to "gift|ferns|archies"
    ).map { it.first to Regex(it.second, I) }

    /** Self transfers and card bill payments are not new spending. */
    private val NOT_SPEND = Regex("""credit card (bill|payment)|cc payment|card payment received|self transfer|to own account|mutual fund|\bsip\b|\bppf\b|\bnps\b|fixed deposit|\brd\b installment""", I)

    fun isBankSender(address: String): Boolean = address.any { it.isLetter() }

    fun mask(text: String): String {
        var t = Regex("""\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?(\d{4})\b""").replace(text) { "XX" + it.groupValues[1] }
        t = Regex("""[Xx*]{2,}[ -]?(\d{3,4})\b""").replace(t) { "XX" + it.groupValues[1] }
        t = Regex("""\b\d{6,}(\d{4})\b""").replace(t) { "XX" + it.groupValues[1] }
        return t
    }

    fun payeeKey(payee: String): String =
        payee.lowercase(Locale.US).substringBefore('@')
            .map { if (it.isLetter()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    fun categorize(payee: String, maskedBody: String, categories: List<String>, learned: Map<String, String>): Pair<String, String> {
        val key = payeeKey(payee)
        learned[key]?.let { return it to "Learned from you" }
        if (NOT_SPEND.containsMatchIn(maskedBody)) return NOT_EXPENSE to "From SMS details"
        if (Regex("""\batm\b|cash withdrawal""", I).containsMatchIn(maskedBody)) {
            return NEEDS to "Cash withdrawal"
        }
        val hay = "$payee $maskedBody"
        for ((cat, rx) in RULES) {
            if (rx.containsMatchIn(hay)) {
                val match = categories.firstOrNull { it.equals(cat, ignoreCase = true) }
                if (match != null || categories.isEmpty()) return (match ?: cat) to "From UPI details"
            }
        }
        return NEEDS to NEEDS
    }

    fun parse(smsId: Long, time: Long, sender: String, body: String,
              categories: List<String>, learned: Map<String, String>): Txn? {
        if (OTP.containsMatchIn(body) || PROMO.containsMatchIn(body) || NOTICE.containsMatchIn(body)) return null
        val m = AMT.find(body) ?: return null
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0) return null
        val d = DEBIT.find(body)
        val c = CREDIT.find(body)
        if (d == null && c == null) return null
        val type = if (d != null && (c == null || d.range.first < c.range.first)) "debit" else "credit"

        val clean = mask(body)
        val acct = ACCT.find(clean)?.groupValues?.get(1)?.filter { it.isDigit() }?.takeLast(4)
        val account = if (acct.isNullOrEmpty()) "" else "XX$acct"

        var payee = ""
        for (rx in MERCHANT) {
            val mm = rx.find(clean) ?: continue
            val cand = mm.groupValues[1].trim(' ', '.', '-')
                .replace(Regex("""^(neft|imps|rtgs|upi)\s+(from|to)\s+""", I), "")
            if (!GENERIC.matches(cand) && cand.length >= 2) { payee = cand.take(40); break }
        }
        val channel = when {
            UPI.containsMatchIn(body) -> "UPI"
            CARD.containsMatchIn(body) -> "Card"
            Regex("""\batm\b""", I).containsMatchIn(body) -> "ATM"
            Regex("""neft|imps|rtgs""", I).containsMatchIn(body) -> "NEFT/IMPS"
            else -> "Bank"
        }
        var returnType = ""
        if (type == "credit") {
            for ((rt, rx) in RETURNS) if (rx.containsMatchIn(body)) { returnType = rt; break }
        }
        val (cat, how) = if (type == "debit") categorize(payee, clean, categories, learned) else ("" to "")
        return Txn("s$smsId", time, type, amount, channel, payee, account, cat, returnType, how)
    }
}
