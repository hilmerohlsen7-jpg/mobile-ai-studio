package com.mobileaistudio.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Lets the person pick this app as the phone's digital assistant (power-button hold or assistant gesture). */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** When the assistant is called, open the app ready to listen (it shows over the lock screen for this visit). */
class AssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("wake", true)
            )
        } catch (e: Exception) {
        }
        hide()
    }
}

/** Android needs a recognition service named in the assistant settings. This one is a stub that is never used for listening. */
class AssistRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) {}
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Quick Settings tile: tap it to start talking to the AI. */
class AssistTile : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("wake", true)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
