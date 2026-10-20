/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.axion.themepicker.ui.rime

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.WindowManagerGlobal
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rime.ui.backdrop.TintBackdropRenderer
import dev.rime.ui.components.twoFingerHold
import dev.rime.ui.customization.CalibrationPage
import dev.rime.ui.customization.PresetFileStore
import dev.rime.ui.customization.PresetGallery
import dev.rime.ui.customization.PresetLibraryController
import java.io.File
import java.util.concurrent.Executors

/**
 * The Rime customization page: lock screen gallery, draft editor and calibration. SystemUI opens
 * it after unlock when the lock screen is held; nothing here runs on the keyguard. The window
 * shows the home wallpaper behind it, which is what a preset without its own photo follows.
 */
class RimeCustomizationActivity : ComponentActivity() {
    private lateinit var host: RimePageHost
    private lateinit var library: PresetLibraryController
    private val depthPreview get() = DepthPreviewClient.get(applicationContext)
    // Process-wide: an apply or import can finish after this activity is gone, and its follow-up
    // work must still run instead of crashing on a shut-down executor.
    private val worker get() = sharedWorker

    // The system photo picker stays only as the fallback when no gallery app is installed.
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) library.importPhoto(uri)
    }
    // ACTION_PICK reaches gallery apps; the system's resolver offers "Just once" and "Always".
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(library::importPhoto)
    }

    private fun pickFromGallery() {
        try { galleryPicker.launch(Intent(Intent.ACTION_PICK).setType("image/*")) }
        catch (_: android.content.ActivityNotFoundException) {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        host = RimePageHost(applicationContext)
        val store = PresetFileStore(applicationContext, File(filesDir, "rime-presets"))
        library = PresetLibraryController(store, worker, Handler(Looper.getMainLooper()),
            appearance = { host.appearance }, deviceWallpaper = host::deviceWallpaper,
            pickPhoto = ::pickFromGallery,
            apply = { preset -> host.applyPreset(preset, store.wallpaperFile(preset)) },
            subjectOutline = { preset -> store.wallpaperFile(preset)?.let { depthPreview.request(it.path, preset.depth.outlineSpec) } })
        library.load()

        val fromLockscreen = intent.getBooleanExtra(EXTRA_FROM_LOCKSCREEN, false)
        val startInCalibration = intent.action == ACTION_CALIBRATION
        setContent {
            val tint = remember { TintBackdropRenderer() }
            var calibrating by remember { mutableStateOf(startInCalibration) }
            var closeRequests by remember { mutableIntStateOf(0) }
            BackHandler(!calibrating) { closeRequests++ }
            BackHandler(calibrating) { if (startInCalibration) finish() else calibrating = false }
            // The same hidden gesture as the preview: two fingers held still open calibration.
            Box(Modifier.fillMaxSize().twoFingerHold { calibrating = true }) {
                if (calibrating) CalibrationPage(host, tint, onClose = { if (startInCalibration) finish() else calibrating = false })
                else PresetGallery(library, host, tint, onClosed = { leave(fromLockscreen) }, closeRequests = closeRequests)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        host.refresh()
    }

    /** Opened from the lock screen, the page returns to it, so the chosen lock screen is what shows next. */
    private fun leave(toLockscreen: Boolean) {
        if (toLockscreen) runCatching { checkNotNull(WindowManagerGlobal.getWindowManagerService()).lockNow(null) }
            .onFailure { Log.w("RimePage", "Could not return to the lock screen", it) }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_FROM_LOCKSCREEN = "from_lockscreen"
        const val ACTION_CALIBRATION = "dev.rime.action.CALIBRATION"
    }
}

private val sharedWorker = Executors.newSingleThreadExecutor()

/**
 * Asks Axion's depth service (same uid, so its unexported service is reachable) for a photo's
 * subject outline while the editor is open. A newer request makes older ones answer null at once,
 * so a caller never waits on an answer the service will not send.
 */
private class DepthPreviewClient private constructor(private val context: android.content.Context) {
    companion object {
        @Volatile private var instance: DepthPreviewClient? = null
        /** Process-wide: one binding and one reply thread however often the page opens. */
        fun get(context: android.content.Context): DepthPreviewClient =
            instance ?: synchronized(this) { instance ?: DepthPreviewClient(context.applicationContext).also { instance = it } }
    }

    private val lock = Object()
    private var service: android.os.Messenger? = null
    private var binding = false
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<String?>>()
    private val replyThread = android.os.HandlerThread("RimeDepthPreview").apply { start() }
    private val replies = android.os.Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(msg: android.os.Message) {
            pending.remove(msg.data.getInt("request"))?.complete(msg.data.getString("mask"))
        }
    })
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            synchronized(lock) { service = binder?.let { android.os.Messenger(it) }; lock.notifyAll() }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            synchronized(lock) { service = null; binding = false }
            pending.values.forEach { it.complete(null) }
        }
    }

    /** Blocking; call off the main thread. */
    fun request(photo: String, options: String): String? {
        val target = connect() ?: return null
        val id = counter.incrementAndGet()
        val answer = java.util.concurrent.CompletableFuture<String?>()
        pending.keys.filter { it != id }.forEach { pending.remove(it)?.complete(null) }
        pending[id] = answer
        return try {
            target.send(android.os.Message.obtain(null, 1).apply {
                data = Bundle().apply { putString("photo", photo); putString("options", options); putInt("request", id) }
                replyTo = replies
            })
            // The Best depth model takes up to half a minute on its own; the first request also loads it.
            answer.get(90, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("RimePage", "Depth preview unavailable", e)
            null
        } finally {
            pending.remove(id)
        }
    }

    private fun connect(): android.os.Messenger? = synchronized(lock) {
        service?.let { return it }
        if (!binding) {
            val intent = Intent().setComponent(android.content.ComponentName("com.android.axion.wallpapereffects",
                "com.android.axion.wallpapereffects.service.WallpaperDepthService"))
            binding = runCatching { context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!binding) return null
        }
        lock.wait(3000)
        service
    }
}
