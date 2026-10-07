package com.futo.platformplayer.views.others

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import com.futo.platformplayer.R
import com.futo.platformplayer.constructs.Event1
import com.google.android.material.materialswitch.MaterialSwitch

class Toggle : MaterialSwitch {
    var value: Boolean = false
        private set;

    val onValueChanged = Event1<Boolean>();
    private var _settingValue = false;

    constructor(context: Context, attrs: AttributeSet? = null) : super(ContextThemeWrapper(context, R.style.ThemeOverlay_FutoVideo_Switch), attrs) {
        val attrArr = context.obtainStyledAttributes(attrs, R.styleable.Toggle, 0, 0);
        val toggleEnabled = attrArr.getBoolean(R.styleable.Toggle_toggleEnabled, false);
        attrArr.recycle();
        setValue(toggleEnabled, false);

        setOnCheckedChangeListener { _, checked ->
            if (_settingValue)
                return@setOnCheckedChangeListener;
            value = checked;
            onValueChanged.emit(value);
        };
    }

    fun setValue(v: Boolean, animated: Boolean = true, withEvent: Boolean = false) {
        if (value == v && isChecked == v) {
            return;
        }

        value = v;

        _settingValue = true;
        isChecked = v;
        _settingValue = false;
        if (!animated)
            jumpDrawablesToCurrentState();

        if(withEvent)
            onValueChanged.emit(value);
    }
}
