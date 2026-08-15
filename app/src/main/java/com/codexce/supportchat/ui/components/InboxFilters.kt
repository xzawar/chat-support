package com.codexce.supportchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.data.model.InboxFilter

/**
 * Assigned / Unassigned / Closed / Pending, plus All. Only this row scrolls horizontally.
 * Every chip maps onto the existing `status` / `assignedAgentUid` fields, so the labels are
 * the real queue states rather than a decorative segmented control.
 */
@Composable
fun InboxFilters(
    selected: InboxFilter,
    counts: Map<InboxFilter, Int>,
    onSelect: (InboxFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = InboxFilter.entries, key = { it.name }) { filter ->
            val count = counts[filter] ?: 0
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = debounced { onSelect(filter) },
                label = {
                    Text(
                        text = if (count > 0) "${filter.label} $count" else filter.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    // A solid brand fill with a white label. The soft tinted version that was
                    // here before left dark text on a pale blue chip, which in light mode read
                    // almost the same as an unselected chip - you could not tell at a glance
                    // which queue you were looking at. Solid fill plus white text is
                    // unambiguous, and it is the same pairing every other filled control in
                    // the app uses.
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                // Unselected chips are an outline on the page itself, so the row keeps almost
                // no visual weight until something is actually filtered.
                border = if (isSelected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
            )
        }
    }
}
