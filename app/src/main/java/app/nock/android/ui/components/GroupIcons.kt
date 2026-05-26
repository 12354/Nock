package app.nock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nock.android.domain.model.Group

// Maps seed icon names (see SeedData) to Material icons.
// Unknown names fall back to a generic Label icon.
fun groupIconFor(name: String?): ImageVector = when (name) {
    "Pets" -> Icons.Outlined.Pets
    "Medication" -> Icons.Outlined.Medication
    "FavoriteBorder", "Favorite" -> Icons.Outlined.FavoriteBorder
    "Home" -> Icons.Outlined.Home
    "Work" -> Icons.Outlined.Work
    "ShoppingBag" -> Icons.Outlined.ShoppingBag
    "FitnessCenter" -> Icons.Outlined.FitnessCenter
    "Restaurant" -> Icons.Outlined.Restaurant
    "DirectionsCar" -> Icons.Outlined.DirectionsCar
    "School" -> Icons.Outlined.School
    else -> Icons.Outlined.Label
}

/** Available picker icons for the group editor — mirrors the design's icon grid. */
val GroupIconChoices: List<Pair<String, ImageVector>> = listOf(
    "Pets" to Icons.Outlined.Pets,
    "Medication" to Icons.Outlined.Medication,
    "FavoriteBorder" to Icons.Outlined.FavoriteBorder,
    "Home" to Icons.Outlined.Home,
    "Work" to Icons.Outlined.Work,
    "ShoppingBag" to Icons.Outlined.ShoppingBag,
    "FitnessCenter" to Icons.Outlined.FitnessCenter,
    "Restaurant" to Icons.Outlined.Restaurant,
    "DirectionsCar" to Icons.Outlined.DirectionsCar,
    "School" to Icons.Outlined.School,
)

/** Circle with the group icon tinted in its color over a 22% tinted background. */
@Composable
fun GroupAvatar(group: Group, size: Dp = 40.dp) {
    val color = Color(group.color)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = groupIconFor(group.icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}
