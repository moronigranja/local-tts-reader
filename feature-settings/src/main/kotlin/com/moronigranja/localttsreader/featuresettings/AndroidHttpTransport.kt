package com.moronigranja.localttsreader.featuresettings

import com.moronigranja.localttsreader.tts.DownloadTransport
import com.moronigranja.localttsreader.tts.HttpBody
import com.moronigranja.localttsreader.tts.OpenResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The on-device download transport (V1): the Android adapter behind
 * [DownloadTransport] — HttpURLConnection on the IO dispatcher, following
 * redirects (platform default for GET; GitHub release URLs redirect to the
 * CDN), honoring `Range: bytes=<from>-` for resumable pack downloads.
 *
 * This is the app's sanctioned network path for the settings download UI
 * (hard-facts offline-first: an explicit, user-consented download — same
 * seam the JVM tooling uses; no other socket use).
 */
class AndroidHttpTransport : DownloadTransport {

    override suspend fun open(url: String, rangeFrom: Long?): OpenResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (rangeFrom != null) connection.setRequestProperty("Range", "bytes=$rangeFrom-")
        try {
            val status = connection.responseCode
            if (status in 200..299) {
                OpenResult.Body(
                    HttpBody(
                        statusCode = status,
                        contentLength = connection.contentLengthLong.takeIf { it >= 0 },
                        bytes = connection.inputStream,
                    ),
                )
            } else {
                connection.errorStream?.close()
                OpenResult.HttpError(status)
            }
        } catch (e: IOException) {
            throw e
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "local-tts-reader/0.1"
    }
}
