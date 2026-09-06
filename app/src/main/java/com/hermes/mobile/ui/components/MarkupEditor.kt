package com.hermes.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.screens.chat.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream

/** One freehand stroke (display coordinates — the editor is 1:1 with the
 * decoded bitmap, so flattening is an identity scale). */
private data class MarkStroke(val path: Path, val color: Color, val width: Float)

private val MARK_COLORS = listOf(
    Color(0xFFFF3B30), // red
    Color(0xFFFFD60A), // yellow
    Color(0xFF30D158), // green
    Color(0xFF0A84FF), // blue
    Color.White
)

/**
 * Cursor-style visual direction: annotate an image (freehand draw) before
 * sending it to the agent. The strokes are flattened onto the bitmap and
 * the result is uploaded — the agent sees the markup as part of the image.
 *
 * @param attachment     the picked image
 * @param onSend         called with the flattened PNG file (caller uploads)
 * @param onDismiss      close without sending
 */
@Composable
fun MarkupEditorDialog(
    attachment: PendingAttachment,
    onSend: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val strokes = remember { mutableStateListOf<MarkStroke>() }
    var currentStroke by remember { mutableStateOf<MarkStroke?>(null) }
    var markColor by remember { mutableStateOf(MARK_COLORS.first()) }
    // The image is drawn ContentScale.Fit — strokes are in DISPLAY coords.
    // fitRect = the image's on-screen rect, used to map strokes into bitmap
    // coords when flattening (display pixels ≠ bitmap pixels in general).
    var fitRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Decode the picked image ONCE, downscaled so the editor is 1:1
    // (display pixels == bitmap pixels — flattening needs no scaling).
    androidx.compose.runtime.LaunchedEffect(attachment.uri) {
        bitmap = try {
            val resolver = context.contentResolver
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(attachment.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var scale = 1
            val maxDim = 1400
            while ((opts.outWidth / scale) > maxDim || (opts.outHeight / scale) > maxDim) scale *= 2
            val dOpts = BitmapFactory.Options().apply { inSampleSize = scale }
            resolver.openInputStream(attachment.uri)?.use {
                BitmapFactory.decodeStream(it, null, dOpts)
            }
        } catch (_: Exception) { null }
    }

    val bmp = bitmap
    if (bmp == null) {
        // Still decoding / failed — minimal loading dialog.
        androidx.compose.material3.Surface(
            onClick = onDismiss,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading image…", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    androidx.compose.material3.Surface(
        onClick = onDismiss,
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE6000000)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header: title + undo/clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Mark up — draw to direct the agent",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { strokes.clear(); currentStroke = null }) {
                    Text("Clear", color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                        currentStroke = null
                    }
                ) {
                    Text("Undo", color = Color.White)
                }
            }

            // Canvas: image + strokes overlay (display coords; flattened
            // back into bitmap coords on send via the fit rect)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (bmp.width > 0 && bmp.height > 0 && size.width > 0 && size.height > 0) {
                            val scale = min(
                                size.width / bmp.width.toFloat(),
                                size.height / bmp.height.toFloat()
                            )
                            val dw = bmp.width * scale
                            val dh = bmp.height * scale
                            fitRect = androidx.compose.ui.geometry.Rect(
                                left = (size.width - dw) / 2f,
                                top = (size.height - dh) / 2f,
                                right = (size.width + dw) / 2f,
                                bottom = (size.height + dh) / 2f
                            )
                        }
                    }
                    .pointerInput(bmp.width, bmp.height) {
                        detectDragGestures(
                            onDragStart = { start ->
                                currentStroke = MarkStroke(
                                    path = Path().apply { moveTo(start.x, start.y) },
                                    color = markColor,
                                    width = 10f
                                )
                            },
                            onDrag = { change, _ ->
                                val s = currentStroke ?: return@detectDragGestures
                                s.path.lineTo(change.position.x, change.position.y)
                                change.consume()
                            },
                            onDragEnd = {
                                currentStroke?.let { strokes.add(it) }
                                currentStroke = null
                            },
                            onDragCancel = { currentStroke = null }
                        )
                    }
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(Modifier.fillMaxSize()) {
                    strokes.forEach { s ->
                        drawPath(s.path, s.color, style = Stroke(width = s.width))
                    }
                    currentStroke?.let { s ->
                        drawPath(s.path, s.color, style = Stroke(width = s.width))
                    }
                }
            }

            // Bottom: colors + Send/Cancel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                MARK_COLORS.forEach { c ->
                    androidx.compose.material3.Surface(
                        onClick = { markColor = c },
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(if (c == markColor) 26.dp else 22.dp)
                                .background(c, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val b = bmp
                        scope.launch(Dispatchers.IO) {
                            // Flatten strokes onto a copy of the bitmap,
                            // mapping DISPLAY coords → BITMAP coords via the
                            // fitted image rect (ContentScale.Fit ≠ 1:1).
                            val out = b.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = android.graphics.Canvas(out)
                            val fr = fitRect
                            val matrix = if (fr != null && fr.width > 0f && fr.height > 0f) {
                                android.graphics.Matrix().apply {
                                    setScale(b.width / fr.width, b.height / fr.height)
                                    postTranslate(-fr.left * (b.width / fr.width), -fr.top * (b.height / fr.height))
                                }
                            } else null
                            if (matrix != null) canvas.concat(matrix)  // API 1
                            strokes.forEach { s ->
                                val paint = AndroidPaint().apply {
                                    isAntiAlias = true
                                    style = AndroidPaint.Style.STROKE
                                    strokeWidth = s.width
                                    strokeCap = AndroidPaint.Cap.ROUND
                                    strokeJoin = AndroidPaint.Join.ROUND
                                    color = s.color.toArgb()
                                }
                                canvas.drawPath(s.path.asAndroidPath(), paint)
                            }
                            val file = File(context.cacheDir, "marked_${System.currentTimeMillis()}.png")
                            FileOutputStream(file).use { fos ->
                                out.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            }
                            out.recycle()
                            onSend(file)
                        }
                    }
                ) {
                    Text("Send marked")
                }
            }
        }
    }
}
