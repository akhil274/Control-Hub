package com.example.controlhub.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.cos

// Number of times to repeat the list for "infinite" feel
private const val LOOP_FACTOR = 300

/**
 * Mechanical Tumbler time picker with true 3D cylindrical drum perspective.
 * Infinite looping on hours (1-12) and minutes (0-59). AM/PM has only 2 items, no looping.
 */
@Composable
fun ClockDialPicker(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onHour: Int,
    onMinute: Int,
    onPeriod: String,
    offHour: Int,
    offMinute: Int,
    offPeriod: String,
    onTimeChanged: (Boolean, Int, Int, String) -> Unit
) {
    val isOnTime      = selectedTab == 0
    val currentHour   = if (isOnTime) onHour   else offHour
    val currentMinute = if (isOnTime) onMinute  else offMinute
    val currentPeriod = if (isOnTime) onPeriod  else offPeriod

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Tab Selector ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TumblerTab("Set ON Time",  selectedTab == 0, { onTabSelected(0) }, Modifier.weight(1f))
            TumblerTab("Set OFF Time", selectedTab == 1, { onTabSelected(1) }, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Duration Preview ──────────────────────────────────────────
        val onTotal  = calcTotal(onHour,  onMinute,  onPeriod)
        val offTotal = calcTotal(offHour, offMinute, offPeriod)
        val durMin   = if (offTotal > onTotal) offTotal - onTotal else (1440 - onTotal + offTotal)
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (durMin == 0 || durMin == 1440) {
                // ON==OFF time: ESP32 treats this as "never on"
                Text("⚠ Same ON/OFF time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                Text("Runs for: ", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${durMin / 60}h ${durMin % 60}m",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 3D Drum Picker ────────────────────────────────────────────
        // 3 visible items: one above center, center (in glass box), one below
        val itemH: Dp        = 58.dp
        val visibleItems     = 3
        val pickerHeight: Dp = itemH * visibleItems
        val cardBg           = MaterialTheme.colorScheme.surfaceContainerHigh

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))          // rounded card corners ✓
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pickerHeight),
                contentAlignment = Alignment.Center
            ) {
                // Glass centre-highlight stripe
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .height(itemH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        // Top & bottom fade overlay — blends items smoothly into background
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0.00f to cardBg.copy(alpha = 1.00f),
                                    0.20f to cardBg.copy(alpha = 0.60f),
                                    0.38f to Color.Transparent,
                                    0.62f to Color.Transparent,
                                    0.80f to cardBg.copy(alpha = 0.60f),
                                    1.00f to cardBg.copy(alpha = 1.00f)
                                )
                            )
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── HOURS  1–12 (infinite loop) ───────────────────
                    val hourItems = (1..12).map { String.format("%02d", it) }
                    DrumWheel(
                        items         = hourItems,
                        selectedIndex = currentHour - 1,
                        onSelected    = { idx -> onTimeChanged(isOnTime, idx + 1, currentMinute, currentPeriod) },
                        itemHeight    = itemH,
                        visibleItems  = visibleItems,
                        infinite      = true,
                        selectedTab   = selectedTab,
                        modifier      = Modifier.weight(2f)
                    )

                    // Separator colon
                    Box(Modifier.height(pickerHeight), contentAlignment = Alignment.Center) {
                        Text(":", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            fontWeight = FontWeight.Black)
                    }

                    // ── MINUTES  0–59 (infinite loop) ────────────────
                    val minItems = (0..59).map { String.format("%02d", it) }
                    DrumWheel(
                        items         = minItems,
                        selectedIndex = currentMinute,
                        onSelected    = { idx -> onTimeChanged(isOnTime, currentHour, idx, currentPeriod) },
                        itemHeight    = itemH,
                        visibleItems  = visibleItems,
                        infinite      = true,
                        selectedTab   = selectedTab,
                        modifier      = Modifier.weight(2f)
                    )

                    Spacer(Modifier.width(6.dp))

                    // ── AM / PM  (not infinite — only 2 items) ───────
                    val periodItems = listOf("AM", "PM")
                    DrumWheel(
                        items         = periodItems,
                        selectedIndex = if (currentPeriod == "AM") 0 else 1,
                        onSelected    = { idx -> onTimeChanged(isOnTime, currentHour, currentMinute, periodItems[idx]) },
                        itemHeight    = itemH,
                        visibleItems  = visibleItems,
                        infinite      = false,
                        selectedTab   = selectedTab,
                        modifier      = Modifier.weight(1.6f)
                    )
                }
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun calcTotal(hour: Int, minute: Int, period: String): Int {
    val h24 = when {
        period == "AM" && hour == 12 -> 0
        period == "PM" && hour != 12 -> hour + 12
        else                          -> hour
    }
    return h24 * 60 + minute
}

// ─── DrumWheel ────────────────────────────────────────────────────────────────
// Infinite (looping) or finite vertical scroll wheel with 3D cylinder perspective.
//
// Layout math (halfVis = visibleItems / 2 = 1):
//   contentPadding = halfVis × itemHeight  → pushes item 0 down by 1 slot
//   When firstVisibleItemIndex = N → item N appears 1 slot below the top
//     → item N sits at viewportCenter  ✓
//   Therefore: centeredItem = listState.firstVisibleItemIndex
//   To scroll item T to center: scrollToItem(T)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrumWheel(
    items        : List<String>,
    selectedIndex: Int,
    onSelected   : (Int) -> Unit,
    itemHeight   : Dp,
    visibleItems : Int,
    infinite     : Boolean,
    selectedTab  : Int,
    modifier     : Modifier = Modifier
) {
    val halfVis  = visibleItems / 2          // = 1 for visibleItems=3
    val count    = if (infinite) items.size * LOOP_FACTOR else items.size
    // For infinite lists, start somewhere near the middle so there's room both ways
    val midStart = if (infinite) (LOOP_FACTOR / 2) * items.size else 0
    // initScrollIndex: scrollToItem(N) makes item N the FIRST visible item.
    // centeredItem = firstVisibleItemIndex, so to center selectedIndex:
    //   scrollToItem(midStart + selectedIndex)
    val initIdx  = (midStart + selectedIndex).coerceIn(0, count - 1)

    val listState    = rememberLazyListState(initialFirstVisibleItemIndex = initIdx)
    val density      = LocalDensity.current
    val view         = LocalView.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    // Track user dragging to separate user scrolls from programmatic tab-switches
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var wasDragged by remember { mutableStateOf(false) }

    // Reset wasDragged synchronously in composition when the tab changes to prevent race conditions
    var lastTab by remember { mutableStateOf(selectedTab) }
    if (lastTab != selectedTab) {
        wasDragged = false
        lastTab = selectedTab
    }

    // ── Haptic tick — fires once per number that scrolls into the center ──────
    // Uses CLOCK_TICK which is specifically designed for scrolling-wheel UIs.
    // It respects the system "Haptic feedback" setting automatically.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
    }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            wasDragged = true
        }
    }

    // Cancel any pending scroll reports from the previous tab immediately when switching tabs
    LaunchedEffect(selectedTab) {
        wasDragged = false
    }

    // Sync external model → scroll position (e.g. switching ON ↔ OFF tab)
    LaunchedEffect(selectedIndex) {
        val currentCentered = listState.firstVisibleItemIndex
        val currentActual   = currentCentered % items.size
        if (currentActual != selectedIndex) {
            wasDragged = false // programmatic change, reset user-drag flag
            val target = if (infinite) {
                val fwd  = (selectedIndex - currentActual + items.size) % items.size
                val diff = if (fwd > items.size / 2) fwd - items.size else fwd
                (currentCentered + diff).coerceIn(0, count - 1)
            } else {
                selectedIndex
            }
            listState.animateScrollToItem(target)
        }
    }

    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    // Report settled value to parent ONLY if it was dragged by the user
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            if (wasDragged) {
                val actual = listState.firstVisibleItemIndex % items.size
                if (actual != currentSelectedIndex) {
                    currentOnSelected(actual)
                }
                wasDragged = false
            }
        }
    }

    val snap = rememberSnapFlingBehavior(lazyListState = listState)

    LazyColumn(
        state               = listState,
        flingBehavior       = snap,
        modifier            = modifier.fillMaxHeight(),
        // contentPadding = halfVis × itemHeight centers item at firstVisibleItemIndex
        contentPadding      = PaddingValues(vertical = itemHeight * halfVis),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count = count) { index ->
            val actualIdx = index % items.size

            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val info = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                        if (info != null) {
                            val vpCenter = listState.layoutInfo.viewportStartOffset +
                                           listState.layoutInfo.viewportSize.height / 2f
                            val itemCtr  = info.offset + info.size / 2f
                            // normalised distance from drum centre (0 = centre, ±1 = one slot away)
                            val distNorm = (itemCtr - vpCenter) / itemHeightPx

                            // Cylinder tilt — 55° per slot gives strong 3D warp at adjacent items
                            val angle = (distNorm * 55f).coerceIn(-90f, 90f)
                            rotationX     = angle
                            cameraDistance = 8f * density.density   // perspective depth

                            // Lighting: full-bright when facing viewer, dark when rotated away
                            val cosA = cos(Math.toRadians(angle.toDouble())).toFloat()
                            alpha  = (cosA * cosA).coerceIn(0.08f, 1f)
                            // Vertical foreshortening (cylindrical geometry)
                            scaleY = cosA.coerceIn(0.35f, 1f)
                        } else {
                            alpha  = 0.04f
                            scaleY = 0.35f
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Only the item currently snapped to centre gets the "selected" look
                val isSelected by remember {
                    derivedStateOf { listState.firstVisibleItemIndex == index }
                }
                Text(
                    text       = items[actualIdx],
                    style      = if (isSelected) MaterialTheme.typography.displaySmall
                                 else            MaterialTheme.typography.titleLarge,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                    color      = if (isSelected) MaterialTheme.colorScheme.primary
                                 else            MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines   = 1
                )
            }
        }
    }
}

// ─── TumblerTab ───────────────────────────────────────────────────────────────
@Composable
private fun TumblerTab(
    label   : String,
    isActive: Boolean,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.Bold
        )
    }
}
