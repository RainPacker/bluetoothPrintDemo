package com.weifu.app.utils;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.RequiresApi;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.KeyStore;

public class SecurityUtils {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String AES_MODE = "AES/CBC/PKCS7Padding";

    // 生成用户绑定的密钥
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static void generateUserKey(String userId) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);

        KeyGenParameterSpec.Builder builder = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder = new KeyGenParameterSpec.Builder(
                    "user_key_" + userId,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .setUserAuthenticationParameters(0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG);
        }

        keyGenerator.init(builder.build());
        keyGenerator.generateKey();
    }

    // 获取加密后的密码
    public static String encryptPassword(String userId, String password) throws Exception {
        SecretKey key = getSecretKey(userId);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(password.getBytes());
        
        return Base64.encodeToString(iv, Base64.DEFAULT) + "]" + 
               Base64.encodeToString(encrypted, Base64.DEFAULT);
    }

    // 验证密码（需要指纹认证）
    public static boolean verifyPassword(String userId, String storedData) throws Exception {
        SecretKey key = getSecretKey(userId);
        String[] parts = storedData.split("]");
        byte[] iv = Base64.decode(parts[0], Base64.DEFAULT);
        byte[] encrypted = Base64.decode(parts[1], Base64.DEFAULT);

        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(encrypted);

        // 实际应对比服务端存储的密码哈希值
        return new String(decrypted).equals(getStoredPassword(userId));
    }

    private static SecretKey getSecretKey(String userId) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        return (SecretKey) keyStore.getKey("user_key_" + userId, null);
    }

    private static String getStoredPassword(String userId) {
        // 从安全存储获取密码（示例使用SharedPreferences）
        return "user_hashed_password"; // 实际应从服务端获取
    }
}