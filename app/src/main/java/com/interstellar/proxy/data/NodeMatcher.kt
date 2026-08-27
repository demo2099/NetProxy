package com.interstellar.proxy.data

/**
 * Maps a node display name onto a region, and matches user-supplied
 * keywords (香港 / HK / 🇸🇬 …) against node tags.
 *
 * Used both for the nodes-page UI grouping and for generating sing-box
 * urltest outbounds / custom route filters.
 */
object NodeMatcher {

    data class Region(
        val id: String,
        val name: String,
        val flag: String,
        val aliases: List<String>,
    )

    val REGIONS: List<Region> = listOf(
        Region("HK", "香港", "🇭🇰", listOf("HK", "HKG", "Hong Kong", "HongKong")),
        Region("TW", "台湾", "🇹🇼", listOf("TW", "TWN", "Taiwan", "臺湾", "台北")),
        Region("JP", "日本", "🇯🇵", listOf("JP", "JPN", "Japan", "东京", "大阪", "名古屋")),
        Region("SG", "新加坡", "🇸🇬", listOf("SG", "SGP", "Singapore", "狮城")),
        Region("US", "美国", "🇺🇸", listOf("US", "USA", "United States", "America", "洛杉矶", "硅谷", "圣何塞")),
        Region("KR", "韩国", "🇰🇷", listOf("KR", "KOR", "Korea", "首尔")),
        Region("GB", "英国", "🇬🇧", listOf("GB", "UK", "United Kingdom", "London", "伦敦")),
        Region("DE", "德国", "🇩🇪", listOf("DE", "GER", "Germany", "Frankfurt", "法兰克福")),
        Region("CA", "加拿大", "🇨🇦", listOf("CA", "CAN", "Canada", "Toronto", "多伦多")),
        Region("FR", "法国", "🇫🇷", listOf("FR", "FRA", "France", "Paris", "巴黎")),
        Region("AU", "澳大利亚", "🇦🇺", listOf("AU", "AUS", "Australia", "Sydney", "悉尼", "墨尔本")),
        Region("ID", "印尼", "🇮🇩", listOf("ID", "IDN", "Indonesia")),
        Region("IN", "印度", "🇮🇳", listOf("IN", "IND", "India", "Mumbai")),
        Region("RU", "俄罗斯", "🇷🇺", listOf("RU", "RUS", "Russia", "Moscow", "莫斯科")),
        Region("BR", "巴西", "🇧🇷", listOf("BR", "BRA", "Brazil")),
        Region("TR", "土耳其", "🇹🇷", listOf("TR", "TUR", "Turkey")),
        Region("TH", "泰国", "🇹🇭", listOf("TH", "THA", "Thailand", "Bangkok", "曼谷")),
        Region("VN", "越南", "🇻🇳", listOf("VN", "VNM", "Vietnam")),
        Region("PH", "菲律宾", "🇵🇭", listOf("PH", "PHL", "Philippines")),
        Region("MY", "马来西亚", "🇲🇾", listOf("MY", "MYS", "Malaysia")),
        Region("AR", "阿根廷", "🇦🇷", listOf("AR", "ARG", "Argentina")),
        Region("IT", "意大利", "🇮🇹", listOf("IT", "ITA", "Italy")),
        Region("NL", "荷兰", "🇳🇱", listOf("NL", "NLD", "Netherlands", "Amsterdam")),
        Region("CH", "瑞士", "🇨🇭", listOf("CH", "CHE", "Switzerland")),
    )

    /** First matching region, or null for uncategorised nodes. */
    fun regionOf(tag: String): Region? {
        for (region in REGIONS) {
            if (matchesRegion(tag, region)) return region
        }
        return null
    }

    fun regionLabel(tag: String): String {
        if (isInfoTag(tag)) return "ℹ️ 信息"
        val region = regionOf(tag) ?: return "🌐 其他"
        return "${region.flag} ${region.name}"
    }

    fun isInfoTag(tag: String): Boolean =
        tag.contains("剩余") || tag.contains("到期") || tag.contains("流量") ||
            tag.contains("过期") || tag.contains("官网") || tag.contains("套餐")

    /**
     * True when [tag] should be considered a hit for a user-typed [keyword].
     * "香港" also matches 🇭🇰 / HK / Hong Kong; short letter codes only hit
     * as `[HK]`, `HK-`, `-HK` and similar so "US" does not match "AUS".
     */
    fun matchesKeyword(tag: String, keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return false
        if (tag.contains(kw, ignoreCase = true)) return true
        val region = regionForKeyword(kw) ?: return false
        return matchesRegion(tag, region)
    }

    fun regionForKeyword(keyword: String): Region? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        return REGIONS.find { region ->
            region.name.equals(kw, true) ||
                region.flag == kw ||
                region.id.equals(kw, true) ||
                region.aliases.any { it.equals(kw, true) }
        }
    }

    fun filterTags(tags: List<String>, keywords: List<String>, include: Boolean): List<String> {
        val needles = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return if (include) emptyList() else tags
        return tags.filter { tag ->
            val hit = needles.any { matchesKeyword(tag, it) }
            if (include) hit else !hit
        }
    }

    fun matchesRegion(tag: String, region: Region): Boolean {
        if (tag.contains(region.flag)) return true
        if (tag.contains(region.name, ignoreCase = true)) return true
        for (alias in region.aliases) {
            if (alias.length <= 3 && alias.all { it.isLetter() }) {
                if (codeHit(tag, alias)) return true
            } else if (containsWord(tag, alias)) {
                return true
            }
        }
        return false
    }

    private fun codeHit(tag: String, code: String): Boolean {
        val c = code.uppercase()
        val t = tag.uppercase()
        if (t.contains("[$c]")) return true
        val boundaries = listOf("-$c-", "-$c ", "-${c}_", "_$c-", "_$c ", " $c-", " $c ", " ${c}_", "$c-", "${c}_", "$c ")
        if (boundaries.any { t.contains(it) }) return true
        if (t.startsWith("$c-") || t.startsWith("${c}_") || t.startsWith("$c ")) return true
        if (t.endsWith("-$c") || t.endsWith("_$c") || t.endsWith(" $c")) return true
        return false
    }

    /** Substring match that does not fire when [word] is only a prefix of a longer word. */
    private fun containsWord(tag: String, word: String): Boolean {
        val t = tag.lowercase()
        val w = word.lowercase()
        if (w.isEmpty()) return false
        var start = 0
        while (true) {
            val i = t.indexOf(w, start)
            if (i < 0) return false
            val before = i == 0 || !t[i - 1].isLetter()
            val after = i + w.length >= t.length || !t[i + w.length].isLetter()
            if (before && after) return true
            start = i + 1
        }
    }
}
