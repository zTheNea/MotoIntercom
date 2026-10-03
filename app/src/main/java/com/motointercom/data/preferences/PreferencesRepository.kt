package com.motointercom.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for persisting user settings, rider name, and audio preferences.
 */
@Singleton
open class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    protected open val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        const val PREFS_NAME = "motointercom_prefs"
        const val KEY_RIDER_NAME = "rider_name"
        const val KEY_VOX_ENABLED = "vox_enabled"
        const val KEY_SPEAKER_ON = "speaker_on"
        const val KEY_MUSIC_VOLUME = "music_volume"
        const val KEY_MULTITASKING = "multitasking"
        const val KEY_AUDIO_COMPRESSION = "audio_compression"
    }

    open var riderName: String
        get() = prefs.getString(KEY_RIDER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RIDER_NAME, value).apply()

    open var isVoxEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOX_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOX_ENABLED, value).apply()

    open var isSpeakerOn: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKER_ON, value).apply()

    open var musicVolume: Float
        get() = prefs.getFloat(KEY_MUSIC_VOLUME, 0.85f)
        set(value) = prefs.edit().putFloat(KEY_MUSIC_VOLUME, value).apply()

    open var isMultitasking: Boolean
        get() = prefs.getBoolean(KEY_MULTITASKING, true)
        set(value) = prefs.edit().putBoolean(KEY_MULTITASKING, value).apply()

    open var isAudioCompressionEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_COMPRESSION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_COMPRESSION, value).apply()
}
