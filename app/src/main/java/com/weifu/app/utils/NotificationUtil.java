package com.weifu.app.utils;// NotificationUtil.java
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import com.inuker.bluetooth.library.utils.StringUtils;
import com.weifu.app.MainActivity;
import com.weifu.app.R;

public class NotificationUtil {
    private static final String CHANNEL_ID = "socket_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Notifications";
            String description = "Notification channel for socket messages";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            // 配置提示音
            Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_sound);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            channel.setSound(soundUri, audioAttributes);
            channel.enableVibration(true);
            
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    public static void showNotification(Context context,String title, String message) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_sound);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo_round)
                .setContentTitle(StringUtils.isBlank(title)?"提示" : title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 500, 200, 500}) // 振动模式
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // 生成消息唯一id
        int MSG_ID = Math.toIntExact((long) (Math.random() * 9000000L) + 1000000L);

        manager.notify(MSG_ID, notification);
    }


    public static void showNotificationWithMsgId(Context context,String title, String message,int msgId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_sound);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo_round)
                .setContentTitle(StringUtils.isBlank(title)?"提示" : title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 500, 200, 500}) // 振动模式
//                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        manager.notify(msgId, notification);
    }


    public static void showPendingNotificationWithMsgId(Context context,String title, String message,int msgId,PendingIntent pendingIntent) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
//        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_sound);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo_round)
                .setContentTitle(StringUtils.isBlank(title)?"提示" : title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 500, 200, 500}) // 振动模式
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        manager.notify(msgId, notification);
    }

    /**
     *
     * @param context
     * @param title
     * @param message
     * @param msgId
     * @param pendingIntent
     */
    public static void showPendingNotificationWithProgress(Context context,String title, String message,int msgId,PendingIntent pendingIntent,int max,int progress) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
//        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_sound);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo_round)
                .setContentTitle(StringUtils.isBlank(title)?"提示" : title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
//                .setSound(soundUri)
                .setVibrate(new long[]{0, 500, 200, 500}) // 振动模式
                .setContentIntent(pendingIntent)
                .setProgress(max,progress,false)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        manager.notify(msgId, notification);
    }
}