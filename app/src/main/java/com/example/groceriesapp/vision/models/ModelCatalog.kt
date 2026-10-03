package com.example.groceriesapp.vision.models

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** The on-device models the app can download. Ids match `catalog.json` (tools/models/publish_models.py). */
enum class DownloadableModel(val id: String) {
    GLM_OCR("glm_ocr"),
    GLM_OCR_LITERT("glm_ocr_litert"),
    GRANITE("granite"),
}

/** One downloadable file: a URL pinned to a commit, its sha256 and size, and where the app keeps it. */
data class ModelFileSpec(
    val url: String,
    val sha256: String,
    val bytes: Long,
    /** Relative to filesDir, e.g. `models/glm-ocr`. The model managers look for the file there. */
    val destDir: String,
    val destName: String,
)

data class ModelSpec(
    val model: DownloadableModel,
    val name: String,
    val license: String,
    val source: String,
    val files: List<ModelFileSpec>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

data class ModelCatalog(val models: List<ModelSpec>) {
    fun spec(model: DownloadableModel): ModelSpec? = models.firstOrNull { it.model == model }

    companion object {
        const val SUPPORTED_FORMAT = 1

        /** Parses catalog.json; unknown model ids are skipped so a newer catalog stays readable. */
        fun parse(json: String): ModelCatalog {
            val root = JSONObject(json)
            val format = root.getInt("formatVersion")
            require(format <= SUPPORTED_FORMAT) { "Model catalog format $format is newer than this app" }
            val models = root.getJSONArray("models")
            val specs = (0 until models.length()).mapNotNull { i ->
                val m = models.getJSONObject(i)
                val model = DownloadableModel.entries.firstOrNull { it.id == m.getString("id") } ?: return@mapNotNull null
                val files = m.getJSONArray("files")
                ModelSpec(
                    model = model,
                    name = m.getString("name"),
                    license = m.optString("license"),
                    source = m.optString("source"),
                    files = (0 until files.length()).map { j ->
                        val f = files.getJSONObject(j)
                        val destDir = f.getString("destDir")
                        val destName = f.getString("destName")
                        require(isSafeRelativePath(destDir) && isSafeRelativePath(destName) && '/' !in destName) {
                            "Unsafe model path $destDir/$destName"
                        }
                        ModelFileSpec(
                            url = f.getString("url"),
                            sha256 = f.getString("sha256").lowercase(),
                            bytes = f.getLong("bytes"),
                            destDir = destDir,
                            destName = destName,
                        )
                    },
                )
            }
            return ModelCatalog(specs)
        }

        private fun isSafeRelativePath(path: String) =
            path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." || it.isEmpty() }
    }
}

/**
 * The bundled catalog (assets/model_catalog.json), replaced by a newer copy from the model repo when
 * one has been fetched. [refresh] checks the repo at most once a day; failures keep the last copy.
 */
class ModelCatalogStore(context: Context, private val client: OkHttpClient = defaultClient) {
    private val appContext = context.applicationContext
    private val cached = File(appContext.filesDir, "models/catalog.json")

    fun current(): ModelCatalog {
        val text = runCatching { cached.takeIf(File::isFile)?.readText() }.getOrNull()
        text?.let { json -> runCatching { return ModelCatalog.parse(json) } }
        return ModelCatalog.parse(appContext.assets.open(BUNDLED_ASSET).use { it.readBytes().decodeToString() })
    }

    fun refresh(force: Boolean = false) {
        if (!force && cached.isFile && System.currentTimeMillis() - cached.lastModified() < REFRESH_INTERVAL_MS) return
        runCatching {
            val request = Request.Builder().url(REMOTE_URL).build()
            val text = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
            ModelCatalog.parse(text) // only store catalogs this app can read
            cached.parentFile?.mkdirs()
            val tmp = File(cached.path + ".tmp")
            tmp.writeText(text)
            check(tmp.renameTo(cached)) { "could not store the model catalog" }
        }.onFailure { Log.i(TAG, "Model catalog refresh failed; keeping the current one", it) }
    }

    companion object {
        private const val TAG = "ModelCatalog"
        const val BUNDLED_ASSET = "model_catalog.json"
        const val REMOTE_URL = "https://huggingface.co/paulbauriegel/digital-fridge-models/resolve/main/catalog.json"
        private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
