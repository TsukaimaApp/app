package app.tsukaima.ui.chat.components

import android.media.MediaPlayer
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The one audio file playing in the app. A second card that starts takes over; leaving the chat stops it
 * ([stop]). Compose reads [path], [playing] and [durationMs]; the position is asked for ([positionMs]).
 */
internal object AudioPlayback {
    private var player: MediaPlayer? = null
    var path by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Plays [file] as [forPath] from its start; false when it cannot be played (not audio the phone knows). */
    fun start(forPath: String, file: File): Boolean {
        stop()
        val p = MediaPlayer()
        return try {
            p.setDataSource(file.path)
            p.prepare()
            p.setOnCompletionListener {
                playing = false
                runCatching { it.seekTo(0) }
            }
            p.start()
            player = p
            path = forPath
            durationMs = p.duration.toLong().coerceAtLeast(0)
            playing = true
            true
        } catch (e: Exception) { // IOException, IllegalStateException: an unsupported or broken file
            Log.w("Tsukaima", "audio: cannot play", e)
            p.release()
            false
        }
    }

    fun toggle() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause() else p.start()
            playing = p.isPlaying
        } catch (e: IllegalStateException) {
            stop()
        }
    }

    fun positionMs(): Long = try {
        player?.currentPosition?.toLong() ?: 0L
    } catch (e: IllegalStateException) {
        0L
    }

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        path = null
        playing = false
        durationMs = 0
    }
}

/** "0:44" for [ms] of audio or video. */
internal fun clock(ms: Long): String = elapsedText(ms / 1000)

@Composable
private fun PlayButton(playing: Boolean, loading: Boolean, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    Box(
        Modifier
            .size(Dimens.hit)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(enabled = !loading, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = colors.surfacePage, trackColor = colors.accent, strokeWidth = 2.dp)
        else TsukaimaIcon(if (playing) IconName.Pause else IconName.PlayArrow, contentDescription = null, size = 22.dp, tint = colors.surfacePage)
    }
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    Box(modifier.fillMaxWidth().height(4.dp).clip(TsukaimaShapes.pill).background(colors.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
    }
}

/**
 * Plays an audio file the agent delivered, in its card: the round button fetches the file on the first tap
 * ([file], null when it cannot be fetched) and then plays and pauses it; the line and "0:44 / 2:10" follow.
 */
@Composable
fun AudioStrip(path: String, name: String, file: suspend (String) -> File?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val scope = rememberCoroutineScope()
    var loading by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    val mine = AudioPlayback.path == path
    val playing = mine && AudioPlayback.playing
    var position by remember(path) { mutableLongStateOf(0L) }
    LaunchedEffect(playing, mine) {
        if (!mine) position = 0
        while (playing) {
            position = AudioPlayback.positionMs()
            delay(250)
        }
        if (mine) position = AudioPlayback.positionMs()
    }
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayButton(playing, loading, if (playing) "Pause $name" else "Play $name") {
            if (mine) return@PlayButton AudioPlayback.toggle()
            loading = true
            failed = false
            scope.launch {
                val fetched = file(path)
                loading = false
                failed = fetched == null || !AudioPlayback.start(path, fetched)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val duration = if (mine) AudioPlayback.durationMs else 0L
            ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
            Text(
                when {
                    failed -> "Couldn’t play this file"
                    loading -> "Loading…"
                    mine -> "${clock(position)} / ${clock(duration)}"
                    else -> "Tap to play"
                },
                style = TsukaimaType.caption,
                color = if (failed) colors.danger else colors.ink3,
                maxLines = 1,
            )
        }
    }
}

/**
 * Plays a video the agent delivered, in its card: a dark frame with a play button until the first tap, which
 * fetches the file ([file]); then the video at its own shape, a tap pausing and resuming it, with the same line and
 * clock as audio. It pauses when the app leaves the screen.
 */
@Composable
fun VideoFrame(path: String, name: String, file: suspend (String) -> File?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val scope = rememberCoroutineScope()
    var local by remember(path) { mutableStateOf<File?>(null) }
    var loading by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    var ratio by remember(path) { mutableFloatStateOf(16f / 9f) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableLongStateOf(0L) }
    var duration by remember(path) { mutableLongStateOf(0L) }
    var view by remember(path) { mutableStateOf<VideoView?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        runCatching { view?.pause() }
        playing = false
    }
    DisposableEffect(path) { onDispose { runCatching { view?.stopPlayback() } } }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { view?.currentPosition?.toLong() }.getOrNull() ?: position
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth()) {
        val shown = local
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.5f, 2.4f))
                .background(colors.surfaceSunken)
                .clickable(
                    enabled = !loading, role = Role.Button,
                    onClickLabel = if (playing) "Pause $name" else "Play $name",
                ) {
                    val v = view
                    if (shown != null && v != null) {
                        if (v.isPlaying) v.pause() else v.start()
                        playing = v.isPlaying
                    } else {
                        loading = true
                        failed = false
                        AudioPlayback.stop() // one thing sounds at a time
                        scope.launch {
                            val fetched = file(path)
                            loading = false
                            if (fetched == null) failed = true else local = fetched
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setOnPreparedListener { mp ->
                                if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
                                duration = mp.duration.toLong().coerceAtLeast(0)
                                start()
                                playing = true
                            }
                            setOnCompletionListener {
                                playing = false
                                position = duration
                            }
                            setOnErrorListener { _, _, _ ->
                                failed = true
                                playing = false
                                local = null
                                true
                            }
                            setVideoPath(shown.path)
                            view = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f)),
                )
            }
            if (!playing) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(colors.surfacePage.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, trackColor = colors.hairline, strokeWidth = 2.dp)
                    else TsukaimaIcon(IconName.PlayArrow, contentDescription = null, size = 28.dp, tint = colors.ink1)
                }
            }
        }
        if (shown != null && duration > 0) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProgressLine(position.toFloat() / duration, Modifier.weight(1f))
                Text("${clock(position)} / ${clock(duration)}", style = TsukaimaType.caption, color = colors.ink3, maxLines = 1)
            }
        }
        if (failed) {
            Text("Couldn’t play this video", style = TsukaimaType.caption, color = colors.danger, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        }
    }
}
