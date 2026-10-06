package br.com.leitorpdf.reader

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object KokoroLocalTts {
    const val VOICE_DORA = "pf_dora"
    const val VOICE_ALEX = "pm_alex"
    const val VOICE_SANTA = "pm_santa"

    private const val MODEL_VERSION = "kokoro-ptbr-fp32-v1"
    private const val ZIP_URL =
        "https://huggingface.co/cristianoaredes/kokoro-pt-br/resolve/main/letrinhas-kokoro-pt.zip"
    private const val ZIP_NAME = "kokoro-ptbr.zip"

    private var engine: OfflineTts? = null
    private var engineDir: File? = null

    data class Voice(val id: String, val sid: Int, val label: String)

    val voices = listOf(
        Voice(VOICE_ALEX, 43, "Masculina • natural"),
        Voice(VOICE_SANTA, 44, "Masculina • narrativa"),
        Voice(VOICE_DORA, 42, "Feminina • natural")
    )

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "model.onnx").isFile &&
            File(dir, "voices.bin").isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory
    }

    suspend fun prepare(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isReady(context)) {
            onProgress(100)
            return@withContext
        }

        val root = modelRoot(context)
        root.mkdirs()
        val zip = File(root, ZIP_NAME)
        val partial = File(root, "$ZIP_NAME.part")

        download(zip, partial, onProgress)
        extractSafely(zip, modelDir(context), onProgress)
        if (!isReady(context)) {
            throw IllegalStateException("O pacote de voz não contém todos os arquivos necessários.")
        }
        zip.delete()
        partial.delete()
        context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("kokoro_ready", true)
            .putString("kokoro_version", MODEL_VERSION)
            .apply()
        onProgress(100)
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        voice: String,
        speed: Float,
        output: File
    ): File = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Texto vazio." }
        val dir = modelDir(context)
        if (!isReady(context)) {
            throw IllegalStateException("O modelo de voz offline ainda não foi instalado.")
        }

        val selected = voices.firstOrNull { it.id == voice } ?: voices[0]
        val tts = getEngine(context, dir)
        val config = GenerationConfig(
            silenceScale = 0.20f,
            speed = speed.coerceIn(0.5f, 2.0f),
            sid = selected.sid
        )
        val audio = synchronized(tts) {
            tts.generateWithConfig(text, config)
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        check(audio.save(output.absolutePath)) {
            "Não foi possível salvar o áudio gerado."
        }
        output
    }

    @Synchronized
    private fun getEngine(context: Context, dir: File): OfflineTts {
        if (engine != null && engineDir?.absolutePath == dir.absolutePath) {
            return engine!!
        }
        engine?.release()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    lang = "pt",
                    lengthScale = 1.0f
                ),
                numThreads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(4)),
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.20f
        )
        engine = OfflineTts(config = config)
        engineDir = dir
        return engine!!
    }

    fun release() {
        synchronized(this) {
            engine?.release()
            engine = null
            engineDir = null
        }
    }

    private fun modelRoot(context: Context): File = File(context.filesDir, "kokoro")
    private fun modelDir(context: Context): File = File(modelRoot(context), "model")

    private fun download(
        destination: File,
        partial: File,
        onProgress: (Int) -> Unit
    ) {
        if (partial.exists() && partial.length() > 0L) partial.delete()

        val connection = (URL(ZIP_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Falha ao baixar o modelo de voz: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val progress = ((copied * 85L) / total).toInt().coerceIn(0, 85)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() < 100_000_000L) {
                throw IllegalStateException("Download incompleto do modelo de voz.")
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                throw IllegalStateException("Não foi possível finalizar o download do modelo.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractSafely(zipFile: File, destination: File, onProgress: (Int) -> Unit) {
        val temp = File(destination.parentFile, "model.tmp")
        if (temp.exists()) temp.deleteRecursively()
        temp.mkdirs()

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            val buffer = ByteArray(64 * 1024)
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                val raw = entry.name.replace('\\', '/').trimStart('/')
                if (raw.isBlank() || raw.contains("../") || raw.contains("..\\") || raw.startsWith("..")) {
                    zip.closeEntry()
                    continue
                }
                val clean = raw.substringAfter("letrinhas-kokoro-pt/", raw)
                val target = File(temp, clean)
                val canonicalRoot = temp.canonicalFile
                val canonicalTarget = target.canonicalFile
                require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Entrada ZIP inválida."
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                entryCount++
                onProgress((85 + entryCount.coerceAtMost(15)).coerceAtMost(99))
                zip.closeEntry()
            }
        }

        val model = findNamed(temp, "model.onnx")
            ?: throw IllegalStateException("model.onnx não encontrado no pacote.")
        val voices = findNamed(temp, "voices.bin")
            ?: throw IllegalStateException("voices.bin não encontrado no pacote.")
        val tokens = findNamed(temp, "tokens.txt")
            ?: throw IllegalStateException("tokens.txt não encontrado no pacote.")
        val data = findNamedDirectory(temp, "espeak-ng-data")
            ?: throw IllegalStateException("espeak-ng-data não encontrado no pacote.")

        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        model.copyTo(File(destination, "model.onnx"), overwrite = true)
        voices.copyTo(File(destination, "voices.bin"), overwrite = true)
        tokens.copyTo(File(destination, "tokens.txt"), overwrite = true)
        copyDirectory(data, File(destination, "espeak-ng-data"))
        temp.deleteRecursively()
    }

    private fun findNamed(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun findNamedDirectory(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isDirectory && it.name == name }

    private fun copyDirectory(source: File, destination: File) {
        source.walkTopDown().forEach { sourceFile ->
            val relative = sourceFile.relativeTo(source)
            val target = File(destination, relative.path)
            if (sourceFile.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                sourceFile.copyTo(target, overwrite = true)
            }
        }
    }
}
