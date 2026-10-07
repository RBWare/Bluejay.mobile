package com.futo.platformplayer.views.announcements

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.futo.platformplayer.BuildConfig
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateUpdate
import com.futo.platformplayer.states.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateBannerView : LinearLayout {
    private val _root: FrameLayout
    private val _iconUpdate: ImageView
    private val _textTitle: TextView
    private val _progressBar: ProgressBar
    private val _buttonAction: FrameLayout
    private val _textAction: TextView

    private val _scope: CoroutineScope?

    constructor(context: Context, attrs: AttributeSet? = null) : super(context, attrs) {
        inflate(context, R.layout.view_update_banner, this)

        _scope = findViewTreeLifecycleOwner()?.lifecycleScope ?: StateApp.instance.scopeOrNull

        _root = findViewById(R.id.root)
        _iconUpdate = findViewById(R.id.icon_update)
        _textTitle = findViewById(R.id.text_title)
        _progressBar = findViewById(R.id.update_banner_progress)
        _buttonAction = findViewById(R.id.button_action)
        _textAction = findViewById(R.id.text_action)

        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        StateUpdate.instance.onUiChanged.subscribe(this) {
            _scope?.launch(Dispatchers.Main) {
                refresh()
            }
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        StateUpdate.instance.onUiChanged.remove(this)
    }

    private fun refresh() {
        val st = StateUpdate.instance
        //Official updates can't be installed over this build, so the banner only informs
        val visible = Settings.instance.autoUpdate.showGrayjayUpdates && st.uiState == UpdateUiState.AVAILABLE

        if (!visible) {
            _root.visibility = View.GONE
            return
        }
        _root.visibility = View.VISIBLE
        _textTitle.text = "Grayjay v${st.uiVersion} available (based on v${BuildConfig.GRAYJAY_BASE_VERSION})"
        _progressBar.visibility = View.GONE
        _buttonAction.visibility = View.GONE
    }

    companion object {
        const val TAG = "UpdateBannerView"
    }
}
