package com.example.groceriesapp.feature.recipes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A recipe's picture: a locally stored photo (user/AI) when one exists, else the bundled asset
 * photo, else a collage of the main ingredients' icons. [imageVersion] exists so callers can bump
 * it after attaching a new photo for the same recipe id.
 */
@Composable
fun RecipeImage(
    recipe: Recipe,
    imagesDir: File?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    imageVersion: Int = 0,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, recipe.id, recipe.imageRef, imageVersion) {
        value = withContext(Dispatchers.IO) {
            val localFile = imagesDir?.let { File(it, "${recipe.id}.webp") }
            when {
                localFile?.isFile == true ->
                    BitmapFactory.decodeFile(localFile.path)?.asImageBitmap()
                recipe.imageRef?.startsWith(ASSET_PREFIX) == true -> runCatching {
                    context.assets.open(recipe.imageRef.removePrefix(ASSET_PREFIX)).use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }.getOrNull()
                else -> null
            }
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = "Photo of ${recipe.title}",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        // The collage is generated from the ingredient list the screen already shows, so it stays
        // decorative rather than repeating that list to a screen reader.
        IngredientCollage(recipe = recipe, iconSize = iconSize, modifier = modifier)
    }
}

@Composable
private fun IngredientCollage(
    recipe: Recipe,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val icons = recipe.ingredients
        .filter { !it.optional }
        .ifEmpty { recipe.ingredients }
        .map { resolveIconRes(it.foodConceptId, null, it.displayName) }
        .distinct()
        .take(4)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icons.chunked(2).forEach { rowIcons ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowIcons.forEach { iconRes ->
                        // Food icons are full-colour vectors; Image keeps the artwork untinted.
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
        }
    }
}

private const val ASSET_PREFIX = "asset:"
