package com.issinoho.radiotimes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browsing one Plex server: libraries (with tvdinner's "On Deck" first),
 * then a poster grid, shows → seasons → an episode list. OK drills in or
 * plays; Back goes up a level, and out at the top.
 */
class PlexActivity : ComponentActivity() {
    /** One level of the drill-down. `focus` is restored when coming back up to it. */
    private class Level(val node: PlexNode?, val title: String) {
        var items by mutableStateOf<List<PlexNode>?>(null)
        var detail by mutableStateOf<PlexDetail?>(null)
        var error by mutableStateOf<String?>(null)
        var query by mutableStateOf("")
        var focus = 0
        val isSearch get() = node?.kind == SEARCH
        val isDetail get() = node != null && !node.container && !isSearch
    }

    private val stack = mutableStateListOf<Level>()
    private var server = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = intent.getIntExtra(EXTRA_SERVER, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (stack.isEmpty()) push(Level(null, name))

        setContent {
            RadiotimesTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.background) {
                    BackHandler {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
                    }
                    val level = stack.lastOrNull() ?: return@Surface
                    val detail = level.detail
                    when {
                        level.isDetail && detail != null -> PlexDetailScreen(detail) { fromStart ->
                            startActivity(VodPlayerActivity.intent(this@PlexActivity, server, detail.json, fromStart))
                        }
                        else -> Browser(path = stack.map { it.title }, level = level, onOpen = ::open, onSearch = ::search)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the player: watched/in-progress state has moved on.
        stack.lastOrNull()?.takeIf { it.items != null || it.detail != null }?.let { reload(it) }
    }

    private fun push(level: Level) {
        stack.add(level)
        reload(level)
    }

    private fun reload(level: Level) {
        if (level.isSearch && level.query.isBlank()) {
            level.items = emptyList()
            return
        }
        lifecycleScope.launch {
            val json = when {
                level.isSearch -> Engine.callPlex("plex_search", server, level.query)
                level.isDetail -> Engine.callPlex("plex_resolve", server, level.node!!.json)
                else -> Engine.callPlex("plex_children", server, level.node?.json)
            }
            val error = bridgeError(json)
            if (error != null) {
                level.error = redactTokens(error)
                Log.w(TAG, "plex | ${level.title}: ${level.error}")
                return@launch
            }
            level.error = null
            when {
                level.isDetail -> level.detail = PlexDetail(json)
                level.node == null -> level.items = listOf(searchNode) + parsePlexNodes(json)
                else -> level.items = parsePlexNodes(json)
            }
        }
    }

    private fun search(query: String) {
        val level = stack.lastOrNull()?.takeIf { it.isSearch } ?: return
        level.query = query.trim()
        level.items = null
        reload(level)
    }

    /** Containers and Search open a list; a movie or episode opens its details page. */
    private fun open(node: PlexNode, index: Int) {
        stack.lastOrNull()?.focus = index
        push(Level(node, node.title))
    }

    companion object {
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_NAME = "name"
        private const val SEARCH = "search"

        /** A tile at the top of the libraries, not a Plex node. */
        private val searchNode = PlexNode(
            ratingKey = SEARCH, title = "Search", kind = SEARCH, container = true,
            subtitle = "Movies, shows and episodes", thumb = null, watched = false, progress = null,
            year = null, seriesTitle = null, seasonThumb = null, json = "{}",
        )

        fun intent(context: Context, server: PlexServer): Intent =
            Intent(context, PlexActivity::class.java)
                .putExtra(EXTRA_SERVER, server.index)
                .putExtra(EXTRA_NAME, server.name)
    }

    @Composable
    private fun Browser(path: List<String>, level: Level, onOpen: (PlexNode, Int) -> Unit, onSearch: (String) -> Unit) {
        var focused by remember(level) { mutableStateOf<PlexNode?>(null) }
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plex", color = Palette.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(
                    path.joinToString("  ›  "),
                    color = Palette.text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(clockText(rememberNow()), color = Palette.text, fontSize = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (level.isSearch) {
                SearchBox(level.query, onSearch)
                Spacer(Modifier.height(8.dp))
            }
            FocusedSummary(focused, Modifier.fillMaxWidth().height(78.dp))
            Spacer(Modifier.height(8.dp))
            val items = level.items
            when {
                level.isSearch && level.query.isBlank() -> Message("Type a title, then press Search on the keyboard")
                level.isSearch && items != null && items.isEmpty() -> Message("Nothing found for \u201c${level.query}\u201d")
                level.error != null && items == null -> Message("Couldn't load: ${level.error}")
                items == null -> Message("Loading…")
                items.isEmpty() -> Message("Nothing here")
                level.node == null -> Tiles(items, level.focus, { focused = it }, onOpen)
                level.node.kind == "season" -> Episodes(items, level.focus, { focused = it }, onOpen)
                else -> Posters(items, level.focus, { focused = it }, onOpen)
            }
        }
    }
}

/**
 * The one place focus may start on a text field: here typing is the
 * point. OK opens the keyboard; its Search key runs the search.
 */
@Composable
private fun SearchBox(initial: String, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val requester = remember { FocusRequester() }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Search") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (text.isNotBlank()) onSearch(text) }),
        modifier = Modifier.width(600.dp).focusRequester(requester),
    )
    LaunchedEffect(Unit) { if (initial.isBlank()) runCatching { requester.requestFocus() } }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.dim, fontSize = 18.sp) }
}

@Composable
private fun FocusedSummary(node: PlexNode?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        node ?: return@Column
        // An episode's subtitle already names the show ("Show · S01E04 · …").
        Text(
            node.title,
            color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            node.subtitle?.let { Text(it, color = Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (node.watched) Text("   ✓ watched", color = Palette.accent, fontSize = 15.sp)
        }
        node.progress?.let { ProgressBar(it, Modifier.width(240.dp)) }
    }
}

/** Focus goes to `index` once that item is laid out (first visit: 0; coming back up: where you were). */
@Composable
private fun rememberRestoredFocus(index: Int): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(60) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    return requester
}

@Composable
private fun Modifier.card(focused: Boolean): Modifier =
    this
        .clip(RoundedCornerShape(8.dp))
        .border(3.dp, if (focused) Palette.text else Color.Transparent, RoundedCornerShape(8.dp))

@Composable
private fun Tiles(items: List<PlexNode>, focus: Int, onFocus: (PlexNode) -> Unit, onOpen: (PlexNode, Int) -> Unit) {
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = focus)
    val requester = rememberRestoredFocus(focus)
    LazyVerticalGrid(
        columns = GridCells.Fixed(4), state = state,
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        itemsIndexed(items, key = { _, n -> n.kind + n.ratingKey }) { i, node ->
            var hasFocus by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .height(72.dp)
                    .then(if (i == focus) Modifier.focusRequester(requester) else Modifier)
                    .card(hasFocus)
                    .background(if (hasFocus) Palette.focus else Palette.surface)
                    .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocus(node) }
                    .clickable { onOpen(node, i) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(node.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    node.subtitle?.let { Text(it, color = Palette.dim, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun Posters(items: List<PlexNode>, focus: Int, onFocus: (PlexNode) -> Unit, onOpen: (PlexNode, Int) -> Unit) {
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = focus)
    val requester = rememberRestoredFocus(focus)
    LazyVerticalGrid(
        columns = GridCells.Fixed(7), state = state,
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        itemsIndexed(items, key = { _, n -> n.kind + n.ratingKey }) { i, node ->
            var hasFocus by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .then(if (i == focus) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocus(node) }
                    .clickable { onOpen(node, i) },
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).card(hasFocus).background(Palette.surface)) {
                    val image = if (node.isEpisode) node.seasonThumb ?: node.thumb else node.thumb
                    if (image != null) {
                        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(node.title, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    }
                    if (node.watched) {
                        Text(
                            "✓", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(10.dp))
                                .background(Palette.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    node.progress?.let {
                        ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                    }
                }
                Text(
                    node.title, color = if (hasFocus) Palette.text else Palette.dim, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Episodes(items: List<PlexNode>, focus: Int, onFocus: (PlexNode) -> Unit, onOpen: (PlexNode, Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = focus)
    val requester = rememberRestoredFocus(focus)
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(items, key = { _, n -> n.ratingKey }) { i, node ->
            var hasFocus by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (i == focus) Modifier.focusRequester(requester) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (hasFocus) Palette.focus else Color.Transparent)
                    .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocus(node) }
                    .clickable { onOpen(node, i) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surface)) {
                    node.thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    node.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(5.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(node.title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    node.subtitle?.let { Text(it, color = Palette.dim, fontSize = 14.sp, maxLines = 1) }
                }
                if (node.watched) Text("✓", color = Palette.accent, fontSize = 18.sp)
            }
        }
    }
}
