package app.nock.android.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.nock.android.R
import app.nock.android.data.entity.GroupEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SeedGroup(
    val key: String,
    @StringRes val nameRes: Int,
    val icon: String,
    val color: Int,
)

@Singleton
class SeedData @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    val groups: List<SeedGroup> = listOf(
        SeedGroup("pets", R.string.seed_group_pets, "Pets", Color(0xFFFFB070).toArgb()),
        SeedGroup("meds", R.string.seed_group_meds, "Medication", Color(0xFFFF6B6B).toArgb()),
        SeedGroup("selfcare", R.string.seed_group_selfcare, "FavoriteBorder", Color(0xFFB388FF).toArgb()),
        SeedGroup("household", R.string.seed_group_household, "Home", Color(0xFF80CBC4).toArgb()),
        SeedGroup("work", R.string.seed_group_work, "Work", Color(0xFF8AB4F8).toArgb()),
        SeedGroup("errands", R.string.seed_group_errands, "ShoppingBag", Color(0xFFF6BF26).toArgb()),
    )

    fun toEntities(): List<GroupEntity> = groups.mapIndexed { i, g ->
        GroupEntity(
            id = 0,
            name = ctx.getString(g.nameRes),
            color = g.color,
            icon = g.icon,
            overrideChainJson = null,
            overrideRepeatIntervalMs = null,
            pausedUntilMs = null,
            telegramSilentMirror = false,
            sortIndex = i,
            seedKey = g.key,
        )
    }
}
