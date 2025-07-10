package com.weifu.app.ui.custom;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.weifu.app.R;

import net.posprinter.utils.StringUtils;

/**
 * @author yangyang.zhang
 */
public class CustomDialog extends Dialog {
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
	}
}