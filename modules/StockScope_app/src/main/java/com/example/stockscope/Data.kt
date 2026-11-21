package com.example.stockscope

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

// ---------- Models ----------
data class Holder(val name: String, val pct: Double)
data class Section(val title: String, val rows: List<Pair<String, String>>)
data class StockRow(val symbol: String, val name: String, val sector: String, val price: Double, val changePct: Double, val marketCap: Double)
data class EtfRow(val symbol: String, val name: String)

data class Stock(
    val symbol: String, val name: String, val sector: String, val industry: String, val about: String,
    val price: Double, val changePct: Double, val targetPrice: Double, val rating: String,
    val peers: List<String>, val revenue: List<Pair<Int, Double>>, // year to $B, ascending
    val holders: List<Holder>, val sections: List<Section>
) {
    val hasTarget get() = targetPrice > 0
    val premiumPct get() = if (targetPrice > 0) (price / targetPrice - 1) * 100 else 0.0 // + premium, - discount vs analyst target
    val revenueUp get() = revenue.size > 1 && revenue.last().second >= revenue[revenue.size - 2].second
    val revenueGrowthPct get() =
        if (revenue.size > 1 && revenue[revenue.size - 2].second != 0.0)
            (revenue.last().second / revenue[revenue.size - 2].second - 1) * 100 else 0.0
}

data class Etf(
    val symbol: String, val name: String, val stats: List<Pair<String, String>>,
    val allocation: List<Pair<String, Double>>, val sectorExposure: List<Pair<String, Double>>,
    val topHoldings: List<Pair<String, Double>>
)

interface MarketRepo {
    suspend fun stocks(page: Int, query: String, sector: String): List<StockRow>
    suspend fun stockDetail(symbol: String): Stock
    suspend fun etfs(query: String): List<EtfRow>
    suspend fun etfDetail(symbol: String): Etf
}

class ApiError(val code: Int, what: String) : Exception(
    if (code == 429) "Yahoo is rate-limiting requests (429). Wait a minute and try again." else "HTTP $code ($what)"
)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private val US_EXCHANGES = setOf("NMS", "NGM", "NCM", "NYQ", "ASE", "PCX", "BTS")

/** Yahoo Finance (unofficial public endpoints, no API key). */
class YahooRepo : MarketRepo {
    private val q1 = "https://query1.finance.yahoo.com"
    private val q2 = "https://query2.finance.yahoo.com"
    private var cookie = ""
    private var crumb = ""
    private val mutex = Mutex()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, cookie: String, body: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (body != null) "POST" else "GET"
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    /** Yahoo needs a session cookie (from fc.yahoo.com) plus a crumb for most data endpoints. */
    private suspend fun ensureSession() = mutex.withLock {
        if (crumb.isEmpty()) withContext(Dispatchers.IO) {
            val c = URL("https://fc.yahoo.com").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.responseCode // 404 is expected; we only need the Set-Cookie header
            cookie = c.headerFields.filterKeys { it?.equals("set-cookie", true) == true }
                .values.flatten().joinToString("; ") { it.substringBefore(";") }
            c.disconnect()
            val c2 = open("$q1/v1/test/getcrumb", cookie)
            if (c2.responseCode != 200) throw ApiError(c2.responseCode, "crumb")
            crumb = c2.inputStream.bufferedReader().use { it.readText() }.trim()
        }
    }

    private suspend fun json(url: String, crumbed: Boolean = false, body: String? = null, retry: Boolean = true): JSONObject {
        if (crumbed) ensureSession()
        return withContext(Dispatchers.IO) {
            val full = if (crumbed) url + (if ("?" in url) "&" else "?") + "crumb=" + enc(crumb) else url
            val c = open(full, if (crumbed) cookie else "", body)
            val code = c.responseCode
            if (code == 401 && crumbed && retry) { crumb = ""; cookie = ""; return@withContext json(url, true, body, false) }
            if (code != 200) throw ApiError(code, url.substringAfter(".com/").substringBefore("?"))
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        }
    }

    private suspend fun screener(type: String, page: Int, sector: String, exchanges: List<String>, sortField: String): List<JSONObject> {
        fun eq(f: String, v: String) = JSONObject().put("operator", "EQ").put("operands", JSONArray(listOf(f, v)))
        val operands = JSONArray()
            .put(eq("region", "us"))
            .put(JSONObject().put("operator", "or").put("operands", JSONArray(exchanges.map { eq("exchange", it) })))
        if (sector.isNotBlank()) operands.put(eq("sector", sector))
        val body = JSONObject()
            .put("size", 100).put("offset", page * 100)
            .put("sortField", sortField).put("sortType", "DESC").put("quoteType", type)
            .put("query", JSONObject().put("operator", "AND").put("operands", operands))
            .put("userId", "").put("userIdType", "guid")
        val r = json("$q1/v1/finance/screener?formatted=false&lang=en-US&region=US", true, body.toString())
        return r.optJSONObject("finance")?.optJSONArray("result")?.optJSONObject(0)?.optJSONArray("quotes").objs()
    }

    // ---- stocks ----
    override suspend fun stocks(page: Int, query: String, sector: String): List<StockRow> {
        if (query.isNotBlank()) {
            val r = json("$q1/v1/finance/search?q=${enc(query)}&quotesCount=25&newsCount=0&listsCount=0")
            return r.optJSONArray("quotes").objs()
                .filter { it.optString("quoteType") == "EQUITY" && it.optString("exchange") in US_EXCHANGES }
                .map { StockRow(it.optString("symbol"), it.optString("longname").ifBlank { it.optString("shortname") },
                    it.optString("sector"), 0.0, 0.0, 0.0) }
        }
        return try {
            screener("EQUITY", page, sector, listOf("NMS", "NGM", "NCM", "NYQ", "ASE"), "intradaymarketcap").map {
                StockRow(it.optString("symbol"), it.optString("longName").ifBlank { it.optString("shortName") }, sector,
                    it.d("regularMarketPrice") ?: 0.0, it.d("regularMarketChangePercent") ?: 0.0, it.d("marketCap") ?: 0.0)
            }
        } catch (e: Exception) {
            if (page == 0 && sector.isBlank()) POPULAR_STOCKS.map { StockRow(it.first, it.second, "", 0.0, 0.0, 0.0) } else throw e
        }
    }

    override suspend fun stockDetail(symbol: String): Stock = coroutineScope {
        val mods = "price,summaryProfile,summaryDetail,defaultKeyStatistics,financialData,majorHoldersBreakdown,institutionOwnership,earnings"
        val summary = async { json("$q2/v10/finance/quoteSummary/${enc(symbol)}?formatted=false&modules=$mods", true) }
        val peers = async { runCatching { json("$q2/v6/finance/recommendationsbysymbol/${enc(symbol)}") }.getOrNull() }
        val series = async {
            runCatching {
                json("$q1/ws/fundamentals-timeseries/v1/finance/timeseries/${enc(symbol)}?symbol=${enc(symbol)}" +
                    "&type=annualTotalRevenue&merge=false&period1=1451606400&period2=${System.currentTimeMillis() / 1000}&lang=en-US&region=US")
            }.getOrNull()
        }
        val res = summary.await().optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0)
            ?: error("No data for $symbol")
        val pr = res.o("price"); val prof = res.o("summaryProfile"); val sd = res.o("summaryDetail")
        val ks = res.o("defaultKeyStatistics"); val fd = res.o("financialData"); val mh = res.o("majorHoldersBreakdown")

        var rev = series.await()?.optJSONObject("timeseries")?.optJSONArray("result").objs()
            .firstNotNullOfOrNull { it.optJSONArray("annualTotalRevenue") }.objs()
            .mapNotNull { e ->
                val y = e.optString("asOfDate").take(4).toIntOrNull()
                val v = e.optJSONObject("reportedValue")?.d("raw")
                if (y != null && v != null) y to v / 1e9 else null
            }.sortedBy { it.first }
        if (rev.isEmpty()) rev = res.o("earnings").o("financialsChart").optJSONArray("yearly").objs()
            .mapNotNull { e -> val y = e.d("date")?.toInt(); val v = e.d("revenue"); if (y != null && v != null) y to v / 1e9 else null }
            .sortedBy { it.first }

        val price = pr.d("regularMarketPrice") ?: 0.0
        val label = when (fd.optString("recommendationKey")) {
            "strong_buy" -> "Strong Buy"; "buy" -> "Buy"; "hold" -> "Hold"
            "underperform" -> "Underperform"; "sell" -> "Sell"; "strong_sell" -> "Strong Sell"; else -> "N/A"
        }
        val mean = fd.d("recommendationMean"); val n = fd.d("numberOfAnalystOpinions")?.toInt()
        val rating = if (label == "N/A") label else label + (mean?.let { " · %.1f/5".format(it) } ?: "") + (n?.let { " · $it analysts" } ?: "")
        val de = fd.d("debtToEquity")?.let { it / 100 }

        val sections = listOf(
            Section("Valuation", listOf(
                "Market cap" to (pr.d("marketCap") ?: sd.d("marketCap")).big(),
                "P/E (TTM)" to sd.d("trailingPE").x(),
                "Forward P/E" to (sd.d("forwardPE") ?: ks.d("forwardPE")).x(),
                "PEG ratio" to ks.d("pegRatio").x(),
                "Price / Sales" to sd.d("priceToSalesTrailing12Months").x(),
                "Price / Book" to ks.d("priceToBook").x(),
                "EV / EBITDA" to ks.d("enterpriseToEbitda").x(),
                "EV / Revenue" to ks.d("enterpriseToRevenue").x())),
            Section("Profitability", listOf(
                "Gross margin" to fd.d("grossMargins").pct(),
                "Operating margin" to fd.d("operatingMargins").pct(),
                "Net margin" to (fd.d("profitMargins") ?: ks.d("profitMargins")).pct(),
                "Return on equity" to fd.d("returnOnEquity").pct(),
                "Return on assets" to fd.d("returnOnAssets").pct())),
            Section("Growth & earnings", listOf(
                "Revenue (TTM)" to fd.d("totalRevenue").big(),
                "Revenue growth (YoY)" to fd.d("revenueGrowth").pct(),
                "Earnings growth (YoY)" to fd.d("earningsGrowth").pct(),
                "EBITDA" to fd.d("ebitda").big(),
                "Free cash flow" to fd.d("freeCashflow").big(),
                "EPS (TTM)" to ks.d("trailingEps").x(),
                "EPS (forward)" to ks.d("forwardEps").x())),
            Section("Financial health", listOf(
                "Total cash" to fd.d("totalCash").big(),
                "Total debt" to fd.d("totalDebt").big(),
                "Debt / Equity" to de.x(),
                "Current ratio" to fd.d("currentRatio").x(),
                "Quick ratio" to fd.d("quickRatio").x(),
                "Operating cash flow" to fd.d("operatingCashflow").big())),
            Section("Trading & dividends", listOf(
                "52-week range" to "${sd.d("fiftyTwoWeekLow").x()} – ${sd.d("fiftyTwoWeekHigh").x()}",
                "Beta" to (sd.d("beta") ?: ks.d("beta")).x(),
                "Dividend yield" to sd.d("dividendYield").pct(),
                "Payout ratio" to sd.d("payoutRatio").pct(),
                "Avg volume" to sd.d("averageVolume").big(""),
                "Shares outstanding" to ks.d("sharesOutstanding").big(""),
                "Held by insiders" to (mh.d("insidersPercentHeld") ?: ks.d("heldPercentInsiders")).pct(),
                "Held by institutions" to (mh.d("institutionsPercentHeld") ?: ks.d("heldPercentInstitutions")).pct(),
                "Short % of float" to ks.d("shortPercentOfFloat").pct())),
            Section("Analyst price targets", listOf(
                "Mean target" to fd.d("targetMeanPrice").x(),
                "Low target" to fd.d("targetLowPrice").x(),
                "High target" to fd.d("targetHighPrice").x()))
        )
        Stock(
            symbol = symbol, name = pr.optString("longName").ifBlank { pr.optString("shortName") },
            sector = prof.optString("sector"), industry = prof.optString("industry"),
            about = prof.optString("longBusinessSummary").take(420),
            price = price, changePct = (pr.d("regularMarketChangePercent") ?: 0.0) * 100,
            targetPrice = fd.d("targetMeanPrice") ?: 0.0, rating = rating,
            peers = peers.await()?.optJSONObject("finance")?.optJSONArray("result")?.optJSONObject(0)
                ?.optJSONArray("recommendedSymbols").objs().map { it.optString("symbol") }
                .filter { it.isNotBlank() && it != symbol }.take(8),
            revenue = rev,
            holders = res.o("institutionOwnership").optJSONArray("ownershipList").objs()
                .map { Holder(it.optString("organization"), (it.d("pctHeld") ?: 0.0) * 100) }
                .sortedByDescending { it.pct }.take(8),
            sections = sections
        )
    }

    // ---- ETFs ----
    override suspend fun etfs(query: String): List<EtfRow> {
        if (query.isNotBlank()) {
            val r = json("$q1/v1/finance/search?q=${enc(query)}&quotesCount=25&newsCount=0&listsCount=0")
            return r.optJSONArray("quotes").objs()
                .filter { it.optString("quoteType") == "ETF" && it.optString("exchange") in US_EXCHANGES }
                .map { EtfRow(it.optString("symbol"), it.optString("longname").ifBlank { it.optString("shortname") }) }
        }
        return try {
            screener("ETF", 0, "", listOf("PCX", "BTS", "NMS", "NGM", "NYQ", "ASE"), "dayvolume")
                .map { EtfRow(it.optString("symbol"), it.optString("longName").ifBlank { it.optString("shortName") }) }
        } catch (e: Exception) {
            POPULAR_ETFS.map { EtfRow(it.first, it.second) }
        }
    }

    override suspend fun etfDetail(symbol: String): Etf = coroutineScope {
        val mods = "price,summaryDetail,defaultKeyStatistics,topHoldings,fundProfile"
        val res = json("$q2/v10/finance/quoteSummary/${enc(symbol)}?formatted=false&modules=$mods", true)
            .optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0) ?: error("No data for $symbol")
        val pr = res.o("price"); val sd = res.o("summaryDetail"); val ks = res.o("defaultKeyStatistics")
        val th = res.o("topHoldings"); val fp = res.o("fundProfile")
        val alloc = listOf("Stocks" to "stockPosition", "Bonds" to "bondPosition", "Cash" to "cashPosition",
            "Preferred" to "preferredPosition", "Convertible" to "convertiblePosition", "Other" to "otherPosition")
            .mapNotNull { (label, k) -> th.d(k)?.let { label to it * 100 } }.filter { it.second > 0.05 }
        Etf(
            symbol = symbol, name = pr.optString("longName").ifBlank { pr.optString("shortName").ifBlank { symbol } },
            stats = listOf(
                "Price" to (pr.d("regularMarketPrice") ?: sd.d("previousClose")).x(),
                "Net assets" to (sd.d("totalAssets") ?: ks.d("totalAssets")).big(),
                "Expense ratio" to fp.o("feesExpensesInvestment").d("annualReportExpenseRatio").pct(2),
                "Yield" to (sd.d("yield") ?: ks.d("yield")).pct(2),
                "YTD return" to ks.d("ytdReturn").pct(),
                "3-yr beta" to ks.d("beta3Year").x(),
                "52-week range" to "${sd.d("fiftyTwoWeekLow").x()} – ${sd.d("fiftyTwoWeekHigh").x()}",
                "Category" to fp.optString("categoryName").ifBlank { "—" },
                "Fund family" to fp.optString("family").ifBlank { "—" }),
            allocation = alloc,
            sectorExposure = th.optJSONArray("sectorWeightings").objs().flatMap { o ->
                o.keys().asSequence().map { k -> pretty(k) to (o.d(k) ?: 0.0) * 100 }.toList()
            }.filter { it.second > 0.05 }.sortedByDescending { it.second },
            topHoldings = th.optJSONArray("holdings").objs().map {
                it.optString("symbol").ifBlank { it.optString("holdingName").take(14) } to (it.d("holdingPercent") ?: 0.0) * 100
            }.take(10)
        )
    }
}

// ---------- helpers ----------
private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject?.o(key: String): JSONObject = this?.optJSONObject(key) ?: JSONObject()

/** Number at key (plain number, numeric string, or {"raw": n}); null if missing. */
private fun JSONObject.d(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.replace("%", "").toDoubleOrNull()
        is JSONObject -> if (v.has("raw")) v.d("raw") else null
        else -> null
    }
}

private fun Double?.x(dec: Int = 2): String = if (this == null || isNaN()) "—" else "%.${dec}f".format(this)
private fun Double?.pct(dec: Int = 1): String = if (this == null || isNaN()) "—" else "%.${dec}f%%".format(this * 100)
private fun Double?.big(p: String = "$"): String = when {
    this == null || isNaN() -> "—"
    abs(this) >= 1e12 -> p + "%.2fT".format(this / 1e12)
    abs(this) >= 1e9 -> p + "%.2fB".format(this / 1e9)
    abs(this) >= 1e6 -> p + "%.2fM".format(this / 1e6)
    else -> p + "%.0f".format(this)
}

private fun pretty(key: String): String =
    if (key == "realestate") "Real Estate"
    else key.split("_").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

private val POPULAR_STOCKS = listOf(
    "AAPL" to "Apple Inc.", "MSFT" to "Microsoft Corp.", "NVDA" to "NVIDIA Corp.", "AMZN" to "Amazon.com Inc.",
    "GOOGL" to "Alphabet Inc.", "META" to "Meta Platforms", "TSLA" to "Tesla Inc.", "BRK-B" to "Berkshire Hathaway",
    "AVGO" to "Broadcom Inc.", "LLY" to "Eli Lilly", "JPM" to "JPMorgan Chase", "V" to "Visa Inc.",
    "XOM" to "Exxon Mobil", "UNH" to "UnitedHealth Group", "MA" to "Mastercard", "COST" to "Costco",
    "HD" to "Home Depot", "PG" to "Procter & Gamble", "JNJ" to "Johnson & Johnson", "WMT" to "Walmart"
)

private val POPULAR_ETFS = listOf(
    "SPY" to "SPDR S&P 500 ETF", "VOO" to "Vanguard S&P 500 ETF", "QQQ" to "Invesco QQQ Trust",
    "VTI" to "Vanguard Total Stock Market", "IWM" to "iShares Russell 2000", "XLK" to "Technology Select Sector SPDR",
    "XLF" to "Financial Select Sector SPDR", "XLE" to "Energy Select Sector SPDR", "SCHD" to "Schwab US Dividend Equity",
    "GLD" to "SPDR Gold Shares", "TLT" to "iShares 20+ Year Treasury", "AGG" to "iShares Core US Aggregate Bond"
)
