package com.weifu.app.service;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.weifu.app.R;
import com.weifu.app.js.JsBridge;
import com.weifu.app.sound.SoundPlayer;
import com.weifu.app.utils.NotificationUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SocketServices    extends Service {
    String TAG = getClass().getSimpleName();

    private Socket ioSocket;
    private  String socketUrl;


    private static final String CHANNEL_ID = "SocketServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, createNotification());
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification createNotification() {
        createNotificationChannel();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("服务运行中")
                .setContentText("已连接通知服务器")
                .setSmallIcon(R.drawable.logo_round)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Socket服务通道",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG,"SocketService start.....");
      String   userId = intent.getStringExtra("userId");
      String token = intent.getStringExtra("token");
      socketUrl = intent.getStringExtra("socketUrl");
      this.initSocketIO(userId,token);
        return START_STICKY;
    }

    public  void initSocketIO (String userId, String token){
        try {
            IO.Options options = new IO.Options();
            options.path = "/socket.io";
            options.reconnection= true;
            Map<String,String> auth = new HashMap<>();
            auth.put("andriod","100");
            options.transports = new String[] { "websocket","polling" }; // 禁用Polling
            options.auth = auth;
            options.upgrade= true;
            ioSocket = IO.socket(socketUrl, options);
            Log.d(TAG, "initSocketIO: "+ioSocket.connect());

        } catch (URISyntaxException e) {
            Log.e(TAG, "initSocketIO:  服务器连接异常 ",e );
           showToast("服务器连接异常");

        }
        setupSocketListeners(userId,token);
        ioSocket.connect();
        Log.d(TAG, "initSocketIO: "+ioSocket.connected());

    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void setupSocketListeners(String userId,String token) {
        ioSocket.on(Socket.EVENT_CONNECT, args ->{ Log.d(TAG, "Connected to server");

            JSONObject loginInfo = new JSONObject();
            try {
                loginInfo.put("userId",userId);
                loginInfo.put("token",token);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            ioSocket.emit("login",loginInfo.toString());
        });

        ioSocket.on("msg", args -> {
            String message = (String) args[0];
            Log.d(TAG, "收到消息: " + message);
            sendBoradcast(message,false);

        });
        ioSocket.on(Socket.EVENT_CONNECT_ERROR, args -> Log.d(TAG, args[0].toString()));

        ioSocket.on(Socket.EVENT_DISCONNECT, args ->{ Log.d(TAG, "Disconnected from server");
            sendBoradcast("通知服务连接已经断开!",true);

        });
    }

    /**
     *
     * @param msg
     * @param flag  失败标识
     */
    public  void sendBoradcast(String msg,Boolean flag){
        Intent broadcastIntent = new Intent("SOCKET_ACTION");
        broadcastIntent.putExtra("DATA", msg);
        broadcastIntent.putExtra("FLAG", flag);
        sendBroadcast(broadcastIntent);
    }
}
