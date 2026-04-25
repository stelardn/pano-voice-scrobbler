package com.arn.scrobble.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.touchlab.kermit.Logger
import com.arn.scrobble.api.lastfm.ScrobbleData
import com.arn.scrobble.pref.MainPrefs
import com.arn.scrobble.pref.TtsAudioFocus
import java.util.Locale

object TrackAnnouncementTts {
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var lastAnnouncedHash: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    fun maybeSpeak(scrobbleData: ScrobbleData, hash: Int, prefs: MainPrefs) {
        if (!prefs.announceTrackWithTts || lastAnnouncedHash == hash) {
            return
        }

        val parts = buildList {
            if (prefs.announceTtsIncludeTrack && scrobbleData.track.isNotBlank()) {
                add(scrobbleData.track)
            }

            if (prefs.announceTtsIncludeArtist && scrobbleData.artist.isNotBlank()) {
                add(scrobbleData.artist)
            }

            if (prefs.announceTtsIncludeAlbum && !scrobbleData.album.isNullOrBlank()) {
                add(scrobbleData.album)
            }
        }

        if (parts.isEmpty()) {
            return
        }

        lastAnnouncedHash = hash
        val text = parts.joinToString(separator = ", ")
        speak(text, prefs.announceTtsAudioFocus)
    }

    private fun speak(text: String, audioFocus: TtsAudioFocus) {
        val context = AndroidStuff.applicationContext
        val tts = getOrInit(context)
        if (!isInitialized || tts == null) {
            pendingText = text
            return
        }

        requestAudioFocus(context, audioFocus)

        tts.language = Locale.getDefault()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "track_announcement")
    }

    private fun getOrInit(context: Context): TextToSpeech? {
        textToSpeech?.let { return it }

        return try {
            TextToSpeech(context) { status ->
                isInitialized = status == TextToSpeech.SUCCESS
                if (!isInitialized) {
                    Logger.e { "TTS init failed with status: $status" }
                    return@TextToSpeech
                }

                pendingText?.let {
                    pendingText = null
                    textToSpeech?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "track_announcement")
                }
            }.also {
                it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        abandonAudioFocus(context)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        abandonAudioFocus(context)
                    }
                })
                textToSpeech = it
            }
        } catch (e: Exception) {
            Logger.e(e) { "Unable to initialize TextToSpeech" }
            null
        }
    }

    private fun requestAudioFocus(context: Context, audioFocus: TtsAudioFocus) {
        if (audioFocus != TtsAudioFocus.DUCK) {
            return
        }

        val audioManager = context.getSystemService(AudioManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = audioFocusRequest ?: return
            audioManager.abandonAudioFocusRequest(focusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
