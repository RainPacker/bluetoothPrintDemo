package com.weifu.app.version;
 
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
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
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;

import androidx.core.content.FileProvider;
import androidx.core.graphics.PathUtils;

import com.weifu.app.BuildConfig;
import com.weifu.app.R;
import com.weifu.utils.XMLParserUtil;


/**
 * APK更新管理类
 *
 *
 */
public class UpdateManager {
 
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
 
	private static final String savePath = 	Environment.DIRECTORY_DOWNLOADS;
 
	private static final String saveFileName = "wps.apk";
 
	//下载地址
	private String downloadURL = null;
	/**
	 * 下载
	 */
	private 	AlertDialog downloadDg;
 
 
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
		Builder builder = new Builder(mContext);
		builder.setTitle("提示");
		builder.setMessage("网络或软件版本信息有错误，数据无法下载,请到网页下载最新版本软件");
		builder.setPositiveButton("确定", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		builder.show();
	}
	/**
	 * 提示更新对话框
	 *
	 */
	private void showUpdateDialog() {
		Builder builder = new Builder(mContext);
		builder.setTitle("版本更新");
		builder.setMessage(info.getDisplayMessage());
		builder.setCancelable(false);
		builder.setPositiveButton("下载", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				// 弹出下载框
				showDownloadDialog();
			}
		});
		builder.setNegativeButton("以后再说", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		builder.create().show();
	}
 
	/**
	 * 弹出下载框
	 */
	private void showDownloadDialog() {
		 Builder builder = new Builder(mContext);
		builder.setTitle("版本更新中...");
		final LayoutInflater inflater = LayoutInflater.from(mContext);
		View v = inflater.inflate(R.layout.update_progress, null);
		progressBar = (ProgressBar) v.findViewById(R.id.pb_update_progress);
		builder.setView(v);
		builder.setNegativeButton("取消", new OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				//终止下载
				isInterceptDownload = true;
			}
		});
		downloadDg =	builder.create();
		downloadDg.show();
		//下载apk
		downloadApk();
	}
 
	/**
	 * 下载apk
	 */
	private void downloadApk(){
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
			String path = android.os.Environment.getExternalStorageState();
			System.out.println(path);
			if (!android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
				//如果没有SD卡
				Builder builder = new Builder(mContext);
				builder.setTitle("提示");
				builder.setMessage("当前设备无SD卡，数据无法下载");
				builder.setPositiveButton("确定", new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
				builder.show();
				return;
			}else if(downloadURL != null){
				try {
					//服务器上新版apk地址
					URL url = new URL(downloadURL);
					HttpURLConnection conn = (HttpURLConnection)url.openConnection();
					conn.connect();
					int length = conn.getContentLength();
					InputStream is = conn.getInputStream();
					File file = new File(savePath);
					if (!file.exists()) {







					}

 
					String apkFile = saveFileName;
					File ApkFile = new File(mContext.getExternalFilesDir(savePath),apkFile);
					FileOutputStream fos = new FileOutputStream(ApkFile);
 
					int count = 0;
					byte buf[] = new byte[1024];
 
					do{
						int numRead = is.read(buf);
						count += numRead;
						//更新进度条
						progress = (int) (((float) count / length) * 100);
						handler.sendEmptyMessage(1);
						if(numRead <= 0){
							//下载完成通知安装

							handler.sendEmptyMessage(0);
							isInterceptDownload = true;
							break;
						}
						fos.write(buf,0,numRead);
						//当点击取消时，则停止下载
					}while(!isInterceptDownload);
					fos.close();
					is.close();
				} catch (MalformedURLException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}else{
				Builder builder = new Builder(mContext);
				builder.setTitle("提示");
				builder.setMessage("获取服务器版本信息错误，数据无法下载");
				builder.setPositiveButton("确定", new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
				builder.show();
			}
		}
	};
 
	/**
	 * 声明一个handler来跟进进度条
	 */
	private Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				// 更新进度情况
				progressBar.setProgress(progress);
				break;
			case 0:
				if (downloadDg != null){
				  downloadDg.dismiss();
				}
				progressBar.setVisibility(View.INVISIBLE);
				// 安装apk文件
				installApk();
				break;
			default:
				break;
			}
		};
	};
 
	/**
	 * 安装apk
	 */
	private void installApk() {
		try{
		File apkfile =  new File(mContext.getExternalFilesDir(savePath),saveFileName);
		if (!apkfile.exists()) {
			return;
		}
//		Intent i = new Intent(Intent.ACTION_VIEW);
//		i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//		i.setDataAndType(Uri.parse("file://" + apkfile.toString()),
//				"application/vnd.android.package-archive");
//		mContext.startActivity(i);


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
		mContext.startActivity(intent);
	} catch (Exception e) {
		e.printStackTrace();
	}

	}
}