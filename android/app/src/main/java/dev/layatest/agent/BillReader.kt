package dev.layatest.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private val BILL_CURRENCY = Regex("(?:₹|rs\\.?|inr)\\s*(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d{1,2})?(?![\\d,.])", RegexOption.IGNORE_CASE)
private val BILL_LABEL = Regex("\\b(?:total (?:amount )?due|amount due|bill amount|unpaid (?:bill|amount)|(?:current |total )?outstanding(?: balance| amount)?|grand total due|total (?:amount )?payable)(?: (?:across|for) all (?:credit )?cards)?\\b", RegexOption.IGNORE_CASE)
private val BILL_PROMO = Regex("\\b(?:offers?|cash ?back|rewards?|earn|up to|loans?|credit limit|available credit|discount|savings?|minimum(?: amount)? due|lakh|crore|thousand|million|billion)\\b|\\d\\s*[km]\\b", RegexOption.IGNORE_CASE)
private val ALL_CARDS_TOTAL = Regex("\\bgrand total due\\b|\\btotal (?:amount )?(?:due|outstanding|payable) (?:across|for) all (?:credit )?cards\\b", RegexOption.IGNORE_CASE)

internal fun totalBillAsked(request: String): Boolean =
    Regex("\\b(?:total|altogether|overall|combined)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)

/** Read explicitly labeled due amounts from one visible screen; never add unverified card rows or offers. */
internal fun readBillAmounts(texts: List<ScreenText>, totalRequested: Boolean = false): String? =
    billAnswer(parseBillAmounts(texts), totalRequested)

/** Evidence for the coordinator; the exact labels/amounts stay local, and no unexposed account data is inferred. */
internal fun observeBillAmounts(screen: Screen, totalRequested: Boolean = false): JSONObject? {
    val pkg = screen.pkg ?: return null
    val amounts = parseBillAmounts(screen.texts)
    val answer = billAnswer(amounts, totalRequested) ?: return null
    val total = explicitBillTotal(amounts)
    val source = screen.texts.joinToString("\n") { it.text }
    val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return JSONObject().put("source_package", pkg).put("captured_at", System.currentTimeMillis())
        .put("screen_text", source).put("source_text_sha256", hash).put("coverage", "visible_screen").put("explicit_total", total != null)
        .put("items", JSONArray(amounts.map { (label, amount) -> JSONObject().put("label", label).put("amount", amount) }))
        .put("answer", answer).apply { total?.let { put("total_amount", it) } }
}

private fun parseBillAmounts(texts: List<ScreenText>): List<Pair<String, String>> {
    val lines = texts.map { it.text.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.distinct()
    val amounts = linkedMapOf<String, Pair<String, String>>()
    lines.forEachIndexed { i, line ->
        if (BILL_PROMO.containsMatchIn(line)) return@forEachIndexed
        val labels = BILL_LABEL.findAll(line).toList()
        if (labels.size != 1) return@forEachIndexed
        val label = labels.single().value
        val direct = BILL_CURRENCY.findAll(line).map { it.value }.toList()
        val amount = when {
            direct.size == 1 -> direct.single()
            direct.isNotEmpty() -> null
            line.trim(' ', ':', '-') == label -> {
                // Split accessibility nodes are accepted only when the adjacent node is an isolated currency amount.
                listOf(i - 1, i + 1).filter { it in lines.indices }.filter { j ->
                    BILL_CURRENCY.matches(lines[j]) &&
                        (maxOf(0, j - 1)..minOf(lines.lastIndex, j + 1)).none { BILL_PROMO.containsMatchIn(lines[it]) }
                }.map { lines[it] }.distinct().singleOrNull()
            }
            else -> null
        } ?: return@forEachIndexed
        val canonical = label.lowercase() + "|" + amount.lowercase().replace(Regex("\\s+"), "")
        amounts[canonical] = label to amount
    }
    return amounts.values.toList()
}

private fun explicitBillTotal(amounts: List<Pair<String, String>>): String? = amounts
    .filter { ALL_CARDS_TOTAL.containsMatchIn(it.first) }.map { it.second }.distinct().singleOrNull()

private fun billAnswer(amounts: List<Pair<String, String>>, totalRequested: Boolean): String? {
    if (amounts.isEmpty()) return null
    val shown = amounts.joinToString("\n") { (label, amount) -> "$label: $amount" }
    if (!totalRequested) return "Labeled bill amounts on this screen:\n$shown\nI haven't verified that every card is included."
    val total = explicitBillTotal(amounts)
    return if (total != null) "Total due shown on this screen: $total"
    else "Labeled bill amounts on this screen:\n$shown\nI couldn't verify a complete total across your cards, so I haven't added these amounts."
}

internal fun billReaderSelfCheck(): String {
    fun texts(vararg values: String) = values.mapIndexed { i, value -> ScreenText(value, intArrayOf(0, i * 20, 200, (i + 1) * 20)) }
    check(readBillAmounts(texts("Personal loan", "Up to ₹40 lakh", "Earn ₹500 cashback", "Credit limit ₹80,000")) == null)
    check(readBillAmounts(texts("Minimum amount due ₹400", "Available credit ₹20,000")) == null)
    check(readBillAmounts(texts("Total amount due", "₹5,000"))?.contains("Total amount due: ₹5,000") == true)
    check(readBillAmounts(texts("₹5,000", "Amount due", "₹2,000")) == null) // ambiguous neighboring values
    check(readBillAmounts(texts("Total amount due ₹5,000", "Total amount due ₹5,000"))?.lines()?.count { "₹5,000" in it } == 1)
    check(readBillAmounts(texts("Total amount due ₹5,000 minimum amount due ₹400")) == null)
    check(readBillAmounts(texts("Earn cashback", "₹500", "Amount due")) == null)
    check(readBillAmounts(texts("Bill amount ₹3 lakh")) == null)
    check(readBillAmounts(texts("Bill amount ₹2,000", "Outstanding balance ₹3,000"), true)?.let {
        "haven't added" in it && "₹5,000" !in it
    } == true)
    check(readBillAmounts(texts("Grand total due ₹7,500"), true) == "Total due shown on this screen: ₹7,500")
    check(readBillAmounts(texts("Total due across all credit cards ₹7,500"), true) == "Total due shown on this screen: ₹7,500")
    val partial = observeBillAmounts(Screen("bill.test", emptyList(), texts("Bill amount ₹2,000")), true)!!
    check(partial.optString("coverage") == "visible_screen" && !partial.optBoolean("explicit_total") && !partial.has("total_amount"))
    check(partial.getJSONArray("items").length() == 1 && partial.optString("source_text_sha256").length == 64 && partial.optLong("captured_at") > 0)
    val complete = observeBillAmounts(Screen("bill.test", emptyList(), texts("Grand total due ₹7,500")), true)!!
    check(complete.optBoolean("explicit_total") && complete.optString("total_amount") == "₹7,500")
    check(observeBillAmounts(Screen(null, emptyList(), texts("Grand total due ₹7,500"))) == null)
    check(totalBillAsked("what is the total bill I need to oay") && !totalBillAsked("list my bills"))
    return "ok"
}
