package com.hiresia.audioplayer.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.net.URLDecoder

/**
 * StoragePathUtils
 *
 * Resolves Android SAF (Storage Access Framework) URIs, MediaStore URIs, and
 * DocumentFile references into direct physical filesystem paths (/storage/emulated/0/...)
 * for C++ Engine bit-perfect audio streaming.
 *
 * Ensures audio files and folders are accessed and played in-place from their original
 * storage location without copying or moving files into app-internal storage.
 */
object StoragePathUtils {
    private const val TAG = "StoragePathUtils"

    /**
     * Resolves a Content URI into its real physical storage path (e.g. /storage/emulated/0/Music/track.flac).
     * Returns null if it cannot be mapped to a physical path.
     */
    fun resolvePhysicalPath(context: Context, uri: Uri): String? {
        try {
            val scheme = uri.scheme ?: return null
            if (scheme.equals("file", ignoreCase = true)) {
                return uri.path
            }
            if (!scheme.equals("content", ignoreCase = true)) {
                return null
            }

            val auth = uri.authority ?: ""

            // 1. External Storage Documents Provider (com.android.externalstorage.documents)
            if (auth == "com.android.externalstorage.documents") {
                val docId = try {
                    when {
                        DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                        DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                        else -> extractDocIdFromUri(uri)
                    }
                } catch (e: Exception) {
                    extractDocIdFromUri(uri)
                }

                if (!docId.isNullOrEmpty()) {
                    val resolved = parseDocumentIdToPath(context, docId)
                    if (resolved != null) {
                        return resolved
                    }
                }
            }

            // 2. Media Documents Provider (com.android.providers.media.documents)
            if (auth == "com.android.providers.media.documents") {
                try {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":")
                    val type = split[0]
                    if (type == "audio" || type == "document") {
                        val id = split.getOrNull(1)
                        if (id != null) {
                            val contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            val selection = "${MediaStore.Audio.Media._ID} = ?"
                            val selectionArgs = arrayOf(id)
                            context.contentResolver.query(
                                contentUri,
                                arrayOf(MediaStore.Audio.Media.DATA),
                                selection,
                                selectionArgs,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                                    if (idx != -1) {
                                        val path = cursor.getString(idx)
                                        if (!path.isNullOrBlank()) return path
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Media documents resolve error: ${e.message}")
                }
            }

            // 3. Direct MediaStore Audio URI (content://media/external/audio/media/123)
            if (auth == "media") {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.Audio.Media.DATA),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                            if (idx != -1) {
                                val path = cursor.getString(idx)
                                if (!path.isNullOrBlank()) return path
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore resolve error: ${e.message}")
                }
            }

            // 4. Raw URI string fallback parsing
            val rawUri = uri.toString()
            if (rawUri.contains("com.android.externalstorage.documents")) {
                val extracted = extractPathFromRawUriString(context, rawUri)
                if (extracted != null) {
                    return extracted
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolvePhysicalPath failed for $uri: ${e.message}")
        }
        return null
    }

    /**
     * Extracts Document ID from URI path segments if DocumentsContract fails
     */
    private fun extractDocIdFromUri(uri: Uri): String? {
        val path = uri.path ?: return null
        val decoded = try { URLDecoder.decode(path, "UTF-8") } catch (e: Exception) { path }

        val docMarker = "/document/"
        val treeMarker = "/tree/"

        return when {
            decoded.contains(docMarker) -> decoded.substringAfter(docMarker)
            decoded.contains(treeMarker) -> decoded.substringAfter(treeMarker)
            else -> uri.lastPathSegment
        }
    }

    /**
     * Parses documentId (e.g. "primary:Music/flac/song.flac" or "1234-5678:Audio") to real physical path.
     */
    private fun parseDocumentIdToPath(context: Context, docId: String): String? {
        val decodedId = try { URLDecoder.decode(docId, "UTF-8") } catch (e: Exception) { docId }
        val colonIdx = decodedId.indexOf(':')
        if (colonIdx == -1) return null

        val volumeId = decodedId.substring(0, colonIdx)
        val relativePath = decodedId.substring(colonIdx + 1).trimStart('/')

        val cleanSubPath = if (relativePath.isNotEmpty()) "/$relativePath" else ""

        if (volumeId.equals("primary", ignoreCase = true)) {
            val primaryRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
            return primaryRoot + cleanSubPath
        }

        // Secondary storage (microSD card or USB OTG drive)
        val candidate = File("/storage/$volumeId$cleanSubPath")
        if (candidate.exists()) {
            return candidate.absolutePath
        }

        // Search mounted external app dirs to find actual storage volume root
        val extDirs = context.getExternalFilesDirs(null)
        for (dir in extDirs) {
            if (dir != null) {
                val path = dir.absolutePath
                val appIdx = path.indexOf("/Android/data")
                if (appIdx != -1) {
                    val root = path.substring(0, appIdx)
                    if (root.contains(volumeId, ignoreCase = true)) {
                        return root + cleanSubPath
                    }
                }
            }
        }

        return candidate.absolutePath
    }

    /**
     * Fallback parser for raw URI strings containing com.android.externalstorage.documents
     */
    private fun extractPathFromRawUriString(context: Context, uriString: String): String? {
        val decoded = try { URLDecoder.decode(uriString, "UTF-8") } catch (e: Exception) { uriString }
        val marker = if (decoded.contains("/document/")) "/document/" else "/tree/"
        val sub = decoded.substringAfter(marker, "")
        if (sub.isNotEmpty()) {
            return parseDocumentIdToPath(context, sub)
        }
        return null
    }

    /**
     * Sanitizes any path or URI for display. If it's a content URI, decodes to real physical path.
     */
    fun getDisplayablePath(context: Context, pathOrUri: String?): String {
        if (pathOrUri.isNullOrBlank()) return ""
        if (pathOrUri.startsWith("/")) return pathOrUri

        if (pathOrUri.startsWith("content://")) {
            val resolved = resolvePhysicalPath(context, Uri.parse(pathOrUri))
            if (!resolved.isNullOrEmpty()) {
                return resolved
            }
            // If still cannot resolve, at least show decoded human-readable folder name
            val decoded = try { URLDecoder.decode(pathOrUri, "UTF-8") } catch (e: Exception) { pathOrUri }
            if (decoded.contains("primary:")) {
                return "/storage/emulated/0/" + decoded.substringAfter("primary:").trimStart('/')
            }
        }
        return pathOrUri
    }
}
