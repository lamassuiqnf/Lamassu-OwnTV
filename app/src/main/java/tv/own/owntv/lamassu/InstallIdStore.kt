package tv.own.owntv.lamassu

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

class InstallIdStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        val existing = prefs.getString(KEY, null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }

    fun deviceFingerprint(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()
        if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            return "aid:$androidId"
        }
        return "iid:${getOrCreate()}"
    }

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        private const val PREFS = "lamassu_install_id"
        private const val KEY = "install_id"
    }
}
