package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.github.GitHubReleaseChecker
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingAboutPage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val releaseChecker: GitHubReleaseChecker = koinInject()
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<GitHubReleaseChecker.CheckResult?>(null) }
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    SettingScaffold(
        title = stringResource(R.string.about_page_title),
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx)
                                }
                        )

                        Text(
                            text = "RikkaHub",
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }

                item {
                    IosGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = {
                                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                        item(
                            onClick = {
                                if (checkingUpdate) return@item
                                checkingUpdate = true
                                scope.launch(Dispatchers.IO) {
                                    val result = releaseChecker.check(BuildConfig.VERSION_NAME)
                                    withContext(Dispatchers.Main) {
                                        checkingUpdate = false
                                        updateResult = result
                                    }
                                }
                            },
                            leadingContent = { Icon(HugeIcons.Download04, null) },
                            supportingContent = {
                                Text(if (checkingUpdate) "检查中…" else "通过 GitHub Releases 检查新版本")
                            },
                            headlineContent = { Text("检查更新") },
                        )
                    }
                }

                item {
                    IosGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://github.com/Inonvation/rikkahub") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text("https://github.com/Inonvation/rikkahub") },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/Inonvation/rikkahub/blob/master/LICENSE") },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text("https://github.com/Inonvation/rikkahub/blob/master/LICENSE") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }
        }
    }

    updateResult?.let { result ->
        AlertDialog(
            onDismissRequest = { updateResult = null },
            title = {
                Text(
                    when (result) {
                        is GitHubReleaseChecker.CheckResult.Available -> "发现新版本"
                        is GitHubReleaseChecker.CheckResult.UpToDate -> "已是最新版本"
                        is GitHubReleaseChecker.CheckResult.Unavailable -> "检查失败"
                    }
                )
            },
            text = {
                when (result) {
                    is GitHubReleaseChecker.CheckResult.Available ->
                        Text("最新版本 ${result.release.tagName}，当前 ${BuildConfig.VERSION_NAME}")
                    is GitHubReleaseChecker.CheckResult.UpToDate -> Text(result.tagName)
                    is GitHubReleaseChecker.CheckResult.Unavailable -> Text(result.reason)
                }
            },
            confirmButton = {
                if (result is GitHubReleaseChecker.CheckResult.Available) {
                    TextButton(onClick = {
                        context.openUrl(result.release.htmlUrl)
                        updateResult = null
                    }) { Text("打开下载页") }
                } else {
                    TextButton(onClick = { updateResult = null }) { Text("知道了") }
                }
            },
            dismissButton = {
                if (result is GitHubReleaseChecker.CheckResult.Available) {
                    TextButton(onClick = { updateResult = null }) { Text("关闭") }
                }
            },
        )
    }
}
