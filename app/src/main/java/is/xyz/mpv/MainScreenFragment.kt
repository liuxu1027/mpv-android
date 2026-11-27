package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding

    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var lastPath = ""
    private val mediaAdapter = MediaAdapter { video -> playFile(video.uri) }

    private val mediaPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                i.putExtra("allow_dir", true)
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            it.data?.getStringExtra("last_path")?.let { path ->
                lastPath = path
            }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
                return@registerForActivityResult
            }
            it.data?.getStringArrayExtra("paths")?.let { paths ->
                // need to convert to playlist at this point
                if (paths.size == 1) {
                    playFile(paths[0])
                } else {
                    val memoryUri = Utils.makeMemoryPlaylist(paths.asIterable())
                    playFile(memoryUri)
                }
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // we don't care about the result but remember that we've been here
            Log.v(TAG, "returned from player ($it)")
        }
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadMedia() else showEmpty("需要媒体权限才能列出视频")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)

        Utils.handleInsetsAsPadding(binding.root)

        binding.docBtn.setOnClickListener {
            try {
                documentTreeOpener.launch(null)
            } catch (e: ActivityNotFoundException) {
                // Android TV doesn't come with a document picker and certain versions just throw
                // instead of handling this gracefully
                binding.docBtn.isEnabled = false
            }
        }
        binding.urlBtn.setOnClickListener {
            val helper = Utils.OpenUrlDialog(requireContext())
            with (helper) {
                builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                    playFile(helper.text)
                }
                builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                create().show()
            }
        }
        binding.filepickerBtn.setOnClickListener {
            val i = Intent(context, FilePickerActivity::class.java)
            i.putExtra("skip", FilePickerActivity.FILE_PICKER)
            i.putExtra("allow_dir", true)
            if (lastPath != "")
                i.putExtra("default_path", lastPath)
            filePickerLauncher.launch(i)
        }
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(context, PureSettingsActivity::class.java))
        }

        if (BuildConfig.DEBUG) {
            binding.settingsBtn.setOnLongClickListener { showDebugMenu(); true }
        }

        binding.mediaList.layoutManager = LinearLayoutManager(requireContext())
        binding.mediaList.adapter = mediaAdapter

        ensurePermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) loadMedia()
    }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), mediaPermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionAndLoad() {
        if (hasMediaPermission()) loadMedia()
        else permissionLauncher.launch(mediaPermission)
    }

    private fun showEmpty(message: String) {
        binding.mediaList.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyView.text = message
    }

    private fun loadMedia() {
        val resolver = requireContext().contentResolver
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val videos = mutableListOf<VideoItem>()
        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val data = cursor.getString(dataCol) ?: ""
                val dateAdded = cursor.getLong(dateCol)
                val duration = cursor.getLong(durCol)
                val size = cursor.getLong(sizeCol)
                val bucket = cursor.getString(bucketCol)
                val folder = folderOf(data, bucket)
                val contentUri = ContentUris.withAppendedId(uri, id).toString()
                videos.add(VideoItem(id, name, contentUri, folder, duration, dateAdded, size))
            }
        }
        if (videos.isEmpty()) {
            mediaAdapter.submit(emptyList())
            showEmpty("没有找到视频")
            return
        }
        binding.mediaList.isVisible = true
        binding.emptyView.isVisible = false
        // group by folder; folders ordered by their newest video first
        val grouped = videos.groupBy { it.folder }
        val folders = grouped.entries.sortedByDescending { (_, list) -> list.maxOf { it.dateAdded } }
        val items = mutableListOf<MediaListItem>()
        for ((folder, list) in folders) {
            items.add(MediaListItem.Header(folder, list.size))
            list.sortedByDescending { it.dateAdded }.forEach { items.add(MediaListItem.Video(it)) }
        }
        mediaAdapter.submit(items)
    }

    private fun folderOf(data: String, bucket: String?): String {
        if (data.isNotEmpty()) {
            val parent = data.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) return parent.substringAfterLast('/', parent)
        }
        if (!bucket.isNullOrEmpty()) return bucket
        return "其他"
    }

    private fun playFile(filepath: String) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            // if we pass 'filepath' it will go directly to mpv without any processing
            i.putExtra("video_path", filepath)
        }
        i.setClass(requireContext(), PurePlayerActivity::class.java)
        playerLauncher.launch(i)
    }

    private fun showDebugMenu() {
        assert(BuildConfig.DEBUG)
        val context = requireContext()
        with (AlertDialog.Builder(context)) {
            setItems(DEBUG_ACTIVITIES) { dialog, idx ->
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_MAIN)
                intent.setClassName(context, "${context.packageName}.${DEBUG_ACTIVITIES[idx]}")
                startActivity(intent)
            }
            create().show()
        }
    }

    companion object {
        private const val TAG = "mpv"

        // list of debug or testing activities that can be launched
        private val DEBUG_ACTIVITIES = arrayOf(
            "IntentTestActivity",
            "CodecInfoActivity"
        )
    }
}

private data class VideoItem(
    val id: Long,
    val name: String,
    val uri: String,
    val folder: String,
    val durationMs: Long,
    val dateAdded: Long,
    val sizeBytes: Long
)

private sealed class MediaListItem {
    data class Header(val folder: String, val count: Int) : MediaListItem()
    data class Video(val item: VideoItem) : MediaListItem()
}

private class MediaAdapter(
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<MediaListItem> = emptyList()

    fun submit(newItems: List<MediaListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is MediaListItem.Header -> TYPE_HEADER
        is MediaListItem.Video -> TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val v = inflater.inflate(R.layout.item_media_folder, parent, false)
            HeaderViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_media_video, parent, false)
            VideoViewHolder(v, onVideoClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MediaListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is MediaListItem.Video -> (holder as VideoViewHolder).bind(item.item)
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val folderName: TextView = view.findViewById(R.id.folderName)
        fun bind(header: MediaListItem.Header) {
            folderName.text = "${header.folder} (${header.count})"
        }
    }

    private class VideoViewHolder(
        view: View,
        private val onVideoClick: (VideoItem) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val videoName: TextView = view.findViewById(R.id.videoName)
        private val videoMeta: TextView = view.findViewById(R.id.videoMeta)
        private var video: VideoItem? = null

        init {
            view.setOnClickListener { video?.let(onVideoClick) }
        }

        fun bind(item: VideoItem) {
            video = item
            videoName.text = item.name
            videoMeta.text = "${formatDuration(item.durationMs)}  ·  ${formatSize(item.sizeBytes)}  ·  ${formatDate(item.dateAdded)}"
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_VIDEO = 1
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatDate(sec: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sec * 1000))
}
