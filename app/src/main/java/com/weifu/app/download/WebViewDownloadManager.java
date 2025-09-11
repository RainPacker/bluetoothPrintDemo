package com.weifu.app.download;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.weifu.app.BuildConfig;
import com.weifu.app.utils.NotificationUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.lang.ref.WeakReference;

public class WebViewDownloadManager {

    private static final String TAG = "WebViewDownloadManager";
    private static final int MSG_UPDATE_PROGRESS = 1;
    private static final int MSG_DOWNLOAD_COMPLETE = 2;
    private static final int MSG_DOWNLOAD_FAILED = 3;

    private Context mContext;
    private String mDownloadUrl;
    private String mFileName;
    private String mTitle;
    private int mNotificationId;
    private boolean mIsInterceptDownload = false;
    private int mProgress = 0;
    private long mLastUpdateTime = 0;
    private OnDownloadListener mDownloadListener;

    public interface OnDownloadListener {
        void onDownloadStart(String url, String fileName);
        void onDownloadProgress(int progress);
        void onDownloadComplete(File file);
        void onDownloadFailed(String error);
    }

    public WebViewDownloadManager(Context context) {
        this.mContext = context;
        NotificationUtil.createUpdateNotificationChannel(context);
    }

    public void setOnDownloadListener(OnDownloadListener listener) {
        this.mDownloadListener = listener;
    }

    public void startDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        this.mDownloadUrl = url;
        this.mFileName = getFileNameFromUrl(url, contentDisposition);
        this.mTitle = "文件下载";
        this.mNotificationId = (int) System.currentTimeMillis();
        this.mIsInterceptDownload = false;
        this.mProgress = 0;
        this.mLastUpdateTime = 0;

        if (mDownloadListener != null) {
            mDownloadListener.onDownloadStart(url, mFileName);
        }

        // 初始化上次更新时间
        mLastUpdateTime = System.currentTimeMillis();
        
        // 开始下载线程
        new Thread(new DownloadRunnable()).start();
    }

    public void cancelDownload() {
        mIsInterceptDownload = true;
    }

    private String getFileNameFromUrl(String url, String contentDisposition) {
        // 尝试从contentDisposition获取文件名
        if (contentDisposition != null) {
            int index = contentDisposition.indexOf("filename=");
            if (index > 0) {
                String fileName = contentDisposition.substring(index + 10);
                if (fileName.endsWith("\"") || fileName.endsWith("'") || fileName.endsWith(";")) {
                    fileName = fileName.substring(0, fileName.length() - 1);
                }
                return fileName;
            }
        }

        // 从URL获取文件名
        String fileName = URLUtil.guessFileName(url, null, null);
        if (fileName == null || fileName.trim().isEmpty()) {
            // 如果无法从URL获取文件名，使用当前时间戳生成一个
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            fileName = "download_" + sdf.format(new Date());
        }
        return fileName;
    }

    private class DownloadRunnable implements Runnable {
        @Override
        public void run() {
            try {
                URL url = new URL(mDownloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.connect();
                int length = conn.getContentLength();
                InputStream is = conn.getInputStream();

                File downloadFile = null;
                OutputStream outputStream = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10及以上版本使用MediaStore API保存文件到系统Download目录
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, mFileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(mFileName));
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    // 保存文件到系统Download目录
                    Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri item = mContext.getContentResolver().insert(collection, values);
                    
                    if (item == null) {
                        sendMessage(MSG_DOWNLOAD_FAILED, "无法创建下载文件");
                        return;
                    }

                    try {
                        outputStream = mContext.getContentResolver().openOutputStream(item);
                        if (outputStream == null) {
                            sendMessage(MSG_DOWNLOAD_FAILED, "无法打开文件输出流");
                            return;
                        }

                        // 执行下载
                        performDownload(is, outputStream, length);

                        // 下载完成后更新文件状态
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        mContext.getContentResolver().update(item, values, null, null);

                        // 获取文件路径
                        downloadFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), mFileName);
                        if (!downloadFile.exists()) {
                            // 如果在预期位置找不到文件，尝试使用MediaStore查询文件路径
                            // 注意：Android 10+不允许直接访问公共目录文件路径
                            downloadFile = new File("/storage/emulated/0/Download/" + mFileName);
                        }
                    } catch (Exception e) {
                        // 如果出错，删除创建的条目
                        if (item != null) {
                            mContext.getContentResolver().delete(item, null, null);
                        }
                        throw e;
                    }
                } else {
                    // Android 9及以下版本使用传统方式保存到系统Download目录
                    if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                        sendMessage(MSG_DOWNLOAD_FAILED, "当前设备无SD卡，无法下载文件");
                        return;
                    }

                    // 创建下载目录 - 使用系统Download目录
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadDir == null) {
                        // 如果无法访问公共下载目录，使用应用的私有下载目录作为备选
                        downloadDir = mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    }
                    if (downloadDir != null && !downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }

                    // 创建下载文件
                    downloadFile = new File(downloadDir, mFileName);
                    outputStream = new FileOutputStream(downloadFile);

                    // 执行下载
                    performDownload(is, outputStream, length);
                }

                // 下载完成
                sendMessage(MSG_UPDATE_PROGRESS, 100);
                sendMessage(MSG_DOWNLOAD_COMPLETE, downloadFile);

            } catch (Exception e) {
                Log.e(TAG, "下载失败: " + e.getMessage());
                sendMessage(MSG_DOWNLOAD_FAILED, "下载失败: " + e.getMessage());
            }
        }

        private void performDownload(InputStream is, OutputStream outputStream, int length) throws IOException {
            int count = 0;
            byte[] buffer = new byte[4096];
            
            while (true) {
                int numRead = is.read(buffer);
                if (numRead <= 0) {
                    break;
                }

                count += numRead;
                // 计算进度
                int newProgress = length > 0 ? (int) (((float) count / length) * 100) : -1;

                // 只在进度有变化且距离上次更新已超过500ms时才更新
                if ((newProgress != mProgress || newProgress == -1) && 
                        System.currentTimeMillis() - mLastUpdateTime > 500) {
                    mProgress = newProgress;
                    sendMessage(MSG_UPDATE_PROGRESS, newProgress);
                    mLastUpdateTime = System.currentTimeMillis();
                }

                outputStream.write(buffer, 0, numRead);

                // 检查是否取消下载
                if (mIsInterceptDownload) {
                    break;
                }
            }

            outputStream.flush();
            outputStream.close();
            is.close();
        }
    }

    // 使用静态内部类和WeakReference避免内存泄漏
    private static class DownloadHandler extends Handler {
        private final WeakReference<WebViewDownloadManager> mManagerRef;

        public DownloadHandler(WebViewDownloadManager manager) {
            super(Looper.getMainLooper());
            mManagerRef = new WeakReference<>(manager);
        }

        @Override
        public void handleMessage(Message msg) {
            WebViewDownloadManager manager = mManagerRef.get();
            if (manager == null) {
                // 如果外部类引用已被回收，直接返回
                return;
            }

            switch (msg.what) {
                case MSG_UPDATE_PROGRESS:
                    int progress = (int) msg.obj;
                    manager.updateProgress(progress);
                    break;
                case MSG_DOWNLOAD_COMPLETE:
                    File file = (File) msg.obj;
                    manager.onDownloadComplete(file);
                    break;
                case MSG_DOWNLOAD_FAILED:
                    String error = (String) msg.obj;
                    manager.onDownloadFailed(error);
                    break;
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new DownloadHandler(this);

    private void sendMessage(int what, Object obj) {
        Message message = mHandler.obtainMessage(what);
        message.obj = obj;
        mHandler.sendMessage(message);
    }

    private void updateProgress(int progress) {
        String progressText = progress >= 0 ? progress + "%" : "下载中...";
        
        // 更新通知栏进度
        NotificationUtil.showPendingNotificationWithProgress(
                mContext, 
                mTitle, 
                mFileName + " " + progressText, 
                mNotificationId, 
                getOpenFileIntent(progress == 100), 
                100, 
                progress >= 0 ? progress : 0
        );

        // 回调进度更新
        if (mDownloadListener != null) {
            mDownloadListener.onDownloadProgress(progress);
        }
    }

    private void onDownloadComplete(File file) {
        Toast.makeText(mContext, "文件下载完成: " + mFileName, Toast.LENGTH_SHORT).show();
        
        // 发送完成通知
        NotificationUtil.showPendingNotificationWithProgress(
                mContext, 
                mTitle, 
                mFileName + " 下载完成", 
                mNotificationId, 
                getOpenFileIntent(true), 
                100, 
                100
        );

        // 回调下载完成
        if (mDownloadListener != null) {
            mDownloadListener.onDownloadComplete(file);
        }
    }

    private void onDownloadFailed(String error) {
        Toast.makeText(mContext, error, Toast.LENGTH_SHORT).show();
        
        // 回调下载失败
        if (mDownloadListener != null) {
            mDownloadListener.onDownloadFailed(error);
        }
    }

    private PendingIntent getOpenFileIntent(boolean isComplete) {
        if (!isComplete) {
            // 下载中，点击通知回到应用
            Intent intent = new Intent(mContext, mContext.getClass());
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return createPendingIntent(intent);
        } else {
            // 下载完成，点击通知打开文件
            // 先尝试在系统Download目录查找文件
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), mFileName);
            
            // 如果在系统Download目录找不到，再尝试在应用私有目录查找
            if (!file.exists()) {
                file = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), mFileName);
            }
            
            if (!file.exists()) {
                // 如果文件不存在，回到应用
                Intent intent = new Intent(mContext, mContext.getClass());
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                return createPendingIntent(intent);
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".fileprovider", file);
            } else {
                uri = Uri.fromFile(file);
            }

            // 根据文件扩展名设置MIME类型
            String mimeType = getMimeType(file.getName());
            intent.setDataAndType(uri, mimeType);

            return createPendingIntent(intent);
        }
    }

    private PendingIntent createPendingIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            return PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private String getMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "txt": return "text/plain";
            default: return "*/*";
        }
    }

    // 需要添加的注解
}