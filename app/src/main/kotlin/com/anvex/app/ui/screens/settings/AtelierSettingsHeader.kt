package com.anvex.app.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import com.anvex.app.ui.theme.AtelierAccentPresets
import com.anvex.core.android.repo.AppSettings
import com.anvex.core.android.repo.AtelierAccent
import com.anvex.core.android.repo.ThemeMode

/**
 * Atelier Settings header (design screen 09): identity card, update
 * banner, light/dark/system preview cards and the six accent dots —
 * rendered above the classic category content only while the Atelier
 * style is active. Every control writes the same real settings the
 * classic rows write.
 */
@Composable
fun AtelierSettingsHeader(
    settings: AppSettings?,
    projectCount: Int,
    updateState: com.anvex.app.ui.components.AppUpdateController.State,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (AtelierAccent) -> Unit,
    onUpdateTap: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        // Identity card.
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(com.anvex.app.R.drawable.anvex_emblem),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Anvex", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "v$versionName · $projectCount ${tr("projects")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Update banner (only states worth a banner get one).
        when (updateState) {
            is com.anvex.app.ui.components.AppUpdateController.State.Available -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${tr("Update available")}: v${updateState.info.version}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onUpdateTap,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onPrimary,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) { Text(tr("Update")) }
                    }
                }
            }
            is com.anvex.app.ui.components.AppUpdateController.State.UpToDate -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("You're up to date"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            else -> Unit
        }
        // Appearance: mode preview cards.
        Text(tr("Appearance"), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val mode = settings?.themeMode ?: ThemeMode.DARK
            ModePreviewCard(
                label = tr("Light"),
                selected = mode == ThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
                onClick = { onThemeMode(ThemeMode.LIGHT) },
            ) {
                Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFFF4F1EA))) {
                    Box(
                        Modifier.padding(8.dp).fillMaxWidth().height(12.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color.White),
                    )
                }
            }
            ModePreviewCard(
                label = tr("Dark"),
                selected = mode == ThemeMode.DARK,
                modifier = Modifier.weight(1f),
                onClick = { onThemeMode(ThemeMode.DARK) },
            ) {
                Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0F0E0D))) {
                    Box(
                        Modifier.padding(8.dp).fillMaxWidth().height(12.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A1816)),
                    )
                }
            }
            ModePreviewCard(
                label = tr("System"),
                selected = mode == ThemeMode.SYSTEM,
                modifier = Modifier.weight(1f),
                onClick = { onThemeMode(ThemeMode.SYSTEM) },
            ) {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.weight(1f).height(44.dp).background(Color(0xFFF4F1EA)))
                    Box(Modifier.weight(1f).height(44.dp).background(Color(0xFF0F0E0D)))
                }
            }
        }
        // Accent dots.
        Text(tr("Accent"), style = MaterialTheme.typography.titleMedium)
        val currentAccent = settings?.atelierAccent ?: AtelierAccent.ORANGE
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AtelierAccent.entries.forEach { accent ->
                val preset = AtelierAccentPresets[accent]
                val dot = preset?.gradStartLight ?: MaterialTheme.colorScheme.primary
                val selected = accent == currentAccent
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onAccent(accent) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(30.dp).clip(CircleShape).background(dot),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ModePreviewCard(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            ) { preview() }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
