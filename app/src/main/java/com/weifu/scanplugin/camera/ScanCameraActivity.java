package com.weifu.scanplugin.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.zxing.Result;
import com.weifu.app.R;
import com.weifu.scanplugin.ScanPluginConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX 相机扫码页（F-06 / F-07 / F-09 ~ F-15 / F-19 / F-22）
 * <p>
 * 核心改进（相对旧 MyScan）：
 * 1. 方向继承：onCreate 时锁定与宿主页面一致的方向，跳转全程无旋转动画（解决 P-03）；
 * 2. CameraX PreviewView 自动处理预览旋转，横竖屏取景框动态适配；
 * 3. DPM 增强解码管线（多帧 + 自适应二值化 + 反色 + 旋转 + ROI 放大）；
 * 4. 连续扫码 / 补光灯 / 相册识别 / 超时取消 / 提示音。
 * <p>
 * 结果通过静态监听器回传 ScanPlugin（插件内部闭环，不依赖宿主 onActivityResult）。
 */
public class ScanCameraActivity extends AppCompatActivity {

    private static final String TAG = "ScanCameraActivity";

    // ---- Intent extras ----
    public static final String EXTRA_ORIENTATION = "extra_orientation";
    public static final String EXTRA_DPM = "extra_dpm";
    public static final String EXTRA_CONTINUOUS = "extra_continuous";
    public static final String EXTRA_TIMEOUT = "extra_timeout";
    public static final String EXTRA_TORCH = "extra_torch";
    public static final String EXTRA_ALBUM = "extra_album";
    public static final String EXTRA_BEEP = "extra_beep";
    public static final String EXTRA_FRONT = "extra_front";
    public static final String EXTRA_FORMATS = "extra_formats";

    /**
     * 相机扫码结果监听（静态持有，由 ScanPlugin 在启动前设置）
     */
    public interface CameraResultListener {
        void onCameraResult(String text, String format);

        void onCameraCancel();

        void onCameraError(String message);
    }

    private static volatile CameraResultListener pendingListener;

    public static void setPendingListener(CameraResultListener listener) {
        pendingListener = listener;
    }

    /**
     * 当前活跃实例（供 ScanPlugin.stop() 远程关闭）
     */
    private static volatile ScanCameraActivity activeInstance;

    /**
     * 关闭当前活跃的相机扫码页（F-14 取消）
     */
    public static void finishIfActive() {
        ScanCameraActivity instance = activeInstance;
        if (instance != null) {
            instance.runOnUiThread(instance::finishWithCancel);
        }
    }

    private PreviewView previewView;
    private ScanOverlayView overlayView;
    private View torchButton;
    private View albumButton;
    private View flipButton;
    private ImageView torchIcon;
    private TextView dpmBadge;

    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private DpmImageAnalyzer analyzer;
    private ExecutorService cameraExecutor;
    private ToneGenerator toneGenerator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 相册识别中的进度对话框（期间相机扫码暂停，省电且避免双路识别竞争）
    private android.app.AlertDialog albumProgressDialog;

    /**
     * 微信式周期自动对焦：连续对焦（CONTINUOUS_PICTURE）在金属刻印等低对比度表面
     * 会"对焦失败后静默放弃"，表现为长时间不清晰、识别极慢。
     * 周期性对画面中心触发 AF/AE 测光强制重新搜寻对焦（微信同款策略）。
     */
    private static final long AUTO_FOCUS_INTERVAL_MS = 1800;

    private final Runnable autoFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if (finished || camera == null || previewView.getWidth() <= 0) {
                return;
            }
            try {
                MeteringPoint center = previewView.getMeteringPointFactory().createPoint(
                        previewView.getWidth() / 2f, previewView.getHeight() / 2f);
                FocusMeteringAction action = new FocusMeteringAction.Builder(center,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(1, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                camera.getCameraControl().startFocusAndMetering(action);
            } catch (Exception ignored) {
            }
            mainHandler.postDelayed(this, AUTO_FOCUS_INTERVAL_MS);
        }
    };

    private void startAutoFocus() {
        mainHandler.removeCallbacks(autoFocusRunnable);
        mainHandler.postDelayed(autoFocusRunnable, AUTO_FOCUS_INTERVAL_MS);
    }

    private void stopAutoFocus() {
        mainHandler.removeCallbacks(autoFocusRunnable);
    }

    private boolean dpmMode;
    private boolean continuous;
    private boolean torchOn;
    private boolean frontCamera;
    private boolean albumEnabled;
    private boolean beepEnabled;
    private long timeoutMillis;
    private boolean finished = false;
    private long lastResultTime;
    /** 帧间一致性验证：待确认文本（同文本跨帧出现 2 次才确认输出，防单帧误读） */
    private String validatedText;
    private long validatedFirstTime;

    private final Runnable timeoutRunnable = this::finishWithCancel;
    private final ActivityResultLauncher<Intent> albumLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleAlbumImage(result.getData().getData());
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // F-19：方向锁定（必须在 super.onCreate 之后调用才能可靠覆盖 Manifest 值）
        // 无条件锁定：EXTRA_ORIENTATION 缺失时按当前实际显示方向兜底，
        // 杜绝 unspecified/fullSensor 状态下扫码页跟随传感器旋转
        applyOrientationLock();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activeInstance = this;
        setContentView(R.layout.activity_scan_camera);

        parseConfig();
        bindViews();
        if (checkCameraPermission()) {
            startCamera();
        }
        // F-14：超时自动取消
        if (timeoutMillis > 0) {
            mainHandler.postDelayed(timeoutRunnable, timeoutMillis);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 防御性重锁：系统配置变化重建（如权限对话框、相册返回）后确保方向仍被锁定
        applyOrientationLock();
    }

    /**
     * 方向锁定策略（F-19 终版）：
     * 1. Intent 指定了方向（landscape/portrait 显式值）→ 直接锁定；
     * 2. 未指定（auto）→ SCREEN_ORIENTATION_LOCKED：锁定当前实际显示方向，
     *    之后忽略一切传感器变化。
     *    不可用 SENSOR_LANDSCAPE/SENSOR_PORTRAIT 兜底——它们响应传感器族内翻转，
     *    模拟器虚拟传感器延迟初始化/抖动时，扫码页会在几秒后突然 180° 旋转。
     */
    private void applyOrientationLock() {
        int orientation = getIntent().getIntExtra(EXTRA_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            // auto 兜底：LOCKED 锁定当前方向（已继承宿主），忽略传感器后续变化
            orientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        }
        setRequestedOrientation(orientation);
    }

    private void parseConfig() {
        Intent intent = getIntent();
        dpmMode = intent.getBooleanExtra(EXTRA_DPM, true);
        continuous = intent.getBooleanExtra(EXTRA_CONTINUOUS, false);
        torchOn = intent.getBooleanExtra(EXTRA_TORCH, false);
        frontCamera = intent.getBooleanExtra(EXTRA_FRONT, false);
        albumEnabled = intent.getBooleanExtra(EXTRA_ALBUM, true);
        beepEnabled = intent.getBooleanExtra(EXTRA_BEEP, true);
        timeoutMillis = intent.getIntExtra(EXTRA_TIMEOUT, 30) * 1000L;
        pendingConfig = new ScanPluginConfig();
        // EXTRA_FORMATS 为 JSON 数组字符串（如 ["DATA_MATRIX","QR_CODE"]）
        String formats = intent.getStringExtra(EXTRA_FORMATS);
        if (formats != null && !formats.isEmpty()) {
            try {
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(formats).getAsJsonArray();
                java.util.Set<String> set = new java.util.HashSet<>();
                for (com.google.gson.JsonElement el : arr) {
                    set.add(el.getAsString());
                }
                pendingConfig.setFormats(set);
            } catch (Exception e) {
                Log.w(TAG, "invalid formats extra: " + formats, e);
            }
        }
    }

    private ScanPluginConfig pendingConfig;

    private void bindViews() {
        previewView = findViewById(R.id.preview_view);
        overlayView = findViewById(R.id.overlay_view);
        torchButton = findViewById(R.id.btn_torch);
        albumButton = findViewById(R.id.btn_album);
        flipButton = findViewById(R.id.btn_flip);
        torchIcon = findViewById(R.id.iv_torch);
        dpmBadge = findViewById(R.id.tv_dpm);
        ImageView backButton = findViewById(R.id.btn_back);
        initScaleDetector();

        if (dpmMode) {
            dpmBadge.setVisibility(View.VISIBLE);
        }
        if (!albumEnabled) {
            albumButton.setVisibility(View.GONE);
        }
        updateTorchIcon();
        torchButton.setOnClickListener(v -> toggleTorch());
        albumButton.setOnClickListener(v -> pickFromAlbum());
        // 前后摄切换（微信风格翻转相机）
        flipButton.setOnClickListener(v -> {
            frontCamera = !frontCamera;
            if (cameraProvider != null) {
                bindCamera();
            }
        });
        backButton.setOnClickListener(v -> finishWithCancel());

        // 横屏时隐藏提示文字：横屏高度小，屏幕居中的取景框会延伸到低处，
        // 底部提示文字区与取景框下沿重叠（遮挡扫描画面），竖屏保留
        TextView hintView = findViewById(R.id.tv_hint);
        if (getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            hintView.setVisibility(View.GONE);
        }

        // 点击对焦 + 双指缩放变焦（F-30 + 缩放扫码）
        previewView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                tapToFocus(event.getX(), event.getY());
            }
            return true;
        });
    }

    /**
     * 双指缩放变焦：拉近远处的码（缩放扫码）。
     * 必须在 bindViews()（attach 之后）初始化——字段初始化器中 this 未完成 attach，
     * ScaleGestureDetector 构造内部调 getResources() 会 NPE 导致 Activity 无法实例化闪退。
     */
    private android.view.ScaleGestureDetector scaleDetector;

    private void initScaleDetector() {
        scaleDetector = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector detector) {
                if (camera == null) {
                    return false;
                }
                androidx.lifecycle.LiveData<androidx.camera.core.ZoomState> live =
                        camera.getCameraInfo().getZoomState();
                androidx.camera.core.ZoomState state = live.getValue();
                if (state == null) {
                    return false;
                }
                float newRatio = state.getZoomRatio() * detector.getScaleFactor();
                newRatio = Math.max(state.getMinZoomRatio(),
                        Math.min(state.getMaxZoomRatio(), newRatio));
                camera.getCameraControl().setZoomRatio(newRatio);
                return true;
            }
        });
    }

    private boolean checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 200);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                finishWithError("未授予相机权限");
            }
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void startCamera() {
        // executor / analyzer 只创建一次（切换摄像头重复调用时复用，避免线程泄漏）
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor();
            final ScanPluginConfig config = pendingConfig != null ? pendingConfig : new ScanPluginConfig();
            DecodeEngine decodeEngine = new DecodeEngine(dpmMode, config.getFormats());
            // DPM 模式提高帧处理率：刻印码识别依赖多帧概率累积，200ms 节流（5fps）不足
            int intervalMs = dpmMode ? Math.min(120, config.getFrameIntervalMs()) : config.getFrameIntervalMs();
            analyzer = new DpmImageAnalyzer(decodeEngine, intervalMs,
                    result -> mainHandler.post(() -> onDecodeResult(result)));
        }

        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "camera init failed", e);
                finishWithError("相机初始化失败: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 绑定相机（首次启动与翻转相机共用）：按 frontCamera 选择前后摄
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void bindCamera() {
        try {
            // 连续自动对焦（F-06 识别率关键）：Preview+ImageAnalysis 组合下 CameraX 默认
            // 不启用连续对焦，近距离条码模糊导致识别率低。
            // 通过 Camera2Interop 对预览与分析流均开启 CONTINUOUS_PICTURE（扫码枪标准做法）
            Preview.Builder previewBuilder = new Preview.Builder();
            new androidx.camera.camera2.interop.Camera2Interop.Extender<>(previewBuilder)
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Preview preview = previewBuilder.build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // 高分辨率分析流（小尺寸 DM 码需要更高模块分辨率）
            ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                    .setResolutionSelector(new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                    new Size(1920, 1080),
                                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
            new androidx.camera.camera2.interop.Camera2Interop.Extender<>(analysisBuilder)
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            ImageAnalysis imageAnalysis = analysisBuilder.build();
            imageAnalysis.setAnalyzer(cameraExecutor, analyzer);

            CameraSelector selector = frontCamera
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;

            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
            camera.getCameraControl().enableTorch(torchOn);
            startAutoFocus();
        } catch (Exception e) {
            Log.e(TAG, "bindCamera failed", e);
            // 翻转到不支持的摄像头时回退后摄，首次绑定失败才报错退出
            if (frontCamera) {
                frontCamera = false;
                bindCamera();
            } else {
                finishWithError("相机初始化失败: " + e.getMessage());
            }
        }
    }

    private void tapToFocus(float x, float y) {
        if (camera == null) {
            return;
        }
        try {
            MeteringPoint meteringPoint = previewView.getMeteringPointFactory()
                    .createPoint(x, y);
            // autoCancel：2s 后释放测光区域，周期对焦恢复正常工作
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    meteringPoint, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            // 点击对焦后重置周期计时，避免立即与点击测光冲突
            startAutoFocus();
        } catch (Exception e) {
            Log.w(TAG, "focus failed", e);
        }
    }

    private void toggleTorch() {
        torchOn = !torchOn;
        if (camera != null) {
            camera.getCameraControl().enableTorch(torchOn);
        }
        updateTorchIcon();
    }

    /**
     * 闪光灯图标状态切换（开=亮黄闪电）
     */
    private void updateTorchIcon() {
        if (torchIcon != null) {
            torchIcon.setImageResource(torchOn
                    ? R.drawable.ic_scan_torch_on
                    : R.drawable.ic_scan_torch);
        }
    }

    private void pickFromAlbum() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        albumLauncher.launch(intent);
    }

    private void handleAlbumImage(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            // 两段式采样解码：先读尺寸算 inSampleSize 再载入，
            // 避免 Media.getBitmap 整图载入内存（大图可达上百 MB，PDA 堆有限易 OOM）
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            java.io.InputStream bis = getContentResolver().openInputStream(uri);
            if (bis != null) {
                android.graphics.BitmapFactory.decodeStream(bis, null, bounds);
                bis.close();
            }
            int maxSide = 1600;
            int inSample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (inSample * 2) >= maxSide) {
                inSample *= 2;
            }
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = inSample;
            Bitmap bitmap = null;
            java.io.InputStream lis = getContentResolver().openInputStream(uri);
            if (lis != null) {
                bitmap = android.graphics.BitmapFactory.decodeStream(lis, null, opts);
                lis.close();
            }
            if (bitmap == null) {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
                return;
            }
            // EXIF 方向矫正（相机竖拍照片常见 90° 旋转，解码器不读取 EXIF）
            bitmap = applyExifRotation(uri, bitmap);
            // 兜底尺寸限制（inSampleSize 粒度为 2 的幂，可能仍未到目标）
            float scale = Math.min(1f, (float) maxSide / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) {
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        (int) (bitmap.getWidth() * scale), (int) (bitmap.getHeight() * scale), false);
            }
            final Bitmap finalBitmap = bitmap;
            if (analyzer != null) {
                // 暂停相机扫码：解码期间不再逐帧分析（省电），
                // 也避免相机识别与相册识别在解码线程上竞争
                analyzer.setActive(false);
                // 暂停超时计时，避免识别中被超时关页；识别失败后重新计时
                mainHandler.removeCallbacks(timeoutRunnable);
                showAlbumProgress();
                analyzer.decodeBitmap(finalBitmap, result ->
                        mainHandler.post(() -> {
                            dismissAlbumProgress();
                            if (result == null) {
                                Toast.makeText(this, "未识别到条码", Toast.LENGTH_SHORT).show();
                                resumeAfterAlbum();
                            } else if (!finished) {
                                deliverResult(result);
                            }
                        }));
            }
        } catch (Exception e) {
            Log.e(TAG, "album decode failed", e);
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 相册识别进度对话框（模态，直到识别完成/失败才消失）
     */
    private void showAlbumProgress() {
        dismissAlbumProgress();
        albumProgressDialog = new android.app.AlertDialog.Builder(this)
                .setMessage("识别中...")
                .setCancelable(false)
                .show();
    }

    private void dismissAlbumProgress() {
        if (albumProgressDialog != null && albumProgressDialog.isShowing()) {
            albumProgressDialog.dismiss();
        }
        albumProgressDialog = null;
    }

    /**
     * 相册识别失败后恢复：重启扫码分析并重新挂载超时计时
     */
    private void resumeAfterAlbum() {
        if (analyzer != null && !finished) {
            analyzer.setActive(true);
        }
        if (timeoutMillis > 0) {
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.postDelayed(timeoutRunnable, timeoutMillis);
        }
    }

    /**
     * 按 EXIF 方向旋转相册图片（竖拍照片 Orientation=90/180/270）
     */
    private Bitmap applyExifRotation(Uri uri, Bitmap bitmap) {
        try {
            java.io.InputStream eis = getContentResolver().openInputStream(uri);
            if (eis == null) {
                return bitmap;
            }
            android.media.ExifInterface exif = new android.media.ExifInterface(eis);
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            eis.close();
            int degrees = 0;
            if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
            if (degrees != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(degrees);
                return Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), m, true);
            }
        } catch (Exception ignored) {
        }
        return bitmap;
    }

    /**
     * 解码结果处理（F-09 连续模式 / F-15 提示音）
     * <p>
     * 帧间一致性验证（F-34，工业读码器标准做法）：
     * 低质量 DM 帧（反光/网格错位/部分模块误判）可能被 ECC 纠错出各不相同的错误内容，
     * 而真值在多帧中重复出现——同文本跨帧 ≥2 次才确认输出，单帧误读被自然稀释，
     * 解决"同一码每次扫描结果不一样"的问题。代价是确认延迟约 1~2 帧（100~200ms）。
     */
    private void onDecodeResult(Result result) {
        if (finished || result == null) {
            return;
        }
        String text = result.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // ===== 帧间一致性验证 =====
        if (!text.equals(validatedText)) {
            // 与上次待确认文本不同：本帧结果进入待确认状态（首帧不输出）
            validatedText = text;
            validatedFirstTime = now;
            Log.d(TAG, "result pending validation: " + text);
            return;
        }
        // 同文本跨帧再次出现 → 可重复 → 确认（真值唯一，误读内容各异难以重复）
        validatedText = null;
        validatedFirstTime = 0;
        Log.i(TAG, "result validated: " + text);
        deliverResult(result);
    }

    /**
     * 结果输出（连续模式防抖 + 成功反馈 + 回调 + 页面关闭/连续暂停）：
     * 相机识别（跨帧验证通过后）与相册识别（单次直接调用）共用
     */
    private void deliverResult(Result result) {
        String text = result.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (continuous && now - lastResultTime < 800) {
            return;
        }
        lastResultTime = now;
        Log.i(TAG, "decode success: " + (result.getBarcodeFormat() == null ? "?" : result.getBarcodeFormat().name())
                + " text=" + text);
        // 成功反馈：震动 + 提示音（结果展示在测试页/H5 回调，不用 Toast 打扰）
        vibrate();
        if (beepEnabled) {
            playBeep();
        }
        CameraResultListener listener = pendingListener;
        if (listener != null) {
            listener.onCameraResult(text,
                    result.getBarcodeFormat() == null ? null : result.getBarcodeFormat().name());
        }
        if (!continuous) {
            finishWithSuccess();
        } else {
            // 连续模式：短暂暂停分析后继续
            if (analyzer != null) {
                analyzer.setActive(false);
                mainHandler.postDelayed(() -> {
                    if (analyzer != null && !finished) {
                        analyzer.setActive(true);
                    }
                }, 1200);
            }
        }
    }

    private void playBeep() {
        try {
            if (toneGenerator == null) {
                // STREAM_MUSIC：通知流（STREAM_NOTIFICATION）音量小且常被用户关闭，
                // 音乐流音量饱满可靠，扫码提示音业界均用此流
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {
        }
    }

    /**
     * 成功震动反馈（~120ms），与提示音互补
     */
    private void vibrate() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(120,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(120);
            }
        } catch (Exception ignored) {
        }
    }

    private void finishWithSuccess() {
        finished = true;
        mainHandler.removeCallbacks(timeoutRunnable);
        finish();
    }

    private void finishWithCancel() {
        finished = true;
        mainHandler.removeCallbacks(timeoutRunnable);
        CameraResultListener listener = pendingListener;
        if (listener != null) {
            listener.onCameraCancel();
        }
        finish();
    }

    private void finishWithError(String message) {
        finished = true;
        mainHandler.removeCallbacks(timeoutRunnable);
        CameraResultListener listener = pendingListener;
        if (listener != null) {
            listener.onCameraError(message);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        stopAutoFocus();
        dismissAlbumProgress();
        mainHandler.removeCallbacks(timeoutRunnable);
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (analyzer != null) {
            analyzer.release();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        pendingListener = null;
        super.onDestroy();
    }
}
