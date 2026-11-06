package com.ajay.storyboard.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The Almanac surfaces (Hallmark theme "Almanac"): no boxed cards, no shadows. Depth is a rule and a paper step.
 *  - SectionHead: a grotesk head over a firm 2px rule.
 *  - Sheet: the pale printed sheet laid on the slate paper, rounded 8px with a hairline edge.
 *  - MetaText: labels and meta in the body face, a step smaller, sentence case, muted.
 */

/** A section head: the title, an optional note on the right, and a firm rule under both. */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trailing != null) MetaText(trailing)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline))
    }
}

/** The printed sheet: pale stock with soft 8px corners and a hairline edge. */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}

/** Meta and labels: the body face a step smaller, muted, sentence case. */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
