package com.weifu.app;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.weifu.app.utils.SecurityUtils;

import java.util.concurrent.Executor;

public class AuthActivity extends AppCompatActivity {
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private String currentUserId;

    @RequiresApi(api = Build.VERSION_CODES.P)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        executor = getMainExecutor();

        EditText etUsername = findViewById(R.id.etUsername);
        EditText etPassword = findViewById(R.id.etPassword);
        Button btnRegister = findViewById(R.id.btnRegister);
        Button btnFingerprint = findViewById(R.id.btnFingerprint);
        Button btnPasswordLogin = findViewById(R.id.btnPasswordLogin);

        // 注册流程
        btnRegister.setOnClickListener(v -> {
            String username = etUsername.getText().toString();
            String password = etPassword.getText().toString();

            try {
                // 1. 生成用户密钥
                SecurityUtils.generateUserKey(username);
                
                // 2. 加密存储密码
                String encrypted = SecurityUtils.encryptPassword(username, password);
                saveUserCredential(username, encrypted);
                
                // 3. 显示登录选项
                setupLoginUI(username);
                currentUserId = username;
                
            } catch (Exception e) {
                e.printStackTrace();
                Log.e( "onCreate: ",e.getMessage() );
                Toast.makeText(this, "注册失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 密码登录
        btnPasswordLogin.setOnClickListener(v -> {
            String input = etPassword.getText().toString();
            if (verifyPassword(input)) {
                startMainActivity();
            }
        });

        // 初始化生物识别
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                try {
                    if (SecurityUtils.verifyPassword(currentUserId, getStoredCredential())) {
                        startMainActivity();
                    }
                } catch (Exception e) {
                    Toast.makeText(AuthActivity.this, "认证失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void setupLoginUI(String username) {
        findViewById(R.id.etUsername).setEnabled(false);
       // (EditText) findViewById(R.id.etPassword).setText
        findViewById(R.id.btnRegister).setVisibility(View.GONE);
        findViewById(R.id.btnFingerprint).setVisibility(View.VISIBLE);
        findViewById(R.id.btnPasswordLogin).setVisibility(View.VISIBLE);
        
        // 启动指纹认证
        showBiometricPrompt();
    }

    private void showBiometricPrompt() {
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹登录")
                .setSubtitle("请验证指纹")
                .setNegativeButtonText("使用密码")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void saveUserCredential(String username, String data) {
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        pref.edit().putString(username, data).apply();
    }

    private String getStoredCredential() {
        SharedPreferences pref = getSharedPreferences("user_creds", MODE_PRIVATE);
        return pref.getString(currentUserId, "");
    }

    private boolean verifyPassword(String input) {
        // 实际应对比服务端验证
        return input.equals("temp_password");
    }

    private void startMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        biometricPrompt.cancelAuthentication();
    }
}