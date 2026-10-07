package com.futo.platformplayer.states

import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.video.IPlatformVideo
import com.futo.platformplayer.api.media.structures.AdhocPager
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.getNowDiffMinutes
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.HistoryVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds the "For You" feed: subscription content ranked by how much the user engages with each
 * channel, how fresh it is and how well it performs relative to the channel's other uploads,
 * mixed with discovery content taken from recommendations of recently watched videos.
 */
class StateForYou {
    private val _discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob());
    private val _lock = Object();

    private var _feed: List<IPlatformContent> = listOf();
    private var _feedTime: OffsetDateTime = OffsetDateTime.MIN;

    private var _discovery: List<IPlatformVideo> = listOf();
    private var _discoveryTime: OffsetDateTime = OffsetDateTime.MIN;

    fun isStale(): Boolean = synchronized(_lock) { _feed.isEmpty() || _feedTime.getNowDiffMinutes() > FEED_STALE_MINUTES };

    fun getCachedPager(): IPager<IPlatformContent>? = synchronized(_lock) {
        if (_feed.isEmpty()) null else createPager(_feed);
    }

    fun clear() {
        synchronized(_lock) {
            _feed = listOf();
            _feedTime = OffsetDateTime.MIN;
            _discovery = listOf();
            _discoveryTime = OffsetDateTime.MIN;
        }
    }

    /**
     * Rebuilds the feed. With [withRefresh], subscriptions are refetched and discovery recommendations are requested again.
     */
    suspend fun buildFeed(scope: CoroutineScope, withRefresh: Boolean): IPager<IPlatformContent> {
        val now = OffsetDateTime.now();
        val history = StateHistory.instance.getRecentHistory(now.minusDays(HISTORY_DAYS), 1000);

        val candidates = LinkedHashMap<String, IPlatformContent>();
        if (withRefresh) {
            try {
                val livePager = StateSubscriptions.instance.getGlobalSubscriptionFeed(scope, true);
                //Fresh items are cached asynchronously, so read the newest ones directly. Limited, deeper pages hit the network.
                collect(livePager, candidates, now, MAX_SUBSCRIPTION_CANDIDATES, MAX_LIVE_PAGES);
            } catch (e: Throwable) {
                Logger.w(TAG, "Failed to refresh subscriptions, falling back to cache", e);
            }
        }
        collect(StateCache.instance.getSubscriptionCachePager(), candidates, now, MAX_SUBSCRIPTION_CANDIDATES, MAX_CACHE_PAGES);

        val ranked = rankSubscriptionContent(candidates.values.toList(), history, now);

        val discovery = getDiscovery(history, withRefresh, ranked.map { it.url }.toHashSet());
        val feed = interleave(ranked, discovery);

        Logger.i(TAG, "Built For You feed (${ranked.size} subscription items, ${discovery.size} discovery items)");
        synchronized(_lock) {
            _feed = feed;
            _feedTime = OffsetDateTime.now();
        }
        return createPager(feed);
    }

    private fun collect(pager: IPager<IPlatformContent>, into: MutableMap<String, IPlatformContent>, now: OffsetDateTime, max: Int, maxPages: Int) {
        val minDate = now.minusDays(MAX_AGE_DAYS);
        var pages = 0;
        while (true) {
            var reachedEnd = false;
            for (item in pager.getResults()) {
                val date = item.datetime;
                if (date != null && date < minDate) {
                    reachedEnd = true;
                    continue;
                }
                if (item !is IPlatformVideo || item.isShort)
                    continue;
                if (date != null && date.isAfter(now.plusMinutes(5)))
                    continue;
                into.putIfAbsent(item.url, item);
            }
            pages++;
            if (reachedEnd || into.size >= max || pages >= maxPages || !pager.hasMorePages())
                break;
            pager.nextPage();
        }
    }

    private fun rankSubscriptionContent(items: List<IPlatformContent>, history: List<HistoryVideo>, now: OffsetDateTime): List<IPlatformContent> {
        val random = Random(System.currentTimeMillis());

        //Channel affinity: lifetime playback tracked on the subscription plus recent watch history
        val recentWatches = history.groupingBy { it.video.author.url }.eachCount();
        val rawAffinity = HashMap<String, Double>();
        for (item in items) {
            val url = item.author.url;
            if (rawAffinity.containsKey(url))
                continue;
            val sub = StateSubscriptions.instance.getSubscription(url);
            val playbackMinutes = (sub?.playbackSeconds ?: 0) / 60.0;
            val views = (sub?.playbackViews ?: 0).toDouble();
            val recent = (recentWatches[url] ?: 0).toDouble();
            rawAffinity[url] = ln(1.0 + playbackMinutes + views * 5.0 + recent * 10.0);
        }
        val maxAffinity = rawAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0;

        //Performance relative to channel: view velocity compared to the channel's median velocity
        val velocities = HashMap<String, Double>();
        for (item in items) {
            val v = velocity(item, now) ?: continue;
            velocities[item.url] = v;
        }
        val channelMedians = items.groupBy { it.author.url }.mapValues { (_, channelItems) ->
            val sorted = channelItems.mapNotNull { velocities[it.url] }.sorted();
            if (sorted.isEmpty()) null else sorted[sorted.size / 2];
        };

        val scored = items.mapNotNull { item ->
            val video = item as IPlatformVideo;
            val position = StateHistory.instance.getHistoryPosition(item.url);
            if (video.duration > 0 && StateHistory.instance.isHistoryWatched(item.url, video.duration))
                return@mapNotNull null;

            val ageHours = item.datetime?.let { max(0.0, (now.toEpochSecond() - it.toEpochSecond()) / 3600.0) } ?: (MAX_AGE_DAYS * 24.0);
            val freshness = 0.15 + 0.85 * exp(-ageHours / FRESHNESS_TAU_HOURS);

            val affinity = 0.35 + 0.65 * ((rawAffinity[item.author.url] ?: 0.0) / maxAffinity);

            val median = channelMedians[item.author.url];
            val velocity = velocities[item.url];
            val performance = if (median != null && velocity != null && median > 0)
                1.0 + 0.25 * log2(velocity / median).coerceIn(-1.0, 1.5)
            else 1.0;

            var score = freshness * affinity * performance * random.nextDouble(0.85, 1.15);
            if (video.isLive)
                score *= 1.2;
            if (position > 0)
                score *= 0.7;
            Pair(item, score);
        };

        return diversify(scored);
    }

    private fun velocity(item: IPlatformContent, now: OffsetDateTime): Double? {
        val video = item as? IPlatformVideo ?: return null;
        if (video.viewCount <= 0)
            return null;
        val date = item.datetime ?: return null;
        val ageHours = max(0.0, (now.toEpochSecond() - date.toEpochSecond()) / 3600.0);
        return video.viewCount / sqrt(ageHours + 2.0);
    }

    /**
     * Greedily picks the best remaining item while penalizing authors that were already picked,
     * so a single prolific channel cannot dominate the top of the feed.
     */
    private fun <T : IPlatformContent> diversify(scored: List<Pair<T, Double>>): List<T> {
        val remaining = scored.sortedByDescending { it.second }.toMutableList();
        val picked = HashMap<String, Int>();
        val result = ArrayList<T>(remaining.size);
        while (remaining.isNotEmpty()) {
            var bestIndex = 0;
            var bestScore = Double.NEGATIVE_INFINITY;
            for (i in remaining.indices) {
                val (item, score) = remaining[i];
                //Sorted by raw score, nothing further down can beat the current best after penalties
                if (score <= bestScore)
                    break;
                val adjusted = score * AUTHOR_REPEAT_PENALTY.pow(picked[item.author.url] ?: 0);
                if (adjusted > bestScore) {
                    bestScore = adjusted;
                    bestIndex = i;
                }
            }
            val (item, _) = remaining.removeAt(bestIndex);
            picked[item.author.url] = (picked[item.author.url] ?: 0) + 1;
            result.add(item);
        }
        return result;
    }

    private suspend fun getDiscovery(history: List<HistoryVideo>, withRefresh: Boolean, excludeUrls: Set<String>): List<IPlatformVideo> {
        val cached = synchronized(_lock) {
            if (!withRefresh && _discovery.isNotEmpty() && _discoveryTime.getNowDiffMinutes() < DISCOVERY_STALE_MINUTES) _discovery else null;
        };
        val discovery = cached ?: fetchDiscovery(history).also {
            synchronized(_lock) {
                _discovery = it;
                _discoveryTime = OffsetDateTime.now();
            }
        };

        //Re-filter cached results, user may have watched or subscribed since they were fetched
        return discovery.filter { isDiscoveryCandidate(it) && !excludeUrls.contains(it.url) };
    }

    private suspend fun fetchDiscovery(history: List<HistoryVideo>): List<IPlatformVideo> {
        //Seeds: recently watched videos the user engaged with, at most one per channel
        val seeds = history
            .filter { it.position >= 60 || (it.video.duration > 0 && it.position >= it.video.duration * 0.3) }
            .distinctBy { it.video.author.url }
            .take(MAX_DISCOVERY_SEEDS);
        if (seeds.isEmpty())
            return listOf();

        val now = OffsetDateTime.now();
        val results = ConcurrentHashMap<Int, List<IPlatformVideo>>();
        val tasks = seeds.mapIndexed { index, seed ->
            _discoveryScope.async {
                try {
                    val pager = StatePlatform.instance.getContentRecommendations(seed.video.url) ?: return@async;
                    results[index] = pager.getResults().filterIsInstance<IPlatformVideo>().take(MAX_RECOMMENDATIONS_PER_SEED);
                } catch (e: Throwable) {
                    Logger.w(TAG, "Failed to get recommendations for ${seed.video.url}", e);
                }
            }
        };
        //Plugin calls are blocking and cannot be interrupted, use whatever finished in time
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { tasks.awaitAll() };

        val scored = HashMap<String, Pair<IPlatformVideo, Double>>();
        for ((index, recommendations) in results) {
            val seed = seeds[index];
            val seedDays = max(0.0, (now.toEpochSecond() - seed.date.toEpochSecond()) / 86400.0);
            val seedWeight = exp(-seedDays / 7.0);
            recommendations.forEachIndexed { rank, video ->
                if (!isDiscoveryCandidate(video))
                    return@forEachIndexed;
                val score = seedWeight / (1.0 + rank * 0.15);
                //Recommended from multiple seeds is a stronger signal
                val existing = scored[video.url];
                scored[video.url] = Pair(video, (existing?.second ?: 0.0) + score);
            }
        }
        return diversify(scored.values.toList());
    }

    private fun isDiscoveryCandidate(video: IPlatformVideo): Boolean {
        if (video.isShort || video.author.url.isEmpty())
            return false;
        if (StateSubscriptions.instance.isSubscribed(video.author.url))
            return false;
        //Anything the user already started is not a discovery
        return StateHistory.instance.getHistoryPosition(video.url) <= 0;
    }

    private fun interleave(subscriptions: List<IPlatformContent>, discovery: List<IPlatformVideo>): List<IPlatformContent> {
        val result = ArrayList<IPlatformContent>(subscriptions.size + discovery.size);
        val seen = HashSet<String>();
        var discoveryIndex = 0;
        var subscriptionIndex = 0;
        while (subscriptionIndex < subscriptions.size || discoveryIndex < discovery.size) {
            val isDiscoverySlot = result.size >= DISCOVERY_FIRST_SLOT && (result.size - DISCOVERY_FIRST_SLOT) % DISCOVERY_INTERVAL == 0;
            val next = if ((isDiscoverySlot || subscriptionIndex >= subscriptions.size) && discoveryIndex < discovery.size)
                discovery[discoveryIndex++]
            else
                subscriptions[subscriptionIndex++];
            if (seen.add(next.url))
                result.add(next);
        }
        return result;
    }

    private fun createPager(items: List<IPlatformContent>): IPager<IPlatformContent> {
        return AdhocPager({ page -> items.drop(page * PAGE_SIZE).take(PAGE_SIZE) }, items.take(PAGE_SIZE));
    }

    companion object {
        private const val TAG = "StateForYou";

        private const val PAGE_SIZE = 20;
        private const val MAX_CACHE_PAGES = 50;
        private const val MAX_LIVE_PAGES = 5;
        private const val MAX_SUBSCRIPTION_CANDIDATES = 500;
        private const val MAX_AGE_DAYS = 30L;
        private const val HISTORY_DAYS = 30L;
        private const val FRESHNESS_TAU_HOURS = 72.0;
        private const val AUTHOR_REPEAT_PENALTY = 0.6;
        private const val FEED_STALE_MINUTES = 15;

        private const val MAX_DISCOVERY_SEEDS = 6;
        private const val MAX_RECOMMENDATIONS_PER_SEED = 10;
        private const val DISCOVERY_TIMEOUT_MS = 15_000L;
        private const val DISCOVERY_STALE_MINUTES = 60;
        private const val DISCOVERY_FIRST_SLOT = 3;
        private const val DISCOVERY_INTERVAL = 4;

        private var _instance: StateForYou? = null;
        val instance: StateForYou
            get() {
                if (_instance == null)
                    _instance = StateForYou();
                return _instance!!;
            };
    }
}
