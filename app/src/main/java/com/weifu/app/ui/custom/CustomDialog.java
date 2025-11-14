package com.weifu.app.ui.custom;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.weifu.app.R;

import net.posprinter.utils.StringUtils;

/**
 * @author yangyang.zhang
 */
public class CustomDialog extends Dialog {
	/**
	 * 更新弹窗类型：强制更新
	 */
	public static final int UPDATE_TYPE_FORCE = 1;
	/**
	 * 更新弹窗类型：可选更新
	 */
	public static final int UPDATE_TYPE_OPTIONAL = 2;
	/* Constructor */
	private CustomDialog(Context context) {
		super(context);
	}
 
	private CustomDialog(Context context, int themeResId) {
		super(context, themeResId);
	}
 
	private CustomDialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
		super(context, cancelable, cancelListener);
	}
 
	/* Builder */
	public static class Builder {
		private TextView tvTitle, tvWarning, tvInfo;
		private Button btnCancel, btnConfirm;

		private View mLayout;
		private View.OnClickListener mButtonCancelClickListener;
		private View.OnClickListener mButtonConfirmClickListener;

		private CustomDialog mDialog;
 
		public Builder(Context context) {
			mDialog = new CustomDialog(context, R.style.custom_dialog);
			LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			// 加载布局文件
			mLayout = inflater.inflate(R.layout.custom_dg, null, false);
			// 添加布局文件到 Dialog
			mDialog.addContentView(mLayout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
 
			tvTitle = (TextView) mLayout.findViewById(R.id.tv_title);
			tvWarning = (TextView) mLayout.findViewById(R.id.tv_warning);
			tvInfo = (TextView) mLayout.findViewById(R.id.tv_info);
			btnCancel = (Button) mLayout.findViewById(R.id.btn_cancel);
			btnConfirm = (Button) mLayout.findViewById(R.id.btn_confirm);
		}
 
		/**
		 * 设置 Dialog 标题
		 */
		public Builder setTitle(String title) {
			tvTitle.setText(title);
			tvTitle.setVisibility(View.VISIBLE);
			return this;
		}
 
		/**
		 * 设置 Warning
		 */
		public Builder setWarning(String waring) {
			tvWarning.setText(waring);
			if (waring == null || waring.equals("")) {
				tvWarning.setVisibility(View.GONE);
			}
			return this;
		}
 
		/**
		 * 设置 Info
		 */
		public Builder setInfo(String message) {
			tvInfo.setText(message);
			return this;
		}
 
		/**
		 * 设置取消按钮文字和监听
		 */
		public Builder setButtonCancel(String text, View.OnClickListener listener) {
			btnCancel.setText(text);
			mButtonCancelClickListener = listener;
			return this;
		}
 
		/**
		 * 设置确认按钮文字和监听
		 */
		public Builder setButtonConfirm(String text, View.OnClickListener listener) {
			btnConfirm.setText(text);
			mButtonConfirmClickListener = listener;
			return this;
		}
 
		public CustomDialog create() {
			btnCancel.setOnClickListener(new android.view.View.OnClickListener() {
				@Override
				public void onClick(View view) {
					mDialog.dismiss();
					mButtonCancelClickListener.onClick(view);
				}
			});
 
			btnConfirm.setOnClickListener(new android.view.View.OnClickListener() {
				@Override
				public void onClick(View view) {
					mDialog.dismiss();
					mButtonConfirmClickListener.onClick(view);
				}
			});
 
			mDialog.setContentView(mLayout);
			mDialog.setCancelable(false);
			mDialog.setCanceledOnTouchOutside(false);
			if(btnCancel.getText() ==null || "".equals(btnCancel.getText())){
				btnCancel.setVisibility(View.GONE);
			}
			return mDialog;
	}
	
	/**
	 * 检测设备是否为平板
	 * @param context 上下文
	 * @return 是否为平板设备
	 */
	private static boolean isTablet(Context context) {
		return (context.getResources().getConfiguration().screenLayout
				& android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK)
				>= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
	}
	
	/**
	 * 根据设备类型获取适合的弹窗宽度
	 * @param context 上下文
	 * @return 弹窗宽度
	 */
	private static int getDialogWidth(Context context) {
		boolean isTabletDevice = isTablet(context);
		android.util.DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
		int screenWidth = displayMetrics.widthPixels;
		
		if (isTabletDevice) {
			// 平板设备使用固定宽度或屏幕宽度的70%
			return Math.max(500, (int) (screenWidth * 0.85f));
		} else {
			// 手机设备使用屏幕宽度减去边距
			return (int) (screenWidth * 0.85f);
		}
	}
	
	/**
	 * 创建更新弹窗
	 * @param context 上下文
	 * @param updateType 更新类型：UPDATE_TYPE_FORCE 或 UPDATE_TYPE_OPTIONAL
	 * @param version 版本号
	 * @param updateContents 更新内容列表
	 * @param updateListener 更新按钮点击监听器
	 * @param laterListener 稍后再说按钮点击监听器（仅可选更新有效）
	 * @return 更新弹窗
	 */
	public static Dialog createUpdateDialog(Context context, int updateType, String version,
										  String[] updateContents, View.OnClickListener updateListener,
										  View.OnClickListener laterListener) {
		Dialog dialog;
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View layout;
		
		if (updateType == UPDATE_TYPE_FORCE) {
			// 强制更新弹窗
			layout = inflater.inflate(R.layout.dialog_force_update, null);
			dialog = new Dialog(context, R.style.custom_dialog);
			
			// 设置版本号
			TextView tvVersion = layout.findViewById(R.id.tv_update_version);
			tvVersion.setText(version);
			
			// 动态设置更新内容
			setUpdateContents(context, layout, updateContents);
			
			// 设置立即更新按钮
			Button btnUpdate = layout.findViewById(R.id.btn_force_update);
			btnUpdate.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (updateListener != null) {
						updateListener.onClick(v);
					}
				}
			});
			
			// 强制更新不可取消
			dialog.setCancelable(false);
			dialog.setCanceledOnTouchOutside(false);
			
		} else {
			// 可选更新弹窗
			layout = inflater.inflate(R.layout.dialog_optional_update, null);
			dialog = new Dialog(context, R.style.custom_dialog);
			
			// 设置版本号
			TextView tvVersion = layout.findViewById(R.id.tv_update_version);
			tvVersion.setText(version);
			
			// 动态设置更新内容
			setUpdateContents(context, layout, updateContents);
			
			// 设置立即更新按钮
			Button btnUpdate = layout.findViewById(R.id.btn_optional_update);
			btnUpdate.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
					if (updateListener != null) {
						updateListener.onClick(v);
					}
				}
			});
			
			// 设置稍后再说按钮
			// 注意：这里修改了findViewById的参数，确保与布局文件中的ID匹配
			Button btnLater = layout.findViewById(R.id.btn_later_update);
			btnLater.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
					if (laterListener != null) {
						laterListener.onClick(v);
					}
				}
			});
			
			// 可选更新可以取消
			dialog.setCancelable(true);
			dialog.setCanceledOnTouchOutside(true);
		}
		
		dialog.setContentView(layout);
		
		// 根据设备类型设置弹窗宽度和位置
		Window window = dialog.getWindow();
		if (window != null) {
			WindowManager.LayoutParams params = window.getAttributes();
			// 获取适合当前设备的弹窗宽度
			params.width = getDialogWidth(context);
			// 设置弹窗高度自适应内容
			params.height = WindowManager.LayoutParams.WRAP_CONTENT;
			// 居中显示
			params.gravity = Gravity.CENTER;
			// 应用参数
			window.setAttributes(params);
		}
		
		return dialog;
	}
	
	/**
	 * 动态设置更新内容
	 * @param context 上下文
	 * @param layout 布局视图
	 * @param updateContents 更新内容数组
	 */
	private static void setUpdateContents(Context context, View layout, String[] updateContents) {
		if (updateContents == null || updateContents.length == 0) {
			return;
		}
		
		// 查找内容容器
		ViewGroup contentContainer = layout.findViewById(R.id.update_contents_container);
		if (contentContainer == null) {
			// 如果没有内容容器，尝试使用原来的固定布局方式作为兼容
			setFixedUpdateContents(layout, updateContents);
			return;
		}
		
		// 获取LayoutInflater
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		if (inflater == null) {
			// 如果inflater获取失败，使用兼容方式
			setFixedUpdateContents(layout, updateContents);
			return;
		}
		
		// 清空容器
		contentContainer.removeAllViews();
		
		// 动态添加更新内容项
		for (String content : updateContents) {
			if (content != null && !content.isEmpty()) {
				// 创建内容项视图
				View itemView = inflater.inflate(R.layout.update_content_item, null);
				TextView contentText = itemView.findViewById(R.id.update_content_text);
				contentText.setText(content);
				
				// 添加到容器
				ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, 
						ViewGroup.LayoutParams.WRAP_CONTENT);
				contentContainer.addView(itemView, params);
			}
		}
	}
	
	/**
	 * 兼容原有的固定内容布局方式
	 * @param layout 布局视图
	 * @param updateContents 更新内容数组
	 */
	private static void setFixedUpdateContents(View layout, String[] updateContents) {
		try {
			TextView tvContent1 = layout.findViewById(R.id.tv_update_content_1);
			TextView tvContent2 = layout.findViewById(R.id.tv_update_content_2);
			TextView tvContent3 = layout.findViewById(R.id.tv_update_content_3);
			
			// 设置前三个内容项（兼容原有布局）
			if (tvContent1 != null) {
				tvContent1.setText(updateContents.length >= 1 ? updateContents[0] : "");
				tvContent1.setVisibility(updateContents.length >= 1 ? View.VISIBLE : View.GONE);
			}
			if (tvContent2 != null) {
				tvContent2.setText(updateContents.length >= 2 ? updateContents[1] : "");
				tvContent2.setVisibility(updateContents.length >= 2 ? View.VISIBLE : View.GONE);
			}
			if (tvContent3 != null) {
				tvContent3.setText(updateContents.length >= 3 ? updateContents[2] : "");
				tvContent3.setVisibility(updateContents.length >= 3 ? View.VISIBLE : View.GONE);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
}