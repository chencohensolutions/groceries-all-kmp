package com.hencohen.grocery.source

import com.hencohen.grocery.model.PortalFile
import com.hencohen.grocery.parse.XmlPullParser
import com.hencohen.grocery.parse.decodedChars
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import okio.BufferedSource
import okio.FileSystem
import okio.GzipSource
import okio.Path
import okio.buffer
import okio.use

/** Streams a portal file to a temp file, then opens it decompressed and decoded for parsing. */
class FileDownloader(
    private val http: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val tempDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
) {
    suspend fun download(file: PortalFile): Path {
        val target = tempDir / "grocery-${file.name}.download"
        http.prepareGet(file.url).execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value} for ${file.name}" }
            val channel = response.bodyAsChannel()
            fileSystem.sink(target).buffer().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n > 0) sink.write(buffer, 0, n)
                }
            }
        }
        return target
    }

    /** Opens [path] (gzip or plain), detects the text encoding and hands [block] a parser. */
    fun <T> parse(path: Path, block: (XmlPullParser) -> T): T =
        openDecompressed(path).use { source -> block(XmlPullParser(source.decodedChars())) }

    fun delete(path: Path) {
        fileSystem.delete(path, mustExist = false)
    }

    private fun openDecompressed(path: Path): BufferedSource {
        val raw = fileSystem.source(path).buffer()
        val peek = raw.peek()
        val magic = if (peek.request(2)) peek.readShort().toInt() and 0xFFFF else 0
        return when (magic) {
            0x1F8B -> GzipSource(raw).buffer()
            0x504B -> {
                raw.close()
                error("ZIP-wrapped price files aren't supported yet: $path")
            }
            else -> raw
        }
    }
}
