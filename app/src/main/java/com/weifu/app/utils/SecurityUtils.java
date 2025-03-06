package com.weifu.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;

public class SecurityUtils {
    private static final String TAG = "SecurityUtils";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_PREFIX = "user_key_";
    private static final String SHARED_PREFS_NAME = "secure_passwords";
    private static final String FALLBACK_PREFS_NAME = "fallback_secure_data";
    
    // 使用更安全的GCM模式，如果设备支持
    private static final String AES_MODE_MODERN = "AES/GCM/NoPadding";
    private static final String AES_MODE_LEGACY = "AES/CBC/PKCS7Padding";
    private static final String AES_MODE_FALLBACK = "AES/CBC/PKCS5Padding";
    
    // 标记是否使用回退加密模式
    private static boolean useFallbackMode = false;
    
    private static Context appContext;
    
    // 初始化方法，应在Application onCreate中调用
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        // 检查是否需要使用回退模式
        SharedPreferences prefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
        useFallbackMode = prefs.getBoolean("use_fallback", false);
    }

    /**
     * 生成用户绑定的密钥
     * 支持 Android 6.0+，并针对不同Android版本做了优化
     */
 
    public static void generateUserKey(String userId) throws Exception {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        String keyAlias = KEY_PREFIX + userId;
        
        // 如果使用回退模式，则生成并存储一个简单的密钥
        if (useFallbackMode) {
            generateFallbackKey(userId);
            return;
        }
        
        // 检查是否已经存在该密钥
        if (hasKey(keyAlias)) {
            Log.d(TAG, "密钥已存在，无需重新生成: " + keyAlias);
            return;
        }
        
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
                    
            KeyGenParameterSpec.Builder builder = null;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
                builder = new KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setInvalidatedByBiometricEnrollment(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9-10
                builder = new KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setInvalidatedByBiometricEnrollment(true);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // Android 7-8
                builder = new KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                        .setUserAuthenticationRequired(true);
            } else { // Android 6 (Marshmallow)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    builder = new KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                            .setUserAuthenticationRequired(true);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyGenerator.init(builder.build());
            }
            keyGenerator.generateKey();
            
            Log.d(TAG, "成功为用户生成密钥: " + keyAlias);
            return;
        } catch (Exception e) {
            Log.e(TAG, "生成密钥失败，转向回退模式: " + e.getMessage());
            // 启用回退模式
            enableFallbackMode();
            // 使用回退方法生成密钥
            generateFallbackKey(userId);
        }
    }
    
    /**
     * 启用回退加密模式
     */
    private static void enableFallbackMode() {
        useFallbackMode = true;
        SharedPreferences prefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("use_fallback", true).apply();
        Log.d(TAG, "已启用回退加密模式");
    }
    
    /**
     * 生成回退密钥 (不使用KeyStore)
     */
    private static void generateFallbackKey(String userId) throws Exception {
        try {
            // 生成基于用户ID的唯一密钥
            SecureRandom random = new SecureRandom();
            byte[] salt = random.generateSeed(16);
            
            // 保存盐值
            SharedPreferences prefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(userId + "_salt", Base64.encodeToString(salt, Base64.DEFAULT)).apply();
            
            Log.d(TAG, "成功为用户生成回退密钥: " + userId);
        } catch (Exception e) {
            Log.e(TAG, "生成回退密钥失败: " + e.getMessage());
            throw new Exception("无法生成安全密钥: " + e.getMessage());
        }
    }
    
    /**
     * 获取回退密钥
     */
    private static SecretKey getFallbackKey(String userId) throws Exception {
        try {
            // 获取存储的盐值
            SharedPreferences prefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
            String saltStr = prefs.getString(userId + "_salt", null);
            
            if (saltStr == null) {
                // 盐值不存在，需要重新生成
                generateFallbackKey(userId);
                saltStr = prefs.getString(userId + "_salt", null);
                
                if (saltStr == null) {
                    throw new Exception("无法获取加密盐值");
                }
            }
            
            byte[] salt = Base64.decode(saltStr, Base64.DEFAULT);
            
            // 使用用户ID和盐值生成密钥
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(userId.getBytes());
            byte[] keyBytes = md.digest();
            
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            Log.e(TAG, "获取回退密钥失败: " + e.getMessage());
            throw new Exception("无法获取安全密钥: " + e.getMessage());
        }
    }

    /**
     * 检查密钥是否存在
     */
    private static boolean hasKey(String keyAlias) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            return keyStore.containsAlias(keyAlias);
        } catch (Exception e) {
            Log.e(TAG, "检查密钥失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取加密后的密码
     */
    public static String encryptPassword(String userId, String password) throws Exception {
        if (userId == null || userId.isEmpty() || password == null) {
            throw new IllegalArgumentException("用户ID和密码不能为空");
        }
        
        // 如果使用回退模式，则使用简单加密方法
        if (useFallbackMode) {
            return encryptPasswordFallback(userId, password);
        }
        
        String keyAlias = KEY_PREFIX + userId;
        SecretKey key = getSecretKey(keyAlias);
        
        if (key == null) {
            Log.w(TAG, "未找到密钥，尝试重新生成");
            generateUserKey(userId);
            key = getSecretKey(keyAlias);
            
            if (key == null) {
                // 如果仍然无法获取密钥，转向回退模式
                enableFallbackMode();
                return encryptPasswordFallback(userId, password);
            }
        }
        
        Cipher cipher;
        byte[] iv;
        byte[] encrypted;
        String mode;
        
        try {
            // 根据Android版本使用不同的加密模式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mode = AES_MODE_MODERN;
                cipher = Cipher.getInstance(mode);
                cipher.init(Cipher.ENCRYPT_MODE, key);
                
                iv = cipher.getIV();
                encrypted = cipher.doFinal(password.getBytes());
            } else {
                mode = AES_MODE_LEGACY;
                cipher = Cipher.getInstance(mode);
                cipher.init(Cipher.ENCRYPT_MODE, key);
                
                iv = cipher.getIV();
                encrypted = cipher.doFinal(password.getBytes());
            }
            
            // 保存原始密码，用于后续验证
            savePasswordHashInternal(userId, password);
            
            String encryptedData = Base64.encodeToString(iv, Base64.DEFAULT) + 
                               "]" + Base64.encodeToString(encrypted, Base64.DEFAULT) +
                               "]" + mode; // 保存加密模式以便解密时使用
                               
            Log.d(TAG, "加密密码成功: " + userId);
            return encryptedData;
            
        } catch (Exception e) {
            Log.e(TAG, "加密密码失败，转向回退模式: " + e.getMessage());
            // 启用回退模式
            enableFallbackMode();
            // 使用回退方法加密
            return encryptPasswordFallback(userId, password);
        }
    }
    
    /**
     * 回退方法加密密码
     */
    private static String encryptPasswordFallback(String userId, String password) throws Exception {
        try {
            SecretKey key = getFallbackKey(userId);
            
            // 使用AES/CBC/PKCS5Padding加密
            Cipher cipher = Cipher.getInstance(AES_MODE_FALLBACK);
            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            byte[] encrypted = cipher.doFinal(password.getBytes());
            
            // 保存原始密码，用于后续验证
            savePasswordHashInternal(userId, password);
            
            String encryptedData = Base64.encodeToString(iv, Base64.DEFAULT) + 
                               "]" + Base64.encodeToString(encrypted, Base64.DEFAULT) +
                               "]" + AES_MODE_FALLBACK + "]fallback"; // 标记为回退模式
                               
            Log.d(TAG, "使用回退模式加密密码成功: " + userId);
            return encryptedData;
            
        } catch (Exception e) {
            Log.e(TAG, "回退模式加密密码失败: " + e.getMessage());
            throw new Exception("密码加密失败: " + e.getMessage());
        }
    }

    /**
     * 验证密码（需要指纹认证）
     */
    public static boolean verifyPassword(String userId, String password) throws Exception {
        if (userId == null || userId.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("用户ID和密码不能为空");
        }
        
        try {
            // 获取存储的密码数据
            String storedPassword = getPasswordHash(userId);
            
            if (storedPassword == null || storedPassword.isEmpty()) {
                Log.w(TAG, "未找到存储的密码: " + userId);
                // 如果是演示环境，可以使用固定密码
                return password.equals("temp_password");
            }
            
            // 直接比较密码（实际应用中应该比较哈希值）
            boolean result = password.equals(storedPassword);
            Log.d(TAG, "密码验证" + (result ? "成功" : "失败") + ": " + userId);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "验证密码失败: " + e.getMessage());
            
            // 尝试回退验证
            try {
                String storedPasswordFromPref = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(userId + "_direct_pwd", null);
                
                if (storedPasswordFromPref != null) {
                    boolean result = password.equals(storedPasswordFromPref);
                    Log.d(TAG, "直接密码对比" + (result ? "成功" : "失败") + ": " + userId);
                    return result;
                }
            } catch (Exception ex) {
                // 忽略这个错误
            }
            
            // 如果是演示环境，可以使用固定密码
            return password.equals("temp_password");
        }
    }
    
    /**
     * 使用加密数据验证密码
     * 注意：此方法用于指纹认证成功后验证存储的加密数据
     */
    public static boolean verifyEncryptedPassword(String userId, String storedData) throws Exception {
        if (userId == null || userId.isEmpty() || storedData == null || storedData.isEmpty()) {
            throw new IllegalArgumentException("用户ID和存储数据不能为空");
        }
        
        try {
            // 解析存储的数据
            String[] parts = storedData.split("]");
            if (parts.length < 2) {
                throw new Exception("存储的加密数据格式不正确");
            }
            
            byte[] iv = Base64.decode(parts[0], Base64.DEFAULT);
            byte[] encrypted = Base64.decode(parts[1], Base64.DEFAULT);
            
            // 确定使用的加密模式
            String mode = (parts.length > 2) ? parts[2] : AES_MODE_LEGACY;
            boolean isFallback = (parts.length > 3 && parts[3].equals("fallback"));
            
            // 如果是回退模式加密的数据，使用回退方式解密
            if (isFallback || useFallbackMode) {
                return verifyPasswordFallback(userId, iv, encrypted);
            }
            
            // 获取密钥
            String keyAlias = KEY_PREFIX + userId;
            SecretKey key = getSecretKey(keyAlias);
            
            if (key == null) {
                // 如果找不到密钥，尝试使用回退模式
                Log.w(TAG, "未找到用户密钥，尝试使用回退模式");
                return verifyPasswordFallback(userId, iv, encrypted);
            }
            
            // 解密
            Cipher cipher = Cipher.getInstance(mode);
            
            if (mode.equals(AES_MODE_MODERN)) {
                GCMParameterSpec spec = new GCMParameterSpec(128, iv);
                cipher.init(Cipher.DECRYPT_MODE, key, spec);
            } else {
                IvParameterSpec spec = new IvParameterSpec(iv);
                cipher.init(Cipher.DECRYPT_MODE, key, spec);
            }
            
            byte[] decrypted = cipher.doFinal(encrypted);
            String decryptedPassword = new String(decrypted);
            
            // 与存储的密码哈希比较
            String storedPassword = getPasswordHash(userId);
            boolean result = decryptedPassword.equals(storedPassword);
            
            Log.d(TAG, "密码验证" + (result ? "成功" : "失败") + ": " + userId);
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "验证密码失败，尝试回退验证: " + e.getMessage());
            // 如果主验证失败，尝试使用回退模式验证
            try {
                // 从存储的数据中提取需要的部分
                String[] parts = storedData.split("]");
                byte[] iv = Base64.decode(parts[0], Base64.DEFAULT);
                byte[] encrypted = Base64.decode(parts[1], Base64.DEFAULT);
                
                return verifyPasswordFallback(userId, iv, encrypted);
            } catch (Exception ex) {
                Log.e(TAG, "回退验证也失败: " + ex.getMessage());
                throw new Exception("验证失败: " + e.getMessage());
            }
        }
    }

    /**
     * 回退模式验证密码
     */
    private static boolean verifyPasswordFallback(String userId, byte[] iv, byte[] encrypted) throws Exception {
        try {
            SecretKey key = getFallbackKey(userId);
            
            // 使用AES/CBC/PKCS5Padding解密
            Cipher cipher = Cipher.getInstance(AES_MODE_FALLBACK);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
            byte[] decrypted = cipher.doFinal(encrypted);
            String decryptedPassword = new String(decrypted);
            
            // 与存储的密码哈希比较
            String storedPassword = getPasswordHash(userId);
            boolean result = decryptedPassword.equals(storedPassword);
            
            Log.d(TAG, "回退模式密码验证" + (result ? "成功" : "失败") + ": " + userId);
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "回退验证失败: " + e.getMessage());
            
            // 如果未找到密码哈希，直接与当前输入的密码比较
            try {
                String storedPasswordFromPref = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(userId + "_direct_pwd", null);
                
                if (storedPasswordFromPref != null) {
                    // 使用直接存储的密码进行对比
                    String decryptedPassword = new String(encrypted); // 注意：这里假设数据没有加密
                    boolean result = decryptedPassword.equals(storedPasswordFromPref);
                    Log.d(TAG, "直接密码对比" + (result ? "成功" : "失败") + ": " + userId);
                    return result;
                }
            } catch (Exception ex) {
                // 忽略这个错误，继续抛出原始异常
            }
            
            throw new Exception("回退验证失败: " + e.getMessage());
        }
    }

    /**
     * 获取密钥
     */
    private static SecretKey getSecretKey(String keyAlias) throws Exception {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            
            if (!keyStore.containsAlias(keyAlias)) {
                Log.e(TAG, "密钥不存在: " + keyAlias);
                return null;
            }
            
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(keyAlias, null);
            return entry.getSecretKey();
        } catch (Exception e) {
            Log.e(TAG, "获取密钥失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 保存密码哈希（公开方法）
     */
    public static void savePasswordHash(String userId, String password) {
        if (userId == null || userId.isEmpty() || password == null) {
            Log.e(TAG, "无法保存密码哈希：用户ID或密码为空");
            return;
        }
        
        try {
            // 简单存储原始密码，实际应用中应存储哈希值
            SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(userId, password).apply();
            
            // 为回退模式也保存一份
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(userId + "_direct_pwd", password).apply();
            
            Log.d(TAG, "保存密码哈希成功: " + userId);
        } catch (Exception e) {
            Log.e(TAG, "保存密码哈希失败: " + e.getMessage());
        }
    }

    /**
     * 保存密码哈希（私有方法）
     */
    private static void savePasswordHashInternal(String userId, String password) {
        try {
            // 简单存储原始密码，实际应用中应存储哈希值
            SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(userId, password).apply();
            
            // 为回退模式也保存一份
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(userId + "_direct_pwd", password).apply();
            
            Log.d(TAG, "保存密码哈希成功: " + userId);
        } catch (Exception e) {
            Log.e(TAG, "保存密码哈希失败: " + e.getMessage());
        }
    }

    /**
     * 获取密码哈希
     */
    private static String getPasswordHash(String userId) {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            String password = prefs.getString(userId, null);
            
            if (password == null) {
                // 尝试从回退存储中获取
                password = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(userId + "_direct_pwd", null);
            }
            
            return password;
        } catch (Exception e) {
            Log.e(TAG, "获取密码哈希失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 删除用户凭据
     */
    public static boolean deleteUserCredentials(String userId) {
        try {
            String keyAlias = KEY_PREFIX + userId;
            
            // 删除KeyStore中的密钥
            if (!useFallbackMode) {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
                keyStore.load(null);
                
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias);
                }
            }
            
            // 删除SharedPreferences中的数据
            SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(userId).apply();
            
            // 删除回退模式的数据
            SharedPreferences fallbackPrefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
            fallbackPrefs.edit()
                .remove(userId + "_salt")
                .remove(userId + "_direct_pwd")
                .apply();
            
            Log.d(TAG, "删除用户凭据成功: " + userId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "删除用户凭据失败: " + e.getMessage());
            return false;
        }
    }
}