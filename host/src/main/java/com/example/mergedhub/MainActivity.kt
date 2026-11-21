package com.example.mergedhub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {
    class Entry(val title: String, val sub: String, val cls: String)

    private val entries = listOf(
        Entry("Stock Levels", "StockLevels", "com.stocklevels.MainActivity"),
        Entry("Word Saver", "WordSaver", "com.wordsaver.MainActivity"),
        Entry("Trading Hub", "TradingHub-project", "com.example.hub.HubActivity"),
        Entry("StockScope", "StockScope", "com.example.stockscope.MainActivity")
    )
    private val icons = intArrayOf(
        R.drawable.ic_0, R.drawable.ic_1, R.drawable.ic_2, R.drawable.ic_3,
        R.drawable.ic_4, R.drawable.ic_5, R.drawable.ic_6, R.drawable.ic_7
    )
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Stocks_Dictionary"
        val list = ListView(this)
        setContentView(list)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(i: Int) = entries[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup): View {
                val e = entries[i]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                row.addView(ImageView(this@MainActivity).apply {
                    setImageResource(icons[i % icons.size])
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                })
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, 0, 0)
                }
                col.addView(TextView(this@MainActivity).apply { text = (i + 1).toString() + ". " + e.title; textSize = 18f })
                col.addView(TextView(this@MainActivity).apply { text = e.sub; textSize = 13f })
                row.addView(col)
                return row
            }
        }
        list.setOnItemClickListener { _, _, i, _ -> open(entries[i]) }
    }

    private fun open(e: Entry) {
        try {
            startActivity(Intent().setClassName(packageName, e.cls))
        } catch (t: Throwable) {
            AlertDialog.Builder(this).setTitle(e.title).setMessage(t.toString())
                .setPositiveButton("OK", null).show()
        }
    }
}
