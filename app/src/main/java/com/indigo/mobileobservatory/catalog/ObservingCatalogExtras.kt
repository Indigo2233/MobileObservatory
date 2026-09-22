package com.indigo.mobileobservatory.catalog

/**
 * Observer-facing aliases layered on the shared OpenNGC + named-star table that
 * both the star map and the target library search.
 *
 * OpenNGC already has NGC/IC/Messier coordinates; this overlay only adds the
 * spellings people actually type (Caldwell numbers, Chinese common names).
 */
object ObservingCatalogExtras {

    fun enrich(objects: List<CatalogObject>): List<CatalogObject> {
        if (objects.isEmpty()) return objects
        val extras = HashMap<String, MutableList<String>>()
        fun add(hostId: String, vararg names: String) {
            val key = normalizeCatalogQuery(hostId)
            val bucket = extras.getOrPut(key) { ArrayList() }
            names.forEach { name ->
                if (name.isNotBlank() && name !in bucket) bucket.add(name)
            }
        }

        for ((number, hostId) in CALDWELL_HOSTS) {
            add(hostId, "C $number", "C$number", "Caldwell $number")
        }
        for ((hostId, names) in CHINESE_NAMES) {
            add(hostId, *names.toTypedArray())
        }

        return objects.map { obj ->
            val extra = extras[normalizeCatalogQuery(obj.id)].orEmpty()
            val fromAliases = obj.aliases.flatMap { alias ->
                extras[normalizeCatalogQuery(alias)].orEmpty()
            }
            val merged = (obj.aliases + extra + fromAliases).distinct()
            if (merged.size == obj.aliases.size) obj else obj.copy(
                name = preferredDisplayName(obj, merged),
                aliases = merged
            )
        }
    }

    private fun preferredDisplayName(obj: CatalogObject, aliases: List<String>): String {
        val existing = obj.name
        if (existing.isNotBlank() && existing != obj.id) return existing
        return aliases.lastOrNull { it != obj.id && !CATALOG_ID.matches(it) } ?: existing
    }

    private val CATALOG_ID = Regex("^[A-Za-z]+ ?\\d+[A-Za-z]?$")

    /**
     * Caldwell number → OpenNGC primary / NGC / IC / Messier id already in
     * `deepsky.csv`. Objects that OpenNGC already stores as `C n` (C 9, 14, 41, 99)
     * still get the number attached to their NGC counterpart when one exists.
     */
    internal val CALDWELL_HOSTS: List<Pair<Int, String>> = listOf(
        1 to "NGC 188", 2 to "NGC 40", 3 to "NGC 4236", 4 to "NGC 7023",
        5 to "IC 342", 6 to "NGC 6543", 7 to "NGC 2403", 8 to "NGC 559",
        9 to "C 9", 10 to "NGC 663", 11 to "NGC 7635", 12 to "NGC 6946",
        13 to "NGC 457", 14 to "NGC 869", 15 to "NGC 6826", 16 to "NGC 7243",
        17 to "NGC 147", 18 to "NGC 185", 19 to "M 110", 20 to "NGC 7000",
        21 to "NGC 4449", 22 to "NGC 7662", 23 to "NGC 891", 24 to "NGC 1275",
        25 to "NGC 2419", 26 to "NGC 4244", 27 to "NGC 6888", 28 to "NGC 752",
        29 to "NGC 5005", 30 to "NGC 7331", 31 to "IC 405", 32 to "NGC 4631",
        33 to "NGC 6992", 34 to "NGC 6960", 35 to "NGC 4889", 36 to "NGC 4559",
        37 to "NGC 6885", 38 to "NGC 4565", 39 to "NGC 2392", 40 to "NGC 3626",
        41 to "C 41", 42 to "NGC 7006", 43 to "NGC 7814", 44 to "NGC 7479",
        45 to "NGC 5248", 46 to "NGC 2261", 47 to "NGC 6934", 48 to "NGC 2775",
        49 to "NGC 2237", 50 to "NGC 2244", 51 to "IC 1613", 52 to "NGC 4697",
        53 to "NGC 3115", 54 to "NGC 2506", 55 to "NGC 7009", 56 to "NGC 246",
        57 to "NGC 6822", 58 to "NGC 2360", 59 to "NGC 3242", 60 to "NGC 4038",
        61 to "NGC 4039", 62 to "NGC 247", 63 to "NGC 7293", 64 to "NGC 2362",
        65 to "NGC 253", 66 to "NGC 5694", 67 to "NGC 1097", 68 to "NGC 6729",
        69 to "NGC 6302", 70 to "NGC 300", 71 to "NGC 2477", 72 to "NGC 55",
        73 to "NGC 1851", 74 to "NGC 3132", 75 to "NGC 6124", 76 to "NGC 6231",
        77 to "NGC 5128", 78 to "NGC 6541", 79 to "NGC 3201", 80 to "NGC 5139",
        81 to "NGC 6352", 82 to "NGC 6193", 83 to "NGC 4945", 84 to "NGC 5286",
        85 to "IC 2391", 86 to "NGC 6397", 87 to "NGC 1261", 88 to "NGC 5823",
        89 to "NGC 6087", 90 to "NGC 2867", 91 to "NGC 3532", 92 to "NGC 3372",
        93 to "NGC 6752", 94 to "NGC 4755", 95 to "NGC 6025", 96 to "NGC 2516",
        97 to "NGC 3766", 98 to "NGC 4609", 99 to "C 99", 100 to "IC 2944",
        101 to "NGC 6744", 102 to "IC 2602", 103 to "NGC 2070", 104 to "NGC 362",
        105 to "NGC 4833", 106 to "NGC 104", 107 to "NGC 6101", 108 to "NGC 4372",
        109 to "NGC 1365"
    )

    internal val CHINESE_NAMES: Map<String, List<String>> = mapOf(
        "M 1" to listOf("蟹状星云"),
        "M 6" to listOf("蝴蝶星团"),
        "M 7" to listOf("托勒密星团"),
        "M 8" to listOf("礁湖星云"),
        "M 11" to listOf("野鸭星团"),
        "M 13" to listOf("武仙座大球状星团", "武仙座球状星团"),
        "M 16" to listOf("鹰状星云", "鹰星云"),
        "M 17" to listOf("天鹅星云", "欧米茄星云"),
        "M 20" to listOf("三叶星云"),
        "M 27" to listOf("哑铃星云"),
        "M 31" to listOf("仙女座星系", "仙女座大星系"),
        "M 33" to listOf("三角座星系"),
        "M 42" to listOf("猎户座大星云", "猎户座星云"),
        "M 44" to listOf("蜂巢星团", "鬼宿星团"),
        "M 45" to listOf("昴星团", "七姐妹"),
        "M 51" to listOf("涡状星系"),
        "M 57" to listOf("环状星云"),
        "M 63" to listOf("向日葵星系"),
        "M 64" to listOf("黑眼星系"),
        "M 76" to listOf("小哑铃星云"),
        "M 81" to listOf("波德星系"),
        "M 82" to listOf("雪茄星系"),
        "M 97" to listOf("枭状星云", "猫头鹰星云"),
        "M 101" to listOf("风车星系"),
        "M 104" to listOf("草帽星系"),
        "NGC 253" to listOf("玉夫座星系"),
        "NGC 869" to listOf("英仙双星团", "双星团"),
        "NGC 884" to listOf("英仙双星团", "双星团"),
        "C 14" to listOf("英仙双星团", "双星团"),
        "NGC 1499" to listOf("加州星云"),
        "NGC 2070" to listOf("蜘蛛星云"),
        "NGC 2237" to listOf("玫瑰星云"),
        "NGC 2264" to listOf("圣诞树星团"),
        "NGC 2392" to listOf("小丑星云", "爱斯基摩星云"),
        "NGC 3372" to listOf("船底座星云", "南船座星云"),
        "NGC 5139" to listOf("半人马座ω", "欧米伽星团"),
        "NGC 5128" to listOf("半人马A"),
        "NGC 6543" to listOf("猫眼星云"),
        "NGC 6888" to listOf("新月星云"),
        "NGC 6960" to listOf("面纱星云", "西面纱星云"),
        "NGC 6992" to listOf("东面纱星云", "面纱星云"),
        "NGC 7000" to listOf("北美洲星云"),
        "NGC 7293" to listOf("螺旋星云"),
        "NGC 7635" to listOf("气泡星云"),
        "IC 434" to listOf("马头星云"),
        "B 33" to listOf("马头星云"),
        "IC 405" to listOf("火焰星星云"),
        "IC 1396" to listOf("象鼻星云"),
        "IC 1805" to listOf("心脏星云"),
        "IC 1848" to listOf("灵魂星云"),
        "IC 5070" to listOf("鹈鹕星云"),
        "C 41" to listOf("毕星团"),
        "C 99" to listOf("煤袋星云"),
        "Sirius" to listOf("天狼星"),
        "Canopus" to listOf("老人星"),
        "Arcturus" to listOf("大角星"),
        "Vega" to listOf("织女星"),
        "Capella" to listOf("五车二"),
        "Rigel" to listOf("参宿七"),
        "Procyon" to listOf("南河三"),
        "Betelgeuse" to listOf("参宿四"),
        "Altair" to listOf("牛郎星", "河鼓二"),
        "Aldebaran" to listOf("毕宿五"),
        "Antares" to listOf("心宿二"),
        "Spica" to listOf("角宿一"),
        "Pollux" to listOf("北河三"),
        "Castor" to listOf("北河二"),
        "Fomalhaut" to listOf("北落师门"),
        "Deneb" to listOf("天津四"),
        "Regulus" to listOf("轩辕十四"),
        "Polaris" to listOf("北极星"),
        "Bellatrix" to listOf("参宿五"),
        "Alnilam" to listOf("参宿二"),
        "Alnitak" to listOf("参宿一"),
        "Mintaka" to listOf("参宿三"),
        "Dubhe" to listOf("天枢"),
        "Merak" to listOf("天璇"),
        "Phecda" to listOf("天玑"),
        "Megrez" to listOf("天权"),
        "Alioth" to listOf("玉衡"),
        "Mizar" to listOf("开阳"),
        "Alkaid" to listOf("摇光")
    )
}
