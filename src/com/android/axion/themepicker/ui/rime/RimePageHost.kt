/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.axion.themepicker.ui.rime

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.android.systemui.shared.clocks.ClockSettingsRepository
import dev.rime.ui.lockscreen.WallpaperGeometry
import dev.rime.ui.models.LockShortcut
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.models.PresetWallpaper
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.UiConnectivityState
import dev.rime.ui.models.UiControlAction
import dev.rime.ui.models.UiKeyguardState
import dev.rime.ui.models.UiLockscreenContent
import dev.rime.ui.models.UiMediaState
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiThemeState
import dev.rime.ui.models.UiWallpaperState
import dev.rime.ui.settings.AxionSettingsCodec
import dev.rime.ui.settings.Calibration
import dev.rime.ui.settings.CalibrationCodec
import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.LockscreenSettings
import dev.rime.ui.state.RimeUiHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host of the customization page. It shows scenes, so it supplies what a scene draws from, but
 * it has no notifications and performs no lockscreen actions. What it does own is the hand-over
 * to SystemUI: a preset is applied by setting the lock wallpaper and writing the same Secure
 * settings SystemUI's Rime host reads (`RimeSystemUIHost`), and calibration is one Secure value.
 */
class RimePageHost(private val context: Context) : RimeUiHost {
    private val resolver = context.contentResolver
    private val wallpapers = WallpaperManager.getInstance(context)

    override val notifications: StateFlow<List<UiNotification>> = MutableStateFlow(emptyList<UiNotification>()).asStateFlow()
    override val displayMode: StateFlow<NotificationDisplayMode> = MutableStateFlow(NotificationDisplayMode.Stack).asStateFlow()
    override val media: StateFlow<UiMediaState?> = MutableStateFlow<UiMediaState?>(null).asStateFlow()
    override val connectivity: StateFlow<UiConnectivityState> =
        MutableStateFlow(UiConnectivityState(null, null, null, null, null, null, null, null, null, 0, 0f, 0f)).asStateFlow()
    // The real lockscreen keeps its own fingerprint, shortcuts and status region; a page shows none of them.
    override val keyguard: StateFlow<UiKeyguardState> = MutableStateFlow(UiKeyguardState(locked = true, showStatusRegion = false,
        showUnlockAffordance = false, showShortcuts = false, showGestureHandle = false)).asStateFlow()
    private val _clock = MutableStateFlow(UiClockState("", ""))
    override val clock = _clock.asStateFlow()
    private val _wallpaper = MutableStateFlow(UiWallpaperState(false, Color.Black))
    override val wallpaper = _wallpaper.asStateFlow()
    private val _content = MutableStateFlow(UiLockscreenContent())
    override val lockscreenContent = _content.asStateFlow()
    private val _theme = MutableStateFlow(UiThemeState())
    override val theme = _theme.asStateFlow()
    private val _settings = MutableStateFlow(LockscreenSettings())
    override val lockscreenSettings = _settings.asStateFlow()
    private val _calibration = MutableStateFlow(Calibration())
    override val calibration = _calibration.asStateFlow()

    init {
        ClockSettingsRepository.init(context)
        refresh()
    }

    /** Reads the clock, the device wallpaper's colours and the appearance SystemUI is using now. */
    fun refresh() {
        val now = Date()
        val device = deviceWallpaper()
        _wallpaper.value = UiWallpaperState(device.supportsDarkText, Color(device.primaryColorArgb))
        val surfaces = notificationSurfaceColors(context)
        _theme.value = UiThemeState(Color(context.getColor(android.R.color.system_accent1_600)),
            Color(context.getColor(if (device.supportsDarkText) android.R.color.system_accent1_900 else android.R.color.system_accent1_100)),
            surface = surfaces.first, onSurface = surfaces.second, onSurfaceVariant = surfaces.third)
        _content.value = UiLockscreenContent(clockId = ClockSettingsRepository.clockId.value,
            hostedClock = isAxionFace(), hostedClockName = axionFaceName(ClockSettingsRepository.clockId.value))
        _settings.value = readAppearance()
        // Weather is left to the lock screen; the editor shows a sample in its place.
        val alarm = context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            ?.takeIf { it - now.time in 0..24 * 60 * 60_000L }?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { "$it%" }
        _clock.value = UiClockState(DateFormat.getTimeFormat(context).format(now), DateFormat.format("EEE, d MMM", now).toString(),
            epochMillis = now.time, nextAlarm = alarm, battery = battery, accent = _theme.value.accent)
        _calibration.value = Settings.Secure.getStringForUser(resolver, CALIBRATION_KEY, UserHandle.USER_CURRENT)
            ?.let(CalibrationCodec::fromJson) ?: Calibration()
    }

    /** Rime draws its own clock only for Axion's big clock in its wide face; any other face is Axion's. */
    private fun isAxionFace() = !ClockSettingsRepository.clockId.value.startsWith("OPLUS_BIG") ||
        Settings.Secure.getStringForUser(resolver, ClockSettingsRepository.SETTING_OPLUS_BIG_FACE, UserHandle.USER_CURRENT) !=
            ClockSettingsRepository.OPLUS_BIG_FACE_WIDE

    override fun openClockPicker() {
        context.startActivity(Intent().setClassName(context, "com.android.axion.themepicker.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun useRimeClock() {
        // The clock Rime draws itself: Axion's big clock, wide face.
        Settings.Secure.putStringForUser(resolver, ClockSettingsRepository.SETTING_CLOCK_FACE,
            "{\"clockId\":\"OPLUS_BIG\",\"metadata\":{\"appliedTimestamp\":${System.currentTimeMillis()}},\"axes\":[]}",
            UserHandle.USER_CURRENT)
        Settings.Secure.putStringForUser(resolver, ClockSettingsRepository.SETTING_OPLUS_BIG_FACE,
            ClockSettingsRepository.OPLUS_BIG_FACE_WIDE, UserHandle.USER_CURRENT)
        _content.value = _content.value.copy(hostedClock = false)
    }

    /** A preset without its own photo follows the home wallpaper, which is what shows behind the page. */
    fun deviceWallpaper(): PresetWallpaper {
        val colors = runCatching { wallpapers.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        return PresetWallpaper(
            supportsDarkText = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0,
            primaryColorArgb = colors?.primaryColor?.toArgb() ?: PresetWallpaper().primaryColorArgb)
    }

    // Mirrors RimeSystemUIHost.refreshInternal: the same keys, read the same way.
    private fun readAppearance(): LockscreenSettings {
        val defaults = LockscreenSettings()
        return defaults.copy(clock = defaults.clock.copy(
            sizeScale = ClockSettingsRepository.sizeScale.value,
            horizontalOffsetDp = ClockSettingsRepository.horizontalOffsetDp.value,
            topPaddingDp = Settings.Secure.getFloatForUser(resolver, "rime_clock_top_padding_dp", ClockSettingsRepository.topPaddingDp.value, UserHandle.USER_CURRENT),
            dateBelow = ClockSettingsRepository.isDateBelow.value,
            showDate = ClockSettingsRepository.infoDisplayMode.value != ClockSettingsRepository.INFO_DISPLAY_OFF,
            colorArgb = ClockSettingsRepository.clockColorOverride.value,
            dualTone = ClockSettingsRepository.oplusBigDualTone.value,
            translucentDigits = ClockSettingsRepository.clockBackgroundBlurEnabled.value,
            digitOpacity = ClockSettingsRepository.clockBackgroundTransparency.value / 100f,
            heightScale = Settings.Secure.getFloatForUser(resolver, "rime_clock_height_scale", 1f, UserHandle.USER_CURRENT),
            blurRadiusDp = Settings.Secure.getStringForUser(resolver, "rime_clock_blur_radius_dp", UserHandle.USER_CURRENT)?.toFloatOrNull(),
        ).let { clock ->
            // Rime's own keys: face, layout, weight and the date row.
            val values = AxionSettingsCodec.RIME_KEYS.mapNotNull { key ->
                Settings.Secure.getStringForUser(resolver, key, UserHandle.USER_CURRENT)?.let { key to it }
            }.toMap()
            AxionSettingsCodec.decode(values, LockscreenSettings(clock = clock)).clock
        }, surfaces = defaults.surfaces.copy(
            blurRadiusDp = ClockSettingsRepository.clockBackgroundBlurRadius.value / 100f * 48f,
            cornerRadiusDp = Settings.Secure.getFloatForUser(resolver, "rime_surface_corner_radius_dp", 28f, UserHandle.USER_CURRENT),
            opacity = Settings.Secure.getFloatForUser(resolver, "rime_surface_opacity", 0.72f, UserHandle.USER_CURRENT),
        )).validated()
    }

    // Mirrors RimeSystemUIHost.updateLockscreenSettings, plus the date's visibility.
    private fun writeAppearance(settings: LockscreenSettings) {
        val s = settings.validated()
        ClockSettingsRepository.writeSizeAndPosition(context, s.clock.sizeScale, s.clock.horizontalOffsetDp, s.clock.topPaddingDp)
        AxionSettingsCodec.encode(s).filterKeys { it !in setOf("ax_clock_size_scale", "ax_clock_horizontal_offset_dp", "ax_clock_top_padding_dp") }
            .forEach { (key, value) -> Settings.Secure.putStringForUser(resolver, key, value, UserHandle.USER_CURRENT) }
        Settings.Secure.putStringForUser(resolver, "rime_clock_top_padding_dp",
            if (s.clock.topPaddingDp < ClockSettingsRepository.TOP_PADDING_MIN_DP) s.clock.topPaddingDp.toString() else null, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(resolver, "rime_surface_corner_radius_dp", s.surfaces.cornerRadiusDp, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(resolver, "rime_surface_opacity", s.surfaces.opacity, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(resolver, "rime_clock_height_scale", s.clock.heightScale, UserHandle.USER_CURRENT)
        Settings.Secure.putStringForUser(resolver, "rime_clock_blur_radius_dp", s.clock.blurRadiusDp?.toString(), UserHandle.USER_CURRENT)
        // Only the on/off part of Axion's info line is ours to change; its other modes are left alone.
        val hidden = Settings.Secure.getStringForUser(resolver, ClockSettingsRepository.SETTING_INFO_DISPLAY_MODE, UserHandle.USER_CURRENT) ==
            ClockSettingsRepository.INFO_DISPLAY_OFF
        if (hidden == s.clock.showDate) Settings.Secure.putStringForUser(resolver, ClockSettingsRepository.SETTING_INFO_DISPLAY_MODE,
            if (s.clock.showDate) ClockSettingsRepository.INFO_DISPLAY_AUTO else ClockSettingsRepository.INFO_DISPLAY_OFF, UserHandle.USER_CURRENT)
        _settings.value = s
    }

    /**
     * Applies a preset for real. Runs off the main thread. The photo is checked and prepared
     * before anything is written, so a preset whose photo cannot be read changes nothing. The
     * wallpaper goes first and the settings second; a failure after the wallpaper is reported,
     * not hidden, and leaves the previous clock settings in place.
     */
    fun applyPreset(preset: LockscreenPreset, photo: File?): Boolean = try {
        val accentBefore = context.getColor(android.R.color.system_accent1_600)
        val themeBefore = Settings.Secure.getStringForUser(resolver, THEME_SETTING, UserHandle.USER_CURRENT)
        val depth = preset.depthEnabled && preset.wallpaper.assetId != null
        val spatial = depth && preset.depth.spatial
        // Read the photo before writing anything: a preset whose photo cannot be read changes nothing.
        val bytes = if (preset.wallpaper.assetId == null) null else photo?.let { framed(it, preset) }?.readBytes() ?: return false
        // Depth settings first: Axion's effects service recomputes the subject mask, with these
        // outline options, as soon as the wallpaper below changes.
        Settings.Secure.putStringForUser(resolver, "ax_depth_options", preset.depth.outlineSpec, UserHandle.USER_CURRENT)
        Settings.Secure.putIntForUser(resolver, "rime_depth_date", if (preset.depth.includeDate) 1 else 0, UserHandle.USER_CURRENT)
        Settings.Secure.putIntForUser(resolver, "rime_depth_readable", if (preset.depth.keepClockReadable) 1 else 0, UserHandle.USER_CURRENT)
        Settings.Secure.putIntForUser(resolver, "ax_spatial_mode", if (spatial) 1 else 0, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(resolver, "ax_spatial_strength", preset.depth.spatialStrength, UserHandle.USER_CURRENT)
        if (bytes == null) wallpapers.clear(WallpaperManager.FLAG_LOCK)
        else {
            if (spatial) {
                // Home keeps the still photo; the lock screen runs Axion's Cinematic wallpaper in
                // its spatial mode, which reads the photo from the effects app's own storage.
                wallpapers.setStream(ByteArrayInputStream(bytes), null, false, WallpaperManager.FLAG_SYSTEM)
                writeEffectsPhoto(bytes)
                val component = android.content.ComponentName(EFFECTS_PACKAGE, "$EFFECTS_PACKAGE.service.CinematicService")
                wallpapers.setWallpaperComponentWithDescription(
                    android.app.wallpaper.WallpaperDescription.Builder().setComponent(component).setTitle("CinematicService")
                        .setContent(android.os.PersistableBundle().apply { putString("effect_type", "CINEMATIC") }).build(),
                    WallpaperManager.FLAG_LOCK, UserHandle.myUserId())
                context.sendBroadcast(android.content.Intent("$EFFECTS_PACKAGE.RELOAD_WALLPAPER").setPackage(EFFECTS_PACKAGE))
            } else {
                // Home and lock together, as on iOS; the system colours follow the home wallpaper.
                wallpapers.setStream(ByteArrayInputStream(bytes), null, false, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            }
        }
        writeAppearance(_settings.value.copy(clock = preset.clock, surfaces = preset.surfaces))
        Settings.Secure.putIntForUser(resolver, "ax_depth_clock_enabled", if (depth) 1 else 0, UserHandle.USER_CURRENT)
        awaitThemeApplied(accentBefore, themeBefore)
        true
    } catch (failure: Exception) {
        Log.e(TAG, "Applying preset failed", failure)
        false
    }

    /** Same uid as the effects app, so its device-protected files are ours to write. */
    private fun writeEffectsPhoto(bytes: ByteArray) {
        val dir = context.createPackageContext(EFFECTS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            .createDeviceProtectedStorageContext().filesDir.apply { mkdirs() }
        val target = File(dir, "wallpaper.jpg")
        val partial = File(dir, "wallpaper.jpg.partial")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) { target.writeBytes(bytes); partial.delete() }
    }

    /**
     * The page's spinner stays until Android has re-themed for the new wallpaper. The theme
     * service records new colours in [THEME_SETTING] first; when it does not within a moment,
     * the colours did not change. Bounded either way.
     */
    private fun awaitThemeApplied(accentBefore: Int, themeBefore: String?) {
        val start = android.os.SystemClock.uptimeMillis()
        var themeChanged = false
        while (android.os.SystemClock.uptimeMillis() - start < 7000L) {
            Thread.sleep(150L)
            val elapsed = android.os.SystemClock.uptimeMillis() - start
            if (!themeChanged) themeChanged = Settings.Secure.getStringForUser(resolver, THEME_SETTING, UserHandle.USER_CURRENT) != themeBefore
            if (!themeChanged && elapsed > 2500L) return
            if (themeChanged && context.getColor(android.R.color.system_accent1_600) != accentBefore) { Thread.sleep(300L); return }
        }
    }

    /** The photo cut to the frame the editor showed, at the display's own size, so the lock screen shows the same picture. */
    private fun framed(photo: File, preset: LockscreenPreset): ByteArrayInputStream? {
        val source = BitmapFactory.decodeFile(photo.path) ?: return null
        // An application context has no display of its own; ask for the default one.
        val bounds = android.graphics.Point()
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(bounds)
        val metrics = context.resources.displayMetrics
        val width = minOf(bounds.x, bounds.y).takeIf { it > 0 } ?: minOf(metrics.widthPixels, metrics.heightPixels)
        val height = maxOf(bounds.x, bounds.y).takeIf { it > 0 } ?: maxOf(metrics.widthPixels, metrics.heightPixels)
        val shown = WallpaperGeometry.sourceRect(source.width, source.height, preset.wallpaper.crop, width.toFloat() / height)
        val from = Rect(shown.left.toInt().coerceAtLeast(0), shown.top.toInt().coerceAtLeast(0),
            shown.right.toInt().coerceAtMost(source.width), shown.bottom.toInt().coerceAtMost(source.height))
        if (from.width() <= 0 || from.height() <= 0) { source.recycle(); return null }
        // Stored photos can be large; each intermediate is released as soon as it is used.
        val cut = Bitmap.createBitmap(source, from.left, from.top, from.width(), from.height())
        if (cut !== source) source.recycle()
        val sized = Bitmap.createScaledBitmap(cut, width, height, true)
        if (sized !== cut) cut.recycle()
        val bytes = ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        sized.recycle()
        return ByteArrayInputStream(bytes.toByteArray())
    }

    override fun previewCalibration(candidate: Calibration) { _calibration.value = candidate.validated() }
    /** SystemUI observes Secure settings and applies the new revision on its next frame. */
    override fun saveCalibration(candidate: Calibration) {
        previewCalibration(candidate)
        Settings.Secure.putStringForUser(resolver, CALIBRATION_KEY, CalibrationCodec.toJson(_calibration.value), UserHandle.USER_CURRENT)
    }

    override fun updateLockscreenSettings(settings: LockscreenSettings) { _settings.value = settings.validated() }
    override fun requestWallpaperGallery() { }
    override fun setDisplayMode(mode: NotificationDisplayMode) { }
    override fun activateNotification(key: String) { }
    override fun setNotificationExpanded(key: String, expanded: Boolean) { }
    override fun dismissNotification(key: String) { }
    override fun manageNotification(key: String) { }
    override fun performNotificationAction(key: String, actionId: String) { }
    override fun clearNotifications() { }
    override fun requestControl(action: UiControlAction) { }
    override fun setBrightness(level: Float) { }
    override fun setVolume(level: Float) { }
    override fun setMediaPlaying(playing: Boolean) { }
    override fun skipToPrevious() { }
    override fun skipToNext() { }
    override fun seekTo(progress: Float) { }
    override fun requestUnlock() { }
    override fun setLocked(locked: Boolean) { }
    override fun requestLockShortcut(shortcut: LockShortcut) { }

    val appearance: Pair<ClockAppearance, dev.rime.ui.settings.SurfaceAppearance> get() = _settings.value.let { it.clock to it.surfaces }

    companion object {
        private const val TAG = "RimePage"
        private const val THEME_SETTING = "theme_customization_overlay_packages"
        private const val EFFECTS_PACKAGE = "com.android.axion.wallpapereffects"
        /** One versioned JSON value, well under the 32,768 characters a Secure setting may hold. */
        const val CALIBRATION_KEY = "rime_calibration"
    }
}

/** The colours Android's own notifications use for the current light or dark mode. */
internal fun notificationSurfaceColors(context: android.content.Context): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    fun c(id: Int) = androidx.compose.ui.graphics.Color(context.getColor(id))
    return Triple(
        c(if (night) android.R.color.system_surface_dim_dark else android.R.color.system_surface_bright_light),
        c(if (night) android.R.color.system_on_surface_dark else android.R.color.system_on_surface_light),
        c(if (night) android.R.color.system_on_surface_variant_dark else android.R.color.system_on_surface_variant_light))
}

/** "OPLUS_CLASSIC" -> "Axion classic". */
internal fun axionFaceName(clockId: String): String =
    "Axion " + clockId.removePrefix("OPLUS_").lowercase().replace('_', ' ')
