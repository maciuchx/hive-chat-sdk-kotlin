package com.hivehd.chat.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Records a voice note as AAC-in-MP4.
 *
 * `audio/mp4` is chosen over the alternatives because it is the one recording
 * format that survives every leg of the journey: the agent dashboard plays it
 * in an `<audio>` element, and it is on the narrow list of media WhatsApp
 * accepts, so a chat that later moves to WhatsApp does not fail with a 63021.
 * `.3gp`/AMR would record smaller and sound worse; Ogg is fine in browsers and
 * rejected by WhatsApp.
 *
 * Not a Composable and not tied to a screen: recording outlives a
 * recomposition, and a recorder leaked across one holds the microphone.
 */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns false if recording could not start — the caller shows the error. */
    fun start(): Boolean {
        if (recorder != null) return true
        return runCatching {
            val file = File.createTempFile("hive-voice-", ".m4a", context.cacheDir)
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                /* Speech, not music. 32 kbps mono at 44.1 kHz keeps a minute
                   under a quarter of a megabyte, which matters on a phone
                   uploading over cellular against a 5 MB cap. */
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            true
        }.getOrElse {
            release()
            false
        }
    }

    /**
     * Stops and returns the recording, or null if it was too short to be
     * anything but a mis-tap.
     */
    fun stopAndTake(): File? {
        val file = outputFile
        val stoppedCleanly = runCatching { recorder?.stop() }.isSuccess
        release()

        if (!stoppedCleanly || file == null || !file.exists() || file.length() < MIN_BYTES) {
            file?.delete()
            return null
        }
        return file
    }

    /** Abandons the recording and deletes the file. */
    fun cancel() {
        runCatching { recorder?.stop() }
        release()
        outputFile?.delete()
        outputFile = null
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    private companion object {
        /* An MP4 header alone is a few hundred bytes, so anything at this size
           carries no audio — a tap that never became a recording. */
        const val MIN_BYTES = 1_200L
    }
}
