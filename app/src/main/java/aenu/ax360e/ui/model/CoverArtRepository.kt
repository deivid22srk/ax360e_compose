package aenu.ax360e.ui.model

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches box art for Xbox 360 games from TheGamesDB (https://api.thegamesdb.net).
 *
 * Flow:
 *   1. /v1.1/Games/ByGameName?name=<query>&filter[platform]=15&apikey=<key>
 *      -> pick first game id (15 = Microsoft Xbox 360)
 *   2. /v1/Games/Images?games_id=<id>&apikey=<key>
 *      -> find first image with type="boxart" and side="front"
 *      -> base_url.base_img_url + original (or thumb)
 *   3. Download the image bytes.
 *
 * The API requires a free API key from https://thegamesdb.net/key.php (login required).
 * If [apiKey] is blank, every call returns null fast.
 *
 * Returns null on any error so the caller can fall back to the placeholder icon.
 */
object CoverArtRepository {

    private const val TAG = "CoverArtRepository"
    private const val TGDB_BASE = "https://api.thegamesdb.net/v1"
    private const val XBOX_360_PLATFORM_ID = 15
    private const val USER_AGENT = "ax360e-compose/1.0 (Android; cover-art-fetcher)"

    /**
     * Fetch raw cover bytes for [gameName]. Returns null on any failure.
     */
    suspend fun fetchCoverBytes(gameName: String, apiKey: String): ByteArray? {
        if (apiKey.isBlank() || gameName.isBlank()) return null
        val cleanedName = sanitizeQuery(gameName)
        if (cleanedName.isBlank()) return null

        return runCatching {
            val gameId = searchGameIdByName(cleanedName, apiKey) ?: return@runCatching null
            val boxUrl = fetchFrontBoxArtUrl(gameId, apiKey) ?: return@runCatching null
            downloadImageBytes(boxUrl)
        }.onFailure { Log.w(TAG, "fetchCoverBytes failed for '$gameName': ${it.message}") }.getOrNull()
    }

    private fun sanitizeQuery(name: String): String {
        // Strip common release-group / region tags that hurt search quality:
        //   "Halo 3 [PAL] [RF]"  -> "Halo 3"
        //   "Gears of War (USA)"  -> "Gears of War"
        //   "Forza.Motorsport.4-SPARED" -> "Forza Motorsport 4"
        var s = name
            .replace('_', ' ')
            .replace('.', ' ')
        // Remove bracketed / parenthesised tail tokens.
        s = Regex("""\s*[\[\(][^\]\)]*[\]\)]""").replace(s, "")
        // Strip trailing release group tags after a dash.
        s = Regex("""\s+-\s+\S+$""").replace(s, "")
        return s.trim()
    }

    private fun searchGameIdByName(name: String, apiKey: String): Long? {
        val urlStr =
            "$TGDB_BASE.1/Games/ByGameName?name=${URLEncoder.encode(name, "UTF-8")}" +
                "&filter[platform]=$XBOX_360_PLATFORM_ID&apikey=${URLEncoder.encode(apiKey, "UTF-8")}"
        val body = httpGetText(urlStr) ?: return null
        return parseFirstGameId(body)
    }

    private fun parseFirstGameId(json: String): Long? {
        return JsonReader(json.reader()).use { reader ->
            var id: Long? = null
            reader.beginObject()
            while (reader.hasNext() && id == null) {
                when (reader.nextName()) {
                    "data" -> {
                        reader.beginObject()
                        while (reader.hasNext() && id == null) {
                            if (reader.nextName() == "games") {
                                // The "games" field can be an array (v1.0) or an
                                // object keyed by id (v1.1) — handle both.
                                when (reader.peek()) {
                                    JsonToken.BEGIN_ARRAY -> {
                                        reader.beginArray()
                                        if (reader.hasNext()) {
                                            id = readIdFromGameObject(reader)
                                        }
                                        while (reader.hasNext()) reader.skipValue()
                                        reader.endArray()
                                    }
                                    JsonToken.BEGIN_OBJECT -> {
                                        reader.beginObject()
                                        if (reader.hasNext()) {
                                            reader.nextName() // game id key
                                            id = readIdFromGameObject(reader)
                                        }
                                        while (reader.hasNext()) {
                                            reader.nextName()
                                            reader.skipValue()
                                        }
                                        reader.endObject()
                                    }
                                    else -> reader.skipValue()
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            id
        }
    }

    private fun readIdFromGameObject(reader: JsonReader): Long? {
        var id: Long? = null
        reader.beginObject()
        while (reader.hasNext() && id == null) {
            if (reader.nextName() == "id") {
                id = when (reader.peek()) {
                    JsonToken.NUMBER -> reader.nextLong()
                    JsonToken.STRING -> reader.nextString().toLongOrNull()
                    else -> { reader.skipValue(); null }
                }
            } else {
                reader.skipValue()
            }
        }
        while (reader.hasNext()) reader.skipValue()
        reader.endObject()
        return id
    }

    private fun fetchFrontBoxArtUrl(gameId: Long, apiKey: String): String? {
        val urlStr =
            "$TGDB_BASE/Games/Images?games_id=$gameId&apikey=${URLEncoder.encode(apiKey, "UTF-8")}"
        val body = httpGetText(urlStr) ?: return null

        var baseUrl = ""
        var frontRelative: String? = null

        JsonReader(body.reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "include" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "boxart") {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "base_url" -> {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                if (reader.nextName() == "original") {
                                                    baseUrl = reader.nextString()
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            reader.endObject()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "data" -> {
                        reader.beginObject()
                        while (reader.hasNext() && frontRelative == null) {
                            if (reader.nextName() == gameId.toString()) {
                                reader.beginArray()
                                while (reader.hasNext() && frontRelative == null) {
                                    var type = ""
                                    var side = ""
                                    var filename = ""
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "type" -> type = reader.nextString()
                                            "side" -> side = reader.nextString()
                                            "filename" -> filename = reader.nextString()
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    if (type == "boxart" && side == "front" && filename.isNotEmpty()) {
                                        frontRelative = filename
                                    }
                                }
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
        }

        if (baseUrl.isEmpty() || frontRelative.isNullOrEmpty()) return null
        return baseUrl.trimEnd('/') + "/" + frontRelative.trimStart('/')
    }

    private fun downloadImageBytes(urlStr: String): ByteArray? {
        return httpGetBytes(urlStr)
    }

    /** UTF-8 decoded GET, for JSON endpoints. */
    private fun httpGetText(urlStr: String): String? {
        return httpGet(urlStr)?.let { (_, body) -> body }
    }

    /** Raw-bytes GET, for image downloads. */
    private fun httpGetBytes(urlStr: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream?.use { s ->
                val buf = ByteArrayOutputStream(16 * 1024)
                s.copyTo(buf)
                buf.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGetBytes failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Single-shot HTTP GET returning (statusCode, UTF-8 body) or null on connection failure.
     */
    private fun httpGet(urlStr: String): Pair<Int, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                val buf = ByteArrayOutputStream(8 * 1024)
                s.copyTo(buf)
                buf.toString(Charsets.UTF_8.name())
            }
            code to body
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
