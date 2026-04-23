package com.example.pmtu

import android.content.Context

class DetailsRepository(private val context: Context) {
    private val detailsMap = mutableMapOf<String, Array<String>>()

    init {
        loadDetailsFromCsv()
    }

    private fun loadDetailsFromCsv() {
        try {
            context.assets.open("details.csv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(",", limit = 3)
                    if (parts.size == 3) {
                        var detkey = parts[0].trim().lowercase()
                        detkey = detkey.replace("\"", "")
                        val headline = parts[1].trim().trim('\"')
                        val text = parts[2].trim().trim('\"')
                        detailsMap[detkey] = arrayOf(headline, text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDetails(key: String): Array<String>? {
        val lowerKey = key.lowercase()
        var description = detailsMap[lowerKey]
        
        if (description == null) {
            if (key.startsWith("B ") || key.startsWith("W ")) {
                val splits = key.split(" ")
                if (splits.size >= 2) {
                    val fallbackKey = (splits[0] + " " + splits[1]).lowercase()
                    description = detailsMap[fallbackKey]
                }
            }
        }
        return description
    }
}
