package com.ajay.storyboard.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.ajay.storyboard.ai.AiCatalog
import com.ajay.storyboard.ai.AiTarget

/** Drop-down of every AI that can do the job (web chats, API keys, taught sites). */
@Composable
fun TargetPicker(catalog: AiCatalog, selectedId: String, images: Boolean = false, onSelect: (AiTarget) -> Unit) {
    // collected so the list updates when keys / sites are added
    val profiles by catalog.profiles.collectAsState()
    val sites by catalog.sites.collectAsState()
    val options = remember(profiles, sites, images) { if (images) catalog.forImages() else catalog.forText() }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(catalog.label(selectedId), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val groups = listOf(
                "Web chats (your login)" to options.filterIsInstance<AiTarget.Web>(),
                "API keys" to options.filterIsInstance<AiTarget.Api>(),
                "Taught sites" to options.filterIsInstance<AiTarget.Site>(),
            )
            groups.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                items.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        trailingIcon = { if (t.id == selectedId) Icon(Icons.Filled.Check, null) },
                        onClick = { open = false; onSelect(t) },
                    )
                }
            }
        }
    }
}
