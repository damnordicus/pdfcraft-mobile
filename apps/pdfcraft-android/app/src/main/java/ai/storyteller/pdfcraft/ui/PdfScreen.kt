package ai.storyteller.pdfcraft.ui

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
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
        bottomBar = { Text("build ${buildInfo()}", modifier = Modifier.padding(4.dp)) },
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

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(state.page) {
                        detectTransformGestures { _, panDelta, zoomDelta, _ ->
                            zoom = (zoom * zoomDelta).coerceIn(1f, 5f)
                            pan += panDelta
                        }
                    }
                    .pointerInput(state.page, bmp) {
                        detectTapGestures(
                            onLongPress = { screen ->
                                if (bmp == null) return@detectTapGestures
                                val pt = toBitmapPoint(screen)
                                // Off the main thread: this page's text may not be cached yet
                                // (text_cache, crates/mobile-ffi/src/lib.rs), so this specific
                                // call — unlike extendSelection below, which only ever runs once
                                // a selection (and so a warm cache) already exists — can be slow.
                                coroutineScope.launch(Dispatchers.Default) {
                                    val found = state.document.wordAt(state.page.toUInt(), pt.x, pt.y, RENDER_SCALE)
                                    onSelectionChange(found)
                                }
                            },
                            onTap = { if (state.selection != null) onSelectionChange(null) },
                        )
                    },
            contentAlignment = Alignment.Center,
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
            } ?: CircularProgressIndicator()

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
