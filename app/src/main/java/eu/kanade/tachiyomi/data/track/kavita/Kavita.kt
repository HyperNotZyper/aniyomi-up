package eu.kanade.tachiyomi.data.track.kavita

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.annotation.StringRes
import eu.kanade.domain.entries.manga.model.Manga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack
import eu.kanade.tachiyomi.data.track.EnhancedMangaTrackService
import eu.kanade.tachiyomi.data.track.MangaTrackService
import eu.kanade.tachiyomi.data.track.NoLoginTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.MangaTrackSearch
import eu.kanade.tachiyomi.source.MangaSource
import java.security.MessageDigest
import eu.kanade.domain.track.manga.model.MangaTrack as DomainTrack

class Kavita(private val context: Context, id: Long) : TrackService(id), EnhancedMangaTrackService, NoLoginTrackService, MangaTrackService {

    companion object {
        const val UNREAD = 1
        const val READING = 2
        const val COMPLETED = 3
    }

    var authentications: OAuth? = null

    private val interceptor by lazy { KavitaInterceptor(this) }
    val api by lazy { KavitaApi(client, interceptor) }

    @StringRes
    override fun nameRes() = R.string.tracker_kavita

    override fun getLogo(): Int = R.drawable.ic_tracker_kavita

    override fun getLogoColor() = Color.rgb(74, 198, 148)

    override fun getStatusListManga() = listOf(UNREAD, READING, COMPLETED)

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            UNREAD -> getString(R.string.unread)
            READING -> getString(R.string.reading)
            COMPLETED -> getString(R.string.completed)
            else -> ""
        }
    }

    override fun getReadingStatus(): Int = READING

    override fun getRereadingStatus(): Int = -1

    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(): List<String> = emptyList()

    override fun displayScore(track: MangaTrack): String = ""

    override suspend fun update(track: MangaTrack, didReadChapter: Boolean): MangaTrack {
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toInt() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                } else {
                    track.status = READING
                }
            }
        }
        return api.updateProgress(track)
    }

    override suspend fun bind(track: MangaTrack, hasReadChapters: Boolean): MangaTrack {
        return track
    }

    override suspend fun searchManga(query: String): List<MangaTrackSearch> {
        TODO("Not yet implemented: search")
    }

    override suspend fun refresh(track: MangaTrack): MangaTrack {
        val remoteTrack = api.getTrackSearch(track.tracking_url)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials("user", "pass")
    }

    // TrackService.isLogged works by checking that credentials are saved.
    // By saving dummy, unused credentials, we can activate the tracker simply by login/logout
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.extension.all.kavita.Kavita")

    override suspend fun match(manga: Manga): MangaTrackSearch? =
        try {
            api.getTrackSearch(manga.url)
        } catch (e: Exception) {
            null
        }

    override fun isTrackFrom(track: DomainTrack, manga: Manga, source: MangaSource?): Boolean =
        track.remoteUrl == manga.url && source?.let { accept(it) } == true

    override fun migrateTrack(track: DomainTrack, manga: Manga, newSource: MangaSource): DomainTrack? =
        if (accept(newSource)) {
            track.copy(remoteUrl = manga.url)
        } else {
            null
        }

    fun loadOAuth() {
        val oauth = OAuth()
        for (sourceId in 1..3) {
            val authentication = oauth.authentications[sourceId - 1]
            val sourceSuffixID by lazy {
                val key = "kavita_$sourceId/all/1" // Hardcoded versionID to 1
                val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
                (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
                    .reduce(Long::or) and Long.MAX_VALUE
            }
            val preferences: SharedPreferences by lazy {
                context.getSharedPreferences("source_$sourceSuffixID", 0x0000)
            }
            val prefApiUrl = preferences.getString("APIURL", "")!!
            if (prefApiUrl.isEmpty()) {
                // Source not configured. Skip
                continue
            }
            val prefApiKey = preferences.getString("APIKEY", "")!!
            val token = api.getNewToken(apiUrl = prefApiUrl, apiKey = prefApiKey)
            if (token.isNullOrEmpty()) {
                // Source is not accessible. Skip
                continue
            }
            authentication.apiUrl = prefApiUrl
            authentication.jwtToken = token.toString()
        }
        authentications = oauth
    }
}
