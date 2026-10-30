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
import tv.own.owntv.core.theme.ThemeMode

/**
 * One-time Lamassu shell defaults: brand accent, hidden nav sections, single profile with logo.
 */
class LamassuBootstrap(
    private val context: Context,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val profiles: ProfileManager,
    private val homeContent: HomeContentRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                settings.setThemeMode(ThemeMode.DARK)
                settings.setCustomAccent(LamassuBrand.AccentHex)
                settings.setNavMenuMode(SettingsRepository.NavMenuMode.STATIC)
                settings.setNavMenuHidden(LamassuBrand.HiddenNavSections)
                ensureSingleLamassuProfile()
                homeContent.refresh()
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
