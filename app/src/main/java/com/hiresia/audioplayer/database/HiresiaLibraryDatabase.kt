package com.hiresia.audioplayer.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.hiresia.audioplayer.HiresiaApplication
import com.hiresia.audioplayer.model.Album
import com.hiresia.audioplayer.model.Artist
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.model.FolderItem
import com.hiresia.audioplayer.model.LibraryFolder
import com.hiresia.audioplayer.model.UserPlaylist
import com.hiresia.audioplayer.repository.FolderSortOrder
import com.hiresia.audioplayer.repository.StoragePathUtils
import java.io.File

/**
 * Hiresia Library SQLite Database Engine.
 * High-performance, self-contained, audiophile-indexed SQLite database.
 *
 * Replaces old Android MediaStore and legacy custom_tracks.json with a high-performance,
 * self-contained, audiophile-indexed SQLite database.
 */
class HiresiaLibraryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "HiresiaLibraryDB"
        private const val DATABASE_NAME = "hiresia_library.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var instance: HiresiaLibraryDatabase? = null

        fun getInstance(context: Context): HiresiaLibraryDatabase {
            return instance ?: synchronized(this) {
                try {
                    val newDb = context.getDatabasePath(DATABASE_NAME)
                    if (!newDb.exists()) {
                        val dbList = context.databaseList() ?: emptyArray()
                        for (dbName in dbList) {
                            if (dbName != DATABASE_NAME && dbName.endsWith("_library.db")) {
                                val candidate = context.getDatabasePath(dbName)
                                if (candidate.exists()) {
                                    candidate.renameTo(newDb)
                                    break
                                }
                            }
                        }
                    }
                } catch (ignored: Exception) {}
                instance ?: HiresiaLibraryDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating Hiresia library database schema...")

        // 1. FolderSearch table — indexed music library roots
        db.execSQL("""
            CREATE TABLE FolderSearch (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                FolderName TEXT UNIQUE NOT NULL,
                UpdateID TEXT DEFAULT '',
                UpdateID2 TEXT DEFAULT '',
                FriendlyName TEXT DEFAULT '',
                ModelNr INTEGER DEFAULT 1,
                TrackCount INTEGER DEFAULT 0,
                LastScanned INTEGER DEFAULT 0
            )
        """.trimIndent())

        // 2. Genre table
        db.execSQL("""
            CREATE TABLE Genre (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT UNIQUE NOT NULL,
                FolderSearchEntryID NUMERIC NOT NULL,
                FOREIGN KEY(FolderSearchEntryID) REFERENCES FolderSearch(_id)
            )
        """.trimIndent())

        // 3. Artist table
        db.execSQL("""
            CREATE TABLE Artist (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT UNIQUE NOT NULL,
                IsAlbumArtist INTEGER DEFAULT 0,
                ArtURL TEXT DEFAULT '',
                Rating NUMERIC DEFAULT 0,
                RatedAt INTEGER DEFAULT 0,
                FolderSearchEntryID NUMERIC NOT NULL,
                FOREIGN KEY(FolderSearchEntryID) REFERENCES FolderSearch(_id)
            )
        """.trimIndent())

        // 4. Composer table
        db.execSQL("""
            CREATE TABLE Composer (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT UNIQUE NOT NULL,
                FolderSearchEntryID NUMERIC NOT NULL,
                FOREIGN KEY(FolderSearchEntryID) REFERENCES FolderSearch(_id)
            )
        """.trimIndent())

        // 5. Album table
        db.execSQL("""
            CREATE TABLE Album (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT NOT NULL,
                AddedAt TEXT DEFAULT '',
                AlbumArtistID NUMERIC NOT NULL,
                GenreID NUMERIC NOT NULL,
                Year NUMERIC DEFAULT 0,
                FolderName TEXT DEFAULT '',
                ArtURL TEXT DEFAULT '',
                SampleRate NUMERIC DEFAULT 0,
                Resolution NUMERIC DEFAULT 0,
                FolderSearchEntryID NUMERIC NOT NULL,
                FOREIGN KEY(AlbumArtistID) REFERENCES Artist(_id),
                FOREIGN KEY(GenreID) REFERENCES Genre(_id),
                FOREIGN KEY(FolderSearchEntryID) REFERENCES FolderSearch(_id)
            )
        """.trimIndent())

        // 6. Song table
        db.execSQL("""
            CREATE TABLE Song (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT NOT NULL,
                AddedAt TEXT DEFAULT '',
                FileName TEXT NOT NULL,
                ArtistID NUMERIC NOT NULL,
                AlbumArtistID NUMERIC NOT NULL,
                ComposerID NUMERIC NOT NULL,
                AlbumID NUMERIC NOT NULL,
                GenreID NUMERIC NOT NULL,
                TrackNr NUMERIC DEFAULT 0,
                DiscNr NUMERIC DEFAULT 1,
                BitRate NUMERIC DEFAULT 0,
                Resolution NUMERIC DEFAULT 16,
                SampleRate NUMERIC DEFAULT 44100,
                Duration REAL DEFAULT 0,
                Channels NUMERIC DEFAULT 2,
                FileType TEXT DEFAULT 'FLAC',
                Year NUMERIC DEFAULT 0,
                ArtURL TEXT DEFAULT '',
                HasEmbeddedArt NUMERIC DEFAULT 0,
                FolderSearchEntryID NUMERIC NOT NULL,
                AddedAtLong INTEGER DEFAULT 0,
                PlayCount INTEGER DEFAULT 0,
                LastTimePlayed INTEGER DEFAULT 0,
                IsLiked NUMERIC DEFAULT 0,
                Active INTEGER DEFAULT 1,
                FOREIGN KEY(ArtistID) REFERENCES Artist(_id),
                FOREIGN KEY(AlbumArtistID) REFERENCES Artist(_id),
                FOREIGN KEY(ComposerID) REFERENCES Composer(_id),
                FOREIGN KEY(AlbumID) REFERENCES Album(_id),
                FOREIGN KEY(GenreID) REFERENCES Genre(_id),
                FOREIGN KEY(FolderSearchEntryID) REFERENCES FolderSearch(_id)
            )
        """.trimIndent())

        // 7. PlayList table
        db.execSQL("""
            CREATE TABLE PlayList (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT NOT NULL,
                PlayListID TEXT NOT NULL,
                UpdatedAt TEXT NOT NULL,
                Description TEXT DEFAULT '',
                REFS TEXT DEFAULT ''
            )
        """.trimIndent())

        // 8. Performance Indexes for instantaneous searching & queries
        db.execSQL("CREATE INDEX idx_song_filename ON Song(FileName)")
        db.execSQL("CREATE INDEX idx_song_folder ON Song(FolderSearchEntryID)")
        db.execSQL("CREATE INDEX idx_song_album ON Song(AlbumID)")
        db.execSQL("CREATE INDEX idx_song_artist ON Song(ArtistID)")
        db.execSQL("CREATE INDEX idx_album_artist ON Album(AlbumArtistID)")
        db.execSQL("CREATE INDEX idx_song_name ON Song(Name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS PlayList")
        db.execSQL("DROP TABLE IF EXISTS Song")
        db.execSQL("DROP TABLE IF EXISTS Album")
        db.execSQL("DROP TABLE IF EXISTS Composer")
        db.execSQL("DROP TABLE IF EXISTS Artist")
        db.execSQL("DROP TABLE IF EXISTS Genre")
        db.execSQL("DROP TABLE IF EXISTS FolderSearch")
        onCreate(db)
    }

    /* -------------------------------------------------------------
     * FolderSearch Operations (Managing Scanned Roots)
     * ------------------------------------------------------------- */

    fun getFolderSearches(): List<LibraryFolder> {
        val list = mutableListOf<LibraryFolder>()
        val db = readableDatabase
        db.query(
            "FolderSearch",
            arrayOf("_id", "FolderName", "FriendlyName", "ModelNr", "TrackCount", "LastScanned"),
            null, null, null, null, "_id ASC"
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("_id")
            val pathCol = cursor.getColumnIndexOrThrow("FolderName")
            val nameCol = cursor.getColumnIndexOrThrow("FriendlyName")
            val modelCol = cursor.getColumnIndexOrThrow("ModelNr")
            val countCol = cursor.getColumnIndexOrThrow("TrackCount")
            val scannedCol = cursor.getColumnIndexOrThrow("LastScanned")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(pathCol)
                val name = cursor.getString(nameCol)
                val modelNr = cursor.getInt(modelCol)
                val count = cursor.getInt(countCol)
                val lastScanned = cursor.getLong(scannedCol)

                list.add(
                    LibraryFolder(
                        id = id,
                        pathOrUri = path,
                        friendlyName = if (name.isNotBlank()) name else path.substringAfterLast('/'),
                        isSaf = (modelNr == 3 || path.startsWith("content://")),
                        trackCount = count,
                        lastScanned = lastScanned
                    )
                )
            }
        }
        return list
    }

    fun addOrGetFolderSearch(pathOrUri: String, friendlyName: String, isSaf: Boolean): Long {
        val db = writableDatabase
        db.query(
            "FolderSearch",
            arrayOf("_id"),
            "FolderName = ?",
            arrayOf(pathOrUri),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }

        val values = ContentValues().apply {
            put("FolderName", pathOrUri)
            put("FriendlyName", friendlyName)
            put("ModelNr", if (isSaf) 3 else 1)
            put("TrackCount", 0)
            put("LastScanned", 0L)
        }
        return db.insertWithOnConflict("FolderSearch", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateFolderStats(folderId: Long, trackCount: Int, lastScanned: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("TrackCount", trackCount)
            put("LastScanned", lastScanned)
        }
        db.update("FolderSearch", values, "_id = ?", arrayOf(folderId.toString()))
    }

    /**
     * Purges a folder from FolderSearch and cascades to all its Songs,
     * Albums, Artists, Genres, and Composers.
     */
    fun removeFolderSearch(folderId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("Song", "FolderSearchEntryID = ?", arrayOf(folderId.toString()))
            db.delete("Album", "FolderSearchEntryID = ?", arrayOf(folderId.toString()))
            db.delete("Artist", "FolderSearchEntryID = ?", arrayOf(folderId.toString()))
            db.delete("Genre", "FolderSearchEntryID = ?", arrayOf(folderId.toString()))
            db.delete("Composer", "FolderSearchEntryID = ?", arrayOf(folderId.toString()))
            db.delete("FolderSearch", "_id = ?", arrayOf(folderId.toString()))
            db.setTransactionSuccessful()
            Log.i(TAG, "Successfully removed folder $folderId and all associated audio items")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing folder $folderId", e)
        } finally {
            db.endTransaction()
        }
    }

    fun removeFolderAndSongs(path: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val searches = getFolderSearches()
            for (fs in searches) {
                if (fs.pathOrUri == path || path.startsWith(fs.pathOrUri) || fs.pathOrUri.startsWith(path)) {
                    db.delete("FolderSearch", "_id = ?", arrayOf(fs.id.toString()))
                }
            }
            db.delete("Song", "FileName LIKE ? OR FileName = ?", arrayOf("$path/%", path))
            db.setTransactionSuccessful()
            Log.i(TAG, "Removed folder and songs for path: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing folder path $path", e)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Migrates any legacy SAF content URIs in FolderSearch and Song tables into direct
     * physical paths (/storage/emulated/0/...) so audio files are played directly from their
     * original locations without SAF overhead or displaying com.android URIs.
     */
    fun migrateSafPathsToPhysical(context: Context) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Migrate FolderSearch
            db.query("FolderSearch", arrayOf("_id", "FolderName", "FriendlyName"), "FolderName LIKE 'content://%'", null, null, null, null).use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow("_id")
                val pathCol = cursor.getColumnIndexOrThrow("FolderName")
                val nameCol = cursor.getColumnIndexOrThrow("FriendlyName")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uriStr = cursor.getString(pathCol)
                    val oldName = cursor.getString(nameCol)
                    val resolved = StoragePathUtils.resolvePhysicalPath(context, Uri.parse(uriStr))
                    if (!resolved.isNullOrEmpty()) {
                        val dir = File(resolved)
                        val newFriendly = if (oldName.startsWith("content://") || oldName == "SAF Folder") dir.name.ifBlank { oldName } else oldName
                        val cv = ContentValues().apply {
                            put("FolderName", resolved)
                            put("FriendlyName", newFriendly)
                            put("ModelNr", 1) // 1 = physical directory
                        }
                        db.update("FolderSearch", cv, "_id = ?", arrayOf(id.toString()))
                        Log.i(TAG, "Migrated FolderSearch $id from $uriStr to $resolved")
                    }
                }
            }

            // 2. Migrate Song
            db.query("Song", arrayOf("_id", "FileName"), "FileName LIKE 'content://%'", null, null, null, null).use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow("_id")
                val fnCol = cursor.getColumnIndexOrThrow("FileName")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uriStr = cursor.getString(fnCol)
                    val resolved = StoragePathUtils.resolvePhysicalPath(context, Uri.parse(uriStr))
                    if (!resolved.isNullOrEmpty()) {
                        val cv = ContentValues().apply {
                            put("FileName", resolved)
                        }
                        db.update("Song", cv, "_id = ?", arrayOf(id.toString()))
                    }
                }
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate SAF paths to physical", e)
        } finally {
            db.endTransaction()
        }
    }

    /* -------------------------------------------------------------
     * Deduplication & Relational Entity Helpers
     * ------------------------------------------------------------- */

    private fun getOrCreateArtist(db: SQLiteDatabase, name: String, folderSearchId: Long, isAlbumArtist: Boolean = false): Long {
        val cleanName = if (name.isBlank()) "Unknown Artist" else name.trim()
        db.query("Artist", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put("Name", cleanName)
            put("IsAlbumArtist", if (isAlbumArtist) 1 else 0)
            put("FolderSearchEntryID", folderSearchId)
        }
        return db.insertWithOnConflict("Artist", null, values, SQLiteDatabase.CONFLICT_IGNORE).takeIf { it != -1L }
            ?: run {
                db.query("Artist", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use {
                    if (it.moveToFirst()) it.getLong(0) else 1L
                }
            }
    }

    private fun getOrCreateGenre(db: SQLiteDatabase, name: String, folderSearchId: Long): Long {
        val cleanName = if (name.isBlank()) "General" else name.trim()
        db.query("Genre", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put("Name", cleanName)
            put("FolderSearchEntryID", folderSearchId)
        }
        return db.insertWithOnConflict("Genre", null, values, SQLiteDatabase.CONFLICT_IGNORE).takeIf { it != -1L }
            ?: run {
                db.query("Genre", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use {
                    if (it.moveToFirst()) it.getLong(0) else 1L
                }
            }
    }

    private fun getOrCreateComposer(db: SQLiteDatabase, name: String, folderSearchId: Long): Long {
        val cleanName = if (name.isBlank()) "Unknown Composer" else name.trim()
        db.query("Composer", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put("Name", cleanName)
            put("FolderSearchEntryID", folderSearchId)
        }
        return db.insertWithOnConflict("Composer", null, values, SQLiteDatabase.CONFLICT_IGNORE).takeIf { it != -1L }
            ?: run {
                db.query("Composer", arrayOf("_id"), "UPPER(Name) = UPPER(?)", arrayOf(cleanName), null, null, null).use {
                    if (it.moveToFirst()) it.getLong(0) else 1L
                }
            }
    }

    private fun getOrCreateAlbum(
        db: SQLiteDatabase,
        albumName: String,
        artistId: Long,
        genreId: Long,
        folderSearchId: Long,
        sampleRate: Int,
        bitDepth: Int
    ): Long {
        val cleanName = if (albumName.isBlank()) "Unknown Album" else albumName.trim()
        db.query(
            "Album",
            arrayOf("_id"),
            "UPPER(Name) = UPPER(?) AND AlbumArtistID = ?",
            arrayOf(cleanName, artistId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put("Name", cleanName)
            put("AlbumArtistID", artistId)
            put("GenreID", genreId)
            put("SampleRate", sampleRate)
            put("Resolution", bitDepth)
            put("FolderSearchEntryID", folderSearchId)
        }
        return db.insertWithOnConflict("Album", null, values, SQLiteDatabase.CONFLICT_IGNORE).takeIf { it != -1L }
            ?: run {
                db.query(
                    "Album",
                    arrayOf("_id"),
                    "UPPER(Name) = UPPER(?) AND AlbumArtistID = ?",
                    arrayOf(cleanName, artistId.toString()),
                    null, null, null
                ).use {
                    if (it.moveToFirst()) it.getLong(0) else 1L
                }
            }
    }

    /* -------------------------------------------------------------
     * Batch Sync & Song Insertion
     * ------------------------------------------------------------- */

    /**
     * Performs an incremental sync:
     * - Retains existing songs, play statistics, ratings
     * - Inserts newly discovered songs
     * - Removes songs no longer on storage
     */
    fun syncFolderSongs(folderSearchId: Long, scannedTracks: List<AudioTrack>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Get currently stored FileNames for this folder
            val existingPaths = HashSet<String>()
            db.query(
                "Song",
                arrayOf("FileName"),
                "FolderSearchEntryID = ?",
                arrayOf(folderSearchId.toString()),
                null, null, null
            ).use { cursor ->
                val fnCol = cursor.getColumnIndexOrThrow("FileName")
                while (cursor.moveToNext()) {
                    existingPaths.add(cursor.getString(fnCol))
                }
            }

            val scannedPaths = scannedTracks.map { it.path }.toSet()

            // 2. Delete tracks that no longer exist
            val toRemove = existingPaths - scannedPaths
            if (toRemove.isNotEmpty()) {
                for (path in toRemove) {
                    db.delete("Song", "FileName = ? AND FolderSearchEntryID = ?", arrayOf(path, folderSearchId.toString()))
                }
                Log.i(TAG, "Removed ${toRemove.size} non-existing songs from folder $folderSearchId")
            }

            // 3. Insert or update new tracks
            for (track in scannedTracks) {
                val artistId = getOrCreateArtist(db, track.artist, folderSearchId)
                val genreId = getOrCreateGenre(db, "General", folderSearchId)
                val composerId = getOrCreateComposer(db, "Unknown Composer", folderSearchId)
                val albumId = getOrCreateAlbum(db, track.album, artistId, genreId, folderSearchId, track.sampleRate, track.bitDepth)

                val values = ContentValues().apply {
                    put("Name", track.title)
                    put("FileName", track.path)
                    put("ArtistID", artistId)
                    put("AlbumArtistID", artistId)
                    put("ComposerID", composerId)
                    put("AlbumID", albumId)
                    put("GenreID", genreId)
                    put("TrackNr", 0)
                    put("DiscNr", 1)
                    put("BitRate", 0)
                    put("Resolution", track.bitDepth)
                    put("SampleRate", track.sampleRate)
                    put("Duration", track.durationMs)
                    put("Channels", track.channels)
                    put("FileType", track.format)
                    put("ArtURL", track.albumArtUri?.path ?: (track.albumArtUri?.toString() ?: ""))
                    put("HasEmbeddedArt", if (track.albumArtUri != null) 1 else 0)
                    put("FolderSearchEntryID", folderSearchId)
                    put("AddedAtLong", System.currentTimeMillis())
                    put("Active", 1)
                }

                if (existingPaths.contains(track.path)) {
                    db.update("Song", values, "FileName = ? AND FolderSearchEntryID = ?", arrayOf(track.path, folderSearchId.toString()))
                } else {
                    db.insert("Song", null, values)
                }
            }

            // 4. Update FolderSearch stats
            val countValues = ContentValues().apply {
                put("TrackCount", scannedTracks.size)
                put("LastScanned", System.currentTimeMillis())
            }
            db.update("FolderSearch", countValues, "_id = ?", arrayOf(folderSearchId.toString()))

            db.setTransactionSuccessful()
            Log.i(TAG, "Synced ${scannedTracks.size} songs for folder ID $folderSearchId successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync folder songs for $folderSearchId", e)
        } finally {
            db.endTransaction()
        }
    }

    /* -------------------------------------------------------------
     * Query Methods (C++ Engine queries)
     * ------------------------------------------------------------- */

    private fun cursorToAudioTrack(cursor: Cursor): AudioTrack {
        val idCol = cursor.getColumnIndexOrThrow("_id")
        val nameCol = cursor.getColumnIndexOrThrow("Name")
        val pathCol = cursor.getColumnIndexOrThrow("FileName")
        val durCol = cursor.getColumnIndexOrThrow("Duration")
        val srCol = cursor.getColumnIndexOrThrow("SampleRate")
        val resCol = cursor.getColumnIndexOrThrow("Resolution")
        val chCol = cursor.getColumnIndexOrThrow("Channels")
        val ftCol = cursor.getColumnIndexOrThrow("FileType")
        val fseCol = cursor.getColumnIndexOrThrow("FolderSearchEntryID")
        val artCol = cursor.getColumnIndexOrThrow("artist_name")
        val albCol = cursor.getColumnIndexOrThrow("album_name")
        val likedCol = cursor.getColumnIndex("IsLiked")
        val lastCol = cursor.getColumnIndex("LastTimePlayed")
        val artUrlCol = cursor.getColumnIndex("ArtURL")

        val path = cursor.getString(pathCol)
        val appCtx = HiresiaApplication.instance
        val resolvedPhysical = if (path.startsWith("content://")) {
            StoragePathUtils.resolvePhysicalPath(appCtx, Uri.parse(path))
        } else null

        val effectivePath = if (resolvedPhysical != null && File(resolvedPhysical).exists()) {
            resolvedPhysical
        } else {
            path
        }
        val effectiveRawPath = resolvedPhysical ?: StoragePathUtils.getDisplayablePath(appCtx, path)

        val format = cursor.getString(ftCol) ?: "AUDIO"
        val isDsd = format.equals("DSD", ignoreCase = true) ||
                format.equals("DSF", ignoreCase = true) ||
                format.equals("DFF", ignoreCase = true)

        val isLiked = if (likedCol != -1) cursor.getInt(likedCol) == 1 else false
        val lastPlayed = if (lastCol != -1) cursor.getLong(lastCol) else 0L

        var artUri: Uri? = null
        if (artUrlCol != -1) {
            val artUrl = cursor.getString(artUrlCol) ?: ""
            if (artUrl.isNotBlank()) {
                try {
                    when {
                        artUrl.startsWith("content://") -> artUri = Uri.parse(artUrl)
                        artUrl.startsWith("file://") -> {
                            val parsedPath = Uri.parse(artUrl).path
                            if (!parsedPath.isNullOrBlank()) {
                                val f = File(parsedPath)
                                if (f.exists() && f.length() > 0) {
                                    artUri = Uri.fromFile(f)
                                }
                            }
                        }
                        else -> {
                            val f = File(artUrl)
                            if (f.exists() && f.length() > 0) {
                                artUri = Uri.fromFile(f)
                            }
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }
        if (artUri == null) {
            try {
                val coversDir = File(HiresiaApplication.instance.cacheDir, "covers")
                val coverFile1 = File(coversDir, "${Math.abs(effectivePath.hashCode())}.jpg")
                val coverFile2 = File(coversDir, "${Math.abs(path.hashCode())}.jpg")
                when {
                    coverFile1.exists() && coverFile1.length() > 0 -> artUri = Uri.fromFile(coverFile1)
                    coverFile2.exists() && coverFile2.length() > 0 -> artUri = Uri.fromFile(coverFile2)
                }
            } catch (ignored: Exception) {}
        }

        return AudioTrack(
            id = cursor.getLong(idCol),
            title = cursor.getString(nameCol),
            artist = cursor.getString(artCol) ?: "Unknown Artist",
            album = cursor.getString(albCol) ?: "Unknown Album",
            path = effectivePath,
            durationMs = cursor.getLong(durCol),
            sampleRate = cursor.getInt(srCol),
            bitDepth = cursor.getInt(resCol),
            channels = cursor.getInt(chCol),
            format = format,
            isDsd = isDsd,
            dsdRate = if (isDsd) 64 else 0,
            albumArtUri = artUri,
            sizeBytes = if (effectivePath.startsWith("/")) File(effectivePath).length() else 0L,
            rawPath = effectiveRawPath,
            isUserImported = true,
            folderSearchId = cursor.getLong(fseCol),
            isLiked = isLiked,
            lastTimePlayed = lastPlayed
        )
    }

    /**
     * Queries all active songs joined with Artist, Album, Genre.
     */
    fun getAllSongs(sortOrder: String = "S.Name COLLATE NOCASE ASC"): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val db = readableDatabase

        val sql = """
            SELECT S._id, S.Name, S.FileName, S.Duration, S.SampleRate, S.Resolution,
                   S.Channels, S.FileType, S.HasEmbeddedArt, S.FolderSearchEntryID,
                   S.IsLiked, S.LastTimePlayed, S.ArtURL,
                   AR.Name AS artist_name, A.Name AS album_name
            FROM Song AS S
            LEFT JOIN Artist AS AR ON S.ArtistID = AR._id
            LEFT JOIN Album AS A ON S.AlbumID = A._id
            WHERE S.Active = 1
            ORDER BY $sortOrder
        """.trimIndent()

        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                tracks.add(cursorToAudioTrack(cursor))
            }
        }
        return tracks
    }

    /**
     * Real-time search across title, artist, album, and path.
     */
    fun searchSongs(query: String): List<AudioTrack> {
        if (query.isBlank()) return getAllSongs()

        val tracks = mutableListOf<AudioTrack>()
        val db = readableDatabase
        val pattern = "%${query.trim()}%"

        val sql = """
            SELECT S._id, S.Name, S.FileName, S.Duration, S.SampleRate, S.Resolution,
                   S.Channels, S.FileType, S.HasEmbeddedArt, S.FolderSearchEntryID,
                   S.IsLiked, S.LastTimePlayed, S.ArtURL,
                   AR.Name AS artist_name, A.Name AS album_name
            FROM Song AS S
            LEFT JOIN Artist AS AR ON S.ArtistID = AR._id
            LEFT JOIN Album AS A ON S.AlbumID = A._id
            WHERE S.Active = 1 AND (
                S.Name LIKE ? OR
                AR.Name LIKE ? OR
                A.Name LIKE ? OR
                S.FileName LIKE ? OR
                S.FileType LIKE ?
            )
            ORDER BY S.Name COLLATE NOCASE ASC
        """.trimIndent()

        db.rawQuery(sql, arrayOf(pattern, pattern, pattern, pattern, pattern)).use { cursor ->
            while (cursor.moveToNext()) {
                tracks.add(cursorToAudioTrack(cursor))
            }
        }
        return tracks
    }

    fun setTrackLiked(trackId: Long, isLiked: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("IsLiked", if (isLiked) 1 else 0)
        }
        db.update("Song", cv, "_id = ?", arrayOf(trackId.toString()))
    }

    fun getLikedTracks(): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val db = readableDatabase
        val sql = """
            SELECT S._id, S.Name, S.FileName, S.Duration, S.SampleRate, S.Resolution,
                   S.Channels, S.FileType, S.HasEmbeddedArt, S.FolderSearchEntryID,
                   S.IsLiked, S.LastTimePlayed, S.ArtURL,
                   AR.Name AS artist_name, A.Name AS album_name
            FROM Song AS S
            LEFT JOIN Artist AS AR ON S.ArtistID = AR._id
            LEFT JOIN Album AS A ON S.AlbumID = A._id
            WHERE S.Active = 1 AND S.IsLiked = 1
            ORDER BY S.Name COLLATE NOCASE ASC
        """.trimIndent()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                tracks.add(cursorToAudioTrack(cursor))
            }
        }
        return tracks
    }

    fun recordTrackPlayed(trackId: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE Song SET PlayCount = PlayCount + 1, LastTimePlayed = ? WHERE _id = ?", arrayOf(now, trackId))
    }

    fun getRecentlyPlayedTracks(limit: Int = 100): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val db = readableDatabase
        val sql = """
            SELECT S._id, S.Name, S.FileName, S.Duration, S.SampleRate, S.Resolution,
                   S.Channels, S.FileType, S.HasEmbeddedArt, S.FolderSearchEntryID,
                   S.IsLiked, S.LastTimePlayed, S.ArtURL,
                   AR.Name AS artist_name, A.Name AS album_name
            FROM Song AS S
            LEFT JOIN Artist AS AR ON S.ArtistID = AR._id
            LEFT JOIN Album AS A ON S.AlbumID = A._id
            WHERE S.Active = 1 AND S.LastTimePlayed > 0
            ORDER BY S.LastTimePlayed DESC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                tracks.add(cursorToAudioTrack(cursor))
            }
        }
        return tracks
    }

    fun clearRecentlyPlayedHistory() {
        val db = writableDatabase
        db.execSQL("UPDATE Song SET LastTimePlayed = 0")
    }

    /* -------------------------------------------------------------
     * Playlist CRUD Operations (PlayList Table)
     * ------------------------------------------------------------- */

    fun createPlaylist(name: String, coverUri: String = "", description: String = ""): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("Name", name.trim())
            put("PlayListID", "pl_${System.currentTimeMillis()}")
            put("UpdatedAt", System.currentTimeMillis().toString())
            put("Description", if (coverUri.isNotBlank()) coverUri else description)
            put("REFS", "")
        }
        return db.insert("PlayList", null, values)
    }

    fun getAllPlaylists(): List<UserPlaylist> {
        val list = mutableListOf<UserPlaylist>()
        val db = readableDatabase
        db.query("PlayList", null, null, null, null, null, "_id DESC").use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("_id")
            val nameCol = cursor.getColumnIndexOrThrow("Name")
            val descCol = cursor.getColumnIndexOrThrow("Description")
            val refsCol = cursor.getColumnIndexOrThrow("REFS")
            val updCol = cursor.getColumnIndexOrThrow("UpdatedAt")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val desc = cursor.getString(descCol) ?: ""
                val refs = cursor.getString(refsCol) ?: ""
                val upd = cursor.getString(updCol)?.toLongOrNull() ?: 0L

                val ids = refs.split(",").mapNotNull { it.trim().toLongOrNull() }
                val tracks = getTracksByIds(ids)
                val totalDuration = tracks.sumOf { it.durationMs }

                list.add(
                    UserPlaylist(
                        id = id,
                        name = name,
                        description = desc,
                        coverUri = desc,
                        trackIds = ids,
                        trackCount = ids.size,
                        totalDurationMs = totalDuration,
                        updatedAt = upd
                    )
                )
            }
        }
        return list
    }

    fun getPlaylistById(playlistId: Long): UserPlaylist? {
        val db = readableDatabase
        db.query("PlayList", null, "_id = ?", arrayOf(playlistId.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                val name = cursor.getString(cursor.getColumnIndexOrThrow("Name"))
                val desc = cursor.getString(cursor.getColumnIndexOrThrow("Description")) ?: ""
                val refs = cursor.getString(cursor.getColumnIndexOrThrow("REFS")) ?: ""
                val upd = cursor.getString(cursor.getColumnIndexOrThrow("UpdatedAt"))?.toLongOrNull() ?: 0L

                val ids = refs.split(",").mapNotNull { it.trim().toLongOrNull() }
                val tracks = getTracksByIds(ids)
                val totalDuration = tracks.sumOf { it.durationMs }

                return UserPlaylist(
                    id = id,
                    name = name,
                    description = desc,
                    coverUri = desc,
                    trackIds = ids,
                    trackCount = ids.size,
                    totalDurationMs = totalDuration,
                    updatedAt = upd
                )
            }
        }
        return null
    }

    fun updatePlaylistName(playlistId: Long, newName: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("Name", newName.trim())
            put("UpdatedAt", System.currentTimeMillis().toString())
        }
        db.update("PlayList", cv, "_id = ?", arrayOf(playlistId.toString()))
    }

    fun updatePlaylistCover(playlistId: Long, coverUri: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("Description", coverUri)
            put("UpdatedAt", System.currentTimeMillis().toString())
        }
        db.update("PlayList", cv, "_id = ?", arrayOf(playlistId.toString()))
    }

    fun deletePlaylist(playlistId: Long) {
        val db = writableDatabase
        db.delete("PlayList", "_id = ?", arrayOf(playlistId.toString()))
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val db = writableDatabase
        val current = getPlaylistById(playlistId) ?: return
        val currentIds = current.trackIds.toMutableList()
        if (!currentIds.contains(trackId)) {
            currentIds.add(trackId)
            val newRefs = currentIds.joinToString(",")
            val cv = ContentValues().apply {
                put("REFS", newRefs)
                put("UpdatedAt", System.currentTimeMillis().toString())
            }
            db.update("PlayList", cv, "_id = ?", arrayOf(playlistId.toString()))
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        val db = writableDatabase
        val current = getPlaylistById(playlistId) ?: return
        val currentIds = current.trackIds.toMutableList()
        if (currentIds.remove(trackId)) {
            val newRefs = currentIds.joinToString(",")
            val cv = ContentValues().apply {
                put("REFS", newRefs)
                put("UpdatedAt", System.currentTimeMillis().toString())
            }
            db.update("PlayList", cv, "_id = ?", arrayOf(playlistId.toString()))
        }
    }

    fun getPlaylistTracks(playlistId: Long): List<AudioTrack> {
        val pl = getPlaylistById(playlistId) ?: return emptyList()
        return getTracksByIds(pl.trackIds)
    }

    fun getTracksByIds(ids: List<Long>): List<AudioTrack> {
        if (ids.isEmpty()) return emptyList()
        val all = getAllSongs()
        val idMap = all.associateBy { it.id }
        return ids.mapNotNull { idMap[it] }
    }

    fun cleanupRootInternalStorage(extStorage: String) {
        val db = writableDatabase
        val searches = getFolderSearches()
        for (fs in searches) {
            if (fs.pathOrUri == extStorage || fs.pathOrUri == "$extStorage/" || fs.friendlyName == "Penyimpanan Internal") {
                removeFolderSearch(fs.id)
                Log.i(TAG, "Removed full internal storage search root ${fs.id} from library")
            }
        }
    }

    /**
     * Queries all albums with artist and track count (Album view).
     */
    fun getAlbums(): List<Album> {
        val albums = mutableListOf<Album>()
        val db = readableDatabase

        val sql = """
            SELECT A._id, A.Name, A.SampleRate, A.Resolution,
                   AR.Name AS artist_name,
                   COUNT(S._id) AS track_count
            FROM Album AS A
            LEFT JOIN Artist AS AR ON A.AlbumArtistID = AR._id
            LEFT JOIN Song AS S ON S.AlbumID = A._id
            GROUP BY A._id
            HAVING track_count > 0
            ORDER BY A.Name COLLATE NOCASE ASC
        """.trimIndent()

        db.rawQuery(sql, null).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("_id")
            val nameCol = cursor.getColumnIndexOrThrow("Name")
            val artCol = cursor.getColumnIndexOrThrow("artist_name")
            val countCol = cursor.getColumnIndexOrThrow("track_count")

            while (cursor.moveToNext()) {
                albums.add(
                    Album(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(nameCol),
                        artist = cursor.getString(artCol) ?: "Unknown Artist",
                        trackCount = cursor.getInt(countCol),
                        albumArtUri = null
                    )
                )
            }
        }
        return albums
    }

    /**
     * Queries all artists with track and album counts (Artist view).
     */
    fun getArtists(): List<Artist> {
        val artists = mutableListOf<Artist>()
        val db = readableDatabase

        val sql = """
            SELECT AR._id, AR.Name,
                   COUNT(DISTINCT A._id) AS album_count,
                   COUNT(S._id) AS track_count
            FROM Artist AS AR
            LEFT JOIN Album AS A ON A.AlbumArtistID = AR._id
            LEFT JOIN Song AS S ON S.ArtistID = AR._id
            GROUP BY AR._id
            HAVING track_count > 0
            ORDER BY AR.Name COLLATE NOCASE ASC
        """.trimIndent()

        db.rawQuery(sql, null).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("_id")
            val nameCol = cursor.getColumnIndexOrThrow("Name")
            val albCol = cursor.getColumnIndexOrThrow("album_count")
            val songCol = cursor.getColumnIndexOrThrow("track_count")

            while (cursor.moveToNext()) {
                artists.add(
                    Artist(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol),
                        trackCount = cursor.getInt(songCol),
                        albumCount = cursor.getInt(albCol)
                    )
                )
            }
        }
        return artists
    }

    /* -------------------------------------------------------------
     * Interactive Folder Hierarchy Browser (re1.java)
     * ------------------------------------------------------------- */

    /**
     * Generates directory items dynamically from indexed SQLite songs and physical folders.
     */
    fun getFolderHierarchyItems(
        context: Context,
        currentPath: String,
        allSongs: List<AudioTrack>,
        sortOrder: FolderSortOrder = FolderSortOrder.NAME_ASC
    ): List<FolderItem> {
        // 1. Root level: Show all registered FolderSearch roots
        if (currentPath == "ROOT" || currentPath.isEmpty()) {
            val searches = getFolderSearches()
            val roots = mutableListOf<FolderItem>()

            for (fs in searches) {
                val childSongs = allSongs.filter { it.folderSearchId == fs.id }
                val resolvedPath = if (fs.pathOrUri.startsWith("content://")) {
                    StoragePathUtils.resolvePhysicalPath(context, Uri.parse(fs.pathOrUri)) ?: fs.pathOrUri
                } else fs.pathOrUri
                val friendly = if (fs.friendlyName.startsWith("content://") || fs.friendlyName == "SAF Folder") {
                    File(resolvedPath).name.ifBlank { fs.friendlyName }
                } else fs.friendlyName

                roots.add(
                    FolderItem(
                        name = friendly,
                        path = resolvedPath,
                        isDirectory = true,
                        childCount = childSongs.size,
                        audioTrack = null,
                        dateModified = fs.lastScanned,
                        folderSearchId = fs.id
                    )
                )
            }
            return roots
        }

        // 2. Physical Directory Browsing
        val items = mutableListOf<FolderItem>()
        val curDir = File(currentPath)
        if (curDir.exists() && curDir.isDirectory) {
            val files = curDir.listFiles() ?: emptyArray()

            // Subdirectories
            for (f in files) {
                if (f.isDirectory && !f.name.startsWith(".")) {
                    val count = f.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                    items.add(
                        FolderItem(
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = true,
                            childCount = count,
                            audioTrack = null,
                            dateModified = f.lastModified(),
                            sizeBytes = 0L
                        )
                    )
                }
            }

            // Audio files inside current directory
            val songsInThisDir = allSongs.filter {
                val f = File(it.path)
                f.parent == currentPath || it.path.substringBeforeLast('/') == currentPath
            }

            for (song in songsInThisDir) {
                val f = File(song.path)
                val size = if (f.exists()) f.length() else song.sizeBytes
                val date = if (f.exists()) f.lastModified() else 0L

                items.add(
                    FolderItem(
                        name = song.title,
                        path = song.path,
                        isDirectory = false,
                        childCount = 0,
                        audioTrack = song,
                        dateModified = date,
                        sizeBytes = size
                    )
                )
            }
        } else {
            // Virtual / SAF Tree fallback browsing
            val matchingSongs = allSongs.filter { it.path.startsWith(currentPath) }
            for (song in matchingSongs) {
                items.add(
                    FolderItem(
                        name = song.title,
                        path = song.path,
                        isDirectory = false,
                        childCount = 0,
                        audioTrack = song,
                        dateModified = 0L,
                        sizeBytes = song.sizeBytes
                    )
                )
            }
        }

        // Apply Sorting
        val sorted = when (sortOrder) {
            FolderSortOrder.NAME_ASC -> items.sortedWith(
                compareByDescending<FolderItem> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            FolderSortOrder.DATE_DESC -> items.sortedWith(
                compareByDescending<FolderItem> { it.isDirectory }.thenByDescending { it.dateModified }
            )
            FolderSortOrder.SIZE_DESC -> items.sortedWith(
                compareByDescending<FolderItem> { it.isDirectory }.thenByDescending { it.sizeBytes }
            )
        }
        return sorted
    }
}
