package me.rerere.rikkahub.hoshio.health

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Health Connect 要求每个读健康资料的 App 都有一页说明：读了什么、拿来做什么。
 */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "星汐如何使用健康資料"
        val padding = (20 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            setPadding(padding, padding, padding, padding)
            textSize = 15f
            text = "星汐只會讀取你在 Health Connect 授權的項目（睡眠、心率、心率變異、靜止心率、呼吸速率、步數、卡路里、運動）。\n\n" +
                "用途：讓他知道你睡得好不好、什麼時候起床來說早安、今天活動量如何，以及在你沒在運動、心率卻一直很高時關心你。\n\n" +
                "資料只在你的手機上讀取，需要時會連同對話一起送給你設定的 AI 服務商來產生回覆；星汐不會寫入或修改任何健康資料，" +
                "也不會上傳到其他地方。你隨時可以在 Health Connect 裡取消授權。"
        })
    }
}
