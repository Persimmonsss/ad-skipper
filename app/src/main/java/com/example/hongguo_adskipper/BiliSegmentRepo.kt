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
        Stats.log("搜索命中 ${candidates.size} 条候选")
        // 优先取社区库里有 sponsor 片段的候选，避免同名视频误匹配
        for ((bvid, _) in candidates.take(3)) {
            val segs = fetchSegments(bvid)
            if (segs.isNotEmpty()) {
                titleCache[key] = bvid
                return bvid
            }
        }
        val fallback = candidates.first().first
        Stats.log("社区库无片段：$fallback")
        titleCache[key] = fallback
        return fallback
    }

    /**
     * 整句标题（带标点、很长）拿去搜索，B站 常返回一堆相似但不相干的视频；
     * 但只给首句又会漏掉目标（实测“一只小胡蜂钻进飞机”召不回原片，加上第二分句才命中），
     * 所以按“前两个分句 → 首句 → 整句”依次试，命中即停。
     */
    private fun searchKeywords(title: String): List<String> {
        val clauses = title.split('，', ',', '。', '！', '？', ' ', '|', '｜', '：', ':')
            .filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        if (clauses.size >= 2) out.add((clauses[0] + clauses[1]).take(24))
        clauses.firstOrNull()?.let { out.add(it) }
        out.add(title.take(24))
        return out.filter { it.isNotBlank() }
    }

    /** 返回 BV 号 + 时长秒数 */
    private fun searchCandidates(title: String): List<Pair<String, Int>> {
        for (keyword in searchKeywords(title)) {
            val hit = searchOnce(title, keyword)
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }

    private fun searchOnce(title: String, keyword: String): List<Pair<String, Int>> {
        // app 端 x/v2/search 会忽略关键词返回推荐流（实测全是无关内容），改用 web 搜索接口
        val url = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=${URLEncoder.encode(keyword, "UTF-8")}"
        val body = httpGet(url, referer = "https://www.bilibili.com/") ?: return emptyList()
        return try {
            val root = JSONObject(body)
            val groups = root.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
            val items = JSONArray()
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                if (g.optString("result_type") != "video") continue
                val arr = g.optJSONArray("data") ?: continue
                for (j in 0 until arr.length()) items.put(arr.optJSONObject(j) ?: continue)
            }
            Stats.log("搜索返回 ${items.length()} 条（code=${root.optInt("code")}）")
            val want = normalize(title)
            // 时长 / 相似度；标题可能被加尾注或标点差异，不能只认完全相等
            val scored = mutableListOf<Triple<String, Int, Int>>()
            var firstTitle = ""
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val t = normalize(stripTags(item.optString("title")))
                if (i == 0) firstTitle = t
                val bvid = item.optString("bvid").takeIf { it.startsWith("BV") } ?: continue
                val dur = parseDuration(item.optString("duration"))
                val score = when {
                    t == want -> 1000
                    t.contains(want) || want.contains(t) -> 500 + minOf(t.length, want.length)
                    else -> commonPrefix(t, want)
                }
                if (score >= 8 && durationCompatible(dur)) scored.add(Triple(bvid, dur, score))
            }
            scored.sortByDescending { it.third }
            if (scored.isEmpty() && firstTitle.isNotEmpty()) {
                Stats.log("无匹配候选：want(${want.length})=${want.take(20)} / first(${firstTitle.length})=${firstTitle.take(20)}")
            }
            scored.map { it.first to it.second }
        } catch (e: Exception) {
            Stats.log("搜索解析失败：${e.message}")
            emptyList()
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** 时长已知时要求接近（±5 秒），未知则不拦。 */
    private fun durationCompatible(searchSec: Int): Boolean {
        val curSec = Stats.biliDurationMs / 1000
        if (searchSec <= 0 || curSec <= 0) return true
        return kotlin.math.abs(searchSec - curSec) <= 5
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

    private fun httpGet(url: String, referer: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                if (referer != null) setRequestProperty("Referer", referer)
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
