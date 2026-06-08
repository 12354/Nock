package app.nock.android.data

import android.content.Context
import android.content.res.Configuration
import app.nock.android.data.dao.GroupDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the default groups' names in sync with the app locale.
 *
 * Groups that originated from [SeedData] carry a stable [GroupEntity.seedKey]. When
 * the locale changes, we look up each seed group's known translations across all
 * supported locales — if the current name still matches any of them, it's an
 * untouched seed and we rewrite it to the active locale's translation. If the user
 * renamed it to something custom, the name doesn't match any known translation and
 * we leave it alone.
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

    suspend fun sync() {
        val groups = groupDao.getAll()
        if (groups.isEmpty()) return

        // For each seed key, collect (locale -> translated name) and the inverse (translated name -> key).
        val translations: Map<String, Map<Locale, String>> = seedData.groups.associate { sg ->
            sg.key to supportedLocales.associateWith { localized(it, sg.nameRes) }
        }
        val nameToKey: Map<String, String> = buildMap {
            translations.forEach { (key, byLocale) ->
                byLocale.values.forEach { name -> putIfAbsent(name, key) }
            }
        }

        groups.forEach { g ->
            val key = g.seedKey ?: nameToKey[g.name] ?: return@forEach
            // A group may carry a seedKey that isn't a SeedData seed (e.g. the
            // device-local "trips" group). Those have no translations to sync — skip them.
            val seed = seedData.groups.firstOrNull { it.key == key } ?: return@forEach
            val currentTranslation = ctx.getString(seed.nameRes)
            val knownNames = translations[key]?.values?.toSet().orEmpty()
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
