package com.futo.platformplayer.fragment.mainactivity.main

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.constructs.TaskHandler
import com.futo.platformplayer.dp
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.SearchType
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateForYou
import com.futo.platformplayer.states.StateMeta
import com.futo.platformplayer.states.StatePlugins
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.views.FeedStyle
import com.futo.platformplayer.views.NoResultsView
import com.futo.platformplayer.views.adapters.ContentPreviewViewHolder
import com.futo.platformplayer.views.adapters.InsertedViewAdapterWithLoader
import com.futo.platformplayer.views.adapters.InsertedViewHolder
import com.futo.platformplayer.views.buttons.BigButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ForYouFragment : MainFragment() {
    override val isMainView : Boolean = true;
    override val hasBottomBar: Boolean get() = true;

    private var _view: ForYouView? = null;
    private var _cachedRecyclerData: FeedView.RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, GridLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>? = null;

    override fun onShownWithView(parameter: Any?, isBack: Boolean) {
        super.onShownWithView(parameter, isBack);
        _view?.onShown();
    }

    override fun onResume() {
        super.onResume()
        _view?.onResume();
    }

    override fun onPause() {
        super.onPause()
        _view?.onPause();
    }

    override fun onCreateMainView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = ForYouView(this, inflater, _cachedRecyclerData);
        _view = view;
        return view;
    }

    override fun onDestroyMainView() {
        super.onDestroyMainView();
        val view = _view;
        if (view != null) {
            _cachedRecyclerData = view.recyclerData;
            view.cleanup();
            _view = null;
        }
    }

    override fun onBackPressed(): Boolean {
        if (_view?.onBackPressed() == true)
            return true

        return super.onBackPressed()
    }

    fun setPreviewsEnabled(previewsEnabled: Boolean) {
        _view?.setPreviewsEnabled(previewsEnabled && Settings.instance.subscriptions.previewFeedItems);
    }

    fun reloadFeed() {
        _view?.reload();
    }

    @SuppressLint("ViewConstructor")
    class ForYouView : ContentFeedView<ForYouFragment> {
        override val shouldShowTimeBar: Boolean get() = Settings.instance.subscriptions.progressBar
        override val feedStyle: FeedStyle get() = Settings.instance.subscriptions.getSubscriptionsFeedStyle();

        private val _taskBuildFeed = TaskHandler<Boolean, IPager<IPlatformContent>>({ fragment.lifecycleScope }, { withRefresh ->
            StateForYou.instance.buildFeed(fragment.lifecycleScope, withRefresh);
        })
            .success { loadedResult(it); }
            .exception<Throwable> {
                Logger.w(TAG, "Failed to build For You feed.", it);
                finishRefreshLayoutLoader();
                setLoading(false);
                if (it !is CancellationException)
                    UIDialogs.showGeneralRetryErrorDialog(context, it.message ?: "", it, { loadResults(true) }, null, fragment);
            };

        constructor(fragment: ForYouFragment, inflater: LayoutInflater, cachedRecyclerData: RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, GridLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>? = null) : super(fragment, inflater, cachedRecyclerData) {
            StateSubscriptions.instance.onSubscriptionsChanged.subscribe(this) { _, _ ->
                StateForYou.instance.clear();
            };
            StateMeta.instance.onCreatorHidden.subscribe(this) { creatorUrl ->
                fragment.lifecycleScope.launch(Dispatchers.Main) { removeCreatorContent(creatorUrl) };
            };
            setPreviewsEnabled(Settings.instance.subscriptions.previewFeedItems);
        }

        fun onShown() {
            if (recyclerData.loadedFeedStyle != feedStyle || recyclerData.results.isEmpty() || StateForYou.instance.isStale()) {
                val cached = StateForYou.instance.getCachedPager();
                if (cached != null && !StateForYou.instance.isStale())
                    loadedResult(cached);
                else
                    loadResults(false);
            }
        }

        override fun cleanup() {
            super.cleanup();
            StateSubscriptions.instance.onSubscriptionsChanged.remove(this);
            StateMeta.instance.onCreatorHidden.remove(this);
        }

        override fun filterResults(results: List<IPlatformContent>): List<IPlatformContent> {
            return results.filter { !StateMeta.instance.isCreatorHidden(it.author.url) && !StateMeta.instance.isVideoHidden(it.url) };
        }

        private fun removeCreatorContent(creatorUrl: String) {
            for (i in recyclerData.results.indices.reversed()) {
                if (recyclerData.results[i].author.url == creatorUrl) {
                    recyclerData.results.removeAt(i);
                    recyclerData.adapter.notifyItemRemoved(recyclerData.adapter.childToParentPosition(i));
                }
            }
        }

        override fun reload() {
            StatePlugins.instance.clearUpdating(); //Fallback in case it doesnt clear, UI should be blocked.
            loadResults(true);
        }

        private fun loadResults(withRefresh: Boolean) {
            setLoading(true);
            _taskBuildFeed.run(withRefresh);
        }

        private fun loadedResult(pager: IPager<IPlatformContent>) {
            try {
                finishRefreshLayoutLoader();
                setLoading(false);
                setPager(pager);
                setEmptyPager(pager.getResults().isEmpty());
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to finish loading", e)
            }
        }

        override fun onRestoreCachedData(cachedData: RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, GridLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>) {
            super.onRestoreCachedData(cachedData);
            setEmptyPager(cachedData.results.isEmpty());
        }

        override fun getEmptyPagerView(): View? {
            val dp10 = 10.dp(resources);
            val dp30 = 30.dp(resources);
            if (StateSubscriptions.instance.getSubscriptions().isEmpty())
                return NoResultsView(context, "You have no subscriptions", "For You recommends content based on your subscriptions and watch history. Subscribe to some creators or import them from elsewhere.", R.drawable.ic_explore, listOf(
                    BigButton(context, "Search", "Search for creators in your enabled plugins", R.drawable.ic_creators) {
                        fragment.navigate<SuggestionsFragment>(SuggestionsFragmentData("", SearchType.CREATOR));
                    }.withMargin(dp10, dp30),
                    BigButton(context, "Import", "Import your subscriptions from another format", R.drawable.ic_move_up) {
                        val activity = StateApp.instance.context;
                        if (activity is MainActivity)
                            UIDialogs.showImportOptionsDialog(activity);
                    }.withMargin(dp10, dp30)
                ));
            return null;
        }
    }

    companion object {
        const val TAG = "ForYouFragment";

        fun newInstance() = ForYouFragment().apply {}
    }
}
