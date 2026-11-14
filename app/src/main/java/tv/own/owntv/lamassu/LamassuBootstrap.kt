package tv.own.owntv.lamassu

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.profile.ProfileManager
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.features.pip.PictureInPicture

/**
 * Lamassu shell + product defaults: brand, nav, single profile, Quick/Live/PiP/font defaults.
 */
internal class LamassuBootstrap(
    private val context: Context,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val profiles: ProfileManager,
    private val homeContent: HomeContentRepository,
    private val pictureInPicture: PictureInPicture,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                settings.setThemeMode(ThemeMode.DARK)
                settings.setCustomAccent(LamassuBrand.AccentHex)
                settings.setNavMenuMode(SettingsRepository.NavMenuMode.STATIC)
                settings.setNavMenuHidden(LamassuBrand.HiddenNavSections)
                applyProductDefaults()
                ensureSingleLamassuProfile()
                homeContent.refresh()
            }
        }
    }

    private suspend fun applyProductDefaults() {
        // Quick — all on, no startup update check.
        // Live preview + sound: same as OwnTV (auto-play focused channel with audio).
        settings.setLivePreviewEnabled(true)
        settings.setLivePreviewAudio(true)
        settings.setDirectTune(true)
        settings.setPreferSdr(true)
        settings.setAutoPlayNext(true)
        settings.setUpdateCheckOnStart(false)
        settings.setQuickPinnedKeys(LamassuSettings.QuickPinnedKeys)

        // Live: list + always-visible sections column; preview pane on (OwnTV Stage defaults).
        settings.setLiveView(SettingsRepository.LiveView.LIST)
        settings.setLiveLayout(SettingsRepository.LiveLayout.SEPARATE)
        settings.setLiveStageWidths(
            enabled = true,
            widths = tv.own.owntv.core.settings.LiveStageWidths.DEFAULT,
        )
        // Movies / Series: Separate layout so categories show without opening a sheet.
        settings.setVodLayout(SettingsRepository.VodLayout.SEPARATE)
        settings.setVodViewMode(SettingsRepository.VodViewMode.GRID)

        // Live TV — enable then hide the settings group from UI.
        settings.setTimeshiftEnabled(true)
        settings.setLiveLeftRightRewinds(true)

        // PiP on by default, then Watching & Recording is removed from UI.
        settings.setPipEnabled(true)
        pictureInPicture.setEnabled(true)

        // UI zoom / popup size default 120% (max 160%); font family fixed to bein via SYSTEM_SANS.
        settings.setUiZoomPercent(120)
        val fonts = settings.fontCustomization.first()
        settings.setFontCustomization(
            fonts.copy(
                mainFamily = AppFontFamily.SYSTEM_SANS,
                popupFamily = AppFontFamily.SYSTEM_SANS,
                popupSizePercent = 120,
            ),
        )

        // Home: hide every OwnTV row — Lamassu HomeScreen shows CPanel banners only.
        val profileId = settings.activeProfileId.first()
        if (profileId >= 0L) {
            settings.updateHomeConfig(profileId) { cfg ->
                cfg.copy(hidden = tv.own.owntv.core.model.HomeRow.entries.toSet())
            }
        }
    }

    private suspend fun ensureSingleLamassuProfile() {
        val name = context.getString(R.string.app_name)
        val existing = profileDao.observeAll().first()
        val keep = when {
            existing.isEmpty() -> {
                val id = profiles.create(
                    name = name,
                    avatarId = -1,
                    isKids = false,
                    pin = null,
                    fallbackName = name,
                )
                profileDao.getById(id)
            }
            else -> {
                val primary = existing.first()
                if (primary.name != name) {
                    profileDao.update(primary.copy(name = name))
                }
                existing.drop(1).forEach { profiles.delete(it) }
                profileDao.getById(primary.id) ?: primary
            }
        } ?: return

        if (keep.avatarPath.isNullOrBlank()) {
            applyLogoAvatar(keep.id)
        }
        settings.setActiveProfile(keep.id)
    }

    private suspend fun applyLogoAvatar(profileId: Long) {
        val profile = profileDao.getById(profileId) ?: return
        val out = File(context.cacheDir, "lamassu_profile_avatar.png")
        runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeResource(
                context.resources,
                R.drawable.lamassu_logo,
            ) ?: return@runCatching
            out.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            profiles.setCustomAvatar(profile, out)
        }
    }
}
