package no.acntech.easycontainers.output

import java.io.InputStream

class LineReader(
    private val input: InputStream,
    private val callback: LineCallback,
) {

    fun read() {
        input.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null && !Thread.currentThread().isInterrupted) {
                callback.onLine(line)
            }
            if (Thread.currentThread().isInterrupted) {
                callback.onLine("<Thread interrupted>")
                Thread.currentThread().interrupt()
            } else {
                callback.onLine(null) // Called when the stream is exhausted
            }
        }
    }

}