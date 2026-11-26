package `is`.xyz.mpv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * 承载 4 个精简版 PurePlayer 设置页的宿主 Activity。
 * 直接使用 android.R.id.content 作为容器，无需额外布局文件。
 */
class PureSettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val CAT_PLAYER = "player"
        const val CAT_DECODER = "decoder"
        const val CAT_AUDIO = "audio"
        const val CAT_SUBTITLE = "subtitle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            val initialFragment = when (intent?.getStringExtra(EXTRA_CATEGORY)) {
                CAT_PLAYER -> CategoryPreferenceFragment.newInstance(R.xml.frag_player, "播放器设置")
                CAT_DECODER -> CategoryPreferenceFragment.newInstance(R.xml.frag_decoder, "解码器设置")
                CAT_AUDIO -> CategoryPreferenceFragment.newInstance(R.xml.frag_audio, "音频设置")
                CAT_SUBTITLE -> CategoryPreferenceFragment.newInstance(R.xml.frag_subtitle, "字幕设置")
                else -> RootSettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, initialFragment)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragment = when (pref.key) {
            CAT_PLAYER -> CategoryPreferenceFragment.newInstance(R.xml.frag_player, "播放器设置")
            CAT_DECODER -> CategoryPreferenceFragment.newInstance(R.xml.frag_decoder, "解码器设置")
            CAT_AUDIO -> CategoryPreferenceFragment.newInstance(R.xml.frag_audio, "音频设置")
            CAT_SUBTITLE -> CategoryPreferenceFragment.newInstance(R.xml.frag_subtitle, "字幕设置")
            else -> return false
        }
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    /**
     * 设置首页：动态构建 4 个分类入口，对应原 preference_header.xml
     */
    class RootSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(ctx)

            val categories = listOf(
                Triple(CAT_PLAYER, "播放器", "界面交互、倍速、续播与画中画"),
                Triple(CAT_DECODER, "解码器", "硬件/软件解码、线程数与去隔行"),
                Triple(CAT_AUDIO, "音频", "输出后端、音量增强、延迟与直通"),
                Triple(CAT_SUBTITLE, "字幕", "目录、编码、同步延迟与渲染模式")
            )

            categories.forEach { (key, title, summary) ->
                screen.addPreference(Preference(ctx).apply {
                    this.key = key
                    this.title = title
                    this.summary = summary
                    setOnPreferenceClickListener {
                        (activity as? OnPreferenceStartFragmentCallback)
                            ?.onPreferenceStartFragment(this@RootSettingsFragment, this) == true
                    }
                })
            }

            preferenceScreen = screen
        }

        override fun onResume() {
            super.onResume()
            activity?.title = "设置"
        }
    }

    /**
     * 通用子设置页 Fragment，加载对应的 frag_*.xml
     */
    class CategoryPreferenceFragment : PreferenceFragmentCompat() {
        companion object {
            private const val ARG_XML_RES = "arg_xml_res"
            private const val ARG_TITLE = "arg_title"

            fun newInstance(xmlResId: Int, title: String) = CategoryPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_XML_RES, xmlResId)
                    putString(ARG_TITLE, title)
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val xmlRes = requireArguments().getInt(ARG_XML_RES)
            setPreferencesFromResource(xmlRes, rootKey)
        }

        override fun onResume() {
            super.onResume()
            activity?.title = requireArguments().getString(ARG_TITLE, "设置")
        }
    }
}
