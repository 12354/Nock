package app.nock.android.data

import android.content.Context
import android.content.res.Configuration
import app.nock.android.R
import app.nock.android.data.dao.GroupDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the default groups' names in sync with the app locale.
 *
 * The default groups carry a stable [GroupEntity.seedKey]: the [SeedData] groups
 * created at install, plus the device-local "trips" group spun up on demand by the
 * calendar feature. When the locale changes, we look up each key's known
 * translations across all supported locales — if the current name still matches any
 * of them (or a legacy default), it's an untouched default and we rewrite it to the
 * active locale's translation. If the user renamed it to something custom, the name
 * doesn't match any known translation and we leave it alone.
 *
 * Also handles old installs whose `seedKey` is NULL by inferring the key from the
 * stored name.
 */
@Singleton
class SeedGroupLocaleSync @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val seedData: SeedData,
    private val groupDao: GroupDao,
) {

    private val supportedLocales = listOf(Locale("en"), Locale("de"))

    // Every seed key that should track the locale, mapped to its name resource. The
    // "trips" group isn't in SeedData (it's created on demand) but is localized the
    // same way.
    private val nameResByKey: Map<String, Int> = buildMap {
        seedData.groups.forEach { put(it.key, it.nameRes) }
        put("trips", R.string.trips_group_name)
    }

    // Earlier defaults that shipped as hardcoded literals. Treated as untouched so
    // existing installs migrate to the localized name on the next sync.
    private val legacyNames: Map<String, List<String>> = mapOf("trips" to listOf("Trips"))

    suspend fun sync() {
        val groups = groupDao.getAll()
        if (groups.isEmpty()) return

        // For each key, collect (locale -> translated name) and the inverse (translated name -> key).
        val translations: Map<String, Map<Locale, String>> = nameResByKey.mapValues { (_, nameRes) ->
            supportedLocales.associateWith { localized(it, nameRes) }
        }
        val nameToKey: Map<String, String> = buildMap {
            translations.forEach { (key, byLocale) ->
                byLocale.values.forEach { name -> putIfAbsent(name, key) }
            }
        }

        groups.forEach { g ->
            val key = g.seedKey ?: nameToKey[g.name] ?: return@forEach
            val nameRes = nameResByKey[key] ?: return@forEach
            val currentTranslation = ctx.getString(nameRes)
            val knownNames = translations[key]?.values.orEmpty().toSet() + legacyNames[key].orEmpty()
            val isUntouchedSeed = g.name in knownNames
            val needsBackfill = g.seedKey == null && key in translations
            val needsRename = isUntouchedSeed && g.name != currentTranslation
            if (needsBackfill || needsRename) {
                groupDao.update(
                    g.copy(
                        name = if (isUntouchedSeed) currentTranslation else g.name,
                        seedKey = key,
                    )
                )
            }
        }
    }

    private fun localized(locale: Locale, resId: Int): String {
        val config = Configuration(ctx.resources.configuration).apply { setLocale(locale) }
        return ctx.createConfigurationContext(config).resources.getString(resId)
    }
}
