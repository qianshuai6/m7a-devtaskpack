package com.march7th.hkrpg.dev

/**
 * 崩铁角色图标的中文索引。
 *
 * `assets/starrail/images/share/character/` 下是英文文件名（Acheron、Aventurine…），
 * 模板库原本只能按英文搜。这里给出**官方中文译名**，加载图标索引时追加到 keywords，
 * 于是「黄泉」「砂金」「流萤」都能直接搜到对应头像。
 *
 * - 键是文件名（去扩展名），大小写不敏感；
 * - 时装 / 形态变体（AventurineWaveflair、FireflySpringMissive…）复用本体的中文名，
 *   并在括号里补一个形态说明词，仍然能搜到；
 * - 命途图标（Caelum*、Stelle* 的命途变体）按命途中文名索引；
 * - 未收录的新角色保留英文原名，不影响原功能。
 *
 * 这里只是**搜索关键词**，不生成、不修改任何图标文件，图标仍来自仓库现有资源。
 */
object TaskPackCharacterNames {

    /** 主体角色：英文文件名 -> 官方中文名。 */
    private val BASE = mapOf(
        "Acheron" to "黄泉",
        "Aglaea" to "阿格莱雅",
        "Anaxa" to "那刻夏",
        "Archer" to "Archer 英灵",
        "Argenti" to "银枝",
        "Arlan" to "阿兰",
        "Asta" to "艾丝妲",
        "Aventurine" to "砂金",
        "Bailu" to "白露",
        "BlackSwan" to "黑天鹅",
        "Blade" to "刃",
        "Boothill" to "波提欧",
        "Bronya" to "布洛妮娅",
        "Castorice" to "遐蝶",
        "Cerydra" to "刻律德菈",
        "Cipher" to "赛飞儿",
        "Clara" to "克拉拉",
        "Cyrene" to "昔涟",
        "DanHeng" to "丹恒",
        "DanHengImbibitorLunae" to "丹恒·饮月",
        "DanHengPermansorTerrae" to "丹恒·腾荒",
        "DrRatio" to "真理医生",
        "Evernight" to "长夜月",
        "Feixiao" to "飞霄",
        "Firefly" to "流萤",
        "Fugue" to "忘归人",
        "FuXuan" to "符玄",
        "Gallagher" to "加拉赫",
        "Gepard" to "杰帕德",
        "Gilgamesh" to "吉尔伽美什",
        "Guinaifen" to "桂乃芬",
        "Hanya" to "寒鸦",
        "Herta" to "黑塔",
        "Himeko" to "姬子",
        "Hook" to "虎克",
        "Huohuo" to "藿藿",
        "Hyacine" to "风堇",
        "Hysilens" to "海瑟音",
        "Jade" to "翡翠",
        "Jiaoqiu" to "椒丘",
        "Jingliu" to "镜流",
        "JingYuan" to "景元",
        "Kafka" to "卡芙卡",
        "Lingsha" to "灵砂",
        "Luka" to "卢卡",
        "Luocha" to "罗刹",
        "Lynx" to "玲可",
        "March7th" to "三月七",
        "March7thImaginary" to "三月七·虚数",
        "Misha" to "米沙",
        "Moze" to "貊泽",
        "Mydei" to "万敌",
        "Natasha" to "娜塔莎",
        "Pela" to "佩拉",
        "Phainon" to "白厄",
        "Qingque" to "青雀",
        "Rappa" to "乱破",
        "RinTohsaka" to "远坂凛",
        "Robin" to "知更鸟",
        "RuanMei" to "阮·梅",
        "Saber" to "Saber",
        "Sampo" to "桑博",
        "Seele" to "希儿",
        "Serval" to "希露瓦",
        "SilverWolf" to "银狼",
        "Sparkle" to "花火",
        "Stelle" to "星",
        "Sunday" to "星期日",
        "Sushang" to "素裳",
        "TheDahlia" to "大丽花",
        "TheHerta" to "大黑塔",
        "Tingyun" to "停云",
        "TopazandNumby" to "托帕&账账",
        "Tribbie" to "缇宝",
        "Welt" to "瓦尔特",
        "Xueyi" to "雪衣",
        "Yanqing" to "彦卿",
        "YaoGuang" to "爻光",
        "Yukong" to "驭空",
        "Yunli" to "云璃",
        "Ashveil" to "Ashveil",
        "Evanescia" to "Evanescia",
        "Sparxie" to "Sparxie",
        "MortenaxBlade" to "MortenaxBlade",
        "Pearl" to "Pearl",
    )

    /** 命途 / 开拓者命途形态：文件名前缀或全名 -> 中文。 */
    private val PATHES = mapOf(
        "CaelumDestruction" to "毁灭命途",
        "CaelumElation" to "欢愉命途",
        "CaelumImaginary" to "虚数命途",
        "CaelumPreservation" to "存护命途",
        "CaelumRemembrance" to "记忆命途",
        "StelleDestruction" to "星·毁灭命途",
        "StelleElation" to "星·欢愉命途",
        "StelleImaginary" to "星·虚数命途",
        "StellePreservation" to "星·存护命途",
        "StelleRemembrance" to "星·记忆命途",
        "SilverWolfLV999" to "银狼",
    )

    /**
     * 时装 / 形态变体后缀 -> 补充中文词（附在本体中文名后一起作为关键词）。
     * 只用于生成搜索词，不改图标。
     */
    private val VARIANT_WORDS = mapOf(
        "Waveflair" to "时装",
        "GossamerFlutter" to "时装",
        "SpringMissive" to "时装",
        "Nova" to "时装",
        "WarmCottonSkies" to "时装",
        "Summeretto" to "时装",
        "PlumblossomLetter" to "时装",
        "CandyCoatedWaves" to "时装",
        "LunarBlossoming" to "时装",
        "NascentSpring" to "时装",
        "Imaginary" to "虚数",
        "LV999" to "时装",
    )

    private val ALL = (BASE + PATHES).let { base ->
        base.mapKeys { (k, _) -> k.lowercase() }
    }

    /** 返回该图标要追加的中文关键词；非角色图标返回空串。 */
    fun chineseKeywords(assetPath: String, name: String): String {
        if (!assetPath.contains("/character/", ignoreCase = true) &&
            !assetPath.contains("/aglaea/", ignoreCase = true)
        ) {
            // aglaea 目录是阿格莱雅相关的道具图，也补一个中文词便于搜。
            return ""
        }
        val key = name.substringBeforeLast('.').lowercase()
        ALL[key]?.let { return it }

        // 变体：找出最长匹配的本体前缀。
        val matched = ALL.entries
            .filter { (k, _) -> key.startsWith(k) && key.length > k.length }
            .maxByOrNull { it.key.length }
        if (matched != null) {
            val suffix = key.removePrefix(matched.key)
            val extra = VARIANT_WORDS.entries
                .firstOrNull { suffix.contains(it.key.lowercase()) }?.value.orEmpty()
            return "${matched.value} $extra".trim()
        }
        // aglaea 目录里的道具图统一挂到「阿格莱雅」中文索引下。
        if (assetPath.contains("/aglaea/", ignoreCase = true)) return "阿格莱雅"
        return ""
    }

    /** 模板库「角色」分类用的判定：图标属于角色/命途头像。 */
    fun isCharacter(assetPath: String): Boolean =
        assetPath.contains("/character/", ignoreCase = true)
}
