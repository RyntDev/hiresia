package com.hiresia.audioplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.hiresia.audioplayer.model.Album
import com.hiresia.audioplayer.model.Artist
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository

/**
 * Custom artwork canvas view for "Terakhir diputar" square cards
 * and "Album" vinyl sleeve cards (dark vinyl disc peeking out on the right side).
 */
class PastelCardArtView(
    context: Context,
    private val isVinylAlbumStyle: Boolean
) : View(context) {

    var cardBgColor: Int = Color.parseColor("#DF6E46")
    var accentQuarterOrRingColor: Int = Color.parseColor("#C75A34")
    var vinylDiscColor: Int = Color.parseColor("#1A3222")
    var vinylGrooveColor: Int = Color.parseColor("#294A34")
    var vinylLabelColor: Int = Color.parseColor("#C8EE6B")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val corner = 22f * d

        if (isVinylAlbumStyle) {
            val sleeveSize = h
            // 1. Draw dark vinyl disc peeking out on the right side of the album cover
            val discCx = sleeveSize * 0.72f
            val discCy = h * 0.50f
            val discR = h * 0.46f
            paint.style = Paint.Style.FILL
            paint.color = vinylDiscColor
            canvas.drawCircle(discCx, discCy, discR, paint)

            paint.style = Paint.Style.STROKE
            paint.color = vinylGrooveColor
            paint.strokeWidth = 1.2f * d
            for (i in 1..5) {
                canvas.drawCircle(discCx, discCy, discR - i * 4.5f * d, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = vinylLabelColor
            canvas.drawCircle(discCx, discCy, discR * 0.30f, paint)

            // 2. Base rounded square sleeve on the left
            rect.set(0f, 0f, sleeveSize, h)
            paint.style = Paint.Style.FILL
            paint.color = cardBgColor
            canvas.drawRoundRect(rect, corner, corner, paint)
        } else {
            rect.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rect, corner, corner, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)

            paint.style = Paint.Style.FILL
            paint.color = cardBgColor
            canvas.drawRect(rect, paint)

            paint.color = accentQuarterOrRingColor
            canvas.drawCircle(w * 0.90f, h * 0.90f, w * 0.42f, paint)
            canvas.restore()
        }
    }
}

private data class PastelStyle(
    val bg: Int,
    val sub: Int,
    val ring: Int
)

private fun getPastelStyles(isAegean: Boolean, isObsidian: Boolean = AudioSettingsRepository.isObsidianGold): List<PastelStyle> {
    if (isObsidian) {
        return listOf(
            PastelStyle(
                bg = Color.parseColor("#262628"),
                sub = Color.parseColor("#323236"),
                ring = Color.parseColor("#F3EAC3")
            ),
            PastelStyle(
                bg = Color.parseColor("#2B2922"),
                sub = Color.parseColor("#3A362B"),
                ring = Color.parseColor("#F3EAC3")
            ),
            PastelStyle(
                bg = Color.parseColor("#222225"),
                sub = Color.parseColor("#2F2F33"),
                ring = Color.parseColor("#D8C994")
            ),
            PastelStyle(
                bg = Color.parseColor("#2E2C27"),
                sub = Color.parseColor("#3D3A31"),
                ring = Color.parseColor("#F3EAC3")
            )
        )
    }
    val darkPrimary = if (isAegean) Color.parseColor("#14263B") else Color.parseColor("#1A3222")
    return listOf(
        PastelStyle(
            bg = Color.parseColor("#DF6E46"),
            sub = Color.parseColor("#C65933"),
            ring = Color.parseColor("#F5EEDC")
        ),
        PastelStyle(
            bg = Color.parseColor("#B9D5E8"),
            sub = Color.parseColor("#8BB5D1"),
            ring = darkPrimary
        ),
        PastelStyle(
            bg = Color.parseColor("#D8C7F2"),
            sub = Color.parseColor("#B8A1DF"),
            ring = darkPrimary
        ),
        PastelStyle(
            bg = if (isAegean) Color.parseColor("#82C4FF") else Color.parseColor("#C8EE6B"),
            sub = if (isAegean) Color.parseColor("#5CA8EC") else Color.parseColor("#A7D149"),
            ring = darkPrimary
        )
    )
}

/* =========================================================================
 * 1. HOME RECENT TRACKS HORIZONTAL ADAPTER ("Terakhir diputar")
 *    Displays the song's real cover art (or palette default cover)
 * ========================================================================= */
class HomeRecentCarouselAdapter(
    private var tracks: List<AudioTrack>,
    private val onTrackClick: (Int, AudioTrack) -> Unit
) : RecyclerView.Adapter<HomeRecentCarouselAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val artView: PastelCardArtView,
        val coverImg: ImageView,
        val tvTitle: TextView,
        val tvArtist: TextView
    ) : RecyclerView.ViewHolder(root)

    fun updateTracks(newList: List<AudioTrack>) {
        tracks = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val sizePx = (126 * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(sizePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (14 * d).toInt()
            }
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        val artView = PastelCardArtView(ctx, isVinylAlbumStyle = false).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val coverImg = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(artView)
        frame.addView(coverImg)

        val tvTitle = TextView(ctx).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * d).toInt()
            }
        }
        val tvArtist = TextView(ctx).apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }

        root.addView(frame)
        root.addView(tvTitle)
        root.addView(tvArtist)
        return VH(root, artView, coverImg, tvTitle, tvArtist)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = tracks[position]
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val isAegean = AudioSettingsRepository.paletteIndex == AudioSettingsRepository.PALETTE_C_AEGEAN
        val styles = getPastelStyles(isAegean)
        val style = styles[position % styles.size]

        holder.tvTitle.text = track.title
        holder.tvTitle.setTextColor(scheme.text)
        holder.tvArtist.text = if (track.artist.isBlank() || track.artist.equals("Unknown Artist", true)) tr("Tidak diketahui", "Unknown") else track.artist
        holder.tvArtist.setTextColor(scheme.muted)

        holder.artView.cardBgColor = style.bg
        holder.artView.accentQuarterOrRingColor = style.sub
        holder.artView.invalidate()

        val cornerPx = (22 * holder.root.context.resources.displayMetrics.density).toInt()
        val placeholderRes = AudioSettingsRepository.getDefaultCoverResId()
        holder.coverImg.visibility = View.VISIBLE
        if (track.albumArtUri != null) {
            Glide.with(holder.root.context)
                .load(track.albumArtUri)
                .transform(CenterCrop(), RoundedCorners(cornerPx))
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .into(holder.coverImg)
        } else {
            Glide.with(holder.root.context)
                .load(placeholderRes)
                .transform(CenterCrop(), RoundedCorners(cornerPx))
                .into(holder.coverImg)
        }

        holder.root.setOnClickListener {
            onTrackClick(position, track)
        }
    }

    override fun getItemCount(): Int = tracks.size
}

/* =========================================================================
 * 2. HOME ALBUMS HORIZONTAL ADAPTER ("Album" with Vinyl Disc Sleeve + Cover Art)
 * ========================================================================= */
class HomeAlbumCarouselAdapter(
    private var albums: List<Album>,
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<HomeAlbumCarouselAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val artView: PastelCardArtView,
        val coverImg: ImageView,
        val tvTitle: TextView,
        val tvArtist: TextView
    ) : RecyclerView.ViewHolder(root)

    fun updateAlbums(newList: List<Album>) {
        albums = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val widthPx = (156 * d).toInt()
        val heightPx = (132 * d).toInt()
        val sleevePx = (132 * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (14 * d).toInt()
            }
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx)
        }
        val artView = PastelCardArtView(ctx, isVinylAlbumStyle = true).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val coverImg = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(sleevePx, heightPx)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(artView)
        frame.addView(coverImg)

        val tvTitle = TextView(ctx).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * d).toInt()
            }
        }
        val tvArtist = TextView(ctx).apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }

        root.addView(frame)
        root.addView(tvTitle)
        root.addView(tvArtist)
        return VH(root, artView, coverImg, tvTitle, tvArtist)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val album = albums[position]
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val isAegean = AudioSettingsRepository.paletteIndex == AudioSettingsRepository.PALETTE_C_AEGEAN
        val styles = getPastelStyles(isAegean)
        val style = styles[position % styles.size]

        holder.tvTitle.text = album.title
        holder.tvTitle.setTextColor(scheme.text)
        holder.tvArtist.text = if (album.artist.isBlank() || album.artist.equals("Unknown Artist", true)) tr("Tidak diketahui", "Unknown") else album.artist
        holder.tvArtist.setTextColor(scheme.muted)

        val isObsidian = AudioSettingsRepository.isObsidianGold
        holder.artView.cardBgColor = style.bg
        holder.artView.accentQuarterOrRingColor = style.ring
        holder.artView.vinylDiscColor = when {
            isObsidian -> Color.parseColor("#18181A")
            isAegean -> Color.parseColor("#14263B")
            else -> Color.parseColor("#1A3222")
        }
        holder.artView.vinylGrooveColor = when {
            isObsidian -> Color.parseColor("#2B2B2F")
            isAegean -> Color.parseColor("#244466")
            else -> Color.parseColor("#2C4E37")
        }
        holder.artView.vinylLabelColor = when {
            isObsidian -> Color.parseColor("#F3EAC3")
            isAegean -> Color.parseColor("#82C4FF")
            else -> Color.parseColor("#C8EE6B")
        }
        holder.artView.invalidate()

        val cornerPx = (22 * holder.root.context.resources.displayMetrics.density).toInt()
        val placeholderRes = AudioSettingsRepository.getDefaultCoverResId()
        holder.coverImg.visibility = View.VISIBLE
        if (album.albumArtUri != null) {
            Glide.with(holder.root.context)
                .load(album.albumArtUri)
                .transform(CenterCrop(), RoundedCorners(cornerPx))
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .into(holder.coverImg)
        } else {
            Glide.with(holder.root.context)
                .load(placeholderRes)
                .transform(CenterCrop(), RoundedCorners(cornerPx))
                .into(holder.coverImg)
        }

        holder.root.setOnClickListener {
            onAlbumClick(album)
        }
    }

    override fun getItemCount(): Int = albums.size
}

/* =========================================================================
 * 3. HOME ARTISTS HORIZONTAL ADAPTER ("Artis" Circular Cover Art + Initials)
 * ========================================================================= */
class HomeArtistCarouselAdapter(
    private var artists: List<Artist>,
    private val onArtistClick: (Artist) -> Unit
) : RecyclerView.Adapter<HomeArtistCarouselAdapter.VH>() {

    class CircleInitialView(context: Context) : View(context) {
        var circleColor: Int = Color.parseColor("#C8EE6B")
        var textColor: Int = Color.parseColor("#1A2018")
        var initialText: String = "L"

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            circlePaint.color = circleColor
            canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.49f, circlePaint)

            textPaint.color = textColor
            textPaint.textSize = h * 0.34f
            val fm = textPaint.fontMetrics
            val ty = h * 0.5f - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(initialText, w * 0.5f, ty, textPaint)
        }
    }

    class VH(
        val root: LinearLayout,
        val circleView: CircleInitialView,
        val avatarImg: ImageView,
        val tvName: TextView
    ) : RecyclerView.ViewHolder(root)

    fun updateArtists(newList: List<Artist>) {
        artists = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val sizePx = (84 * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams((92 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
        }
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        val circleView = CircleInitialView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val avatarImg = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        frame.addView(circleView)
        frame.addView(avatarImg)

        val tvName = TextView(ctx).apply {
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * d).toInt()
            }
        }
        root.addView(frame)
        root.addView(tvName)
        return VH(root, circleView, avatarImg, tvName)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val artist = artists[position]
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val isObsidian = AudioSettingsRepository.isObsidianGold
        val isAegean = AudioSettingsRepository.paletteIndex == AudioSettingsRepository.PALETTE_C_AEGEAN

        val avatarColors = listOf(
            when {
                isObsidian -> Color.parseColor("#F3EAC3")
                isAegean -> Color.parseColor("#82C4FF")
                else -> Color.parseColor("#C8EE6B")
            },
            Color.parseColor("#B9D5E8"),
            Color.parseColor("#D8C7F2"),
            Color.parseColor("#DF6E46")
        )

        val cleanName = artist.name.trim()
        val initial = when {
            cleanName.isEmpty() || cleanName.equals("Unknown Artist", true) || cleanName.equals("Tidak diketahui", true) -> "?"
            else -> cleanName.first().uppercaseChar().toString()
        }
        val displayName = if (cleanName.equals("Unknown Artist", true) || cleanName.equals("Tidak diketahui", true)) tr("Tidak diketahui", "Unknown") else cleanName

        holder.circleView.circleColor = avatarColors[position % avatarColors.size]
        holder.circleView.textColor = Color.parseColor("#182018")
        holder.circleView.initialText = initial
        holder.circleView.invalidate()

        if (artist.albumArtUri != null) {
            holder.avatarImg.visibility = View.VISIBLE
            Glide.with(holder.root.context)
                .load(artist.albumArtUri)
                .transform(CircleCrop())
                .into(holder.avatarImg)
        } else {
            Glide.with(holder.root.context).clear(holder.avatarImg)
            holder.avatarImg.visibility = View.GONE
        }

        holder.tvName.text = displayName
        holder.tvName.setTextColor(scheme.text)

        holder.root.setOnClickListener {
            onArtistClick(artist)
        }
    }

    override fun getItemCount(): Int = artists.size
}
