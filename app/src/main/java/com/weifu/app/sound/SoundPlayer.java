package com.weifu.app.sound;// SoundPlayer.java
import android.content.Context;
import android.media.MediaPlayer;

import com.weifu.app.R;

public class SoundPlayer {
    public static void playNotificationSound(Context context) {
        try {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, R.raw.notification_sound);
            mediaPlayer.setOnCompletionListener(MediaPlayer::release);
            mediaPlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}