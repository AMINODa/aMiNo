package com.rinigan.app.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rinigan.app.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class VideoItem(
    val name: String,
    val file: File,
    val size: Long,
    val durationMs: Long,
    val sourceUrl: String? = null
)

data class AudioItem(
    val name: String,
    val file: File,
    val size: Long,
    val durationMs: Long
)

data class MediaUiState(
    val videos: List<VideoItem> = emptyList(),
    val audios: List<AudioItem> = emptyList(),
    val selectedVideoName: String? = null,
    val selectedAudioName: String? = null,
    val isLoading: Boolean = false,
    val totalVideoSizeMb: Double = 0.0,
    val totalVideoDurationSec: Long = 0,
    val linkDownloading: Boolean = false,
    val linkDownloadProgress: Int = 0,
    val linkDownloadError: String? = null
)

class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MediaUiState())
    val uiState: StateFlow<MediaUiState> = _uiState.asStateFlow()

    private val videoDir = File("/storage/emulated/0/DCIM/Camera1/")
    private val configManager = ConfigManager().apply { setContext(application) }

    init {
        if (!videoDir.exists()) videoDir.mkdirs()
        loadMedia()
    }

    fun loadMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }

            val videoExts = setOf("mp4", "mov", "avi", "mkv")
            val videoFiles = videoDir.listFiles { f ->
                videoExts.contains(f.extension.lowercase())
            }?.sorted() ?: emptyList()

            // v6.1: .url descriptors = LIVE links (played online, never downloaded)
            val linkFiles = videoDir.listFiles { f ->
                f.extension.lowercase() == "url"
            }?.sorted() ?: emptyList()

            val linkItems = linkFiles.map { f ->
                VideoItem(
                    name = f.name.removeSuffix(".url"),
                    file = f,
                    size = 0L,
                    durationMs = -1L, // -1 marks a live link in the UI
                    sourceUrl = f.readText().trim()
                )
            }

            val videoItems = (videoFiles.map { f ->
                var dur = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(f.absolutePath)
                    dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) {}
                VideoItem(f.name, f, f.length(), dur, null)
            } + linkItems).sortedBy { it.name.lowercase() }

            val audioExts = setOf("mp3", "wav", "aac", "m4a", "ogg", "flac")
            val audioFiles = videoDir.listFiles { f ->
                audioExts.contains(f.extension.lowercase())
            }?.sorted() ?: emptyList()

            val audioItems = audioFiles.map { f ->
                var dur = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(f.absolutePath)
                    dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) {}
                AudioItem(f.name, f, f.length(), dur)
            }

            configManager.reload()
            val selectedVideo = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null)
            val selectedAudio = configManager.getString(ConfigManager.KEY_SELECTED_AUDIO, null)

            val totalSize = videoFiles.sumOf { it.length() }
            val totalDur = videoItems.sumOf { it.durationMs }

            _uiState.update {
                it.copy(
                    videos = videoItems,
                    audios = audioItems,
                    selectedVideoName = selectedVideo?.let { n ->
                        if (n.startsWith("/")) File(n).name else n
                    },
                    selectedAudioName = selectedAudio,
                    isLoading = false,
                    totalVideoSizeMb = totalSize / 1048576.0,
                    totalVideoDurationSec = totalDur / 1000
                )
            }
        }
    }

    fun selectVideo(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.reload()
            val current = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null)
            val currentName = if (current != null && current.startsWith("/")) File(current).name else current

            if (currentName == name) {
                configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, "")
                _uiState.update { it.copy(selectedVideoName = null) }
            } else {
                // v6.1: link items end with .url — selecting one switches to LIVE mode
                val isLink = name.endsWith(".url")
                val targetPath = File(videoDir, name).absolutePath
                configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, targetPath)
                if (isLink) {
                    val url = try { File(targetPath).readText().trim() } catch (_: Exception) { "" }
                    if (url.isNotEmpty()) {
                        configManager.setString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_STREAM)
                        configManager.setString(ConfigManager.KEY_STREAM_URL, url)
                    }
                } else {
                    configManager.setString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL)
                }
                _uiState.update { it.copy(selectedVideoName = name) }
            }
        }
    }

    fun selectAudio(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.reload()
            val current = configManager.getString(ConfigManager.KEY_SELECTED_AUDIO, null)
            if (current == name) {
                configManager.setString(ConfigManager.KEY_SELECTED_AUDIO, "")
                _uiState.update { it.copy(selectedAudioName = null) }
            } else {
                configManager.setString(ConfigManager.KEY_SELECTED_AUDIO, name)
                _uiState.update { it.copy(selectedAudioName = name) }
            }
        }
    }

    fun deleteFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            file.delete()
            // v6: also clean the video-link marker if present
            try {
                val marker = File(file.parentFile, file.name + ".url")
                if (marker.exists()) marker.delete()
            } catch (_: Exception) {}
            loadMedia()
        }
    }

    fun importFiles(uris: List<android.net.Uri>, isAudio: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            for (uri in uris) {
                try {
                    var name = "import_${System.currentTimeMillis()}"
                    app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = cursor.getString(idx) ?: name
                        }
                    }
                    if (!name.contains(".")) name += if (isAudio) ".mp3" else ".mp4"
                    val dest = File(videoDir, name)
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    dest.setReadable(true, false)
                } catch (_: Exception) {}
            }
            loadMedia()
        }
    }

    // ===== v6.1: LIVE video link (played online in real time — NOTHING is downloaded) =====

    /**
     * Adds a remote video link as a LIVE source. The link is stored as a tiny
     * .url descriptor and becomes active immediately: when the target app opens
     * its camera, the video plays directly from the network in real time.
     */
    fun addVideoFromUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!isValidVideoUrl(url)) {
            _uiState.update { it.copy(linkDownloadError = "invalid_url") }
            return
        }
        if (_uiState.value.linkDownloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(linkDownloading = true, linkDownloadProgress = 0, linkDownloadError = null) }
            try {
                val linkFile = File(videoDir, liveLinkFileName(url))
                linkFile.writeText(url)
                try { linkFile.setReadable(true, false) } catch (_: Exception) {}

                // activate immediately: stream mode + URL + selected
                configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, linkFile.absolutePath)
                configManager.setString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_STREAM)
                configManager.setString(ConfigManager.KEY_STREAM_URL, url)
                _uiState.update {
                    it.copy(
                        linkDownloading = false,
                        linkDownloadProgress = 100,
                        selectedVideoName = linkFile.name
                    )
                }
                loadMedia()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        linkDownloading = false,
                        linkDownloadProgress = 0,
                        linkDownloadError = e.message ?: "error"
                    )
                }
            }
        }
    }

    fun clearLinkError() {
        _uiState.update { it.copy(linkDownloadError = null) }
    }

    companion object {
        private val VIDEO_EXT = Regex("\\.(mp4|mov|avi|mkv)$", RegexOption.IGNORE_CASE)

        fun isValidVideoUrl(url: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            return try {
                java.net.URL(url).toURI()
                true
            } catch (_: Exception) {
                false
            }
        }

        /** Builds the .url descriptor name: Link_<host>_<ts>.url */
        fun liveLinkFileName(url: String): String {
            val host = try {
                java.net.URI(url).host ?: "video"
            } catch (_: Exception) { "video" }
            var name = "Link_${host}_${System.currentTimeMillis() / 1000}.url"
            name = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            return name
        }
    }
}