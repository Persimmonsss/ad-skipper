package com.example.hongguo_adskipper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class AdSegment(val startMs: Long, val endMs: Long, val uuid: String)

/**
 * B站视频内嵌广告段：标题 → aid → BV 号 → BilibiliSponsorBlock 社区库。
 * 全部阻塞调用，必须放在后台线程执行。
 */
object BiliSegmentRepo {
    private val segmentCache = ConcurrentHashMap<String, List<AdSegment>>()
    private val titleCache = ConcurrentHashMap<String, String>()
    private val segmentHosts = listOf("https://bsbsb.top", "https://bsbsb.cc", "https://www.bsbsb.xyz")

    private const val UA = "Mozilla/5.0 (Linux; Android 12; Mobile) bili/7.0"

    /** 通过视频标题找 BV 号；找不到返回 null。会顺带验证社区库是否有片段。 */
    fun resolveByTitle(title: String): String? {
        val key = normalize(title)
        if (key.isEmpty()) return null
        titleCache[key]?.let { return it }

        val candidates = searchCandidates(title)
        if (candidates.isEmpty()) {
            Stats.log("搜索无结果：${title.take(20)}")
            return null
        }
        // 优先取社区库里有 sponsor 片段的候选，避免同名视频误匹配
        for ((aid, _) in candidates.take(3)) {
            val bvid = AvBv.av2bv(aid)
            val segs = fetchSegments(bvid)
            if (segs.isNotEmpty()) {
                titleCache[key] = bvid
                return bvid
            }
        }
        val fallback = candidates.first().let { AvBv.av2bv(it.first) }
        Stats.log("社区库无片段：$fallback")
        titleCache[key] = fallback
        return fallback
    }

    /** 返回 aid + 时长秒数 */
    private fun searchCandidates(title: String): List<Pair<Long, Int>> {
        val url = "https://app.bilibili.com/x/v2/search?keyword=${URLEncoder.encode(title, "UTF-8")}&build=7000300"
        val body = httpGet(url) ?: return emptyList()
        return try {
            val root = JSONObject(body)
            val items = root.optJSONObject("data")?.optJSONArray("item") ?: JSONArray()
            val want = normalize(title)
            val result = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val t = normalize(stripTags(item.optString("title")))
                val aid = item.optString("param").toLongOrNull()
                    ?: item.optString("bvid").toLongOrNull()
                    ?: item.optLong("aid", 0L).takeIf { it > 0 }
                    ?: continue
                if (t == want) result.add(aid to parseDuration(item.optString("duration")))
            }
            result
        } catch (e: Exception) {
            Stats.log("搜索解析失败：${e.message}")
            emptyList()
        }
    }

    fun fetchSegments(bvid: String): List<AdSegment> {
        segmentCache[bvid]?.let { return it }
        val q = URLEncoder.encode("[\"sponsor\"]", "UTF-8")
        for (host in segmentHosts) {
            val body = httpGet("$host/api/skipSegments?videoID=$bvid&categories=$q") ?: continue
            try {
                val arr = JSONArray(body)
                val segs = mutableListOf<AdSegment>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("category") != "sponsor") continue
                    val seg = o.optJSONArray("segment") ?: continue
                    if (seg.length() < 2) continue
                    val start = (seg.optDouble(0) * 1000).toLong()
                    val end = (seg.optDouble(1) * 1000).toLong()
                    if (end > start) segs.add(AdSegment(start, end, o.optString("UUID")))
                }
                segmentCache[bvid] = segs
                Stats.log("片段库 $bvid：${segs.size} 段")
                return segs
            } catch (e: Exception) {
                Stats.log("片段解析失败($host)：${e.message}")
            }
        }
        return emptyList()
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                Stats.log("HTTP ${conn.responseCode} ${url.take(48)}")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Stats.log("网络失败：${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")

    private fun normalize(s: String): String =
        s.replace(Regex("""[\s\p{P}\p{S}]"""), "").lowercase()

    private fun parseDuration(s: String): Int {
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }
}
