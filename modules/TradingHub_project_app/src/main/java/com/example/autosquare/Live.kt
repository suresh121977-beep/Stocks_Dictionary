package com.example.autosquare
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Writes live portfolio/positions to Downloads/AutoSquare as CSV (universal) and XLS (coloured green/red). */
object Live {
    private fun f2(d: Double) = String.format("%.2f", d)
    private class Row(val cells: List<String>, val tone: Int) // tone: 1 green, -1 red, 0 none

    fun update(ctx: Context, api: AngelApi, s: Store) {
        val sl = s.get("sl", "2").toDoubleOrNull() ?: 2.0; val tp = s.get("tp", "4").toDoubleOrNull() ?: 4.0
        val g = s.get("hsl", "0").toDoubleOrNull() ?: 0.0
        val now = java.text.SimpleDateFormat("dd-MMM HH:mm:ss").format(java.util.Date())
        val hh = listOf("Symbol", "Qty", "Avg Price", "LTP", "P&L Rs", "P&L %", "SL %", "52W High", "52W Low", "Prev High", "Prev Low",
            "Fib 38.2", "Fib 50", "Fib 61.8", "Level Alert", "Updated")
        val hs = api.holdings(); val hr = ArrayList<Row>()
        for (i in 0 until hs.length()) {
            val o = hs.getJSONObject(i); val tok = o.optString("symboltoken")
            val q = o.optString("quantity").toDoubleOrNull() ?: 0.0; val avg = o.optString("averageprice").toDoubleOrNull() ?: 0.0
            val ltp = o.optString("ltp").toDoubleOrNull() ?: 0.0
            val pnl = (ltp - avg) * q; val pct = if (avg > 0) (ltp - avg) / avg * 100 else 0.0
            val lv = try { Data.levels(ctx, api, tok) } catch (e: AuthException) { throw e } catch (e: Exception) { null }
            var alert = ""; var fib = listOf("", "", "")
            var cols = listOf("", "", "", "")
            if (lv != null) {
                val near = Calc.levels(lv).minByOrNull { Math.abs(it.second - ltp) }
                if (near != null && ltp > 0 && Math.abs(near.second - ltp) / ltp <= 0.01)
                    alert = "NEAR ${near.first} ${f2(near.second)} - " + (if (ltp >= near.second) "SUPPORT" else "RESISTANCE")
                val fb = Calc.fibs(lv.getDouble("h52"), lv.getDouble("l52"))
                fib = listOf(f2(fb[1].second), f2(fb[2].second), f2(fb[3].second))
                cols = listOf(f2(lv.getDouble("h52")), f2(lv.getDouble("l52")), f2(lv.getDouble("pdh")), f2(lv.getDouble("pdl")))
            }
            hr.add(Row(listOf(o.optString("tradingsymbol"), q.toInt().toString(), f2(avg), f2(ltp), f2(pnl), f2(pct),
                s.get("hsl_$tok").ifEmpty { g.toString() }) + cols + fib + listOf(alert, now), if (pnl >= 0) 1 else -1))
        }
        val ph = listOf("Symbol", "Product", "Net Qty", "Entry", "LTP", "P&L %", "P&L Rs", "Stop-loss at", "Target at", "Updated")
        val ps = api.positions(); val pr = ArrayList<Row>()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i); val q = p.optString("netqty").toIntOrNull() ?: 0; if (q == 0) continue
            val ltp = p.optString("ltp").toDoubleOrNull() ?: 0.0
            var e = (if (q > 0) p.optString("buyavgprice") else p.optString("sellavgprice")).toDoubleOrNull() ?: 0.0
            if (e <= 0) e = p.optString("avgnetprice").toDoubleOrNull() ?: 0.0
            val d = if (q > 0) 1 else -1; val pct = if (e > 0) (ltp - e) / e * 100 * d else 0.0
            pr.add(Row(listOf(p.optString("tradingsymbol"), p.optString("producttype"), q.toString(), f2(e), f2(ltp), f2(pct),
                p.optString("pnl"), f2(e * (1 - d * sl / 100)), f2(e * (1 + d * tp / 100)), now), if (pct >= 0) 1 else -1))
        }
        save(ctx, "portfolio_live.csv", "text/csv", csv(hh, hr).toByteArray())
        save(ctx, "positions_live.csv", "text/csv", csv(ph, pr).toByteArray())
        save(ctx, "portfolio_live.xls", "application/vnd.ms-excel", xls(listOf("Portfolio" to (hh to hr), "Positions" to (ph to pr))).toByteArray())
    }

    private fun csv(h: List<String>, r: List<Row>) = "\uFEFF" + (listOf(h.joinToString(",")) + r.map { x -> x.cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } }).joinToString("\r\n")

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun xls(sheets: List<Pair<String, Pair<List<String>, List<Row>>>>): String {
        val b = StringBuilder("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Styles>")
        b.append("<Style ss:ID=\"h\"><Font ss:Bold=\"1\"/><Interior ss:Color=\"#CCCCCC\" ss:Pattern=\"Solid\"/></Style>")
        b.append("<Style ss:ID=\"g\"><Font ss:Color=\"#006400\"/><Interior ss:Color=\"#E3F6E3\" ss:Pattern=\"Solid\"/></Style>")
        b.append("<Style ss:ID=\"r\"><Font ss:Color=\"#B00000\"/><Interior ss:Color=\"#FBE3E3\" ss:Pattern=\"Solid\"/></Style></Styles>")
        for ((name, d) in sheets) {
            b.append("<Worksheet ss:Name=\"$name\"><Table>")
            fun row(c: List<String>, st: String) {
                b.append("<Row>"); for (v in c) { val n = v.toDoubleOrNull()
                    b.append("<Cell ss:StyleID=\"$st\"><Data ss:Type=\"${if (n != null && st != "h") "Number" else "String"}\">${esc(v)}</Data></Cell>") }; b.append("</Row>") }
            row(d.first, "h"); d.second.forEach { row(it.cells, if (it.tone >= 0) "g" else "r") }
            b.append("</Table></Worksheet>")
        }
        return b.append("</Workbook>").toString()
    }

    private fun save(ctx: Context, name: String, mime: String, data: ByteArray) {
        if (Build.VERSION.SDK_INT < 29) { File(ctx.getExternalFilesDir(null), name).writeBytes(data); return }
        val rs = ctx.contentResolver; val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri = rs.query(col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "Download/AutoSquare%"), null)?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(col, c.getLong(0)) else null }
        if (uri == null) uri = rs.insert(col, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AutoSquare") })
        rs.openOutputStream(uri!!, "wt")?.use { it.write(data) }
    }
}
