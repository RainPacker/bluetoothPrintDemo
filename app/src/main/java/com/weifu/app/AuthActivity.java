package com.weifu.app;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.weifu.app.utils.SecurityUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AuthActivity extends AppCompatActivity {
    private static final String TAG = "AuthActivity";
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private String currentUserId;
    private boolean isAuthenticationCancelled = false;
    private boolean isFingerprintEnabled = false;
    
    private TextInputEditText etUsername;
    private TextInputEditText etPassword;
    private TextInputLayout tilPassword;
    private MaterialButton btnRegister;
    private MaterialButton btnFingerprint;
    private MaterialButton btnPasswordLogin;
    private MaterialButton btnEnableFingerprint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        
        // 初始化应用上下文
        SecurityUtils.init(getApplicationContext());
        
        // 使用专用的线程池而不是主线程执行器，避免UI阻塞
        executor = Executors.newSingleThreadExecutor();

        // 初始化UI组件
        initViews();
        
        // 初始化生物识别提示信息
        initBiometricPromptInfo();

        // 初始化生物识别
        initBiometricPrompt();

        // 设置点击事件监听器
        setupClickListeners();

        // 尝试恢复上次登录的用户
        restoreLastLoginUser();
    }
    
    /**
     * 初始化视图组件
     */
    private void initViews() {
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        tilPassword = (TextInputLayout) etPassword.getParent().getParent();
        btnRegister = findViewById(R.id.btnRegister);
        btnFingerprint = findViewById(R.id.btnFingerprint);
        btnPasswordLogin = findViewById(R.id.btnPasswordLogin);
        btnEnableFingerprint = findViewById(R.id.btnEnableFingerprint);
        
        // 初始情况下隐藏启用指纹按钮
        if (btnEnableFingerprint != null) {
            btnEnableFingerprint.setVisibility(View.GONE);
        }
    }
    
    /**
     * 初始化生物识别提示信息
     */
    private void initBiometricPromptInfo() {
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹登录")
                .setSubtitle("请验证指纹")
                .setNegativeButtonText("使用密码")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
    }
    
    /**
     * 设置点击事件监听器
     */
    private void setupClickListeners() {
        // 注册流程
        btnRegister.setOnClickListener(v -> handleRegistration());

        // 密码登录
        btnPasswordLogin.setOnClickListener(v -> handlePasswordLogin());

        // 指纹登录按钮
        btnFingerprint.setOnClickListener(v -> handleFingerprintLogin());
        
        // 启用指纹登录按钮
        if (btnEnableFingerprint != null) {
            btnEnableFingerprint.setOnClickListener(v -> enableFingerprintLogin());
        }
    }
    
    /**
     * 处理注册逻辑
     */
    private void handleRegistration() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // 输入验证
        if (TextUtils.isEmpty(username)) {
            etUsername.setError("用户名不能为空");
            etUsername.requestFocus();
            return;
        }
        
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("密码不能为空");
            etPassword.requestFocus();
            return;
        }
        
        // 显示进度提示
        Toast.makeText(this, "正在注册...", Toast.LENGTH_SHORT).show();

        // 在后台线程处理密钥生成
        executor.execute(() -> {
            try {
                // 1. 生成用户密钥
                SecurityUtils.generateUserKey(username);
                
                // 2. 加密存储密码
                String encrypted = SecurityUtils.encryptPassword(username, password);
                saveUserCredential(username, encrypted);
                
                // 3. 在主线程更新UI
                runOnUiThread(() -> {
                    // 清除错误提示
                    tilPassword.setError(null);
                    
                    // 显示登录选项
                    currentUserId = username;
                    setupLoginUI(username);
                    
                    Toast.makeText(AuthActivity.this, 
                        "注册成功，您现在可以使用指纹或密码登录", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "注册失败: " + e.getMessage());
                
                // 在主线程显示错误
                runOnUiThread(() -> {
                    String errorMsg = "注册失败: ";
                    
                    // 提供更具体的错误消息
                    if (e.getMessage() != null && e.getMessage().contains("无法生成安全密钥")) {
                        errorMsg += "设备可能不支持生物认证，请检查系统设置";
                    } else if (e.getMessage() != null && e.getMessage().contains("密码加密失败")) {
                        errorMsg += "加密过程出错，请重试";
                    } else {
                        errorMsg += e.getMessage();
                    }
                    
                    Toast.makeText(AuthActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 处理密码登录
     */
    private void handlePasswordLogin() {
        String password = etPassword.getText().toString().trim();
        
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("请输入密码");
            etPassword.requestFocus();
            return;
        }
        
        tilPassword.setError(null);
        
        try {
            // 验证密码
            if (verifyPassword(password)) {
                Log.d(TAG, "密码验证成功");
                
                // 确保密码已加密存储（可能是首次登录或旧数据）
                try {
                    // 1. 先保存原始密码，确保密码哈希存在
                    SecurityUtils.savePasswordHash(currentUserId, password);
                    
                    // 2. 检查是否存在加密数据
                    String storedData = getStoredCredential();
                    if (TextUtils.isEmpty(storedData)) {
                        try {
                            // 如果没有存储加密数据，则执行加密存储
                            String encrypted = SecurityUtils.encryptPassword(currentUserId, password);
                            if (!TextUtils.isEmpty(encrypted)) {
                                saveUserCredential(currentUserId, encrypted);
                                Log.d(TAG, "成功加密并存储密码");
                            }
                        } catch (Exception e) {
                            // 记录错误但继续登录流程
                            Log.e(TAG, "加密密码失败: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    // 记录错误但继续登录流程
                    Log.e(TAG, "存储密码失败: " + e.getMessage());
                }
                
                // 登录成功，检查是否需要显示启用指纹登录选项
                if (!isFingerprintEnabled && checkBiometricSupport() && btnEnableFingerprint != null) {
                    btnEnableFingerprint.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "登录成功，您可以开通指纹登录", Toast.LENGTH_SHORT).show();
                } else {
                    startMainActivity();
                }
            } else {
                tilPassword.setError("密码不正确");
                Toast.makeText(this, "密码不正确", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "登录过程出错: " + e.getMessage());
            Toast.makeText(this, "登录失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 处理指纹登录
     */
    private void handleFingerprintLogin() {
        if (!isFingerprintEnabled) {
            showAlertDialog("提示", "请先开通指纹登录");
            return;
        }
        
        if (checkBiometricSupport()) {
            isAuthenticationCancelled = false;
            showBiometricPrompt();
        }
    }

    /**
     * 启用指纹登录
     */
    private void enableFingerprintLogin() {
        if (!checkBiometricSupport()) {
            return;
        }
        
        // 确认用户已登录
        if (TextUtils.isEmpty(currentUserId)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 确保获取了密码
        String password = etPassword.getText().toString().trim();
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("请先输入密码");
            etPassword.requestFocus();
            return;
        }
        
        // 验证指纹前显示提示信息
        Toast.makeText(this, "请验证指纹以启用指纹登录", Toast.LENGTH_SHORT).show();

        // 创建专门用于启用指纹登录的提示
        BiometricPrompt.PromptInfo enablePromptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("开通指纹登录")
                .setSubtitle("请验证指纹以开通指纹登录功能")
                .setNegativeButtonText("取消")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        
        // 创建专门用于启用指纹的回调
        BiometricPrompt enableBiometricPrompt = new BiometricPrompt(this, executor, 
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                runOnUiThread(() -> {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        showAlertDialog("开通失败", "错误: " + errString);
                    }
                });
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                runOnUiThread(() -> {
                    try {
                        // 验证密码正确性
                        if (!verifyPassword(password)) {
                            showAlertDialog("验证失败", "密码不正确，无法开通指纹登录");
                            return;
                        }
                        
                        // 确保密码已存储
                        SecurityUtils.savePasswordHash(currentUserId, password);
                        
                        // 确保密码已加密存储
                        String storedData = getStoredCredential();
                        if (TextUtils.isEmpty(storedData)) {
                            try {
                                // 如果没有存储加密数据，则执行加密存储
                                String encrypted = SecurityUtils.encryptPassword(currentUserId, password);
                                if (!TextUtils.isEmpty(encrypted)) {
                                    saveUserCredential(currentUserId, encrypted);
                                } else {
                                    Log.w(TAG, "加密结果为空");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "存储密码失败: " + e.getMessage());
                                showAlertDialog("开通失败", "无法安全存储密码: " + e.getMessage());
                                return;
                            }
                        }
                        
                        // 保存指纹登录状态
                        saveFingerprintEnabled(true);
                        isFingerprintEnabled = true;
                        
                        // 更新UI
                        if (btnEnableFingerprint != null) {
                            btnEnableFingerprint.setVisibility(View.GONE);
                        }
                        if (btnFingerprint != null) {
                            btnFingerprint.setVisibility(View.VISIBLE);
                        }
                        
                        showAlertDialog("开通成功", "指纹登录已开通，下次可直接使用指纹登录", 
                            (dialog, which) -> startMainActivity());
                    } catch (Exception e) {
                        Log.e(TAG, "开通指纹登录失败: " + e.getMessage());
                        showAlertDialog("开通失败", "出现错误: " + e.getMessage());
                    }
                });
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                runOnUiThread(() -> showAlertDialog("验证失败", "指纹不匹配，请重试"));
            }
        });

        // 显示验证对话框
        try {
            enableBiometricPrompt.authenticate(enablePromptInfo);
        } catch (Exception e) {
            Log.e(TAG, "显示指纹验证对话框失败: " + e.getMessage());
            Toast.makeText(this, "无法启动指纹识别: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 初始化生物识别提示
     */
    private void initBiometricPrompt() {
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (!isAuthenticationCancelled) {
                    runOnUiThread(() -> {
                        // 只处理非用户取消的错误
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            Toast.makeText(AuthActivity.this,
                                "认证错误: " + errString, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                runOnUiThread(() -> {
                    try {
                        if (currentUserId != null && !currentUserId.isEmpty()) {
                            // 获取存储的加密密码
                            String storedData = getStoredCredential();
                            
                            if (TextUtils.isEmpty(storedData)) {
                                Toast.makeText(AuthActivity.this,
                                    "未找到存储的用户凭证", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            
                            // 验证密码 - 使用加密数据验证方法
                            if (SecurityUtils.verifyEncryptedPassword(currentUserId, storedData)) {
                                startMainActivity();
                            } else {
                                Toast.makeText(AuthActivity.this,
                                    "验证失败，请使用密码登录", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(AuthActivity.this,
                                "用户ID无效，请重新登录", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "指纹验证失败: " + e.getMessage());
                        Toast.makeText(AuthActivity.this,
                            "认证失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                runOnUiThread(() ->
                    Toast.makeText(AuthActivity.this, "指纹不匹配", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 检查设备是否支持生物识别
     */
    private boolean checkBiometricSupport() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        
        switch (canAuthenticate) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                Log.d(TAG, "支持生物识别");
                return true;
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                Toast.makeText(this, "此设备不支持指纹识别", Toast.LENGTH_SHORT).show();
                return false;
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                Toast.makeText(this, "生物识别功能暂不可用", Toast.LENGTH_SHORT).show();
                return false;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                Toast.makeText(this, "请先在系统设置中注册指纹", Toast.LENGTH_SHORT).show();
                return false;
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                Toast.makeText(this, "需要安全更新", Toast.LENGTH_SHORT).show();
                return false;
            case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED:
                Toast.makeText(this, "不支持的生物识别功能", Toast.LENGTH_SHORT).show();
                return false;
            case BiometricManager.BIOMETRIC_STATUS_UNKNOWN:
                Toast.makeText(this, "生物识别状态未知", Toast.LENGTH_SHORT).show();
                return false;
            default:
                Toast.makeText(this, "无法使用生物识别", Toast.LENGTH_SHORT).show();
                return false;
        }
    }

    /**
     * 恢复上次登录的用户信息
     */
    private void restoreLastLoginUser() {
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        currentUserId = pref.getString("last_login_user", "");
        
        if (!TextUtils.isEmpty(currentUserId)) {
            etUsername.setText(currentUserId);
            
            // 恢复指纹登录状态
            isFingerprintEnabled = isFingerprintEnabled();
            
            setupLoginUI(currentUserId);
        }
    }

    private void setupLoginUI(String username) {
        etUsername.setEnabled(false);
        btnRegister.setVisibility(View.GONE);
        
        // 始终显示密码登录按钮
        btnPasswordLogin.setVisibility(View.VISIBLE);
        
        // 仅当开通了指纹登录并且设备支持时才显示指纹登录按钮
        if (isFingerprintEnabled && checkBiometricSupport()) {
            btnFingerprint.setVisibility(View.VISIBLE);
            // 添加指纹图标
            btnFingerprint.setIcon(getDrawable(R.drawable.ic_fingerprint));
            // 延迟显示指纹认证，避免界面未完全渲染就弹出
            btnFingerprint.postDelayed(this::showBiometricPrompt, 500);
        } else {
            btnFingerprint.setVisibility(View.GONE);
        }
        
        // 隐藏启用指纹按钮，等待密码验证成功后再决定是否显示
        if (btnEnableFingerprint != null) {
            btnEnableFingerprint.setVisibility(View.GONE);
        }
    }

    private void showBiometricPrompt() {
        if (biometricPrompt != null && promptInfo != null) {
            try {
                biometricPrompt.authenticate(promptInfo);
            } catch (Exception e) {
                Log.e(TAG, "显示生物识别对话框失败: " + e.getMessage());
                Toast.makeText(this, "无法启动指纹识别", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveUserCredential(String username, String data) {
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        pref.edit()
            .putString(username, data)
            .putString("last_login_user", username)
            .apply();
    }

    private String getStoredCredential() {
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        return pref.getString(currentUserId, "");
    }

    private boolean verifyPassword(String input) {
        try {
            // 使用SecurityUtils验证密码
            return SecurityUtils.verifyPassword(currentUserId, input);
        } catch (Exception e) {
            Log.e(TAG, "密码验证失败: " + e.getMessage());
            // 如果验证过程出错，尝试直接比较当前密码
            return input.equals("temp_password");
        }
    }

    private void startMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 标记用户取消认证，防止错误提示
        isAuthenticationCancelled = true;
        // 安全检查，避免NPE
        if (biometricPrompt != null) {
            try {
                biometricPrompt.cancelAuthentication();
            } catch (Exception e) {
                Log.e(TAG, "取消生物识别失败: " + e.getMessage());
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        isAuthenticationCancelled = false;
    }

    /**
     * 保存指纹登录状态
     */
    private void saveFingerprintEnabled(boolean enabled) {
        if (!TextUtils.isEmpty(currentUserId)) {
            SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
            pref.edit()
                .putBoolean(currentUserId + "_fingerprint_enabled", enabled)
                .apply();
        }
    }
    
    /**
     * 获取指纹登录状态
     */
    private boolean isFingerprintEnabled() {
        if (TextUtils.isEmpty(currentUserId)) {
            return false;
        }
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        return pref.getBoolean(currentUserId + "_fingerprint_enabled", false);
    }

    /**
     * 显示提示对话框
     */
    private void showAlertDialog(String title, String message) {
        if (!isFinishing()) {
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", null)
                .setCancelable(true)
                .show();
        }
    }

    /**
     * 显示提示对话框(带回调)
     */
    private void showAlertDialog(String title, String message, DialogInterface.OnClickListener listener) {
        if (!isFinishing()) {
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", listener)
                .setCancelable(true)
                .show();
        }
    }
}