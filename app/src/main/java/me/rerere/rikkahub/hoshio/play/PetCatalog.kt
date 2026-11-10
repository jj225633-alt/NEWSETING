package me.rerere.rikkahub.hoshio.play

/** 宠物外观（图片来自 Orbis 的宠物素材，压缩成 webp 放在 assets/hoshio-pets） */
data class PetAppearance(val id: String, val family: String, val label: String)

object PetCatalog {
    val families = linkedMapOf("cat" to "貓", "dog" to "狗", "bird" to "鳥", "reptile" to "爬蟲")

    val appearances = listOf(
        PetAppearance("cat-tabby-v1", "cat", "狸花加白"),
        PetAppearance("cat-orange-v1", "cat", "橘貓"),
        PetAppearance("cat-tuxedo-v1", "cat", "黑白燕尾服"),
        PetAppearance("cat-black-v1", "cat", "純黑貓"),
        PetAppearance("cat-silver-v1", "cat", "銀白長毛貓"),
        PetAppearance("cat-blue-white-v1", "cat", "藍白短毛貓"),
        PetAppearance("cat-calico-v1", "cat", "三花貓"),
        PetAppearance("cat-point-v1", "cat", "重點色貓"),
        PetAppearance("cat-cream-v1", "cat", "奶油長毛貓"),
        PetAppearance("cat-brown-longhair-v1", "cat", "棕虎斑長毛貓"),
        PetAppearance("dog-shiba-v1", "dog", "柴犬"),
        PetAppearance("dog-corgi-v1", "dog", "柯基"),
        PetAppearance("dog-golden-v1", "dog", "金毛"),
        PetAppearance("dog-labrador-v1", "dog", "拉布拉多"),
        PetAppearance("dog-collie-v1", "dog", "邊牧"),
        PetAppearance("dog-beagle-v1", "dog", "比格"),
        PetAppearance("dog-poodle-v1", "dog", "貴賓"),
        PetAppearance("dog-pomeranian-v1", "dog", "博美"),
        PetAppearance("dog-samoyed-v1", "dog", "薩摩耶"),
        PetAppearance("dog-dachshund-v1", "dog", "臘腸"),
        PetAppearance("bird-budgie-v1", "bird", "虎皮鸚鵡"),
        PetAppearance("bird-cockatiel-v1", "bird", "玄鳳鸚鵡"),
        PetAppearance("bird-lovebird-v1", "bird", "牡丹鸚鵡"),
        PetAppearance("bird-conure-v1", "bird", "小太陽鸚鵡"),
        PetAppearance("bird-java-v1", "bird", "文鳥"),
        PetAppearance("bird-zebra-v1", "bird", "斑胸草雀"),
        PetAppearance("bird-canary-v1", "bird", "金絲雀"),
        PetAppearance("bird-pigeon-v1", "bird", "鴿子"),
        PetAppearance("bird-lineolated-v1", "bird", "橫斑鸚鵡"),
        PetAppearance("bird-monk-v1", "bird", "和尚鸚鵡"),
        PetAppearance("reptile-leopard-gecko-v1", "reptile", "豹紋守宮"),
        PetAppearance("reptile-crested-gecko-v1", "reptile", "睫角守宮"),
        PetAppearance("reptile-bearded-dragon-v1", "reptile", "鬃獅蜥"),
        PetAppearance("reptile-blue-tongue-v1", "reptile", "藍舌石龍子"),
        PetAppearance("reptile-corn-snake-v1", "reptile", "玉米蛇"),
        PetAppearance("reptile-ball-python-v1", "reptile", "球蟒"),
        PetAppearance("reptile-hognose-v1", "reptile", "豬鼻蛇"),
        PetAppearance("reptile-hermann-v1", "reptile", "赫曼陸龜"),
        PetAppearance("reptile-redfoot-v1", "reptile", "紅腿陸龜"),
        PetAppearance("reptile-musk-v1", "reptile", "麝香龜"),
    )

    /** 有六格动作图的宠物 */
    val animated = setOf("cat-black-v1", "bird-budgie-v1", "dog-shiba-v1", "reptile-leopard-gecko-v1")
    val frames = listOf("idle", "content", "greet", "eat", "play", "sleep")

    fun find(id: String?): PetAppearance? = appearances.firstOrNull { it.id == id }

    private fun asset(name: String) = "file:///android_asset/hoshio-pets/$name.webp"

    /** 宠物图：有动作图的依 [frame] 换，没有的用单张 */
    fun image(id: String, frame: String = "idle"): String =
        if (id in animated) asset("$id-${frame.takeIf { it in frames } ?: "idle"}") else asset(id)

    fun room(family: String, messy: Boolean): String = asset("$family-room-${if (messy) "messy" else "tidy"}-v1")
}
