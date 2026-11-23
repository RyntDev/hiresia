package com.hiresia.audioplayer.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import com.hiresia.audioplayer.engine.LiveLyricsManager
import com.hiresia.audioplayer.repository.EffectiveVolumeType
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.database.HiresiaLibraryDatabase
import com.hiresia.audioplayer.databinding.ActivityMainBinding
import com.hiresia.audioplayer.databinding.BottomSheetAddQueueBinding
import com.hiresia.audioplayer.databinding.DialogLibraryFoldersBinding
import com.hiresia.audioplayer.databinding.DialogQuickTracksBinding
import com.hiresia.audioplayer.databinding.DialogScanLoadingBinding
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.engine.PlaybackState
import com.hiresia.audioplayer.engine.QueueManager
import com.hiresia.audioplayer.engine.RepeatMode
import com.hiresia.audioplayer.model.Album
import com.hiresia.audioplayer.model.Artist
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.model.FolderItem
import com.hiresia.audioplayer.model.QueueGroup
import com.hiresia.audioplayer.model.QueueGroupType
import com.hiresia.audioplayer.model.UserPlaylist
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.FolderSortOrder
import com.hiresia.audioplayer.repository.MediaRepository
import com.hiresia.audioplayer.repository.StoragePathUtils
import com.hiresia.audioplayer.ui.QueueGroupAdapter
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var searchAdapter: TrackAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistTrackAdapter: TrackAdapter
    private lateinit var queueGroupAdapter: QueueGroupAdapter
    private lateinit var likedAdapter: LikedTrackAdapter
    private lateinit var recentTrackAdapter: RecentTrackAdapter
    private lateinit var homeRecentCarouselAdapter: HomeRecentCarouselAdapter
    private lateinit var homeAlbumCarouselAdapter: HomeAlbumCarouselAdapter
    private lateinit var homeArtistCarouselAdapter: HomeArtistCarouselAdapter
    private lateinit var liveLyricsAdapter: LiveLyricsAdapter
    private var currentLyricsTrackId: Long? = null
    private val selectedNewPlaylistTracks = mutableListOf<AudioTrack>()
    private var currentOpenedPlaylist: UserPlaylist? = null
    private var currentOpenedCollectionTitle: String? = null
    private var heroAutoSlideJob: kotlinx.coroutines.Job? = null

    private var isUserTrackingSeekBar = false
    private var allTracks = listOf<AudioTrack>()
    private var currentHeroTrack: AudioTrack? = null

    private var displayedTrackId: Long = -1L
    private var displayedLiked: Boolean? = null
    private var displayedIsPlaying: Boolean? = null
    private var displayedRepeatMode: RepeatMode? = null
    private var displayedOutputDevice: String? = null
    private var displayedBitPerfect: Boolean? = null
    private var displayedPositionSec: Long = -1L
    private var lastAppliedPaletteIndex: Int = -1
    private var lastAppliedThemeMode: Int = -1
    private var lastAppliedLanguage: String = ""
    private var nowPlayingTopBarColor: Int? = null
    private var nowPlayingBottomBarColor: Int? = null

    enum class LibraryTab {
        LAGU, ALBUM, ARTIS, FOLDER
    }

    enum class MainNavTab {
        BERANDA, ANTREAN, CARI, PUSTAKA
    }

    private var currentNavTab = MainNavTab.BERANDA
    private var currentLibraryTab = LibraryTab.LAGU
    private var currentFolderPath = "ROOT"
    private val folderBackStack = ArrayDeque<String>()
    private var currentFolderSortOrder = FolderSortOrder.NAME_ASC

    private val pickPlaylistCoverLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && currentOpenedPlaylist != null) {
            val pl = currentOpenedPlaylist!!
            val db = HiresiaLibraryDatabase.getInstance(this)
            db.updatePlaylistCover(pl.id, uri.toString())
            currentOpenedPlaylist = db.getPlaylistById(pl.id)
            currentOpenedPlaylist?.let { refreshPlaylistDetailUi(it) }
            loadPlaylists()
            Toast.makeText(this, "Sampul playlist berhasil diperbarui", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            loadMediaLibrary()
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan untuk membaca file audio", Toast.LENGTH_LONG).show()
            loadMediaLibrary()
        }
    }

    private val openAudioFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            importAudioFolder(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioSettingsRepository.init(this)
        AudioSettingsRepository.applyThemeMode()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AudioPlayerController.init(this)

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        binding.layoutMiniPlayer.visibility = View.GONE

        setupBottomNavigation()
        setupAdapters()
        setupHomeInteractions()
        setupQueueInteractions()
        setupLibraryTabs()
        setupSearch()
        setupNowPlayingSheet()
        setupMiniPlayer()
        setupFolderControls()
        observePlaybackState()
        observeUsbDac()
        observeVolume()
        setupBackNavigation()

        checkPermissionsAndLoad()
        handleUsbIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        UsbAudioDeviceManager.setCurrentActivity(this)
        if (UsbAudioDeviceManager.connectedDac.value == null) {
            UsbAudioDeviceManager.scanForAudioDevices()
        }
        val curPalette = AudioSettingsRepository.paletteIndex
        val curTheme = AudioSettingsRepository.themeMode
        val curLang = AudioSettingsRepository.appLanguage
        if (lastAppliedPaletteIndex != curPalette ||
            lastAppliedThemeMode != curTheme ||
            lastAppliedLanguage != curLang
        ) {
            lastAppliedPaletteIndex = curPalette
            lastAppliedThemeMode = curTheme
            lastAppliedLanguage = curLang
            applyDynamicThemeAndAccent()
        } else {
            syncSystemBarColors()
            binding.tvNowPlayingEqText.text = if (AudioSettingsRepository.isGraphicEqEnabled) {
                "EQ · ${AppLanguageManager.translateString(AudioSettingsRepository.activeEqPresetName)}"
            } else {
                tr("EQ · Mati", "EQ · Off")
            }
        }
        startHeroAutoSlideTimer()
    }

    override fun onPause() {
        super.onPause()
        heroAutoSlideJob?.cancel()
        UsbAudioDeviceManager.setCurrentActivity(null)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    /* -------------------------------------------------------------
    * Bottom Navigation (Beranda | Antrean | Cari | Pustaka)
    * ------------------------------------------------------------- */
    private fun setupBottomNavigation() {
        binding.btnNavBeranda.setOnClickListener { switchNavTab(MainNavTab.BERANDA) }
        binding.btnNavAntrean.setOnClickListener { switchNavTab(MainNavTab.ANTREAN) }
        binding.btnNavCari.setOnClickListener { switchNavTab(MainNavTab.CARI) }
        binding.btnNavPustaka.setOnClickListener { switchNavTab(MainNavTab.PUSTAKA) }
        switchNavTab(MainNavTab.BERANDA, animate = false)
    }

    private fun switchNavTab(tab: MainNavTab, animate: Boolean = true) {
        val targetView = when {
            tab == MainNavTab.BERANDA && (currentOpenedPlaylist != null || currentOpenedCollectionTitle != null) -> binding.viewPlaylistDetail
            tab == MainNavTab.BERANDA -> binding.viewHome
            tab == MainNavTab.ANTREAN -> binding.viewQueue
            tab == MainNavTab.CARI -> binding.viewSearch
            tab == MainNavTab.PUSTAKA -> binding.viewLibrary
            else -> binding.viewHome
        }
        if (animate && tab == currentNavTab && targetView.visibility == View.VISIBLE) {
            return
        }
        currentNavTab = tab

        val allPages = listOf(
            binding.viewHome,
            binding.viewPlaylistDetail,
            binding.viewQueue,
            binding.viewSearch,
            binding.viewLibrary,
            binding.viewDisukai,
            binding.viewTerakhir,
            binding.viewNewPlaylist
        )

        for (v in allPages) {
            v.animate().cancel()
            if (v == targetView) {
                if (v.visibility != View.VISIBLE) {
                    if (animate) {
                        v.alpha = 0f
                        v.translationY = 14f
                        v.visibility = View.VISIBLE
                        v.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(150)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    } else {
                        v.visibility = View.VISIBLE
                        v.alpha = 1f
                        v.translationY = 0f
                    }
                } else {
                    v.alpha = 1f
                    v.translationY = 0f
                }
            } else {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationY = 0f
            }
        }

        // Hide mini player on Antrean tab to match PDF design Page 4 & 5, or if no track loaded
        if (tab == MainNavTab.ANTREAN || AudioPlayerController.playbackState.value.currentTrack == null) {
            binding.layoutMiniPlayer.visibility = View.GONE
        } else {
            binding.layoutMiniPlayer.visibility = View.VISIBLE
        }

        // Floating Pill Bottom Navigation styling (Obsidian Gold vs Aegean vs Zest)
        val isObsidian = AudioSettingsRepository.isObsidianGold
        val isAegean = AudioSettingsRepository.paletteIndex == AudioSettingsRepository.PALETTE_C_AEGEAN
        val navBarBg = when {
            isObsidian -> android.graphics.Color.parseColor("#1E1E20")
            isAegean -> android.graphics.Color.parseColor("#14263B")
            else -> android.graphics.Color.parseColor("#182E20")
        }
        val activePillBg = when {
            isObsidian -> android.graphics.Color.parseColor("#F3EAC3")
            isAegean -> android.graphics.Color.parseColor("#82C4FF")
            else -> android.graphics.Color.parseColor("#C8EE6B")
        }
        val activeFg = when {
            isObsidian -> android.graphics.Color.parseColor("#14130F")
            isAegean -> android.graphics.Color.parseColor("#102033")
            else -> android.graphics.Color.parseColor("#142318")
        }
        val inactiveFg = when {
            isObsidian -> android.graphics.Color.parseColor("#D0D0D4")
            isAegean -> android.graphics.Color.parseColor("#E8EEF5")
            else -> android.graphics.Color.parseColor("#E8E6D9")
        }

        binding.layoutBottomNavigation.backgroundTintList = ColorStateList.valueOf(navBarBg)

        fun styleNavTab(
            container: android.widget.LinearLayout,
            icon: ImageView,
            label: TextView,
            isSelected: Boolean
        ) {
            val lp = container.layoutParams as android.widget.LinearLayout.LayoutParams
            if (isSelected) {
                lp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                container.layoutParams = lp
                container.setBackgroundResource(R.drawable.bg_pill_lime)
                container.backgroundTintList = ColorStateList.valueOf(activePillBg)
                icon.setColorFilter(activeFg)
                icon.imageTintList = ColorStateList.valueOf(activeFg)
                label.setTextColor(activeFg)
                label.visibility = View.VISIBLE
            } else {
                lp.width = 0
                lp.weight = 1f
                container.layoutParams = lp
                container.background = null
                icon.setColorFilter(inactiveFg)
                icon.imageTintList = ColorStateList.valueOf(inactiveFg)
                label.visibility = View.GONE
            }
        }

        styleNavTab(binding.btnNavBeranda, binding.ivNavBeranda, binding.tvNavBeranda, tab == MainNavTab.BERANDA)
        styleNavTab(binding.btnNavAntrean, binding.ivNavAntrean, binding.tvNavAntrean, tab == MainNavTab.ANTREAN)
        styleNavTab(binding.btnNavCari, binding.ivNavCari, binding.tvNavCari, tab == MainNavTab.CARI)
        styleNavTab(binding.btnNavPustaka, binding.ivNavPustaka, binding.tvNavPustaka, tab == MainNavTab.PUSTAKA)

        if (tab == MainNavTab.BERANDA && currentOpenedPlaylist == null) {
            binding.viewHome.post { binding.viewHome.scrollTo(0, 0) }
        } else if (tab == MainNavTab.ANTREAN) {
            refreshQueueView()
        } else if (tab == MainNavTab.PUSTAKA) {
            refreshLibraryView()
        }
    }

    private fun applyDynamicThemeAndAccent() {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val accent = scheme.accent
        val csl = android.content.res.ColorStateList.valueOf(accent)

        // Background and window bars matching active palette (or album edges when Now Playing is visible)
        if (AudioSettingsRepository.isObsidianGold) {
            binding.root.setBackgroundResource(R.drawable.bg_obsidian_ambient)
        } else {
            binding.root.setBackgroundColor(scheme.page)
        }
        syncSystemBarColors()
        binding.layoutNowPlayingSheet.setBackgroundColor(scheme.page)

        val surf2Csl = ColorStateList.valueOf(scheme.surface2)
        val textCsl = ColorStateList.valueOf(scheme.text)
        val onAccentCsl = ColorStateList.valueOf(scheme.onAccent)

        binding.btnTambahPlaylistEmpty.backgroundTintList = csl
        binding.btnTambahPlaylistEmpty.setTextColor(scheme.onAccent)
        binding.btnTambahPlaylistGrid.backgroundTintList = csl
        binding.btnTambahPlaylistGrid.setTextColor(scheme.onAccent)
        binding.btnBottomAddQueue.backgroundTintList = csl
        binding.btnBottomAddQueue.setTextColor(scheme.onAccent)
        binding.layoutQueueBottomActions.setBackgroundColor(
            if (AudioSettingsRepository.isObsidianGold) android.graphics.Color.TRANSPARENT else scheme.page
        )
        binding.btnMiniPlayPause.backgroundTintList = csl
        binding.btnMiniPlayPause.imageTintList = onAccentCsl
        binding.btnNowPlayingPlayPause.backgroundTintList = csl
        binding.btnNowPlayingPlayPause.imageTintList = onAccentCsl
        binding.sbNowPlaying.progressTintList = csl
        binding.sbNowPlaying.thumbTintList = csl
        binding.tvLyricsBadge.setTextColor(accent)
        binding.layoutBitPerfectBadge.backgroundTintList = csl

        // Tint screen buttons & compound icons uniformly
        binding.btnDisukaiPlay.backgroundTintList = csl
        binding.btnDisukaiPlay.setTextColor(scheme.onAccent)
        TextViewCompat.setCompoundDrawableTintList(binding.btnDisukaiPlay, onAccentCsl)
        binding.btnCreatePlaylistConfirm.backgroundTintList = csl
        binding.btnCreatePlaylistConfirm.setTextColor(scheme.onAccent)
        binding.btnPlaylistPlay.backgroundTintList = csl
        binding.btnPlaylistPlay.setTextColor(scheme.onAccent)
        TextViewCompat.setCompoundDrawableTintList(binding.btnPlaylistPlay, onAccentCsl)
        binding.btnPlaylistShuffle.backgroundTintList = surf2Csl
        binding.btnPlaylistShuffle.setTextColor(scheme.text)
        TextViewCompat.setCompoundDrawableTintList(binding.btnPlaylistShuffle, textCsl)
        binding.btnPlaylistAddTracks.backgroundTintList = surf2Csl
        binding.btnPlaylistAddTracks.setTextColor(scheme.text)
        TextViewCompat.setCompoundDrawableTintList(binding.btnPlaylistAddTracks, textCsl)
        binding.btnDisukaiAddQueue.backgroundTintList = surf2Csl
        binding.btnDisukaiAddQueue.setTextColor(scheme.text)
        TextViewCompat.setCompoundDrawableTintList(binding.btnDisukaiAddQueue, textCsl)
        binding.btnFolderUp.imageTintList = csl
        binding.tvFolderBackLabel.setTextColor(accent)
        binding.tvFolderCurrentName.setTextColor(scheme.muted)
        val glassCsl = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(scheme.surface, 182))
        binding.ivNowPlayingVolumeIcon.imageTintList = csl
        binding.ivNowPlayingEqIcon.imageTintList = csl
        binding.ivNowPlayingQueueIcon.imageTintList = csl
        binding.layoutAudioTelemetryPill.backgroundTintList = glassCsl
        binding.btnNowPlayingVolumeRow.backgroundTintList = glassCsl
        binding.btnNowPlayingEq.backgroundTintList = glassCsl
        binding.btnNowPlayingQueue.backgroundTintList = glassCsl
        binding.tvNowPlayingEqText.text = if (AudioSettingsRepository.isGraphicEqEnabled) {
            "EQ · ${AppLanguageManager.translateString(AudioSettingsRepository.activeEqPresetName)}"
        } else {
            tr("EQ · Mati", "EQ · Off")
        }

        // Apply selected language (Bahasa Indonesia / English) to all UI elements
        updateHomeGreeting()
        if (allTracks.isNotEmpty()) {
            val totalMs = allTracks.sumOf { it.durationMs }
            val hours = totalMs / (1000 * 60 * 60)
            binding.tvLibraryStats.text = AppLanguageManager.formatSongsHours(allTracks.size, hours)
        }
        binding.tvPlaylistDetailStats.text = AppLanguageManager.translateString(binding.tvPlaylistDetailStats.text.toString())
        binding.tvDisukaiCount.text = AppLanguageManager.translateString(binding.tvDisukaiCount.text.toString())
        binding.tvNewPlaylistTracksSubtitle.text = AppLanguageManager.translateString(binding.tvNewPlaylistTracksSubtitle.text.toString())
        binding.tvNowPlayingSubHeader.text = tr("Sedang diputar dari", "Playing from")
        binding.tvNowPlayingPlaylistOrigin.text = AppLanguageManager.translateString(binding.tvNowPlayingPlaylistOrigin.text.toString())
        AppLanguageManager.applyLanguageToViewTree(binding.root)

        // Tint library top controls
        binding.btnTambahFolderPill.backgroundTintList = csl
        binding.ivTambahFolderIcon.imageTintList = ColorStateList.valueOf(scheme.onAccent)
        binding.tvTambahFolderText.setTextColor(scheme.onAccent)
        binding.btnLibrarySettings.imageTintList = ColorStateList.valueOf(scheme.text)

        // Tint Beranda (Home) Hero Mix, Lagu Disukai Banner, Sections, Queue & Carousels
        val isObsidian = AudioSettingsRepository.isObsidianGold
        val isAegean = AudioSettingsRepository.paletteIndex == AudioSettingsRepository.PALETTE_C_AEGEAN
        val heroBg = when {
            isObsidian -> android.graphics.Color.parseColor("#22201B")
            isAegean -> android.graphics.Color.parseColor("#14263B")
            else -> android.graphics.Color.parseColor("#1A3222")
        }
        val heroHighlight = when {
            isObsidian -> android.graphics.Color.parseColor("#F3EAC3")
            isAegean -> android.graphics.Color.parseColor("#82C4FF")
            else -> android.graphics.Color.parseColor("#C8EE6B")
        }
        val heroHighlightFg = when {
            isObsidian -> android.graphics.Color.parseColor("#14130F")
            isAegean -> android.graphics.Color.parseColor("#102033")
            else -> android.graphics.Color.parseColor("#142318")
        }
        val heroSubCircle = when {
            isObsidian -> android.graphics.Color.parseColor("#35322A")
            isAegean -> android.graphics.Color.parseColor("#233E5C")
            else -> android.graphics.Color.parseColor("#2D4A35")
        }

        binding.tvHomeGreeting.setTextColor(scheme.muted)
        binding.tvHeaderPlaylist.setTextColor(scheme.text)
        binding.layoutHomeSettingsCircle.backgroundTintList = ColorStateList.valueOf(if (isObsidian) scheme.surface2 else scheme.surface)
        binding.btnHomeSettings.setColorFilter(scheme.text)
        binding.btnHomeSettings.imageTintList = ColorStateList.valueOf(scheme.text)

        binding.cardHomeHeroMix.setCardBackgroundColor(heroBg)
        binding.viewHeroMixScrim.setBackgroundResource(R.drawable.bg_hero_cover_edge_vignette)
        binding.btnHeroMixPlay.backgroundTintList = ColorStateList.valueOf(heroHighlight)
        binding.ivHeroMixPlayIcon.imageTintList = ColorStateList.valueOf(heroHighlightFg)
        binding.tvHeroMixPlayText.setTextColor(heroHighlightFg)
        binding.btnHeroMixShuffle.backgroundTintList = ColorStateList.valueOf(heroSubCircle)
        binding.ivHeroMixShuffleIcon.imageTintList = ColorStateList.valueOf(
            if (isObsidian) android.graphics.Color.parseColor("#F3EAC3") else android.graphics.Color.parseColor("#F6F7E8")
        )
        bindHeroMixTrackUi(currentHeroTrack, animateDirection = 0)

        // Lagu Disukai / Liked Songs Banner
        if (isObsidian) {
            binding.viewDisukaiBannerBg.baseColor = android.graphics.Color.parseColor("#FF5B3F")
            binding.viewDisukaiBannerBg.endColor = android.graphics.Color.parseColor("#FF3D68")
            binding.viewDisukaiBannerBg.arcColor = android.graphics.Color.parseColor("#33FF2E7E")
            binding.layoutQuickDisukaiIcon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#F6F4EF"))
            binding.ivQuickDisukaiIcon.setColorFilter(android.graphics.Color.parseColor("#FF2E7E"))
            binding.ivQuickDisukaiIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#FF2E7E"))
            binding.btnQuickDisukaiPlay.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#14130F"))
            binding.ivQuickDisukaiPlayIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#F3EAC3"))
        } else {
            val disukaiBase = android.graphics.Color.parseColor("#DF6E46")
            val disukaiArc = android.graphics.Color.parseColor("#C95B34")
            binding.viewDisukaiBannerBg.baseColor = disukaiBase
            binding.viewDisukaiBannerBg.endColor = null
            binding.viewDisukaiBannerBg.arcColor = disukaiArc
            binding.layoutQuickDisukaiIcon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#F5EEDC"))
            binding.ivQuickDisukaiIcon.setColorFilter(disukaiBase)
            binding.ivQuickDisukaiIcon.imageTintList = ColorStateList.valueOf(disukaiBase)
            binding.btnQuickDisukaiPlay.backgroundTintList = ColorStateList.valueOf(heroBg)
            binding.ivQuickDisukaiPlayIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#F6F7E8"))
        }

        // Queue "Now Playing" card styling (Champagne Gold pill card on Obsidian Gold)
        if (isObsidian) {
            binding.cardNowPlayingQueue.backgroundTintList = ColorStateList.valueOf(scheme.accent)
            binding.tvQueueNowPlayingTitle.setTextColor(scheme.onAccent)
            binding.tvQueueNowPlayingSubtitle.setTextColor(android.graphics.Color.parseColor("#4A4638"))
        } else {
            binding.cardNowPlayingQueue.backgroundTintList = ColorStateList.valueOf(scheme.surface)
            binding.tvQueueNowPlayingTitle.setTextColor(scheme.accent)
            binding.tvQueueNowPlayingSubtitle.setTextColor(scheme.muted)
        }
        binding.tvHeaderNowPlayingQueue.setTextColor(if (isObsidian) scheme.muted else scheme.accent)
        binding.tvHeaderNextQueue.setTextColor(if (isObsidian) scheme.muted else scheme.accent)

        // Section Titles & "Lihat semua" / "+ Baru"
        val seeAllColor = if (isObsidian) scheme.accent else scheme.text
        binding.tvHomeSectionTerakhir.setTextColor(scheme.text)
        binding.tvSeeAllTerakhir.setTextColor(seeAllColor)
        binding.tvHomeSectionAlbum.setTextColor(scheme.text)
        binding.tvSeeAllAlbum.setTextColor(seeAllColor)
        binding.tvHomeSectionArtis.setTextColor(scheme.text)
        binding.tvSeeAllArtis.setTextColor(seeAllColor)
        binding.tvHomeSectionPlaylist.setTextColor(scheme.text)
        binding.btnHomeNewPlaylist.backgroundTintList = ColorStateList.valueOf(if (isObsidian) scheme.accent else heroBg)
        binding.btnHomeNewPlaylist.setTextColor(if (isObsidian) scheme.onAccent else android.graphics.Color.parseColor("#F6F7E8"))
        binding.layoutEmptyPlaylistPlusCircle.backgroundTintList = ColorStateList.valueOf(scheme.surface)
        binding.ivEmptyPlaylistPlus.imageTintList = ColorStateList.valueOf(scheme.text)
        binding.tvEmptyPlaylistTitle.setTextColor(scheme.text)
        binding.tvEmptyPlaylistSub.setTextColor(scheme.muted)

        // Subpage backgrounds matching active palette
        if (isObsidian) {
            binding.viewPlaylistDetail.setBackgroundResource(R.drawable.bg_obsidian_ambient)
            binding.viewDisukai.setBackgroundResource(R.drawable.bg_obsidian_ambient)
            binding.viewTerakhir.setBackgroundResource(R.drawable.bg_obsidian_ambient)
            binding.viewNewPlaylist.setBackgroundResource(R.drawable.bg_obsidian_ambient)
        } else {
            binding.viewPlaylistDetail.setBackgroundColor(scheme.page)
            binding.viewDisukai.setBackgroundColor(scheme.page)
            binding.viewTerakhir.setBackgroundColor(scheme.page)
            binding.viewNewPlaylist.setBackgroundColor(scheme.page)
        }

        val currentTrack = AudioPlayerController.playbackState.value.currentTrack
        val placeholder = AudioSettingsRepository.getDefaultCoverResId()
        if (currentTrack == null || currentTrack.albumArtUri == null) {
            binding.ivNowPlayingCover.setImageResource(placeholder)
            binding.ivMiniCoverArt.setImageResource(placeholder)
            binding.ivQueueNowPlayingThumb.setImageResource(placeholder)
            applyNowPlayingBackdrop(null)
        }
        binding.ivNewPlaylistCover.setImageResource(placeholder)
        currentOpenedPlaylist?.let { refreshPlaylistDetailUi(it) }

        updateNowPlayingRepeatUi(AudioPlayerController.playbackState.value.repeatMode)
        if (::liveLyricsAdapter.isInitialized) {
            liveLyricsAdapter.notifyDataSetChanged()
        }
        if (::homeRecentCarouselAdapter.isInitialized) {
            homeRecentCarouselAdapter.notifyDataSetChanged()
            homeAlbumCarouselAdapter.notifyDataSetChanged()
            homeArtistCarouselAdapter.notifyDataSetChanged()
        }

        switchNavTab(currentNavTab, animate = false)
        selectLibraryTab(currentLibraryTab)

        trackAdapter.notifyDataSetChanged()
        playlistTrackAdapter.notifyDataSetChanged()
        searchAdapter.notifyDataSetChanged()
        playlistAdapter.notifyDataSetChanged()
        likedAdapter.notifyDataSetChanged()
        recentTrackAdapter.notifyDataSetChanged()
        albumAdapter.notifyDataSetChanged()
        artistAdapter.notifyDataSetChanged()
    }

    /* -------------------------------------------------------------
     * Home Screen (Page 1) 2x2 Grid & Playlists
     * ------------------------------------------------------------- */
    private fun setupHomeInteractions() {
        updateHomeGreeting()

        binding.btnHomeSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Hero Vinyl Mix Card: Swipe Left/Right for Random Track + Play & Shuffle
        var touchDownX = 0f
        var touchDownY = 0f
        binding.cardHomeHeroMix.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownX = ev.x
                    touchDownY = ev.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(ev.x - touchDownX)
                    val dy = kotlin.math.abs(ev.y - touchDownY)
                    if (dy > dx && dy > 24f) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.x - touchDownX
                    val dy = ev.y - touchDownY
                    if (kotlin.math.abs(dx) > 55f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        val dir = if (dx < 0f) 1 else -1
                        pickAndShowRandomHeroTrack(animateDirection = dir)
                        startHeroAutoSlideTimer()
                    } else if (kotlin.math.abs(dx) < 18f && kotlin.math.abs(dy) < 18f) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnHeroMixPlay.setOnClickListener {
            val featured = currentHeroTrack ?: allTracks.randomOrNull()
            if (featured != null) {
                val others = allTracks.filter { it.id != featured.id }.shuffled()
                val queue = ArrayList<AudioTrack>(others.size + 1).apply {
                    add(featured)
                    addAll(others)
                }
                AudioPlayerController.setPlaylist(queue, 0, autoPlay = true, groupName = tr("Campuran", "Mix"))
            } else {
                Toast.makeText(this, tr("Pindai folder musik terlebih dahulu di Pustaka", "Scan a music folder in Library first"), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnHeroMixShuffle.setOnClickListener {
            if (allTracks.isNotEmpty()) {
                pickAndShowRandomHeroTrack(animateDirection = 1)
                val featured = currentHeroTrack ?: allTracks.first()
                val others = allTracks.filter { it.id != featured.id }.shuffled()
                val queue = ArrayList<AudioTrack>(others.size + 1).apply {
                    add(featured)
                    addAll(others)
                }
                AudioPlayerController.setPlaylist(queue, 0, autoPlay = true, groupName = tr("Campuran Acak", "Shuffle Mix"))
            } else {
                Toast.makeText(this, tr("Pindai folder musik terlebih dahulu di Pustaka", "Scan a music folder in Library first"), Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Access Cards & "Lihat semua" (Disukai, Terakhir, Album, Artis)
        binding.cardQuickDisukai.setOnClickListener {
            openDisukaiScreen()
        }
        binding.btnQuickDisukaiPlay.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val liked = HiresiaLibraryDatabase.getInstance(this@MainActivity).getLikedTracks()
                withContext(Dispatchers.Main) {
                    if (liked.isNotEmpty()) {
                        AudioPlayerController.setPlaylist(liked, 0, autoPlay = true, groupName = tr("Disukai", "Liked Songs"))
                    } else {
                        openDisukaiScreen()
                    }
                }
            }
        }

        binding.cardQuickTerakhir.setOnClickListener {
            openTerakhirScreen()
        }

        binding.cardQuickAlbum.setOnClickListener {
            switchNavTab(MainNavTab.PUSTAKA)
            selectLibraryTab(LibraryTab.ALBUM)
        }

        binding.cardQuickArtis.setOnClickListener {
            switchNavTab(MainNavTab.PUSTAKA)
            selectLibraryTab(LibraryTab.ARTIS)
        }

        // Playlist Buttons (Page 1 & Page 3 in PDF)
        binding.btnHomeNewPlaylist.setOnClickListener {
            openNewPlaylistScreen()
        }
        binding.layoutPlaylistEmpty.setOnClickListener {
            openNewPlaylistScreen()
        }
        binding.btnTambahPlaylistEmpty.setOnClickListener {
            openNewPlaylistScreen()
        }
        binding.btnTambahPlaylistGrid.setOnClickListener {
            openNewPlaylistScreen()
        }

        // Disukai screen buttons (Page 5 in PDF)
        binding.btnBackDisukai.setOnClickListener {
            closeDisukaiScreen()
        }
        binding.btnDisukaiPlay.setOnClickListener {
            val list = likedAdapter.getTracks()
            if (list.isNotEmpty()) {
                AudioPlayerController.setPlaylist(list, 0, groupName = tr("Disukai", "Liked Songs"))
            } else {
                Toast.makeText(this, tr("Belum ada lagu disukai", "No liked songs yet"), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDisukaiAddQueue.setOnClickListener {
            val list = likedAdapter.getTracks()
            if (list.isNotEmpty()) {
                QueueManager.addCustomTracks(list)
                Toast.makeText(this, tr("${list.size} lagu ditambahkan ke antrean", "${list.size} songs added to queue"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, tr("Belum ada lagu disukai", "No liked songs yet"), Toast.LENGTH_SHORT).show()
            }
        }

        // Terakhir screen buttons (Page 6 in PDF)
        binding.btnBackTerakhir.setOnClickListener {
            closeTerakhirScreen()
        }
        binding.btnClearTerakhirHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Hapus Riwayat Putar?", "Clear Playback History?"))
                .setMessage(tr("Daftar lagu yang terakhir diputar akan dibersihkan.", "Recently played track history will be cleared."))
                .setPositiveButton(tr("Hapus", "Clear")) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                        db.clearRecentlyPlayedHistory()
                        withContext(Dispatchers.Main) {
                            loadRecentlyPlayedTracks()
                            Toast.makeText(this@MainActivity, tr("Riwayat berhasil dihapus", "History cleared"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // Playlist Baru screen buttons (Page 3 in PDF)
        binding.btnBackNewPlaylist.setOnClickListener {
            closeNewPlaylistScreen()
        }
        binding.rowSelectTracksNewPlaylist.setOnClickListener {
            showSelectTracksDialog { selected ->
                selectedNewPlaylistTracks.clear()
                selectedNewPlaylistTracks.addAll(selected)
                binding.tvNewPlaylistTracksSubtitle.text = tr("${selected.size} lagu dipilih", "${selected.size} songs selected")
            }
        }
        binding.btnCreatePlaylistConfirm.setOnClickListener {
            val name = binding.etNewPlaylistName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, tr("Nama playlist tidak boleh kosong", "Playlist name cannot be empty"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                val plId = db.createPlaylist(name)
                for (trItem in selectedNewPlaylistTracks) {
                    db.addTrackToPlaylist(plId, trItem.id)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, tr("Playlist '$name' berhasil dibuat", "Playlist '$name' created"), Toast.LENGTH_SHORT).show()
                    closeNewPlaylistScreen()
                    loadPlaylists()
                }
            }
        }

        // Playlist Detail Screen Controls
        binding.btnBackPlaylistDetail.setOnClickListener {
            closePlaylistDetail()
        }

        binding.btnPlaylistDetailMenu.setOnClickListener {
            currentOpenedPlaylist?.let { pl -> showPlaylistOptionsMenu(pl) }
        }

        binding.cardPlaylistDetailCover.setOnClickListener {
            pickPlaylistCoverLauncher.launch("image/*")
        }

        binding.tvPlaylistDetailTitle.setOnClickListener {
            currentOpenedPlaylist?.let { pl -> showEditPlaylistNameDialog(pl) }
        }

        binding.btnPlaylistPlay.setOnClickListener {
            playCurrentPlaylist(shuffle = false)
        }

        binding.btnPlaylistShuffle.setOnClickListener {
            playCurrentPlaylist(shuffle = true)
        }

        binding.btnPlaylistAddTracks.setOnClickListener {
            currentOpenedPlaylist?.let { pl -> showAddTracksToPlaylistDialog(pl) }
        }
    }

    /* -------------------------------------------------------------
     * Library Screen (Page 2) Filters & Tabs
     * ------------------------------------------------------------- */
    private fun setupLibraryTabs() {
        binding.btnLibrarySettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnTambahFolderPill.setOnClickListener {
            openAudioFolderLauncher.launch(null)
        }

        binding.chipTabLagu.setOnClickListener { selectLibraryTab(LibraryTab.LAGU) }
        binding.chipTabAlbum.setOnClickListener { selectLibraryTab(LibraryTab.ALBUM) }
        binding.chipTabArtis.setOnClickListener { selectLibraryTab(LibraryTab.ARTIS) }
        binding.chipTabFolder.setOnClickListener { selectLibraryTab(LibraryTab.FOLDER) }
    }

    private fun selectLibraryTab(tab: LibraryTab) {
        currentLibraryTab = tab
        val scheme = AudioSettingsRepository.getCurrentScheme()

        val pillLime = ContextCompat.getDrawable(this, R.drawable.bg_pill_lime)
        val pillDark = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
        val selBg = ColorStateList.valueOf(scheme.accent)
        val unselBg = ColorStateList.valueOf(scheme.surface2)
        val selText = scheme.onAccent
        val unselText = scheme.text

        binding.chipTabLagu.background = if (tab == LibraryTab.LAGU) pillLime else pillDark
        binding.chipTabLagu.backgroundTintList = if (tab == LibraryTab.LAGU) selBg else unselBg
        binding.chipTabLagu.setTextColor(if (tab == LibraryTab.LAGU) selText else unselText)

        binding.chipTabAlbum.background = if (tab == LibraryTab.ALBUM) pillLime else pillDark
        binding.chipTabAlbum.backgroundTintList = if (tab == LibraryTab.ALBUM) selBg else unselBg
        binding.chipTabAlbum.setTextColor(if (tab == LibraryTab.ALBUM) selText else unselText)

        binding.chipTabArtis.background = if (tab == LibraryTab.ARTIS) pillLime else pillDark
        binding.chipTabArtis.backgroundTintList = if (tab == LibraryTab.ARTIS) selBg else unselBg
        binding.chipTabArtis.setTextColor(if (tab == LibraryTab.ARTIS) selText else unselText)

        binding.chipTabFolder.background = if (tab == LibraryTab.FOLDER) pillLime else pillDark
        binding.chipTabFolder.backgroundTintList = if (tab == LibraryTab.FOLDER) selBg else unselBg
        binding.chipTabFolder.setTextColor(if (tab == LibraryTab.FOLDER) selText else unselText)

        binding.layoutFolderNavHeader.visibility = if (tab == LibraryTab.FOLDER && currentFolderPath != "ROOT" && currentFolderPath.isNotEmpty()) View.VISIBLE else View.GONE

        refreshLibraryView()
    }

    private fun refreshLibraryView() {
        when (currentLibraryTab) {
            LibraryTab.LAGU -> {
                if (binding.rvTracks.adapter !== trackAdapter) {
                    binding.rvTracks.layoutManager = LinearLayoutManager(this)
                    binding.rvTracks.adapter = trackAdapter
                }
                val q = binding.etLibrarySearch.text?.toString()?.trim() ?: ""
                val filtered = if (q.isEmpty()) allTracks else allTracks.filter {
                    it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                }
                trackAdapter.updateTracks(filtered)
            }
            LibraryTab.ALBUM -> {
                if (binding.rvTracks.adapter !== albumAdapter) {
                    binding.rvTracks.layoutManager = GridLayoutManager(this, 2)
                    binding.rvTracks.adapter = albumAdapter
                }
                val albums = MediaRepository.getAlbums(allTracks)
                albumAdapter.updateAlbums(albums)
            }
            LibraryTab.ARTIS -> {
                if (binding.rvTracks.adapter !== artistAdapter) {
                    binding.rvTracks.layoutManager = LinearLayoutManager(this)
                    binding.rvTracks.adapter = artistAdapter
                }
                val artists = MediaRepository.getArtists(allTracks)
                artistAdapter.updateArtists(artists)
            }
            LibraryTab.FOLDER -> {
                if (binding.rvTracks.adapter !== folderAdapter) {
                    binding.rvTracks.layoutManager = LinearLayoutManager(this)
                    binding.rvTracks.adapter = folderAdapter
                }
                loadFolderItems()
            }
        }
    }

    /* -------------------------------------------------------------
     * Adapters Setup
     * ------------------------------------------------------------- */
    private fun setupAdapters() {
        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackClick = { index, track ->
                val allList = if (allTracks.isNotEmpty()) allTracks else trackAdapter.getTracks()
                val remaining = allList.filter { it.id != track.id }.shuffled()
                val randomizedQueue = ArrayList<AudioTrack>(remaining.size + 1).apply {
                    add(track)
                    addAll(remaining)
                }
                AudioPlayerController.setPlaylist(randomizedQueue, 0, autoPlay = true, groupName = "Pustaka")
                trackAdapter.setSelectedTrackId(track.id)
            },
            onTrackLongClick = { index, track ->
                showTrackOptionsDialog(index, track)
                true
            }
        )
        binding.rvTracks.setHasFixedSize(true)
        binding.rvTracks.itemAnimator = null
        binding.rvTracks.setItemViewCacheSize(24)

        searchAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackClick = { index, track ->
                val allList = if (allTracks.isNotEmpty()) allTracks else searchAdapter.getTracks()
                val remaining = allList.filter { it.id != track.id }.shuffled()
                val randomizedQueue = ArrayList<AudioTrack>(remaining.size + 1).apply {
                    add(track)
                    addAll(remaining)
                }
                AudioPlayerController.setPlaylist(randomizedQueue, 0, autoPlay = true, groupName = "Pencarian")
                searchAdapter.setSelectedTrackId(track.id)
            },
            onTrackLongClick = { index, track ->
                showTrackOptionsDialog(index, track)
                true
            }
        )
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.setHasFixedSize(true)
        binding.rvSearchResults.itemAnimator = null
        binding.rvSearchResults.setItemViewCacheSize(20)
        binding.rvSearchResults.adapter = searchAdapter

        liveLyricsAdapter = LiveLyricsAdapter { lyric ->
            AudioPlayerController.seekTo(lyric.timeMs)
        }
        binding.rvLiveLyrics.layoutManager = LinearLayoutManager(this)
        binding.rvLiveLyrics.adapter = liveLyricsAdapter

        albumAdapter = AlbumAdapter(emptyList()) { album ->
            selectLibraryTab(LibraryTab.LAGU)
            val albumTracks = allTracks.filter { it.album.equals(album.title, ignoreCase = true) }
            trackAdapter.updateTracks(albumTracks)
            if (albumTracks.isNotEmpty()) {
                AudioPlayerController.setPlaylist(albumTracks, 0)
            }
        }

        artistAdapter = ArtistAdapter(emptyList()) { artist ->
            selectLibraryTab(LibraryTab.LAGU)
            val artistTracks = allTracks.filter { it.artist.equals(artist.name, ignoreCase = true) }
            trackAdapter.updateTracks(artistTracks)
            if (artistTracks.isNotEmpty()) {
                AudioPlayerController.setPlaylist(artistTracks, 0)
            }
        }

        folderAdapter = FolderAdapter(
            items = emptyList(),
            onItemClick = { item ->
                if (item.isDirectory) {
                    navigateIntoFolder(item.path)
                } else if (item.audioTrack != null) {
                    val folderTracks = folderAdapter.getItems().mapNotNull { it.audioTrack }
                    val idx = folderTracks.indexOfFirst { it.path == item.audioTrack.path }
                    if (idx != -1 && folderTracks.isNotEmpty()) {
                        AudioPlayerController.setPlaylist(folderTracks, idx)
                    } else {
                        val allIdx = allTracks.indexOfFirst { it.path == item.audioTrack.path }
                        AudioPlayerController.setPlaylist(allTracks, if (allIdx != -1) allIdx else 0)
                    }
                }
            },
            onActionClick = { item ->
                showFolderItemOptionsMenu(item)
            },
            onItemLongClick = { item ->
                showFolderItemOptionsMenu(item)
                true
            }
        )

        playlistAdapter = PlaylistAdapter(
            playlists = emptyList(),
            onPlaylistClick = { playlist ->
                openPlaylistDetail(playlist)
            },
            onPlaylistLongClick = { playlist ->
                showPlaylistOptionsMenu(playlist)
                true
            }
        )
        binding.rvPlaylists.layoutManager = GridLayoutManager(this, 2)
        binding.rvPlaylists.adapter = playlistAdapter

        playlistTrackAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackClick = { index, track ->
                val list = playlistTrackAdapter.getTracks()
                val plName = currentOpenedPlaylist?.name ?: currentOpenedCollectionTitle ?: "Playlist"
                val plId = currentOpenedPlaylist?.id
                AudioPlayerController.setPlaylist(list, index, groupName = plName, playlistId = plId)
                playlistTrackAdapter.setSelectedTrackId(track.id)
            },
            onTrackLongClick = { index, track ->
                if (currentOpenedPlaylist != null) {
                    showPlaylistTrackOptionsMenu(index, track)
                } else {
                    showTrackOptionsDialog(index, track)
                }
                true
            }
        )
        binding.rvPlaylistTracks.layoutManager = LinearLayoutManager(this)
        binding.rvPlaylistTracks.adapter = playlistTrackAdapter

        binding.rvTracks.layoutManager = LinearLayoutManager(this)
        binding.rvTracks.adapter = trackAdapter

        likedAdapter = LikedTrackAdapter(
            tracks = emptyList(),
            onTrackClick = { index, track ->
                val list = likedAdapter.getTracks()
                AudioPlayerController.setPlaylist(list, index, groupName = tr("Disukai", "Liked Songs"))
            },
            onLikeClick = { track ->
                val newLiked = !track.isLiked
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                    db.setTrackLiked(track.id, newLiked)
                    allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                    val liked = db.getLikedTracks()
                    withContext(Dispatchers.Main) {
                        likedAdapter.updateTracks(liked)
                        binding.tvDisukaiCount.text = AppLanguageManager.formatSongsCount(liked.size)
                        binding.layoutDisukaiEmpty.visibility = if (liked.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvDisukaiTracks.visibility = if (liked.isEmpty()) View.GONE else View.VISIBLE
                        refreshLibraryView()
                    }
                }
            },
            onTrackLongClick = { index, track ->
                showTrackOptionsDialog(index, track)
            }
        )
        binding.rvDisukaiTracks.layoutManager = LinearLayoutManager(this)
        binding.rvDisukaiTracks.adapter = likedAdapter

        recentTrackAdapter = RecentTrackAdapter(
            tracks = emptyList(),
            onTrackClick = { index, track ->
                val list = recentTrackAdapter.getTracks()
                AudioPlayerController.setPlaylist(list, index, groupName = tr("Terakhir", "Recent"))
            },
            onTrackLongClick = { index, track ->
                showTrackOptionsDialog(index, track)
            }
        )
        binding.rvTerakhirTracks.layoutManager = LinearLayoutManager(this)
        binding.rvTerakhirTracks.adapter = recentTrackAdapter

        // Home Page 1 Horizontal Carousels (Terakhir diputar, Album, Artis)
        homeRecentCarouselAdapter = HomeRecentCarouselAdapter(emptyList()) { _, track ->
            val allList = if (allTracks.isNotEmpty()) allTracks else listOf(track)
            val remaining = allList.filter { it.id != track.id }
            val queue = ArrayList<AudioTrack>(remaining.size + 1).apply {
                add(track)
                addAll(remaining)
            }
            AudioPlayerController.setPlaylist(queue, 0, autoPlay = true, groupName = tr("Terakhir diputar", "Recently Played"))
        }
        binding.rvHomeRecent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeRecent.adapter = homeRecentCarouselAdapter

        homeAlbumCarouselAdapter = HomeAlbumCarouselAdapter(emptyList()) { album ->
            val albumTracks = allTracks.filter { it.album.equals(album.title, ignoreCase = true) }
            openCollectionDetail(
                title = album.title,
                subtitlePrefix = album.artist.ifBlank { tr("Album", "Album") },
                coverUri = album.albumArtUri,
                tracks = albumTracks
            )
        }
        binding.rvHomeAlbums.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeAlbums.adapter = homeAlbumCarouselAdapter

        homeArtistCarouselAdapter = HomeArtistCarouselAdapter(emptyList()) { artist ->
            val artistTracks = allTracks.filter { it.artist.equals(artist.name, ignoreCase = true) }
            val dispName = if (artist.name.equals("Unknown Artist", true)) tr("Tidak diketahui", "Unknown") else artist.name
            openCollectionDetail(
                title = dispName,
                subtitlePrefix = AppLanguageManager.formatAlbumsCount(artist.albumCount),
                coverUri = artist.albumArtUri,
                tracks = artistTracks
            )
        }
        binding.rvHomeArtists.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeArtists.adapter = homeArtistCarouselAdapter
    }

    private fun startHeroAutoSlideTimer() {
        heroAutoSlideJob?.cancel()
        heroAutoSlideJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(7000L)
                if (currentNavTab == MainNavTab.BERANDA &&
                    binding.viewHome.visibility == View.VISIBLE &&
                    binding.layoutNowPlayingSheet.visibility != View.VISIBLE &&
                    allTracks.size > 1
                ) {
                    val randomDir = if ((0..1).random() == 0) 1 else -1
                    pickAndShowRandomHeroTrack(animateDirection = randomDir)
                }
            }
        }
    }

    private fun updateHomeGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 4..10 -> tr("Selamat pagi", "Good morning")
            in 11..14 -> tr("Selamat siang", "Good afternoon")
            in 15..17 -> tr("Selamat sore", "Good evening")
            else -> tr("Selamat malam", "Good evening")
        }
        binding.tvHomeGreeting.text = greeting
    }

    private fun pickAndShowRandomHeroTrack(animateDirection: Int = 1) {
        if (allTracks.isEmpty()) {
            bindHeroMixTrackUi(null, animateDirection = 0)
            return
        }
        val candidates = if (allTracks.size > 1 && currentHeroTrack != null) {
            allTracks.filter { it.id != currentHeroTrack?.id }
        } else {
            allTracks
        }
        val chosen = candidates.randomOrNull() ?: allTracks.firstOrNull()
        currentHeroTrack = chosen
        bindHeroMixTrackUi(chosen, animateDirection)
        if (chosen != null && chosen.albumArtUri == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val hydrated = MediaRepository.ensureTrackArtwork(chosen)
                if (hydrated.albumArtUri != null) {
                    withContext(Dispatchers.Main) {
                        if (currentHeroTrack?.id == hydrated.id) {
                            currentHeroTrack = hydrated
                            bindHeroMixTrackUi(hydrated, animateDirection = 0)
                        }
                    }
                }
            }
        }
    }

    private fun bindHeroMixTrackUi(track: AudioTrack?, animateDirection: Int = 0) {
        val placeholderRes = AudioSettingsRepository.getDefaultCoverResId()
        val applyContent = {
            if (track != null) {
                binding.tvHeroMixTitle.text = track.title
                val artistClean = if (track.artist.equals("Unknown Artist", true) || track.artist.isBlank()) tr("Tidak diketahui", "Unknown") else track.artist
                val albumClean = if (track.album.equals("Unknown Album", true) || track.album.isBlank()) "" else " · ${track.album}"
                binding.tvHeroMixArtists.text = "$artistClean$albumClean"

                if (track.albumArtUri != null) {
                    Glide.with(this)
                        .load(track.albumArtUri)
                        .centerCrop()
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(binding.ivHeroMixBgCover)
                } else {
                    Glide.with(this)
                        .load(placeholderRes)
                        .centerCrop()
                        .into(binding.ivHeroMixBgCover)
                }
            } else {
                binding.tvHeroMixTitle.text = tr("Belum Ada Lagu", "No Songs Yet")
                binding.tvHeroMixArtists.text = tr("Pindai folder musik di Pustaka untuk memulai", "Scan a music folder in Library to get started")
                Glide.with(this)
                    .load(placeholderRes)
                    .centerCrop()
                    .into(binding.ivHeroMixBgCover)
            }
        }

        if (animateDirection != 0) {
            val slideDistance = 72f * resources.displayMetrics.density
            val slideOutX = if (animateDirection > 0) -slideDistance else slideDistance
            val slideInX = if (animateDirection > 0) slideDistance else -slideDistance
            binding.layoutHeroMixInner.animate().cancel()
            binding.layoutHeroMixInner.animate()
                .alpha(0.15f)
                .translationX(slideOutX)
                .setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    applyContent()
                    binding.layoutHeroMixInner.translationX = slideInX
                    binding.layoutHeroMixInner.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(240)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                .start()
        } else {
            applyContent()
        }
    }

    private fun refreshHomeCarousels() {
        updateHomeGreeting()
        lifecycleScope.launch(Dispatchers.IO) {
            if (allTracks.isNotEmpty() && allTracks.take(40).any { it.albumArtUri == null }) {
                allTracks = allTracks.mapIndexed { idx, tr ->
                    if (idx < 40 && tr.albumArtUri == null) MediaRepository.ensureTrackArtwork(tr) else tr
                }
            }
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val rawRecents = db.getRecentlyPlayedTracks(500).ifEmpty { allTracks }
            val recents = rawRecents.mapIndexed { idx, tr ->
                if (idx < 30 && tr.albumArtUri == null) MediaRepository.ensureTrackArtwork(tr) else tr
            }
            val albums = MediaRepository.getAlbums(allTracks)
            val artists = MediaRepository.getArtists(allTracks)

            if (currentHeroTrack == null && allTracks.isNotEmpty()) {
                currentHeroTrack = MediaRepository.ensureTrackArtwork(allTracks.random())
            } else if (currentHeroTrack != null && currentHeroTrack?.albumArtUri == null) {
                currentHeroTrack = MediaRepository.ensureTrackArtwork(currentHeroTrack!!)
            }

            withContext(Dispatchers.Main) {
                bindHeroMixTrackUi(currentHeroTrack, animateDirection = 0)
                homeRecentCarouselAdapter.updateTracks(recents)
                homeAlbumCarouselAdapter.updateAlbums(albums)
                homeArtistCarouselAdapter.updateArtists(artists)
            }
        }
    }

    /* -------------------------------------------------------------
     * Folder Hierarchy Browsing Controls (Single-Press Back Stack)
     * ------------------------------------------------------------- */
    private fun navigateIntoFolder(targetPath: String) {
        if (targetPath.isBlank() || targetPath == currentFolderPath) return
        folderBackStack.addLast(currentFolderPath)
        currentFolderPath = targetPath
        loadFolderItems()
    }

    private fun navigateFolderUp(): Boolean {
        if (currentFolderPath == "ROOT" || currentFolderPath.isEmpty()) {
            folderBackStack.clear()
            return false
        }
        currentFolderPath = if (folderBackStack.isNotEmpty()) {
            folderBackStack.removeLast()
        } else {
            "ROOT"
        }
        loadFolderItems()
        return true
    }

    private fun setupFolderControls() {
        val backAction: (View) -> Unit = {
            if (!navigateFolderUp()) {
                Toast.makeText(this, "Sudah berada di folder teratas", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnFolderUp.setOnClickListener(backAction)
        binding.btnFolderBackRow.setOnClickListener(backAction)
    }

    private fun loadFolderItems() {
        val inSubfolder = (currentFolderPath != "ROOT" && currentFolderPath.isNotEmpty())
        binding.layoutFolderNavHeader.visibility = if (inSubfolder) View.VISIBLE else View.GONE
        binding.tvFolderCurrentName.text = if (inSubfolder) {
            File(currentFolderPath).name.ifBlank { currentFolderPath }
        } else ""
        lifecycleScope.launch(Dispatchers.IO) {
            val items = MediaRepository.getFolderItems(
                this@MainActivity,
                currentFolderPath,
                allTracks,
                currentFolderSortOrder
            )
            withContext(Dispatchers.Main) {
                folderAdapter.updateItems(items)
            }
        }
    }

    /* -------------------------------------------------------------
     * Queue (Antrean) Screen Interactions (Page 4, 5, 6 in PDF)
     * ------------------------------------------------------------- */
    private fun setupQueueInteractions() {
        queueGroupAdapter = QueueGroupAdapter(
            groups = emptyList(),
            onTrackClick = { group, trackIndex, track ->
                QueueManager.playTrackInGroup(group.id, trackIndex)
            },
            onRemoveTrack = { group, trackIndex, track ->
                QueueManager.removeTrack(group.id, trackIndex)
            },
            onGroupMenuClick = { group, anchor ->
                val popup = PopupMenu(this, anchor)
                val removeLabel = tr("Hapus grup", "Remove group")
                popup.menu.add(removeLabel)
                popup.setOnMenuItemClickListener { item ->
                    if (item.title == removeLabel) {
                        QueueManager.removeGroup(group.id)
                        Toast.makeText(this, tr("Grup '${group.name}' dihapus dari antrean", "Group '${group.name}' removed from queue"), Toast.LENGTH_SHORT).show()
                        true
                    } else false
                }
                popup.show()
            },
            onAddSongToGroupClick = { group ->
                showSelectTracksDialog { selectedTracks ->
                    QueueManager.addCustomTracks(selectedTracks)
                    refreshQueueView()
                    Toast.makeText(this, tr("${selectedTracks.size} lagu ditambahkan ke antrean", "${selectedTracks.size} songs added to queue"), Toast.LENGTH_SHORT).show()
                }
            },
            onToggleExpand = { group ->
                QueueManager.toggleGroupExpanded(group.id)
            }
        )

        binding.rvQueueGroups.layoutManager = LinearLayoutManager(this)
        binding.rvQueueGroups.itemAnimator = null
        binding.rvQueueGroups.setItemViewCacheSize(8)
        binding.rvQueueGroups.adapter = queueGroupAdapter
        queueGroupAdapter.attachToRecyclerView(binding.rvQueueGroups, binding.scrollQueueContent)

        binding.btnBackQueue.setOnClickListener {
            switchNavTab(MainNavTab.BERANDA)
        }

        binding.btnClearAllQueue.setOnClickListener {
            if (QueueManager.groups.value.isEmpty()) {
                Toast.makeText(this, "Antrean sudah kosong", Toast.LENGTH_SHORT).show()
            } else {
                QueueManager.clearQueue()
                Toast.makeText(this, "Antrean telah dikosongkan", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBottomAddQueue.setOnClickListener {
            showAddQueueBottomSheet()
        }

        binding.cardNowPlayingQueue.setOnClickListener {
            showNowPlayingSheet()
        }

        // Live observation of queue groups and playing track
        lifecycleScope.launch {
            QueueManager.groups.collect {
                if (currentNavTab == MainNavTab.ANTREAN && !queueGroupAdapter.isDragging) {
                    refreshQueueView()
                }
            }
        }
        lifecycleScope.launch {
            QueueManager.currentTrack.collect {
                if (currentNavTab == MainNavTab.ANTREAN && !queueGroupAdapter.isDragging) {
                    refreshQueueView()
                }
            }
        }
    }

    private var lastQueueNowPlayingTrackId: Long = -1L
    private var lastQueueNowPlayingOrigin: String = ""

    private fun refreshQueueView() {
        val groups = QueueManager.groups.value
        val currentTrack = QueueManager.currentTrack.value ?: AudioPlayerController.playbackState.value.currentTrack

        val isEmpty = groups.isEmpty() && currentTrack == null

        if (isEmpty) {
            lastQueueNowPlayingTrackId = -1L
            binding.layoutQueueEmpty.visibility = View.VISIBLE
            binding.scrollQueueContent.visibility = View.GONE
        } else {
            binding.layoutQueueEmpty.visibility = View.GONE
            binding.scrollQueueContent.visibility = View.VISIBLE

            // Current playing card (only rebind cover & marquee when track or origin actually changes)
            if (currentTrack != null) {
                binding.tvHeaderNowPlayingQueue.visibility = View.VISIBLE
                binding.cardNowPlayingQueue.visibility = View.VISIBLE
                if (lastQueueNowPlayingTrackId != currentTrack.id || lastQueueNowPlayingOrigin != currentTrack.originGroupName) {
                    lastQueueNowPlayingTrackId = currentTrack.id
                    lastQueueNowPlayingOrigin = currentTrack.originGroupName
                    binding.tvQueueNowPlayingTitle.text = currentTrack.title
                    binding.tvQueueNowPlayingTitle.restartMarquee()
                    val originStr = if (currentTrack.originGroupName.isNotEmpty()) {
                        val dispOrigin = AppLanguageManager.translateString(currentTrack.originGroupName)
                        tr(" · dari $dispOrigin", " · from $dispOrigin")
                    } else ""
                    binding.tvQueueNowPlayingSubtitle.text = "${currentTrack.artist}$originStr"

                    val placeholder = AudioSettingsRepository.getDefaultCoverResId()
                    if (currentTrack.albumArtUri != null) {
                        Glide.with(this)
                            .load(currentTrack.albumArtUri)
                            .override(128, 128)
                            .dontAnimate()
                            .placeholder(placeholder)
                            .error(placeholder)
                            .into(binding.ivQueueNowPlayingThumb)
                    } else {
                        binding.ivQueueNowPlayingThumb.setImageResource(placeholder)
                    }
                }
            } else {
                lastQueueNowPlayingTrackId = -1L
                binding.tvHeaderNowPlayingQueue.visibility = View.GONE
                binding.cardNowPlayingQueue.visibility = View.GONE
            }

            // Next section and groups
            binding.tvHeaderNextQueue.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
            if (binding.rvQueueGroups.childCount == 0 && groups.isNotEmpty()) {
                binding.rvQueueGroups.post {
                    if (!queueGroupAdapter.isDragging) {
                        queueGroupAdapter.updateGroups(QueueManager.groups.value)
                    }
                }
            } else {
                queueGroupAdapter.updateGroups(groups)
            }
        }
    }

    private fun showAddQueueBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Hiresia)
        val sheetBinding = BottomSheetAddQueueBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val scheme = AudioSettingsRepository.getCurrentScheme()
        val cslAccent = ColorStateList.valueOf(scheme.accent)
        val cslOnAccent = ColorStateList.valueOf(scheme.onAccent)

        sheetBinding.root.backgroundTintList = ColorStateList.valueOf(scheme.surface)
        sheetBinding.tvSheetTitle.setTextColor(scheme.text)
        sheetBinding.tvLabelAddSongs.setTextColor(scheme.text)
        sheetBinding.tvLabelAddPlaylist.setTextColor(scheme.text)
        sheetBinding.tvSubAddSongs.setTextColor(scheme.muted)
        sheetBinding.tvSubAddPlaylist.setTextColor(scheme.muted)
        sheetBinding.viewDividerAddQueue.setBackgroundColor(scheme.line)

        // Circle background matches active palette (Aegean Blue #1664B0 on Aegean)
        sheetBinding.layoutIconAddSongs.backgroundTintList = cslAccent
        sheetBinding.layoutIconAddPlaylist.backgroundTintList = cslAccent

        // Logo lambang di menu tambah antrean: HARUS TERANG (scheme.onAccent #F6F7E8)
        sheetBinding.ivIconAddSongs.imageTintList = cslOnAccent
        sheetBinding.ivIconAddPlaylist.imageTintList = cslOnAccent

        sheetBinding.btnOptionAddSongs.setOnClickListener {
            dialog.dismiss()
            showSelectTracksDialog { selectedTracks ->
                QueueManager.addCustomTracks(selectedTracks)
                refreshQueueView()
                binding.scrollQueueContent.post {
                    binding.scrollQueueContent.fullScroll(View.FOCUS_DOWN)
                }
                Toast.makeText(this, tr("${selectedTracks.size} lagu ditambahkan ke antrean", "${selectedTracks.size} songs added to queue"), Toast.LENGTH_SHORT).show()
            }
        }

        sheetBinding.btnOptionAddPlaylist.setOnClickListener {
            dialog.dismiss()
            showSelectPlaylistDialog { playlist, tracks ->
                QueueManager.addPlaylistGroup(playlist.name, tracks, playlist.id)
                refreshQueueView()
                binding.scrollQueueContent.post {
                    binding.scrollQueueContent.fullScroll(View.FOCUS_DOWN)
                }
                Toast.makeText(this, tr("Playlist '${playlist.name}' ditambahkan ke antrean", "Playlist '${playlist.name}' added to queue"), Toast.LENGTH_SHORT).show()
            }
        }

        AppLanguageManager.applyLanguageToViewTree(sheetBinding.root)
        dialog.show()
    }

    private fun showSelectTracksDialog(onSelected: (List<AudioTrack>) -> Unit) {
        if (allTracks.isEmpty()) {
            Toast.makeText(this, tr("Tidak ada lagu di pustaka", "No songs in library"), Toast.LENGTH_SHORT).show()
            return
        }

        val titles = allTracks.map { "${it.title} - ${it.artist}" }.toTypedArray()
        val checkedItems = BooleanArray(titles.size) { false }

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Pilih Lagu untuk Antrean", "Select Songs for Queue"))
            .setMultiChoiceItems(titles, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(tr("Tambahkan", "Add")) { _, _ ->
                val chosen = allTracks.filterIndexed { idx, _ -> checkedItems[idx] }
                if (chosen.isNotEmpty()) {
                    onSelected(chosen)
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showSelectPlaylistDialog(onSelected: (UserPlaylist, List<AudioTrack>) -> Unit) {
        val db = HiresiaLibraryDatabase.getInstance(this)
        val playlists = db.getAllPlaylists()
        if (playlists.isEmpty()) {
            Toast.makeText(this, tr("Belum ada playlist yang dibuat", "No playlists created yet"), Toast.LENGTH_SHORT).show()
            return
        }

        val names = playlists.map { "${it.name} (${AppLanguageManager.formatSongsCount(it.trackCount)})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Pilih Grup Playlist", "Select Playlist Group"))
            .setItems(names) { _, which ->
                val chosenPlaylist = playlists[which]
                val tracks = db.getPlaylistTracks(chosenPlaylist.id)
                if (tracks.isEmpty()) {
                    Toast.makeText(this, tr("Playlist '${chosenPlaylist.name}' masih kosong", "Playlist '${chosenPlaylist.name}' is empty"), Toast.LENGTH_SHORT).show()
                } else {
                    onSelected(chosenPlaylist, tracks)
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    /* -------------------------------------------------------------
     * Real-Time Search (Page 2 & Page 3)
     * ------------------------------------------------------------- */
    private fun setupSearch() {
        // Library Search Box
        binding.etLibrarySearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                binding.btnClearLibrarySearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (currentLibraryTab == LibraryTab.LAGU) {
                    val filtered = if (q.isEmpty()) allTracks else allTracks.filter {
                        it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                    }
                    trackAdapter.updateTracks(filtered)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        binding.btnClearLibrarySearch.setOnClickListener {
            binding.etLibrarySearch.setText("")
        }

        // Global Search Tab Box
        binding.etGlobalSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                binding.btnClearGlobalSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (q.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val results = MediaRepository.searchTracks(this@MainActivity, q)
                        withContext(Dispatchers.Main) {
                            searchAdapter.updateTracks(results)
                        }
                    }
                } else {
                    searchAdapter.updateTracks(emptyList())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        binding.btnClearGlobalSearch.setOnClickListener {
            binding.etGlobalSearch.setText("")
        }
    }

    private fun syncSystemBarColors() {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val topIdleBar = if (AudioSettingsRepository.isObsidianGold) {
            android.graphics.Color.parseColor("#2E2E30")
        } else {
            scheme.page
        }
        if (::binding.isInitialized && binding.layoutNowPlayingSheet.visibility == View.VISIBLE) {
            window.statusBarColor = nowPlayingTopBarColor ?: topIdleBar
            window.navigationBarColor = nowPlayingBottomBarColor ?: scheme.page
        } else {
            window.statusBarColor = topIdleBar
            window.navigationBarColor = scheme.page
        }
    }

    private fun applyNowPlayingBackdrop(bitmap: Bitmap?) {
        lifecycleScope.launch {
            AlbumArtBlurHelper.applyBlurredBackground(
                this@MainActivity,
                bitmap,
                binding.ivNowPlayingBlurredBg,
                binding.viewNowPlayingGradientOverlay
            ) { topColor, bottomColor ->
                nowPlayingTopBarColor = topColor
                nowPlayingBottomBarColor = bottomColor
                syncSystemBarColors()
            }
        }
    }

    private fun showNowPlayingSheet() {
        val sheet = binding.layoutNowPlayingSheet
        if (sheet.visibility == View.VISIBLE) return
        val curState = AudioPlayerController.playbackState.value
        binding.sbNowPlaying.max = curState.totalDurationMs.toInt()
        binding.sbNowPlaying.progress = curState.currentPositionMs.toInt()
        binding.tvNowPlayingPosition.text = formatDuration(curState.currentPositionMs)
        sheet.animate().cancel()
        val h = if (sheet.height > 0) sheet.height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        sheet.translationY = h
        sheet.alpha = 1f
        sheet.visibility = View.VISIBLE
        syncSystemBarColors()
        sheet.animate()
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
        binding.tvNowPlayingTitle.restartMarquee()
    }

    private fun hideNowPlayingSheet() {
        val sheet = binding.layoutNowPlayingSheet
        if (sheet.visibility != View.VISIBLE) return
        sheet.animate().cancel()
        val h = if (sheet.height > 0) sheet.height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        val pageBg = AudioSettingsRepository.getCurrentScheme().page
        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        sheet.animate()
            .translationY(h)
            .setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction {
                sheet.visibility = View.GONE
                sheet.translationY = 0f
                syncSystemBarColors()
            }
            .start()
    }

    /* -------------------------------------------------------------
     * Floating Mini Player (Connected to Now Playing Screen)
     * ------------------------------------------------------------- */
    private fun setupMiniPlayer() {
        binding.layoutMiniPlayer.setOnClickListener {
            showNowPlayingSheet()
        }

        binding.layoutMiniBadge.setOnClickListener {
            AudioOutputBottomSheet.show(this)
        }

        binding.btnMiniPlayPause.setOnClickListener {
            AudioPlayerController.togglePlayPause()
            val isPlaying = AudioPlayerController.playbackState.value.isPlaying
            binding.btnMiniPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause_black else R.drawable.ic_play_black
            )
        }
    }

    /* -------------------------------------------------------------
     * Now Playing Screen (Page 3)
     * ------------------------------------------------------------- */
    private fun setupNowPlayingSheet() {
        binding.btnCollapseNowPlaying.setOnClickListener {
            hideNowPlayingSheet()
        }

        // Tapping the Audio Stream Telemetry Pill opens the Realtime Audio Output Pipeline Inspector
        binding.layoutAudioTelemetryPill.setOnClickListener {
            AudioOutputBottomSheet.show(this)
        }
        binding.tvAudioSourceInfo.setOnClickListener {
            AudioOutputBottomSheet.show(this)
        }
        binding.layoutBitPerfectBadge.setOnClickListener {
            AudioOutputBottomSheet.show(this)
        }
        binding.tvAudioOutputInfo.setOnClickListener {
            AudioOutputBottomSheet.show(this)
        }

        binding.ivNowPlayingCover.setOnClickListener {
            binding.layoutLiveLyricsContainer.visibility = View.VISIBLE
            val lyrics = liveLyricsAdapter.getLyrics()
            val pos = AudioPlayerController.playbackState.value.currentPositionMs
            val activeIdx = lyrics.indexOfLast { it.timeMs <= pos }
            if (activeIdx >= 0) {
                liveLyricsAdapter.setActiveIndex(activeIdx)
                binding.rvLiveLyrics.post {
                    val layoutMgr = binding.rvLiveLyrics.layoutManager as? LinearLayoutManager
                    val rvH = binding.rvLiveLyrics.height
                    val offset = if (rvH > 0) (rvH / 2) - 40 else 80
                    layoutMgr?.scrollToPositionWithOffset(activeIdx, offset)
                }
            }
        }

        binding.btnHeaderLyricsClose.setOnClickListener {
            binding.layoutLiveLyricsContainer.visibility = View.GONE
        }

        binding.btnNowPlayingPlayPause.setOnClickListener {
            AudioPlayerController.togglePlayPause()
            val isPlaying = AudioPlayerController.playbackState.value.isPlaying
            binding.btnNowPlayingPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause_black else R.drawable.ic_play_black
            )
        }

        binding.btnNowPlayingPrev.setOnClickListener {
            AudioPlayerController.previous()
        }

        binding.btnNowPlayingNext.setOnClickListener {
            AudioPlayerController.next()
        }

        binding.btnNowPlayingHeart.setOnClickListener {
            val cur = AudioPlayerController.playbackState.value.currentTrack ?: return@setOnClickListener
            val newLiked = !cur.isLiked
            cur.isLiked = newLiked
            lifecycleScope.launch(Dispatchers.IO) {
                val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                db.setTrackLiked(cur.id, newLiked)
                allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                withContext(Dispatchers.Main) {
                    binding.btnNowPlayingHeart.setImageResource(
                        if (newLiked) R.drawable.ic_card_heart else R.drawable.ic_heart_outline
                    )
                    refreshLibraryView()
                    val msg = if (newLiked) "Ditambahkan ke Disukai" else "Dihapus dari Disukai"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnNowPlayingMenu.setOnClickListener {
            showNowPlayingOptionsMenu()
        }

        binding.btnNowPlayingRepeat.setOnClickListener {
            val newMode = AudioPlayerController.cycleRepeatMode()
            updateNowPlayingRepeatUi(newMode)
        }

        binding.btnNowPlayingQueue.setOnClickListener {
            hideNowPlayingSheet()
            switchNavTab(MainNavTab.ANTREAN)
        }

        binding.sbNowPlaying.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvNowPlayingPosition.text = formatDuration(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                seekBar?.let { AudioPlayerController.seekTo(it.progress.toLong()) }
            }
        })

        // DAC Volume quick pill
        binding.btnNowPlayingVolumeRow.setOnClickListener {
            audiophileVolumeController.showDialog(autoDismiss = false)
        }

        // Equalizer shortcut
        binding.btnNowPlayingEq.setOnClickListener {
            startActivity(Intent(this, EqualizerActivity::class.java))
        }
    }

    private val audiophileVolumeController by lazy {
        AudiophileVolumeController(this) {
            binding.tvNowPlayingVolumeLabel.text = AudioPlayerController.getNowPlayingVolumeLabel()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AudioSettingsRepository.isBlockUsbHidEnabled && UsbAudioDeviceManager.connectedDac.value != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_SEARCH -> return true
            }
        }
        if (audiophileVolumeController.handleDispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (audiophileVolumeController.handleKeyDown(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (audiophileVolumeController.handleKeyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /* -------------------------------------------------------------
     * Reactive StateFlow Observers
     * ------------------------------------------------------------- */
    private fun observePlaybackState() {
        lifecycleScope.launch {
            AudioPlayerController.playbackState.collectLatest { state ->
                updatePlaybackUi(state)
            }
        }
        lifecycleScope.launch {
            AudioPlayerController.volumeFraction.collectLatest {
                binding.tvNowPlayingVolumeLabel.text = AudioPlayerController.getNowPlayingVolumeLabel()
            }
        }
        lifecycleScope.launch {
            AudioPlayerController.routeNoticeEvent.collect { rawNotice ->
                Toast.makeText(
                    this@MainActivity,
                    AppLanguageManager.translateString(rawNotice),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updatePlaybackUi(state: PlaybackState) {
        val track = state.currentTrack
        if (track == null) {
            displayedTrackId = -1L
            displayedLiked = null
            displayedIsPlaying = null
            displayedRepeatMode = null
            displayedOutputDevice = null
            displayedBitPerfect = null
            binding.layoutMiniPlayer.visibility = View.GONE
            return
        }

        // 1. Static Metadata Update (ONLY WHEN TRACK CHANGES - NEVER RESET TEXT WHILE PLAYING!)
        if (displayedTrackId != track.id) {
            displayedTrackId = track.id

            // Mini Player Track info
            if (binding.tvMiniTrackTitle.text.toString() != track.title) {
                binding.tvMiniTrackTitle.text = track.title
            }
            binding.tvMiniTrackTitle.restartMarquee()
            binding.tvMiniTrackArtist.text = track.artist
            binding.layoutMiniBadge.visibility = View.GONE

            val miniPlaceholder = AudioSettingsRepository.getDefaultCoverResId()
            if (track.albumArtUri != null) {
                Glide.with(this).load(track.albumArtUri)
                    .placeholder(miniPlaceholder)
                    .error(miniPlaceholder)
                    .into(binding.ivMiniCoverArt)
            } else {
                Glide.with(this).clear(binding.ivMiniCoverArt)
                binding.ivMiniCoverArt.setImageResource(miniPlaceholder)
            }

            // Now Playing Sheet Track info
            if (binding.tvNowPlayingTitle.text.toString() != track.title) {
                binding.tvNowPlayingTitle.text = track.title
            }
            binding.tvNowPlayingTitle.restartMarquee()
            binding.tvNowPlayingArtist.text = track.artist
            binding.tvNowPlayingDuration.text = formatDuration(state.totalDurationMs)

            binding.tvNowPlayingSubHeader.text = tr("Sedang diputar dari", "Playing from")
            val rawOrigin = if (track.originGroupName.isNotBlank()) track.originGroupName else (currentOpenedPlaylist?.name ?: tr("Pustaka", "Library"))
            binding.tvNowPlayingPlaylistOrigin.text = AppLanguageManager.translateString(rawOrigin)
            binding.tvAudioSourceInfo.text = track.technicalFormatString

            val coverPlaceholder = AudioSettingsRepository.getDefaultCoverResId()
            if (track.albumArtUri != null) {
                Glide.with(this)
                    .asBitmap()
                    .load(track.albumArtUri)
                    .placeholder(coverPlaceholder)
                    .error(coverPlaceholder)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?
                        ) {
                            binding.ivNowPlayingCover.setImageBitmap(resource)
                            applyNowPlayingBackdrop(resource)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            binding.ivNowPlayingCover.setImageDrawable(placeholder)
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            binding.ivNowPlayingCover.setImageResource(coverPlaceholder)
                            applyNowPlayingBackdrop(null)
                        }
                    })
            } else {
                Glide.with(this).clear(binding.ivNowPlayingCover)
                binding.ivNowPlayingCover.setImageResource(coverPlaceholder)
                applyNowPlayingBackdrop(null)
            }

            // Force sync of icons on track change
            displayedLiked = null
            displayedIsPlaying = null
            displayedPositionSec = -1L
            binding.tvNowPlayingVolumeLabel.text = AudioPlayerController.getNowPlayingVolumeLabel()
            refreshHomeCarousels()
        }

        // Visibility of Mini Player
        if (currentNavTab != MainNavTab.ANTREAN) {
            if (binding.layoutMiniPlayer.visibility != View.VISIBLE) {
                binding.layoutMiniPlayer.visibility = View.VISIBLE
            }
        } else {
            if (binding.layoutMiniPlayer.visibility != View.GONE) {
                binding.layoutMiniPlayer.visibility = View.GONE
            }
        }

        // 2. Play/Pause State (ONLY WHEN PLAYING STATE CHANGES)
        if (displayedIsPlaying != state.isPlaying) {
            displayedIsPlaying = state.isPlaying
            val playPauseIcon = if (state.isPlaying) R.drawable.ic_pause_black else R.drawable.ic_play_black
            binding.btnMiniPlayPause.setImageResource(playPauseIcon)
            binding.btnNowPlayingPlayPause.setImageResource(playPauseIcon)
            if (state.isPlaying) {
                binding.tvNowPlayingTitle.restartMarquee()
                binding.tvMiniTrackTitle.restartMarquee()
            }
        }

        // 3. Liked Status (ONLY WHEN LIKED CHANGES)
        if (displayedLiked != track.isLiked) {
            displayedLiked = track.isLiked
            binding.btnNowPlayingHeart.setImageResource(
                if (track.isLiked) R.drawable.ic_card_heart else R.drawable.ic_heart_outline
            )
        }

        // 4. Repeat Mode (ONLY WHEN REPEAT MODE CHANGES)
        if (displayedRepeatMode != state.repeatMode) {
            displayedRepeatMode = state.repeatMode
            updateNowPlayingRepeatUi(state.repeatMode)
        }

        // 5. Output Device & Bit-Perfect Badge (ONLY WHEN CHANGED)
        if (displayedOutputDevice != state.outputDeviceName || displayedBitPerfect != state.isBitPerfect) {
            displayedOutputDevice = state.outputDeviceName
            displayedBitPerfect = state.isBitPerfect
            binding.tvAudioOutputInfo.text = "Output: ${state.outputDeviceName}"
            val bitPerfectLabel = when {
                state.isBitPerfect -> "✓ Bit-perfect"
                state.isHiResDriver -> "✓ Hi-Res Direct"
                state.isUsbExclusive -> "✓ USB Direct"
                else -> "Mixer"
            }
            binding.tvBitPerfectStatus.text = bitPerfectLabel
            binding.tvBitPerfectStatus.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
            binding.ivBitPerfectIcon.setColorFilter(ContextCompat.getColor(this, R.color.on_accent))
        }

        // 6. SeekBar & Position Tick (throttled text update 1x/sec; skip hidden sheet work)
        val sheetVisible = binding.layoutNowPlayingSheet.visibility == View.VISIBLE
        if (!isUserTrackingSeekBar) {
            if (sheetVisible) {
                val maxDur = state.totalDurationMs.toInt()
                if (binding.sbNowPlaying.max != maxDur) {
                    binding.sbNowPlaying.max = maxDur
                }
                binding.sbNowPlaying.progress = state.currentPositionMs.toInt()
            }
            val posSec = state.currentPositionMs / 1000L
            if (displayedPositionSec != posSec) {
                displayedPositionSec = posSec
                binding.tvNowPlayingPosition.text = formatDuration(state.currentPositionMs)
            }
        }

        // 7. Spotify Live Lyrics Syncing
        if (currentLyricsTrackId != track.id) {
            currentLyricsTrackId = track.id
            val immediateLyrics = LiveLyricsManager.loadLyrics(this@MainActivity, track) { loadedTrackId, newLyrics ->
                runOnUiThread {
                    if (currentLyricsTrackId == loadedTrackId) {
                        liveLyricsAdapter.setLyrics(newLyrics)
                        val curPos = AudioPlayerController.playbackState.value.currentPositionMs
                        val active = newLyrics.indexOfLast { it.timeMs <= curPos }
                        if (active >= 0) {
                            liveLyricsAdapter.setActiveIndex(active)
                            val layoutMgr = binding.rvLiveLyrics.layoutManager as? LinearLayoutManager
                            val rvH = binding.rvLiveLyrics.height
                            val offset = if (rvH > 0) (rvH / 2) - 40 else 80
                            layoutMgr?.scrollToPositionWithOffset(active, offset)
                        }
                    }
                }
            }
            liveLyricsAdapter.setLyrics(immediateLyrics)
        }
        if (sheetVisible && binding.layoutLiveLyricsContainer.visibility == View.VISIBLE) {
            val lyrics = liveLyricsAdapter.getLyrics()
            if (lyrics.isNotEmpty()) {
                val activeIdx = lyrics.indexOfLast { it.timeMs <= state.currentPositionMs }
                if (activeIdx >= 0 && liveLyricsAdapter.setActiveIndex(activeIdx)) {
                    val layoutMgr = binding.rvLiveLyrics.layoutManager as? LinearLayoutManager
                    val rvH = binding.rvLiveLyrics.height
                    val offset = if (rvH > 0) (rvH / 2) - 40 else 80
                    layoutMgr?.scrollToPositionWithOffset(activeIdx, offset)
                }
            }
        }
    }

    private fun observeUsbDac() {
        lifecycleScope.launch {
            UsbAudioDeviceManager.connectedDac.collectLatest { dac ->
                if (dac != null) {
                    Toast.makeText(this@MainActivity, "USB DAC Terhubung: ${dac.name}", Toast.LENGTH_SHORT).show()
                }
                AudioPlayerController.syncVolumeToNative()
                binding.tvNowPlayingVolumeLabel.text = AudioPlayerController.getNowPlayingVolumeLabel()
                val isUsb = (dac != null)
                binding.btnNowPlayingVolume.setColorFilter(
                    ContextCompat.getColor(this@MainActivity, if (isUsb) R.color.lime_primary else R.color.text_secondary)
                )
            }
        }
    }

    private fun observeVolume() {
        lifecycleScope.launch {
            AudioPlayerController.volumeFraction.collectLatest {
                binding.tvNowPlayingVolumeLabel.text = AudioPlayerController.getNowPlayingVolumeLabel()
            }
        }
    }

    /* -------------------------------------------------------------
     * Back Press Navigation
     * ------------------------------------------------------------- */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If now playing sheet is open, close it first
                if (binding.layoutNowPlayingSheet.visibility == View.VISIBLE) {
                    hideNowPlayingSheet()
                    return
                }

                // If new playlist screen is open, close it
                if (binding.viewNewPlaylist.visibility == View.VISIBLE) {
                    closeNewPlaylistScreen()
                    return
                }

                // If disukai screen is open, close it
                if (binding.viewDisukai.visibility == View.VISIBLE) {
                    closeDisukaiScreen()
                    return
                }

                // If terakhir screen is open, close it
                if (binding.viewTerakhir.visibility == View.VISIBLE) {
                    closeTerakhirScreen()
                    return
                }

                // If playlist detail is open, close it first
                if (binding.viewPlaylistDetail.visibility == View.VISIBLE) {
                    closePlaylistDetail()
                    return
                }

                // If inside a folder hierarchy, pop 1 visited folder level cleanly
                if (currentNavTab == MainNavTab.PUSTAKA && currentLibraryTab == LibraryTab.FOLDER && currentFolderPath != "ROOT") {
                    if (navigateFolderUp()) return
                }

                // If not on Home tab, return to Home tab
                if (currentNavTab != MainNavTab.BERANDA) {
                    switchNavTab(MainNavTab.BERANDA)
                    return
                }

                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    /* -------------------------------------------------------------
     * Media Library Loader & Database Queries
     * ------------------------------------------------------------- */
    private fun checkPermissionsAndLoad() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun loadMediaLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
            withContext(Dispatchers.Main) {
                val count = allTracks.size
                val totalMs = allTracks.sumOf { it.durationMs }
                val hours = totalMs / (1000 * 60 * 60)
                binding.tvLibraryStats.text = AppLanguageManager.formatSongsHours(count, hours)

                loadPlaylists()
                refreshHomeCarousels()
                refreshLibraryView()
            }
        }
    }

    private fun importAudioFolder(treeUri: Uri) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val dlgBinding = DialogScanLoadingBinding.inflate(layoutInflater)
        dlgBinding.rootScanLoadingDialog.setBackgroundColor(scheme.surface)
        dlgBinding.tvScanLoadingTitle.setTextColor(scheme.text)
        dlgBinding.tvScanLoadingTitle.text = tr("Memindai Folder Musik...", "Scanning Music Folder...")
        dlgBinding.tvScanLoadingCount.setTextColor(scheme.accent)
        dlgBinding.tvScanLoadingCount.text = tr("0 lagu ditemukan", "0 songs found")
        dlgBinding.tvScanLoadingFile.setTextColor(scheme.muted)
        dlgBinding.tvScanLoadingFile.text = tr("Menyiapkan akses folder...", "Preparing folder access...")
        dlgBinding.pbScanSpinner.indeterminateTintList = ColorStateList.valueOf(scheme.accent)
        dlgBinding.pbScanBar.indeterminateTintList = ColorStateList.valueOf(scheme.accent)
        dlgBinding.btnCancelScanLoading.backgroundTintList = ColorStateList.valueOf(scheme.surface2)
        dlgBinding.btnCancelScanLoading.setTextColor(scheme.text)
        dlgBinding.btnCancelScanLoading.text = tr("Batalkan", "Cancel")

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dlgBinding.root)
            .setCancelable(false)
            .create()

        dlgBinding.btnCancelScanLoading.setOnClickListener {
            MediaRepository.abortScan()
            dlgBinding.btnCancelScanLoading.isEnabled = false
            dlgBinding.tvScanLoadingFile.text = tr("Menghentikan pemindaian...", "Stopping scan...")
        }
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            var lastUiUpdate = 0L
            var importedCount = 0
            try {
                val imported = MediaRepository.importFromTreeUri(this@MainActivity, treeUri) { folderName, curFile, count ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= 80L) {
                        lastUiUpdate = now
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                                dlgBinding.tvScanLoadingTitle.text = tr("Memindai: $folderName", "Scanning: $folderName")
                                dlgBinding.tvScanLoadingCount.text = tr("$count lagu ditemukan", "$count songs found")
                                dlgBinding.tvScanLoadingFile.text = curFile
                            }
                        }
                    }
                }
                importedCount = imported.size
                allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    val msg = if (MediaRepository.isScanAborted) {
                        tr("Pemindaian folder dibatalkan", "Folder scan cancelled")
                    } else {
                        tr("Berhasil memindai $importedCount lagu dari folder", "Scanned $importedCount songs from folder")
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    loadMediaLibrary()
                }
            }
        }
    }

    /* -------------------------------------------------------------
     * Playlist CRUD & Detail Operations (Page 1 & 2 & 3 in PDF)
     * ------------------------------------------------------------- */
    private fun loadPlaylists() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val playlists = db.getAllPlaylists()
            withContext(Dispatchers.Main) {
                if (playlists.isEmpty()) {
                    binding.layoutPlaylistEmpty.visibility = View.VISIBLE
                    binding.layoutPlaylistContent.visibility = View.GONE
                } else {
                    binding.layoutPlaylistEmpty.visibility = View.GONE
                    binding.layoutPlaylistContent.visibility = View.VISIBLE
                    playlistAdapter.updatePlaylists(playlists)
                }

                currentOpenedPlaylist?.let { cur ->
                    val updated = playlists.find { it.id == cur.id }
                    if (updated != null) {
                        currentOpenedPlaylist = updated
                        refreshPlaylistDetailUi(updated)
                    }
                }
            }
        }
    }

    private fun showCreatePlaylistDialog(trackToAdd: AudioTrack? = null) {
        val input = EditText(this).apply {
            hint = "Nama Playlist"
            setSingleLine()
            setPadding(48, 36, 48, 36)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tambah Playlist")
            .setView(input)
            .setPositiveButton("Buat") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                        val id = db.createPlaylist(name)
                        if (trackToAdd != null) {
                            db.addTrackToPlaylist(id, trackToAdd.id)
                        }
                        val created = db.getPlaylistById(id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Playlist '$name' dibuat", Toast.LENGTH_SHORT).show()
                            loadPlaylists()
                            if (created != null && trackToAdd == null) {
                                openPlaylistDetail(created)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openPlaylistDetail(playlist: UserPlaylist) {
        currentOpenedPlaylist = playlist
        currentOpenedCollectionTitle = null
        binding.btnPlaylistDetailMenu.visibility = View.VISIBLE
        binding.btnPlaylistAddTracks.visibility = View.VISIBLE
        binding.cardPlaylistDetailCover.isClickable = true
        binding.tvPlaylistDetailTitle.isClickable = true

        binding.viewHome.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                binding.viewHome.visibility = View.GONE
                binding.viewHome.alpha = 1f
                binding.viewPlaylistDetail.alpha = 0f
                binding.viewPlaylistDetail.translationY = 20f
                binding.viewPlaylistDetail.visibility = View.VISIBLE
                binding.viewPlaylistDetail.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
        binding.viewQueue.visibility = View.GONE
        binding.viewSearch.visibility = View.GONE
        binding.viewLibrary.visibility = View.GONE
        refreshPlaylistDetailUi(playlist)
    }

    private fun openCollectionDetail(
        title: String,
        subtitlePrefix: String,
        coverUri: Uri?,
        tracks: List<AudioTrack>
    ) {
        currentOpenedPlaylist = null
        currentOpenedCollectionTitle = title
        binding.btnPlaylistDetailMenu.visibility = View.GONE
        binding.btnPlaylistAddTracks.visibility = View.GONE
        binding.cardPlaylistDetailCover.isClickable = false
        binding.tvPlaylistDetailTitle.isClickable = false

        binding.tvPlaylistDetailTitle.text = title
        val totalMinutes = tracks.sumOf { it.durationMs } / (1000 * 60)
        val prefixTranslated = AppLanguageManager.translateString(subtitlePrefix)
        binding.tvPlaylistDetailStats.text = "$prefixTranslated · ${AppLanguageManager.formatSongsMinutes(tracks.size, totalMinutes)}"

        val placeholder = AudioSettingsRepository.getDefaultCoverResId()
        val effectiveCover = coverUri ?: tracks.firstNotNullOfOrNull { it.albumArtUri }
        if (effectiveCover != null) {
            Glide.with(this)
                .load(effectiveCover)
                .placeholder(placeholder)
                .error(placeholder)
                .into(binding.ivPlaylistDetailCover)
        } else {
            Glide.with(this).clear(binding.ivPlaylistDetailCover)
            binding.ivPlaylistDetailCover.setImageResource(placeholder)
        }
        playlistTrackAdapter.updateTracks(tracks)

        binding.viewHome.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                binding.viewHome.visibility = View.GONE
                binding.viewHome.alpha = 1f
                binding.viewPlaylistDetail.alpha = 0f
                binding.viewPlaylistDetail.translationY = 20f
                binding.viewPlaylistDetail.visibility = View.VISIBLE
                binding.viewPlaylistDetail.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
        binding.viewQueue.visibility = View.GONE
        binding.viewSearch.visibility = View.GONE
        binding.viewLibrary.visibility = View.GONE
    }

    private fun closePlaylistDetail() {
        binding.viewPlaylistDetail.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                binding.viewPlaylistDetail.visibility = View.GONE
                binding.viewPlaylistDetail.alpha = 1f
                binding.viewHome.alpha = 0f
                binding.viewHome.translationY = 20f
                binding.viewHome.visibility = View.VISIBLE
                binding.viewHome.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
        currentOpenedPlaylist = null
        currentOpenedCollectionTitle = null
        loadPlaylists()
    }

    private fun refreshPlaylistDetailUi(playlist: UserPlaylist) {
        binding.tvPlaylistDetailTitle.text = playlist.name

        val placeholder = AudioSettingsRepository.getDefaultCoverResId()
        binding.ivPlaylistDetailCover.setImageResource(placeholder)
        if (playlist.coverUri.isNotEmpty()) {
            val file = File(playlist.coverUri)
            if (file.exists() || playlist.coverUri.startsWith("content://") || playlist.coverUri.startsWith("http")) {
                Glide.with(this)
                    .load(if (file.exists()) file else playlist.coverUri)
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(binding.ivPlaylistDetailCover)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val tracks = db.getPlaylistTracks(playlist.id)
            val totalMinutes = tracks.sumOf { it.durationMs } / (1000 * 60)
            withContext(Dispatchers.Main) {
                binding.tvPlaylistDetailStats.text = AppLanguageManager.formatSongsMinutes(tracks.size, totalMinutes)
                if (playlist.coverUri.isEmpty()) {
                    val firstTrackWithArt = tracks.firstOrNull { it.albumArtUri != null }
                    if (firstTrackWithArt?.albumArtUri != null) {
                        Glide.with(this@MainActivity)
                            .load(firstTrackWithArt.albumArtUri)
                            .placeholder(placeholder)
                            .error(placeholder)
                            .into(binding.ivPlaylistDetailCover)
                    } else {
                        Glide.with(this@MainActivity).clear(binding.ivPlaylistDetailCover)
                        binding.ivPlaylistDetailCover.setImageResource(placeholder)
                    }
                }
                playlistTrackAdapter.updateTracks(tracks)
            }
        }
    }

    private fun showEditPlaylistNameDialog(playlist: UserPlaylist) {
        val input = EditText(this).apply {
            setText(playlist.name)
            setSingleLine()
            setPadding(48, 36, 48, 36)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Ubah Nama Playlist", "Rename Playlist"))
            .setView(input)
            .setPositiveButton(tr("Simpan", "Save")) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                        db.updatePlaylistName(playlist.id, newName)
                        val updated = db.getPlaylistById(playlist.id)
                        withContext(Dispatchers.Main) {
                            currentOpenedPlaylist = updated
                            updated?.let { refreshPlaylistDetailUi(it) }
                            loadPlaylists()
                        }
                    }
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showPlaylistOptionsMenu(playlist: UserPlaylist) {
        val options = arrayOf(
            tr("Ubah Nama Playlist", "Rename Playlist"),
            tr("Ganti Foto Sampul / Logo", "Change Cover Image"),
            tr("Tambah Lagu ke Playlist", "Add Songs to Playlist"),
            tr("Hapus Playlist", "Delete Playlist")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPlaylistNameDialog(playlist)
                    1 -> {
                        currentOpenedPlaylist = playlist
                        pickPlaylistCoverLauncher.launch("image/*")
                    }
                    2 -> showAddTracksToPlaylistDialog(playlist)
                    3 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(tr("Hapus Playlist?", "Delete Playlist?"))
                            .setMessage(tr("Apakah kamu yakin ingin menghapus playlist '${playlist.name}'?", "Are you sure you want to delete playlist '${playlist.name}'?"))
                            .setPositiveButton(tr("Hapus", "Delete")) { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                                    db.deletePlaylist(playlist.id)
                                    withContext(Dispatchers.Main) {
                                        if (currentOpenedPlaylist?.id == playlist.id) {
                                            closePlaylistDetail()
                                        }
                                        loadPlaylists()
                                        Toast.makeText(this@MainActivity, tr("Playlist dihapus", "Playlist deleted"), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton(tr("Batal", "Cancel"), null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showAddTracksToPlaylistDialog(playlist: UserPlaylist) {
        if (allTracks.isEmpty()) {
            Toast.makeText(this, tr("Tidak ada lagu di pustaka", "No songs in library"), Toast.LENGTH_SHORT).show()
            return
        }
        val currentTrackIds = playlist.trackIds.toSet()
        val availableTracks = allTracks.filter { !currentTrackIds.contains(it.id) }
        if (availableTracks.isEmpty()) {
            Toast.makeText(this, tr("Semua lagu sudah ada di playlist ini", "All songs are already in this playlist"), Toast.LENGTH_SHORT).show()
            return
        }
        val titles = availableTracks.map { "${it.title} - ${it.artist}" }.toTypedArray()
        val checked = BooleanArray(titles.size)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Tambah Lagu ke ${playlist.name}", "Add Songs to ${playlist.name}"))
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(tr("Tambahkan", "Add")) { _, _ ->
                val selectedTracks = availableTracks.filterIndexed { index, _ -> checked[index] }
                if (selectedTracks.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                        for (trItem in selectedTracks) {
                            db.addTrackToPlaylist(playlist.id, trItem.id)
                        }
                        val updated = db.getPlaylistById(playlist.id)
                        withContext(Dispatchers.Main) {
                            currentOpenedPlaylist = updated
                            updated?.let { refreshPlaylistDetailUi(it) }
                            loadPlaylists()
                            Toast.makeText(this@MainActivity, tr("${selectedTracks.size} lagu ditambahkan", "${selectedTracks.size} songs added"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showPlaylistTrackOptionsMenu(index: Int, track: AudioTrack) {
        val pl = currentOpenedPlaylist ?: return
        val isLiked = track.isLiked
        val likeOption = if (isLiked) tr("Hapus dari Disukai", "Remove from Liked Songs") else tr("Sukai Lagu", "Like Song")
        val options = arrayOf(
            tr("Hapus dari Playlist", "Remove from Playlist"),
            likeOption,
            tr("Tambahkan ke Antrean", "Add to Queue"),
            tr("Putar Berikutnya", "Play Next"),
            tr("Detail Format Audio", "Audio Format Details")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(track.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                            db.removeTrackFromPlaylist(pl.id, track.id)
                            val updated = db.getPlaylistById(pl.id)
                            withContext(Dispatchers.Main) {
                                currentOpenedPlaylist = updated
                                updated?.let { refreshPlaylistDetailUi(it) }
                                loadPlaylists()
                                Toast.makeText(this@MainActivity, tr("Dihapus dari playlist", "Removed from playlist"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    1 -> {
                        val newLiked = !isLiked
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                            db.setTrackLiked(track.id, newLiked)
                            allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                            withContext(Dispatchers.Main) {
                                refreshLibraryView()
                                val msg = if (newLiked) tr("Ditambahkan ke Disukai", "Added to Liked Songs") else tr("Dihapus dari Disukai", "Removed from Liked Songs")
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    2 -> {
                        AudioPlayerController.addToQueue(track)
                        Toast.makeText(this, tr("Ditambahkan ke antrean", "Added to queue"), Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        AudioPlayerController.playNext(track)
                        Toast.makeText(this, tr("Akan diputar berikutnya", "Playing next"), Toast.LENGTH_SHORT).show()
                    }
                    4 -> showTechnicalDetailsDialog(track)
                }
            }
            .show()
    }

    private fun playCurrentPlaylist(shuffle: Boolean) {
        val tracks = playlistTrackAdapter.getTracks()
        if (tracks.isNotEmpty()) {
            val list = if (shuffle) tracks.shuffled() else tracks
            val plName = currentOpenedPlaylist?.name ?: currentOpenedCollectionTitle ?: "Playlist"
            val plId = currentOpenedPlaylist?.id
            AudioPlayerController.setPlaylist(list, 0, groupName = plName, playlistId = plId)
            Toast.makeText(this, tr("Memutar: $plName", "Playing: $plName"), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, tr("Daftar lagu masih kosong", "Song list is empty"), Toast.LENGTH_SHORT).show()
        }
    }

    /* -------------------------------------------------------------
     * Disukai Screen (Page 5 in PDF)
     * ------------------------------------------------------------- */
    private fun openDisukaiScreen() {
        binding.viewHome.visibility = View.GONE
        binding.viewPlaylistDetail.visibility = View.GONE
        binding.viewQueue.visibility = View.GONE
        binding.viewLibrary.visibility = View.GONE
        binding.viewSearch.visibility = View.GONE
        binding.viewTerakhir.visibility = View.GONE
        binding.viewNewPlaylist.visibility = View.GONE
        binding.viewDisukai.visibility = View.VISIBLE

        loadLikedTracks()
    }

    private fun closeDisukaiScreen() {
        binding.viewDisukai.visibility = View.GONE
        binding.viewHome.visibility = View.VISIBLE
    }

    private fun loadLikedTracks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val liked = db.getLikedTracks()
            withContext(Dispatchers.Main) {
                likedAdapter.updateTracks(liked)
                binding.tvDisukaiCount.text = AppLanguageManager.formatSongsCount(liked.size)
                if (liked.isEmpty()) {
                    binding.layoutDisukaiEmpty.visibility = View.VISIBLE
                    binding.rvDisukaiTracks.visibility = View.GONE
                } else {
                    binding.layoutDisukaiEmpty.visibility = View.GONE
                    binding.rvDisukaiTracks.visibility = View.VISIBLE
                }
            }
        }
    }

    /* -------------------------------------------------------------
     * Terakhir Screen (Page 6 in PDF)
     * ------------------------------------------------------------- */
    private fun openTerakhirScreen() {
        binding.viewHome.visibility = View.GONE
        binding.viewPlaylistDetail.visibility = View.GONE
        binding.viewQueue.visibility = View.GONE
        binding.viewLibrary.visibility = View.GONE
        binding.viewSearch.visibility = View.GONE
        binding.viewDisukai.visibility = View.GONE
        binding.viewNewPlaylist.visibility = View.GONE
        binding.viewTerakhir.visibility = View.VISIBLE

        loadRecentlyPlayedTracks()
    }

    private fun closeTerakhirScreen() {
        binding.viewTerakhir.visibility = View.GONE
        binding.viewHome.visibility = View.VISIBLE
    }

    private fun loadRecentlyPlayedTracks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val recents = db.getRecentlyPlayedTracks(100)
            withContext(Dispatchers.Main) {
                recentTrackAdapter.updateTracks(recents)
                if (recents.isEmpty()) {
                    binding.layoutTerakhirEmpty.visibility = View.VISIBLE
                    binding.rvTerakhirTracks.visibility = View.GONE
                } else {
                    binding.layoutTerakhirEmpty.visibility = View.GONE
                    binding.rvTerakhirTracks.visibility = View.VISIBLE
                }
            }
        }
    }

    /* -------------------------------------------------------------
     * Playlist Baru Screen (Page 3 in PDF)
     * ------------------------------------------------------------- */
    private fun openNewPlaylistScreen() {
        binding.viewHome.visibility = View.GONE
        binding.viewPlaylistDetail.visibility = View.GONE
        binding.viewQueue.visibility = View.GONE
        binding.viewLibrary.visibility = View.GONE
        binding.viewSearch.visibility = View.GONE
        binding.viewDisukai.visibility = View.GONE
        binding.viewTerakhir.visibility = View.GONE
        binding.viewNewPlaylist.visibility = View.VISIBLE

        binding.etNewPlaylistName.setText("")
        selectedNewPlaylistTracks.clear()
        binding.tvNewPlaylistTracksSubtitle.text = tr("Belum ada lagu dipilih", "No songs selected")
        binding.ivNewPlaylistCover.setImageResource(AudioSettingsRepository.getDefaultCoverResId())
    }

    private fun closeNewPlaylistScreen() {
        binding.viewNewPlaylist.visibility = View.GONE
        binding.viewHome.visibility = View.VISIBLE
    }

    /* -------------------------------------------------------------
     * Now Playing Options & Repeat UI
     * ------------------------------------------------------------- */
    private fun showNowPlayingOptionsMenu() {
        val cur = AudioPlayerController.playbackState.value.currentTrack
        val isLiked = cur?.isLiked == true
        val likeOption = if (isLiked) tr("Hapus dari Disukai", "Remove from Liked Songs") else tr("Sukai Lagu", "Like Song")
        val options = arrayOf(
            likeOption,
            tr("Pengaturan Equalizer", "Equalizer Settings"),
            tr("Output Pipeline & Bit-Perfect", "Output Pipeline & Bit-Perfect"),
            tr("Detail Format Audio", "Audio Format Details")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(cur?.title ?: tr("Sedang Diputar", "Now Playing"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> binding.btnNowPlayingHeart.performClick()
                    1 -> startActivity(Intent(this, EqualizerActivity::class.java))
                    2 -> AudioOutputBottomSheet.show(this)
                    3 -> cur?.let { showTechnicalDetailsDialog(it) }
                }
            }
            .show()
    }

    private fun updateNowPlayingRepeatUi(mode: RepeatMode) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        if (mode != RepeatMode.OFF) {
            // ON: Antagonistic high-contrast solid accent pill
            binding.btnNowPlayingRepeat.backgroundTintList = ColorStateList.valueOf(scheme.accent)
            binding.ivNowPlayingRepeatIcon.imageTintList = ColorStateList.valueOf(scheme.onAccent)
            binding.tvNowPlayingRepeatText.setTextColor(scheme.onAccent)
            binding.tvNowPlayingRepeatText.text = tr("Ulangi", "Repeat")
        } else {
            // OFF: Frosted glass surface, muted text & icon letting album backdrop shine softly through
            val glassOff = ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(scheme.surface, 182)
            )
            binding.btnNowPlayingRepeat.backgroundTintList = glassOff
            binding.ivNowPlayingRepeatIcon.imageTintList = ColorStateList.valueOf(scheme.muted)
            binding.tvNowPlayingRepeatText.setTextColor(scheme.muted)
            binding.tvNowPlayingRepeatText.text = tr("Ulangi", "Repeat")
        }
    }

    /* -------------------------------------------------------------
     * Track & Folder Options Menus (Page 2 Interactions)
     * ------------------------------------------------------------- */
    private fun showTrackOptionsDialog(index: Int, track: AudioTrack) {
        val isLiked = track.isLiked
        val likeOption = if (isLiked) tr("Hapus dari Disukai", "Remove from Liked Songs") else tr("Sukai Lagu (Disukai)", "Like Song (Liked Songs)")
        val options = arrayOf(
            likeOption,
            tr("Tambahkan ke Playlist", "Add to Playlist"),
            tr("Tambahkan ke Antrean", "Add to Queue"),
            tr("Putar Berikutnya", "Play Next"),
            tr("Detail Format Audio", "Audio Format Details")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(track.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val newLiked = !isLiked
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
                            db.setTrackLiked(track.id, newLiked)
                            allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                            withContext(Dispatchers.Main) {
                                refreshLibraryView()
                                val msg = if (newLiked) tr("Ditambahkan ke Disukai", "Added to Liked Songs") else tr("Dihapus dari Disukai", "Removed from Liked Songs")
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    1 -> showAddToPlaylistDialog(track)
                    2 -> {
                        AudioPlayerController.addToQueue(track)
                        Toast.makeText(this, tr("Ditambahkan ke antrean", "Added to queue"), Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        AudioPlayerController.playNext(track)
                        Toast.makeText(this, tr("Akan diputar berikutnya", "Playing next"), Toast.LENGTH_SHORT).show()
                    }
                    4 -> showTechnicalDetailsDialog(track)
                }
            }
            .show()
    }

    private fun showAddToPlaylistDialog(track: AudioTrack) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = HiresiaLibraryDatabase.getInstance(this@MainActivity)
            val playlists = db.getAllPlaylists()
            withContext(Dispatchers.Main) {
                val items = mutableListOf(tr("+ Buat Playlist Baru", "+ Create New Playlist"))
                items.addAll(playlists.map { it.name })

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(tr("Tambahkan ke Playlist", "Add to Playlist"))
                    .setItems(items.toTypedArray()) { _, which ->
                        if (which == 0) {
                            showCreatePlaylistDialog(trackToAdd = track)
                        } else {
                            val selected = playlists[which - 1]
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.addTrackToPlaylist(selected.id, track.id)
                                withContext(Dispatchers.Main) {
                                    loadPlaylists()
                                    Toast.makeText(this@MainActivity, tr("Ditambahkan ke '${selected.name}'", "Added to '${selected.name}'"), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showFolderItemOptionsMenu(item: FolderItem) {
        if (item.isDirectory) {
            val options = arrayOf(
                tr("Buka Folder", "Open Folder"),
                tr("Putar Semua Lagu di Sini", "Play All Songs Here"),
                tr("🛑 Berhenti Scan (Hapus dari Pustaka)", "🛑 Stop Scanning (Remove from Library)")
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            navigateIntoFolder(item.path)
                        }
                        1 -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val folderSongs = allTracks.filter { it.path.startsWith(item.path) }
                                withContext(Dispatchers.Main) {
                                    if (folderSongs.isNotEmpty()) {
                                        AudioPlayerController.setPlaylist(folderSongs, 0)
                                        Toast.makeText(this@MainActivity, tr("Memutar ${folderSongs.size} lagu di folder ini", "Playing ${folderSongs.size} songs in this folder"), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, tr("Tidak ada lagu di folder ini", "No songs in this folder"), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        2 -> {
                            MaterialAlertDialogBuilder(this)
                                .setTitle(tr("Berhenti Scan Folder?", "Stop Scanning Folder?"))
                                .setMessage(tr("Folder '${item.name}' dan seluruh lagunya akan dihapus dari pustaka aplikasi.", "Folder '${item.name}' and all its songs will be removed from the app library."))
                                .setPositiveButton(tr("Hapus & Berhenti Scan", "Remove & Stop Scan")) { _, _ ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        MediaRepository.removeFolderAndSongs(this@MainActivity, item)
                                        allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                                        withContext(Dispatchers.Main) {
                                            if (currentFolderPath == item.path || currentFolderPath.startsWith(item.path)) {
                                                folderBackStack.clear()
                                                currentFolderPath = "ROOT"
                                            }
                                            Toast.makeText(this@MainActivity, tr("Folder '${item.name}' dihentikan dari scan", "Stopped scanning folder '${item.name}'"), Toast.LENGTH_SHORT).show()
                                            loadMediaLibrary()
                                            loadFolderItems()
                                        }
                                    }
                                }
                                .setNegativeButton(tr("Batal", "Cancel"), null)
                                .show()
                        }
                    }
                }
                .show()
        } else if (item.audioTrack != null) {
            showTrackOptionsDialog(0, item.audioTrack)
        }
    }

    private fun showTechnicalDetailsDialog(track: AudioTrack) {
        val dispPath = StoragePathUtils.getDisplayablePath(this, track.rawPath.ifEmpty { track.path })
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Detail Teknis Format", "Technical Format Details"))
            .setMessage(
                tr(
                    """
                    Judul: ${track.title}
                    Artis: ${track.artist}
                    Album: ${track.album}
                    Format: ${track.technicalFormatString}
                    Lokasi: $dispPath
                    Ukuran: ${String.format(Locale.US, "%.2f MB", track.sizeBytes / (1024.0 * 1024.0))}
                    """.trimIndent(),
                    """
                    Title: ${track.title}
                    Artist: ${track.artist}
                    Album: ${track.album}
                    Format: ${track.technicalFormatString}
                    Location: $dispPath
                    Size: ${String.format(Locale.US, "%.2f MB", track.sizeBytes / (1024.0 * 1024.0))}
                    """.trimIndent()
                )
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            }
            if (device != null && UsbAudioDeviceManager.isUsbAudioDevice(device)) {
                UsbAudioDeviceManager.connectDevice(device)
            }
        }
    }

    private fun handleIncomingAudioIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        lifecycleScope.launch {
            val track = MediaRepository.importFromUri(this@MainActivity, uri)
            if (track != null) {
                MediaRepository.saveCustomTracks(this@MainActivity, listOf(track))
                allTracks = MediaRepository.loadLocalTracks(this@MainActivity)
                val idx = allTracks.indexOfFirst { it.path == track.path }
                AudioPlayerController.setPlaylist(allTracks, if (idx != -1) idx else 0)
                Toast.makeText(this@MainActivity, "Memutar: ${track.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        audiophileVolumeController.release()
        super.onDestroy()
    }
}
