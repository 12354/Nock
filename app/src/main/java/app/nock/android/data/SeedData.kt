package app.nock.android.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.nock.android.data.entity.GroupEntity
import javax.inject.Inject
import javax.inject.Singleton

data class SeedGroup(val name: String, val icon: String, val color: Int)

@Singleton
class SeedData @Inject constructor() {
    val groups: List<SeedGroup> = listOf(
        SeedGroup("Pets", "Pets", Color(0xFFFFB070).toArgb()),
        SeedGroup("Meds", "Medication", Color(0xFFFF6B6B).toArgb()),
        SeedGroup("Self-care", "FavoriteBorder", Color(0xFFB388FF).toArgb()),
        SeedGroup("Household", "Home", Color(0xFF80CBC4).toArgb()),
        SeedGroup("Work", "Work", Color(0xFF8AB4F8).toArgb()),
        SeedGroup("Errands", "ShoppingBag", Color(0xFFF6BF26).toArgb()),
    )

    fun toEntities(): List<GroupEntity> = groups.mapIndexed { i, g ->
        GroupEntity(
            id = 0,
            name = g.name,
            color = g.color,
            icon = g.icon,
            overrideChainJson = null,
            overrideRepeatIntervalMs = null,
            pausedUntilMs = null,
            telegramSilentMirror = false,
            sortIndex = i
        )
    }
}
