package com.fcbtracker.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Downloads straight from each source, from the phone's own connection. */
open class Http(private val userAgent: String = "fcb-tracker/1.0 (Android)") {
    open suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("Accept-Language", "en")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            val stream = if (conn.contentEncoding == "gzip") GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    open suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
