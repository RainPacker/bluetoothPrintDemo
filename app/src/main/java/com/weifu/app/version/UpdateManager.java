package com.weifu.app.version;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.weifu.app.BuildConfig;
import com.weifu.app.R;
import com.weifu.app.ui.custom.CustomDialog;
import com.weifu.utils.XMLParserUtil;


/**
 * APK更新管理类
 *
 *
 */
public class UpdateManager {

	private static final String TAG = "UpdateManager";
	// 上下文对象
	private Context mContext;
	//更新版本信息对象
	private VersionInfo info = null;
	// 下载进度条
	private ProgressBar progressBar;
	// 是否终止下载
	private boolean isInterceptDownload = false;
	//进度条显示数值
	private int progress = 0;

	private static final String savePath = Environment.DIRECTORY_DOWNLOADS;

	private static final String saveFileName = "wps.apk";

	//下载地址
	private String downloadURL = null;
	/**
	 * 下载
	 */
	private AlertDialog downloadDg;

	/**
	 * 是否强制更新
	 */
	private boolean isForce = false;

	// 主线程Handler，用于在任意线程安全地操作UI
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	// 下载通知渠道ID（低重要性，进度刷新不响铃不打扰）
	private static final String CHANNEL_DOWNLOAD = "update_download";
	// 下载进度/完成/失败通知ID（复用同一ID：后续通知直接覆盖前一条）
	private static final int NOTIFY_DOWNLOAD_ID = 1002;
	// 上次已通知的进度百分比（节流：每变化5%才刷新一次通知）
	private int lastNotifiedProgress = -1;

	/**
	 * 参数为Context(上下文activity)的构造函数
	 *
	 * @param context
	 */
	public UpdateManager(Context context) {
		this.mContext = context;
	}

	public void checkUpdate(String version_url) throws IOException {
		// 从服务端获取版本信息
		info = getVersionInfoFromServer(version_url);
		if (info != null) {
			downloadURL = info.getDownloadURL();
			isForce = "1".equals(info.getIsForce());
			Log.d(TAG, "checkUpdate: 强制更新=" + info.getIsForce());
			try {
				// 获取当前软件包信息
				PackageInfo pi = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), PackageManager.GET_CONFIGURATIONS);
				// 当前软件版本号
				int versionCode = pi.versionCode;
				if (versionCode != info.getVersionCode()) {
					// 如果当前版本号不等于服务端版本号,则弹出提示更新对话框
					showUpdateDialog();
				}
			} catch (NameNotFoundException e) {
				e.printStackTrace();
			}
		}else{
			showErrorDialog();
		}
	}

	/**
	 * 从服务端获取版本信息
	 *
	 * @return
	 * @throws IOException
	 */
	private VersionInfo getVersionInfoFromServer(String version_url) throws IOException {
		VersionInfo info = null;
		URL url = null;
		HttpURLConnection urlConnection = null;
		InputStream inputStream = null;
		try {
			url = new URL(version_url);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		if (url != null) {
			try {
				// 使用HttpURLConnection打开连接
				 urlConnection = (HttpURLConnection) url.openConnection();
				 // 超时设置：避免无超时阻塞数分钟后才弹出错误对话框（扫码页打开期间弹窗会覆盖扫码界面）
				 urlConnection.setConnectTimeout(5000);
				 urlConnection.setReadTimeout(5000);
				 urlConnection.connect();
				 inputStream = urlConnection.getInputStream();
				 if(inputStream == null){
					 // 服务器 和 本地 版本信息文件不一致
					 return null;
				 }
				// 读取服务端version.xml的内容(流)
				info = XMLParserUtil.getUpdateInfo(inputStream);
				urlConnection.disconnect();
				inputStream.close();
			} catch (IOException e) {
				urlConnection.disconnect();
				if(inputStream != null) {
					inputStream.close();
				}
				return null;
			}
		}
		return info;
	}

	public void showErrorDialog(){
		// Dialog 必须在主线程创建和显示
		mainHandler.post(() -> {
			CustomDialog.Builder builder = new CustomDialog.Builder(mContext);
			builder.setTitle("提示");
			builder.setInfo("网络或软件版本信息有错误，数据无法下载,请联系管理员");
			builder.setButtonConfirm("确定", new View.OnClickListener() {

				@Override
				public void onClick(View customDgv) {

				}
			});
			CustomDialog customDg = builder.create();
			customDg.show();
		});
	}

	/**
	 * 提示更新对话框
	 *
	 */
	private void showUpdateDialog() {
		// Dialog 必须在主线程创建和显示
		mainHandler.post(() -> {
			int updateType = isForce ? CustomDialog.UPDATE_TYPE_FORCE : CustomDialog.UPDATE_TYPE_OPTIONAL;
			Dialog dg = CustomDialog.createUpdateDialog(mContext, updateType,
				info.getVersion(), parseUpdateContents(info.getDisplayMessage()),
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						showDownloadDialog();
					}
				}, new View.OnClickListener() {
					@Override
					public void onClick(View v) {

					}
				});
			dg.show();
		});
	}

	/**
	 * 将更新说明按行拆分为更新内容列表（version.xml中displayMessage以##分隔）
	 *
	 * @param displayMessage 更新说明
	 * @return 更新内容列表
	 */
	private String[] parseUpdateContents(String displayMessage) {
		if (displayMessage == null || displayMessage.trim().isEmpty()) {
			return new String[0];
		}
		String[] lines = displayMessage.split("\n");
		List<String> contents = new ArrayList<>();
		for (String line : lines) {
			if (line != null && !line.trim().isEmpty()) {
				contents.add(line.trim());
			}
		}
		return contents.toArray(new String[0]);
	}

	/**
	 * 弹出下载框（静默下载）
	 */
	private void showDownloadDialog() {
		//下载apk
		downloadApk();
	}

	/**
	 * 下载apk
	 */
	private void downloadApk(){
		// 弹窗toast 提示
		mainHandler.post(() -> Toast.makeText(mContext, "正在下载新版本", Toast.LENGTH_SHORT).show());
		//开启另一线程下载
		Thread downLoadThread = new Thread(downApkRunnable);
		downLoadThread.start();
	}

	/**
	 * 从服务器下载新版apk的线程
	 */
	private Runnable downApkRunnable = new Runnable(){
		@Override
		public void run() {
			if (!android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
				//如果没有SD卡
				showErrorAlertDialog("当前设备无SD卡，数据无法下载");
				return;
			}else if(downloadURL != null){
				HttpURLConnection conn = null;
				InputStream is = null;
				FileOutputStream fos = null;
				try {
					//服务器上新版apk地址
					URL url = new URL(downloadURL);
					conn = (HttpURLConnection)url.openConnection();
					// 超时保护：连接/读取卡死时抛异常走失败通知，而不是无限阻塞
					conn.setConnectTimeout(15000);
					conn.setReadTimeout(30000);
					conn.connect();
					int length = conn.getContentLength();
					is = conn.getInputStream();

					String apkFile = saveFileName;
					File ApkFile = new File(mContext.getExternalFilesDir(savePath),apkFile);
					fos = new FileOutputStream(ApkFile);

					//重置进度通知节流标记，并立即发出0%通知
					lastNotifiedProgress = 0;
					progress = 0;
					handler.sendEmptyMessage(1);

					int count = 0;
					byte buf[] = new byte[8192];

					do{
						int numRead = is.read(buf);
						if(numRead <= 0){
							//EOF（服务器关闭流）：下载完成，发完成通知或自动安装
							handler.sendEmptyMessage(0);
							isInterceptDownload = true;
							break;
						}
						fos.write(buf,0,numRead);
						count += numRead;
						//更新进度（总长度未知时走不定进度显示；上限钳制100）
						progress = length > 0 ? Math.min(100, (int) (((float) count / length) * 100)) : -1;
						handler.sendEmptyMessage(1);
						// 关键修复：已收满 Content-Length 即视为完成，不再等待 EOF——
						// 部分服务器收满响应体后保持连接不关闭，而 read() 无超时无限阻塞，
						// 导致进度永远停在 100%、完成/安装流程无法触发（实测卡 100% 根因）
						if(length > 0 && count >= length){
							handler.sendEmptyMessage(0);
							isInterceptDownload = true;
							break;
						}
						//当点击取消时，则停止下载
					}while(!isInterceptDownload);
				} catch (Exception e) {
					e.printStackTrace();
					// 清理损坏的部分文件，避免残留文件被误安装
					try {
						new File(mContext.getExternalFilesDir(savePath), saveFileName).delete();
					} catch (Exception ignored) {
					}
					handler.obtainMessage(2, "网络异常，下载中断，请稍后重试").sendToTarget();
				} finally {
					try { if (fos != null) fos.close(); } catch (Exception ignored) {
					}
					try { if (is != null) is.close(); } catch (Exception ignored) {
					}
					if (conn != null) {
						conn.disconnect();
					}
				}
			}else{
				showErrorAlertDialog("获取服务器版本信息错误，数据无法下载");
			}
		}
	};

	/**
	 * 在主线程显示错误提示框
	 */
	private void showErrorAlertDialog(String message) {
		mainHandler.post(() -> {
			Builder builder = new Builder(mContext);
			builder.setTitle("提示");
			builder.setMessage(message);
			builder.setPositiveButton("确定", new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			});
			builder.show();
		});
	}

	/**
	 * 声明一个handler来跟进进度条（使用主线程Looper）
	 */
	private Handler handler = new Handler(Looper.getMainLooper()) {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				// 更新下载进度通知（节流：每变化5%或到达100%才刷新，避免高频覆盖）
				if (progress >= 0 && (progress == 100 || progress - lastNotifiedProgress >= 5)) {
					lastNotifiedProgress = progress;
					updateDownloadNotification(progress);
				}
				break;
			case 0:
				if (downloadDg != null){
				  downloadDg.dismiss();
				}
				Log.d(TAG, "handleMessage: 下载完成");
				if (isForce || !canNotify()) {
					// 强制更新或无通知权限：自动安装
					installApk();
				} else {
					// 非强制更新：发可点击安装的通知，由用户决定
					showInstallNotification();
				}
				break;
			case 2:
				// 下载失败：发失败通知（覆盖进度通知，取消常驻状态）
				if (msg.obj instanceof String) {
					showDownloadFailedNotification((String) msg.obj);
				} else {
					showDownloadFailedNotification("下载出错，请稍后重试");
				}
				break;
			default:
				break;
			}
		};
	};

	/**
	 * 更新下载进度通知（同一通知ID覆盖刷新，setOnlyAlertOnce 避免每次刷新响铃）
	 *
	 * @param percent 当前下载进度0~100，小于0表示总大小未知（显示不定进度）
	 */
	private void updateDownloadNotification(int percent) {
		if (!canNotify()) {
			return;
		}
		ensureDownloadChannel();
		NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_DOWNLOAD)
				.setSmallIcon(R.drawable.logo_round)
				.setContentTitle("正在下载更新")
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setPriority(NotificationCompat.PRIORITY_LOW);
		if (percent >= 0) {
			builder.setContentText("已下载 " + percent + "%")
					.setProgress(100, percent, false);
		} else {
			builder.setContentText("正在下载…")
					.setProgress(0, 0, true);
		}
		NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.notify(NOTIFY_DOWNLOAD_ID, builder.build());
	}

	/**
	 * 创建下载专用通知渠道（IMPORTANCE_LOW：进度刷新不响铃、不弹横幅）
	 */
	private void ensureDownloadChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
			NotificationChannel channel = new NotificationChannel(CHANNEL_DOWNLOAD, "应用更新下载",
					NotificationManager.IMPORTANCE_LOW);
			channel.setDescription("显示新版本APK下载进度，下载完成后可点击安装");
			channel.setShowBadge(false);
			manager.createNotificationChannel(channel);
		}
	}

	/**
	 * 是否允许发通知（API 33+ 需要 POST_NOTIFICATIONS 运行时权限）
	 */
	private boolean canNotify() {
		if (Build.VERSION.SDK_INT >= 33) {
			return ContextCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
					== PackageManager.PERMISSION_GRANTED;
		}
		return true;
	}

	/**
	 * 下载完成通知：点击通知直接调起安装（非强制更新时不再自动弹安装，由用户点击触发）
	 */
	private void showInstallNotification() {
		if (!canNotify()) {
			// 无通知权限：由调用方（handler case 0）负责走自动安装，此处直接返回
			return;
		}
		ensureDownloadChannel();
		NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_DOWNLOAD)
				.setSmallIcon(R.drawable.logo_round)
				.setContentTitle("更新下载完成")
				.setContentText("点击安装新版本")
				.setOngoing(false)
				.setAutoCancel(true)
				.setContentIntent(buildInstallPendingIntent())
				.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.notify(NOTIFY_DOWNLOAD_ID, builder.build());
	}

	/**
	 * 下载失败通知
	 */
	private void showDownloadFailedNotification(String message) {
		if (!canNotify()) {
			return;
		}
		ensureDownloadChannel();
		NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_DOWNLOAD)
				.setSmallIcon(R.drawable.logo_round)
				.setContentTitle("更新下载失败")
				.setContentText(message)
				.setOngoing(false)
				.setAutoCancel(true)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.notify(NOTIFY_DOWNLOAD_ID, builder.build());
	}

	/**
	 * 构建安装APK的Intent（供手动安装与下载完成通知复用）
	 */
	private Intent buildInstallIntent() {
		File apkfile = new File(mContext.getExternalFilesDir(savePath), saveFileName);
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
				| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		Uri contentUri;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			contentUri = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".fileprovider", apkfile);
		} else {
			contentUri = Uri.fromFile(apkfile);
		}
		intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
		List<ResolveInfo> resolveLists = mContext.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		if (resolveLists != null) {
			for (ResolveInfo resolveInfo : resolveLists) {
				if (resolveInfo != null && resolveInfo.activityInfo != null) {
					String packageName = resolveInfo.activityInfo.packageName;
					mContext.grantUriPermission(packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
				}
			}
		}
		return intent;
	}

	/**
	 * 构建点击通知后调起安装的PendingIntent
	 */
	private PendingIntent buildInstallPendingIntent() {
		Intent intent = buildInstallIntent();
		return PendingIntent.getActivity(mContext, NOTIFY_DOWNLOAD_ID, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	/**
	 * 安装apk
	 */
	private void installApk() {
		try {
			File apkfile = new File(mContext.getExternalFilesDir(savePath), saveFileName);
			if (!apkfile.exists()) {
				// 安装包缺失（下载中断残留清理等）：明确提示而不是静默返回（否则表现为"卡住不动"）
				showDownloadFailedNotification("安装包不存在，请重新下载更新");
				return;
			}
			mContext.startActivity(buildInstallIntent());
		} catch (Exception e) {
			e.printStackTrace();
			// 自动安装失败：退回为通知点击安装（showInstallNotification 无通知权限时静默返回，不会递归）
			showInstallNotification();
		}
	}
}
