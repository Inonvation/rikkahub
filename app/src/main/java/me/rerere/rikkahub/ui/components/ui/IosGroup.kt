package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.theme.CustomColors

private val IosGroupCorner = 12.dp

private data class IosGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
)

@DslMarker
private annotation class IosGroupDsl

@IosGroupDsl
interface IosGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )
}

private class IosGroupScopeImpl : IosGroupScope {
    val items = mutableListOf<IosGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            IosGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            )
        )
    }
}

@Composable
fun IosGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: IosGroupScope.() -> Unit,
) {
    val scope = IosGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
        if (subtitle != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(start = 16.dp, top = 0.dp, bottom = 6.dp),
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(IosGroupCorner),
            color = CustomColors.listItemColors.containerColor,
            tonalElevation = 0.dp,
        ) {
            Column {
                val count = scope.items.size
                scope.items.fastForEachIndexed { index, item ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val hapticController = rememberHaptic()

                    // 鑷畾涔夎甯冨眬锛歭eading 鍥炬爣銆佹爣棰?鎻忚堪銆乼railing 鎺т欢缁熶竴鍨傜洿灞呬腑锛?
                    // 閬垮厤 M3 ListItem 鍦ㄦ弿杩版枃鏈緝闀挎椂鍥炬爣/寮€鍏抽敊浣嶏紱鍑犱綍鍙傛暟澶嶅埢 M3 ListItem銆?
                    Row(
                        modifier = item.modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .then(
                                if (item.onClick != null) {
                                    Modifier.clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        onClick = {
                                            hapticController.lightTap()
                                            item.onClick()
                                        },
                                    )
                                } else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.leadingContent != null) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                item.leadingContent()
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item.overlineContent?.let {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                    ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                        it()
                                    }
                                }
                            }
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                                ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                                    item.headlineContent()
                                }
                            }
                            item.supportingContent?.let {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                    ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                                        it()
                                    }
                                }
                            }
                        }
                        if (item.trailingContent != null) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                item.trailingContent()
                            }
                        }
                    }
                    if (index != count - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}
