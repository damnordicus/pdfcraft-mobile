package ai.storyteller.pdfcraft.ui

import ai.storyteller.pdfcraft.BuildConfig
import ai.storyteller.pdfcraft.PdfState
import ai.storyteller.pdfcraft.PdfViewModel
import ai.storyteller.pdfcraft.RENDER_SCALE
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pdfcraft_mobile_ffi.OutlineEntry
import uniffi.pdfcraft_mobile_ffi.TextSelection
import uniffi.pdfcraft_mobile_ffi.buildInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(viewModel: PdfViewModel, onPickDocument: () -> Unit) {
    val state = viewModel.state
    var searchBarOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var outlineOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text((state as? PdfState.Loaded)?.title ?: "PdfCraft") },
                    actions = {
                        if (state is PdfState.Loaded) {
                            IconButton(onClick = { searchBarOpen = !searchBarOpen }) { Text("Find") }
                            if (state.outline.isNotEmpty()) {
                                IconButton(onClick = { outlineOpen = true }) { Text("TOC") }
                            }
                        }
                    },
                )
                if (searchBarOpen && state is PdfState.Loaded) {
                    FindBar(
                        query = query,
                        onQueryChange = {
                            query = it
                            viewModel.search(it)
                        },
                        matchCount = state.matches.size,
                        matchIndex = state.matchIndex,
                        onPrevious = viewModel::previousMatch,
                        onNext = viewModel::nextMatch,
                    )
                }
            }
        },
        // Debug aid so a rebuild is provably running the commit it's meant to, not a stale
        // native library or a stale installed APK — remove once mobile-ffi is past churning.
        // Two separate commits: ffi is crates/mobile-ffi's own build.rs, baked into the native
        // library, and only changes when a Rust file does; app is this module's own build.gradle.kts
        // (BuildConfig.GIT_COMMIT), the whole repo's commit at the time Gradle last configured —
        // apart on purpose, so a Kotlin-only change that left ffi looking stale is still visible
        // as a moved app tag, instead of looking like the whole rebuild didn't happen.
        bottomBar = { Text("ffi ${buildInfo()} · app ${BuildConfig.GIT_COMMIT}", modifier = Modifier.padding(4.dp)) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (state) {
                is PdfState.Empty -> Button(onClick = onPickDocument) { Text("Open a PDF") }
                is PdfState.Loading -> CircularProgressIndicator()
                is PdfState.Error ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onPickDocument) { Text("Try another file") }
                    }
                is PdfState.Loaded ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        PageView(
                            state = state,
                            onPrev = { viewModel.goToPage(state.page - 1) },
                            onNext = { viewModel.goToPage(state.page + 1) },
                            onGoToPage = { viewModel.goToPage(it) },
                            onSelectionChange = viewModel::setSelection,
                            modifier = Modifier.weight(1f),
                        )
                        state.selection?.let { selection -> CopyBar(text = selection.text, onCopied = { viewModel.setSelection(null) }) }
                    }
            }
        }
    }

    if (outlineOpen && state is PdfState.Loaded) {
        OutlineSheet(
            entries = state.outline,
            onSelect = {
                viewModel.goToPage(it)
                outlineOpen = false
            },
            onDismiss = { outlineOpen = false },
        )
    }
}

@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f), singleLine = true)
        if (query.isNotBlank()) {
            Text(if (matchCount > 0) "${matchIndex + 1}/$matchCount" else "0/0")
            IconButton(onClick = onPrevious, enabled = matchCount > 0) { Text("<") }
            IconButton(onClick = onNext, enabled = matchCount > 0) { Text(">") }
        }
    }
}

/** Shown while a text selection is active; copies it to the system clipboard. */
@Composable
private fun CopyBar(text: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), maxLines = 1)
        Button(onClick = {
            clipboard.setText(AnnotatedString(text))
            onCopied()
        }) { Text("Copy") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(entries: List<OutlineEntry>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        entries.forEach { entry ->
            ListItem(
                headlineContent = { Text(entry.title) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry.page.toInt()) }
                        .padding(start = (entry.depth * 16u).toInt().dp),
            )
        }
    }
}

/**
 * Pan and pinch-zoom, requiring 2+ pointers for both — unlike `detectTransformGestures`, which
 * also treats a single finger's movement as pan. That's the gesture-conflict bug a long-press
 * selection hit in practice: `detectTransformGestures` and `detectTapGestures` are two
 * independent detectors watching the same one-finger touch, and `detectTransformGestures` has no
 * slop tolerance, so natural tremor during a long-press's ~500ms hold (ignored by the long-press
 * timer) still accumulated into `pan` — sometimes *after* the long-press had already fired and
 * computed its hit-test point, so the next recomposition's highlight render (always using the
 * then-current pan) drifted from it. Single-finger touches are now exclusively tap/long-press/
 * selection; panning while zoomed in is two-finger, same as the pinch that zoomed in to begin
 * with. Adapted from `androidx.compose.foundation.gestures.detectTransformGestures`'s own
 * implementation, with the `event.changes.size >= 2` gate added before `onGesture` fires.
 */
private suspend fun PointerInputScope.detectMultiTouchTransformGestures(onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                        pastSlop = true
                    }
                }
                if (pastSlop && event.changes.size >= 2 && (zoomChange != 1f || panChange != Offset.Zero)) {
                    onGesture(panChange, zoomChange)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

/**
 * Renders the current page off the main thread and shows it, with pinch-to-zoom, a jump bar,
 * word-selection (long-press, drag either handle, Copy) and the active search match (if any)
 * baked into the bitmap so it pans/zooms with the page for free.
 *
 * The bitmap is displayed at its exact pixel size, scaled by one explicit transform this
 * composable computes itself (`baseScale`, fitting the bitmap to the available box, composed
 * with the pinch `zoom`/`pan`) rather than `Image`'s own content-scaling layered under a second,
 * independent `graphicsLayer` scale. Selection needs to invert a screen point back to the exact
 * bitmap pixel `nearest(x, y)` expects; two partly-implicit scale factors can't be inverted
 * precisely, one explicit one can (`toBitmapPoint`/`toScreenPoint`).
 */
@Composable
private fun PageView(
    state: PdfState.Loaded,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onSelectionChange: (TextSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(state.document, state.page, state.activeMatch) { mutableStateOf<Bitmap?>(null) }
    var zoom by remember(state.page) { mutableFloatStateOf(1f) }
    var pan by remember(state.page) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
    // Temporary: what the transform math actually computed for the last long-press, so a report
    // of "wrong word" comes with numbers instead of a screenshot to reverse-engineer. Remove once
    // selection's coordinate mapping is confirmed right on-device.
    var debugText by remember(state.page) { mutableStateOf("") }
    // Temporary, alongside debugText: the exact bitmap point the long-press computed, drawn back
    // onto the page as a crosshair. Numbers alone couldn't settle whether a wrong-word report
    // meant the touch-to-bitmap transform is still off (the dot would be off the fingertip too)
    // or that transform is fine and `word_at`/`nearest` (crates/render/src/text.rs) is picking the
    // wrong glyph from a correct point (the dot would land right on the fingertip, on the wrong
    // word). Remove alongside debugText.
    var lastTouchPoint by remember(state.page) { mutableStateOf<Offset?>(null) }

    LaunchedEffect(state.document, state.page, state.activeMatch) {
        val rendered = withContext(Dispatchers.Default) { state.document.renderPage(state.page.toUInt(), RENDER_SCALE) }
        val decoded = Bitmap.createBitmap(rendered.width.toInt(), rendered.height.toInt(), Bitmap.Config.ARGB_8888)
        decoded.copyPixelsFromBuffer(ByteBuffer.wrap(rendered.rgba))
        val match = state.activeMatch
        if (match != null && match.page.toInt() == state.page) {
            val canvas = Canvas(decoded)
            val paint = Paint().apply { color = Color.argb(90, 255, 213, 0) }
            match.rects.forEach { canvas.drawRect(it.x0, it.y0, it.x1, it.y1, paint) }
        }
        bitmap = decoded
    }

    val bmp = bitmap
    val baseScale =
        if (bmp != null && boxSize.width > 0 && boxSize.height > 0) {
            min(boxSize.width.toFloat() / bmp.width, boxSize.height.toFloat() / bmp.height)
        } else {
            1f
        }
    val totalScale = baseScale * zoom
    val baseOffsetX = if (bmp != null) (boxSize.width - bmp.width * baseScale) / 2f else 0f
    val baseOffsetY = if (bmp != null) (boxSize.height - bmp.height * baseScale) / 2f else 0f
    val offsetX = baseOffsetX + pan.x
    val offsetY = baseOffsetY + pan.y
    fun toBitmapPoint(screen: Offset) = Offset((screen.x - offsetX) / totalScale, (screen.y - offsetY) / totalScale)
    fun toScreenPoint(bitmapPt: Offset) = Offset(offsetX + bitmapPt.x * totalScale, offsetY + bitmapPt.y * totalScale)
    // The long-press pointerInput below is keyed on (state.page, bmp), not on zoom/pan/boxSize,
    // so its gesture-detector coroutine survives a pinch-zoom without restarting; reading
    // offsetX/offsetY/totalScale/bmp directly in its closure would keep whatever was current when
    // that coroutine last started, not the latest. rememberUpdatedState on a local *function*
    // reference (val x by rememberUpdatedState(::someLocalFun)) turned out not to update in
    // practice (on-device: every long-press kept reading the pre-layout defaults, base=1.0,
    // off=(0,0), bmp=0x0, regardless of the real, already-loaded state) — wrapping each plain
    // value individually instead, which is the unambiguous, most common use of
    // rememberUpdatedState, to rule out any doubt about why.
    val liveOffsetX by rememberUpdatedState(offsetX)
    val liveOffsetY by rememberUpdatedState(offsetY)
    val liveTotalScale by rememberUpdatedState(totalScale)
    val liveBaseScale by rememberUpdatedState(baseScale)
    val liveZoom by rememberUpdatedState(zoom)
    val liveBoxSize by rememberUpdatedState(boxSize)
    val liveBmp by rememberUpdatedState(bmp)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(state.page) {
                        detectMultiTouchTransformGestures { panDelta, zoomDelta ->
                            zoom = (zoom * zoomDelta).coerceIn(1f, 5f)
                            pan += panDelta
                        }
                    }
                    .pointerInput(state.page, bmp) {
                        detectTapGestures(
                            onLongPress = { screen ->
                                val currentBmp = liveBmp ?: return@detectTapGestures
                                val (ox, oy, ts) = Triple(liveOffsetX, liveOffsetY, liveTotalScale)
                                val pt = Offset((screen.x - ox) / ts, (screen.y - oy) / ts)
                                lastTouchPoint = pt
                                // A local val, not shared mutable state: two long-presses in
                                // quick succession (before the first's background wordAt
                                // returns) must not let the first's late result clobber onto the
                                // second's prefix via `debugText +=` — each press reconstructs
                                // its own full message from its own prefix instead.
                                val prefix =
                                    "zoom=%.2f base=%.3f total=%.3f box=%dx%d bmp=%dx%d off=(%.0f,%.0f) screen=(%.0f,%.0f) pt=(%.0f,%.0f)"
                                        .format(
                                            liveZoom, liveBaseScale, ts, liveBoxSize.width, liveBoxSize.height,
                                            currentBmp.width, currentBmp.height, ox, oy, screen.x, screen.y, pt.x, pt.y,
                                        )
                                debugText = prefix
                                // Off the main thread: this page's text may not be cached yet
                                // (text_cache, crates/mobile-ffi/src/lib.rs), so this specific
                                // call — unlike extendSelection below, which only ever runs once
                                // a selection (and so a warm cache) already exists — can be slow.
                                coroutineScope.launch(Dispatchers.Default) {
                                    val found = state.document.wordAt(state.page.toUInt(), pt.x, pt.y, RENDER_SCALE)
                                    val rect = found?.rects?.firstOrNull()
                                    debugText =
                                        "$prefix -> word=${found?.text} rect=${rect?.let { "(%.0f,%.0f)-(%.0f,%.0f)".format(it.x0, it.y0, it.x1, it.y1) }}"
                                    onSelectionChange(found)
                                }
                            },
                            onTap = {
                                if (state.selection != null) {
                                    lastTouchPoint = null
                                    onSelectionChange(null)
                                }
                            },
                        )
                    },
            // TopStart, not the default Center: every absolutely-positioned child below (the
            // Image's graphicsLayer, each SelectionHandle's Modifier.offset) assumes it's placed
            // at (0, 0) before that positioning is applied, via toScreenPoint/toBitmapPoint's
            // offsetX/offsetY math. Center would place each child (at its own *measured* size —
            // for Image, the bitmap's full native size, before any scaling) in the middle of the
            // box first, silently adding an extra, uncomputed shift on top of the real one.
            contentAlignment = Alignment.TopStart,
        ) {
            val density = LocalDensity.current
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${state.page + 1}",
                    modifier =
                        Modifier
                            .size(with(density) { it.width.toDp() }, with(density) { it.height.toDp() })
                            .graphicsLayer {
                                scaleX = totalScale
                                scaleY = totalScale
                                translationX = offsetX
                                translationY = offsetY
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            },
                )
            } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            state.selection?.let { selection ->
                SelectionOverlay(
                    selection = selection,
                    totalScale = totalScale,
                    toScreenPoint = ::toScreenPoint,
                    onDragHandle = { anchorGlyph, bitmapPoint ->
                        onSelectionChange(
                            state.document.extendSelection(state.page.toUInt(), anchorGlyph, bitmapPoint.x, bitmapPoint.y, RENDER_SCALE),
                        )
                    },
                )
            }
            lastTouchPoint?.let { touch ->
                val crosshair = toScreenPoint(touch)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(ComposeColor.Red, Offset(crosshair.x - 24f, crosshair.y), Offset(crosshair.x + 24f, crosshair.y), strokeWidth = 4f)
                    drawLine(ComposeColor.Red, Offset(crosshair.x, crosshair.y - 24f), Offset(crosshair.x, crosshair.y + 24f), strokeWidth = 4f)
                }
            }
        }
        if (debugText.isNotEmpty()) {
            Text(debugText, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), fontSize = 10.sp)
        }
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, enabled = state.page > 0) { Text("<") }
            Text("${state.page + 1} / ${state.pageCount}")
            IconButton(onClick = onNext, enabled = state.page < state.pageCount - 1) { Text(">") }
        }
        if (state.pageCount > 1) {
            Slider(
                value = state.page.toFloat(),
                onValueChange = { onGoToPage(it.roundToInt()) },
                valueRange = 0f..(state.pageCount - 1).toFloat(),
                steps = (state.pageCount - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** The selection's highlight (a [Canvas], transformed by hand since it isn't baked into the
 * bitmap — re-rendering the whole page on every drag frame would defeat `text_cache` on the Rust
 * side) and its two draggable handles. */
@Composable
private fun SelectionOverlay(
    selection: TextSelection,
    totalScale: Float,
    toScreenPoint: (Offset) -> Offset,
    onDragHandle: (anchorGlyph: UInt, bitmapPoint: Offset) -> Unit,
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        selection.rects.forEach { rect ->
            val topLeft = toScreenPoint(Offset(rect.x0, rect.y0))
            val bottomRight = toScreenPoint(Offset(rect.x1, rect.y1))
            drawRect(
                color = ComposeColor(0xFFFFD500).copy(alpha = 0.3f),
                topLeft = topLeft,
                size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
            )
        }
    }
    val first = selection.rects.firstOrNull()
    val last = selection.rects.lastOrNull()
    if (first != null) {
        SelectionHandle(
            screenPoint = toScreenPoint(Offset(first.x0, first.y1)),
            totalScale = totalScale,
            cornerBitmapPoint = Offset(first.x0, first.y1),
            onDragTo = { bitmapPoint -> onDragHandle(selection.endGlyph, bitmapPoint) },
        )
    }
    if (last != null) {
        SelectionHandle(
            screenPoint = toScreenPoint(Offset(last.x1, last.y1)),
            totalScale = totalScale,
            cornerBitmapPoint = Offset(last.x1, last.y1),
            onDragTo = { bitmapPoint -> onDragHandle(selection.startGlyph, bitmapPoint) },
        )
    }
}

/**
 * A small draggable circle at one end of a selection. Its displayed position always comes from
 * the live `selection` (via `screenPoint`), so it tracks a selection extended by dragging the
 * *other* handle for free; its own drag accumulates in bitmap space from `cornerBitmapPoint`
 * (read fresh at drag start via `rememberUpdatedState`, since `pointerInput`'s gesture coroutine
 * — keyed only on `totalScale` — would otherwise keep whatever `cornerBitmapPoint` was in scope
 * when that coroutine last (re)started, not the latest one).
 */
@Composable
private fun SelectionHandle(screenPoint: Offset, totalScale: Float, cornerBitmapPoint: Offset, onDragTo: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSizeDp = 28.dp
    val handleSizePx = with(density) { handleSizeDp.toPx() }
    val latestCorner by rememberUpdatedState(cornerBitmapPoint)
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier =
            Modifier
                .offset { IntOffset((screenPoint.x - handleSizePx / 2).roundToInt(), (screenPoint.y - handleSizePx / 2).roundToInt()) }
                .size(handleSizeDp)
                .background(ComposeColor(0xFF2962FF), CircleShape)
                .pointerInput(totalScale) {
                    detectDragGestures(
                        onDragStart = { dragPoint = latestCorner },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragPoint += Offset(dragAmount.x / totalScale, dragAmount.y / totalScale)
                            onDragTo(dragPoint)
                        },
                    )
                },
    )
}
