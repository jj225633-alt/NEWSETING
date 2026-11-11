package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.ImageAdd01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Route01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

private data class HubLink(val title: String, val subtitle: String, val icon: ImageVector, val screen: Screen)

@Composable
private fun HubGrid(title: String, intro: String, links: List<HubLink>) {
    val navController = LocalNavController.current
    PlayScaffold(title) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
            }
            items(links) { link ->
                Card(
                    onClick = { navController.navigate(link.screen) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(link.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(link.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            link.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayHubPage() = HubGrid(
    title = "一起玩",
    intro = "一起看、一起猜、一起下棋、一起養。",
    links = listOf(
        HubLink("一起聽", "你在 YT 聽的歌、我們的歌單、歌詞和批註本", HugeIcons.MusicNote03, Screen.ListenTogether),
        HubLink("一起看螢幕", "他陪你看，邊看邊說話", HugeIcons.Eye, Screen.WatchTogether),
        HubLink("下棋", "圈圈叉叉、五子棋", HugeIcons.Play, Screen.BoardGame),
        HubLink("海龜湯", "用是／不是猜出真相", HugeIcons.MessageQuestion, Screen.TurtleSoup),
        HubLink("秘密基地", "你寫序章，他寫番外", HugeIcons.Book02, Screen.SecretBase),
        HubLink("每日一問", "每天一題，兩個人都回答", HugeIcons.Message01, Screen.DailyQuestion),
        HubLink("一起養寵物", "你和他一起照顧的小傢伙", HugeIcons.Sparkles, Screen.Pet),
        HubLink("藏寶地圖", "他藏的信，走到附近才拿得到", HugeIcons.Route01, Screen.Treasure),
        HubLink("幸運轉盤", "今晚轉什麼？", HugeIcons.Sparkles, Screen.Ruota),
        HubLink("遊戲室", "他做的小遊戲和小網頁", HugeIcons.Puzzle, Screen.Playroom),
    ),
)

@Composable
fun MemoriesHubPage() = HubGrid(
    title = "我們的回憶",
    intro = "你們的照片、說好的事、重要的日子，和寫給未來的信。",
    links = listOf(
        HubLink("我們的 100 件小事", "一起做過的小事，拍立得照片牆", HugeIcons.ImageAdd01, Screen.BucketList),
        HubLink("共同空間", "兩個人的動態，按讚和留言", HugeIcons.Message01, Screen.Spaces),
        HubLink("回憶相簿／照片牆", "照片和他寫的一句話", HugeIcons.ImageAdd01, Screen.Album),
        HubLink("我們的約定", "說好的事，做到就打勾", HugeIcons.Pin, Screen.Promises),
        HubLink("紀念日", "重要的日子和認識幾天", HugeIcons.Calendar03, Screen.Anniversary),
        HubLink("時光膠囊", "寫給未來，到那天才能打開", HugeIcons.Time02, Screen.Capsule),
        HubLink("通話紀錄", "每通電話的文字，他的聲音可以重聽", HugeIcons.SmartPhone01, Screen.CallLog),
        HubLink("從 Orbis 搬家", "把舊家的對話、記憶和花園搬過來", HugeIcons.Database02, Screen.OrbisMigrate),
    ),
)
