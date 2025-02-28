package com.weifu.app.utils;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

public class KeyUtils {



    // 用户登录成功后生成密钥
    public static void generateUserBoundKey(String userId) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

            // 构建密钥参数（关键绑定配置）
            KeyGenParameterSpec.Builder builder = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder = new KeyGenParameterSpec.Builder(
                            "user_key_" + userId, // 密钥别名包含用户ID
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                            .setUserAuthenticationRequired(true) // 必须认证
                            .setUserAuthenticationParameters(0, // 0秒立即失效
                                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyGenerator.init(builder.build());
            }
            keyGenerator.generateKey();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public static void authenticateWithFingerprint(String userId) throws CertificateException, IOException, NoSuchAlgorithmException, InvalidKeyException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            // 尝试获取该用户的专属密钥
            SecretKey key = (SecretKey) keyStore.getKey("user_key_" + userId, null);

            // 如果密钥不存在说明未绑定
            if (key == null) {

                return;
            }

            // 触发指纹认证（密钥操作会自动请求生物认证）
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key); // 这里会触发指纹验证

            // 如果执行到这里说明：
            // 1. 指纹验证通过
            // 2. 密钥属于当前用户
          // 登录成功

        } catch (UserNotAuthenticatedException e) {
            // 生物认证失败
        } catch (KeyPermanentlyInvalidatedException e) {
            // 指纹库发生变更（如删除/新增指纹）
            handleInvalidKey(userId);
        } catch (UnrecoverableKeyException e) {
            throw new RuntimeException(e);
        } catch (NoSuchPaddingException e) {
            throw new RuntimeException(e);
        } catch (KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    public static void handleInvalidKey(String userId) {
        // 删除旧密钥
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry("user_key_" + userId);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 强制要求重新密码登录
      //  Toast.makeText(this, "检测到指纹变更，请重新密码登录", Toast.LENGTH_SHORT).show();
//        startActivity(new Intent(this, LoginActivity.class));
//        finish();
    }

}
