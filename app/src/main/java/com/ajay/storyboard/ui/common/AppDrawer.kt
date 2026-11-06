package com.ajay.storyboard.ui.common

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class DrawerDest(val label: String, val icon: ImageVector, val available: Boolean = true) {
    LIBRARY("Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    GALLERY("Gallery", Icons.Outlined.PhotoLibrary),
    ACCOUNTS("AI accounts", Icons.Outlined.ManageAccounts),
    SETTINGS("Settings", Icons.Outlined.Settings),
    ROUNDTABLE("AI Roundtable", Icons.Outlined.Forum),
    BACKUP("Backup & restore", Icons.Outlined.Backup),
}

/** Side menu (opened with the menu button). */
@Composable
fun AppDrawer(state: DrawerState, current: DrawerDest, onNavigate: (DrawerDest) -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = state,
        drawerContent = {
            ModalDrawerSheet(drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                androidx.compose.foundation.layout.Column(Modifier.padding(24.dp, 28.dp, 24.dp, 12.dp)) {
                    Text("Story-Board", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(2.dp)
                        .background(MaterialTheme.colorScheme.onSurface))
                }
                DrawerDest.entries.forEach { d ->
                    if (d == DrawerDest.ROUNDTABLE) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    NavigationDrawerItem(
                        label = { Text(d.label) },
                        icon = { Icon(d.icon, null) },
                        selected = d == current,
                        onClick = {
                            scope.launch { state.close() }
                            if (d.available && d != current) onNavigate(d)
                        },
                        shape = com.ajay.storyboard.ui.common.ControlShape,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        },
        content = content,
    )
}
