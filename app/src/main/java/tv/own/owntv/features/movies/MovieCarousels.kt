package tv.own.owntv.features.movies

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.repository.SmartCategoriesRepository
import tv.own.owntv.features.shell.components.VodPosterArt
import tv.own.owntv.features.shell.components.vodRating
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.StageSegmented
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** (DPTV v2.9.31) How a movie category's grid is ordered. */
internal enum class SmartSort { POPULAR, ALPHA, RATING, RELEASE }

internal fun List<SmartCategoriesRepository.Film>.sortedFor(sort: SmartSort): List<SmartCategoriesRepository.Film> = when (sort) {
    SmartSort.POPULAR -> this
    SmartSort.ALPHA -> sortedBy { it.movie.name.lowercase() }
    SmartSort.RATING -> sortedWith(compareByDescending<SmartCategoriesRepository.Film> { it.rating ?: -1.0 }.thenBy { it.movie.name.lowercase() })
    SmartSort.RELEASE -> sortedWith(compareByDescending<SmartCategoriesRepository.Film> { it.release.orEmpty() }.thenBy { it.movie.name.lowercase() })
}

/** How many films a carousel shows before its View more tile. */
internal const val CarouselFilms = 10

/** Callbacks shared by the carousels and a category's grid. */
internal class MovieTileActions(
    val posterOf: (MovieEntity) -> String?,
    val progressOf: (MovieEntity) -> Float?,
    val watchedOf: (MovieEntity) -> Boolean,
    val onFocus: (MovieEntity) -> Unit,
    val onPlay: (MovieEntity) -> Unit,
    val onMenu: (MovieEntity) -> Unit,
    /** ◀ at the start of a row: the categories sheet. */
    val onLeftEdge: () -> Unit,
)

/**
 * (DPTV v2.9.31) Movies' landing: one carousel per DPTV-Server movie category, its first
 * [CarouselFilms] films and a View more tile that opens the whole category. The focused film fills the
 * hero above, as browsing a category does. Across a row the focused poster stays in the middle; down
 * the rows, the focused row comes to the top.
 */
@Composable
internal fun MovieCarousels(
    categories: List<SmartCategoriesRepository.Category>,
    start: Dp,
    end: Dp,
    firstFocus: FocusRequester,
    actions: MovieTileActions,
    onViewMore: (SmartCategoriesRepository.Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    val headerPx = with(LocalDensity.current) { 52.mpx.toPx() }
    val rowSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - (containerSize - size) / 2f
        }
    }
    val columnSpec = remember(headerPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - headerPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides columnSpec) {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(top = 6.mpx, bottom = 40.mpx),
            verticalArrangement = Arrangement.spacedBy(18.mpx),
        ) {
            itemsIndexed(categories, key = { _, c -> c.id }) { rowIndex, category ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = start, bottom = 12.mpx), verticalAlignment = Alignment.Bottom) {
                        Text(
                            category.name, style = stageText(24, 800), color = StageColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "  ·  ${category.films.size}", style = stageText(18, 500), color = StageColors.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(20.mpx),
                            contentPadding = PaddingValues(start = start, end = end, top = 10.mpx, bottom = 14.mpx),
                            modifier = Modifier.fillMaxWidth().focusRestorer().focusGroup(),
                        ) {
                            val shown = category.films.take(CarouselFilms)
                            itemsIndexed(shown, key = { _, f -> f.movie.id }) { index, film ->
                                MovieTile(
                                    film = film, width = 160.mpx, height = 240.mpx, actions = actions,
                                    modifier = Modifier
                                        .then(if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                        .onPreviewKeyEvent { e ->
                                            index == 0 && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft &&
                                                run { actions.onLeftEdge(); true }
                                        },
                                )
                            }
                            item(key = "more") {
                                StagePoster(
                                    title = stringResource(R.string.movies_view_more),
                                    onClick = { onViewMore(category) },
                                    width = 160.mpx, height = 240.mpx,
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            OwnTVIcon(OwnTVIcon.CHEVRON, StageColors.Text, Modifier.size(44.mpx))
                                            Spacer(Modifier.height(10.mpx))
                                            Text(
                                                category.films.size.toString(), style = stageText(20, 700), color = StageColors.Muted,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** (DPTV v2.9.31) A whole movie category, after View more: its films in a grid, ordered by [sort]. */
@Composable
internal fun SmartCategoryGrid(
    category: SmartCategoriesRepository.Category,
    sort: SmartSort,
    onSort: (SmartSort) -> Unit,
    columns: Int,
    start: Dp,
    end: Dp,
    firstFocus: FocusRequester,
    actions: MovieTileActions,
    modifier: Modifier = Modifier,
) {
    val films = remember(category, sort) { category.films.sortedFor(sort) }
    val labels = listOf(
        stringResource(R.string.movies_sort_popular),
        stringResource(R.string.settings_sort_alpha),
        stringResource(R.string.settings_sort_rating),
        stringResource(R.string.movies_sort_release_date),
    )
    Column(modifier) {
        Row(Modifier.padding(start = start, end = end).fillMaxWidth().focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            StageSegmented(
                options = labels,
                selected = sort.ordinal,
                onSelect = { i -> onSort(SmartSort.entries[i]) },
            )
        }
        Spacer(Modifier.height(14.mpx))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            horizontalArrangement = Arrangement.spacedBy(26.mpx),
            verticalArrangement = Arrangement.spacedBy(30.mpx),
            contentPadding = PaddingValues(start = start, end = end, top = 14.mpx, bottom = 24.mpx),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            itemsIndexed(films, key = { _, f -> f.movie.id }) { index, film ->
                MovieTile(
                    film = film, width = 196.mpx, height = 294.mpx, actions = actions,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onPreviewKeyEvent { e ->
                            index % columns.coerceAtLeast(1) == 0 && e.type == KeyEventType.KeyDown &&
                                e.key == Key.DirectionLeft && run { actions.onLeftEdge(); true }
                        },
                )
            }
        }
    }
}

@Composable
private fun MovieTile(
    film: SmartCategoriesRepository.Film,
    width: Dp,
    height: Dp,
    actions: MovieTileActions,
    modifier: Modifier = Modifier,
) {
    val movie = film.movie
    StagePoster(
        title = movie.name,
        rating = film.rating?.takeIf { it > 0 }?.let(::vodRating),
        width = width, height = height,
        progress = actions.progressOf(movie),
        watched = actions.watchedOf(movie),
        onClick = { actions.onPlay(movie) },
        onLongClick = { actions.onMenu(movie) },
        modifier = modifier
            .onFocusChanged { if (it.isFocused) actions.onFocus(movie) }
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.Menu && run { actions.onMenu(movie); true }
            },
    ) { VodPosterArt(actions.posterOf(movie), OwnTVIcon.MOVIES) }
}
