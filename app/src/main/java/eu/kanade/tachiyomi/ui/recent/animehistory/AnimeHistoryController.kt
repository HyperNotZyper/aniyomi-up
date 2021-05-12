package eu.kanade.tachiyomi.ui.recent.animehistory

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import dev.chrisbanes.insetter.applyInsetter
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.AnimeHistory
import eu.kanade.tachiyomi.databinding.AnimeHistoryControllerBinding
import eu.kanade.tachiyomi.source.AnimeSourceManager
import eu.kanade.tachiyomi.source.model.toEpisodeInfo
import eu.kanade.tachiyomi.ui.anime.AnimeController
import eu.kanade.tachiyomi.ui.anime.episode.EpisodeItem
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.source.browse.ProgressItem
import eu.kanade.tachiyomi.ui.watcher.WatcherActivity
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import reactivecircus.flowbinding.appcompat.queryTextChanges
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Fragment that shows recently read anime.
 */
class AnimeHistoryController :
    NucleusController<AnimeHistoryControllerBinding, AnimeHistoryPresenter>(),
    RootController,
    FlexibleAdapter.OnUpdateListener,
    FlexibleAdapter.EndlessScrollListener,
    AnimeHistoryAdapter.OnRemoveClickListener,
    AnimeHistoryAdapter.OnResumeClickListener,
    AnimeHistoryAdapter.OnItemClickListener,
    RemoveAnimeHistoryDialog.Listener {

    private val db: DatabaseHelper by injectLazy()

    /**
     * Adapter containing the recent anime.
     */
    var adapter: AnimeHistoryAdapter? = null
        private set

    /**
     * Endless loading item.
     */
    private var progressItem: ProgressItem? = null

    /**
     * Search query.
     */
    private var query = ""

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_recent_manga)
    }

    override fun createPresenter(): AnimeHistoryPresenter {
        return AnimeHistoryPresenter()
    }

    override fun createBinding(inflater: LayoutInflater) = AnimeHistoryControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        binding.recycler.applyInsetter {
            type(navigationBars = true) {
                padding()
            }
        }

        // Initialize adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        adapter = AnimeHistoryAdapter(this@AnimeHistoryController)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter
        adapter?.fastScroller = binding.fastScroller
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    /**
     * Populate adapter with chapters
     *
     * @param animeAnimeHistory list of anime history
     */
    fun onNextAnime(animeAnimeHistory: List<AnimeHistoryItem>, cleanBatch: Boolean = false) {
        if (adapter?.itemCount ?: 0 == 0 || cleanBatch) {
            resetProgressItem()
        }
        if (cleanBatch) {
            adapter?.updateDataSet(animeAnimeHistory)
        } else {
            adapter?.onLoadMoreComplete(animeAnimeHistory)
        }
    }

    /**
     * Safely error if next page load fails
     */
    fun onAddPageError(error: Throwable) {
        adapter?.onLoadMoreComplete(null)
        adapter?.endlessTargetCount = 1
    }

    override fun onUpdateEmptyView(size: Int) {
        if (size > 0) {
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(R.string.information_no_recent_manga)
        }
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        progressItem = ProgressItem()
        adapter?.endlessTargetCount = 0
        adapter?.setEndlessScrollListener(this, progressItem!!)
    }

    override fun onLoadMore(lastPosition: Int, currentPage: Int) {
        val view = view ?: return
        if (BackupRestoreService.isRunning(view.context.applicationContext)) {
            onAddPageError(Throwable())
            return
        }
        val adapter = adapter ?: return
        presenter.requestNext(adapter.itemCount, query)
    }

    override fun noMoreLoad(newItemsSize: Int) {}

    override fun onResumeClick(position: Int) {
        val activity = activity ?: return
        val (anime, chapter, _) = (adapter?.getItem(position) as? AnimeHistoryItem)?.mch ?: return

        val nextEpisode = presenter.getNextEpisode(chapter, anime)
        if (nextEpisode != null) {
            val source = Injekt.get<AnimeSourceManager>().getOrStub(anime.source)
            val link = runBlocking {
                return@runBlocking suspendCoroutine<String> { continuation ->
                    var link: String
                    launchIO {
                        try {
                            link = source.getEpisodeLink(nextEpisode.toEpisodeInfo())
                            continuation.resume(link)
                        } catch (e: Throwable) {
                            withUIContext { throw e }
                        }
                    }
                }
            }
            val episodeList: List<EpisodeItem> = Collections.emptyList()
            val newIntent = WatcherActivity.newIntent(activity, anime, nextEpisode, episodeList, link)
            startActivity(newIntent)
        } else {
            activity.toast(R.string.no_next_chapter)
        }
    }

    override fun onRemoveClick(position: Int) {
        val (anime, _, animehistory) = (adapter?.getItem(position) as? AnimeHistoryItem)?.mch ?: return
        RemoveAnimeHistoryDialog(this, anime, animehistory).showDialog(router)
    }

    override fun onItemClick(position: Int) {
        val anime = (adapter?.getItem(position) as? AnimeHistoryItem)?.mch?.anime ?: return
        router.pushController(AnimeController(anime).withFadeTransaction())
    }

    override fun removeAnimeHistory(anime: Anime, animehistory: AnimeHistory, all: Boolean) {
        if (all) {
            // Reset last read of chapter to 0L
            presenter.removeAllFromAnimeHistory(anime.id!!)
        } else {
            // Remove all chapters belonging to anime from library
            presenter.removeFromAnimeHistory(animehistory)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.anime_history, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }
        searchView.queryTextChanges()
            .filter { router.backstack.lastOrNull()?.controller() == this }
            .onEach {
                query = it.toString()
                presenter.updateList(query)
            }
            .launchIn(viewScope)

        // Fixes problem with the overflow icon showing up in lieu of search
        searchItem.fixExpand(
            onExpand = { invalidateMenuOnExpand() }
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_anime_history -> {
                val ctrl = ClearAnimeHistoryDialogController()
                ctrl.targetController = this@AnimeHistoryController
                ctrl.showDialog(router)
            }
        }

        return super.onOptionsItemSelected(item)
    }

    class ClearAnimeHistoryDialogController : DialogController() {
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .message(R.string.clear_history_confirmation)
                .positiveButton(android.R.string.ok) {
                    (targetController as? AnimeHistoryController)?.clearAnimeHistory()
                }
                .negativeButton(android.R.string.cancel)
        }
    }

    private fun clearAnimeHistory() {
        db.deleteHistory().executeAsBlocking()
        activity?.toast(R.string.clear_history_completed)
    }
}
