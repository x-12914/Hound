package com.hound.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records short AAC/MP4 clips one at a time. */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var current: File? = null

    @Suppress("DEPRECATION")
    fun start(): File {
        stop()
        val file = File(
            context.cacheDir,
            "sos_${System.currentTimeMillis()}.m4a",
        )
        current = file
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        return file
    }

    /** Stops and returns the finished file, or null if nothing was recording. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            r.release()
            current
        } catch (e: Exception) {
            r.release()
            current?.delete()
            null
        } finally {
            current = null
        }
    }
}
