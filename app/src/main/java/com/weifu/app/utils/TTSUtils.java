package com.weifu.app.utils;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class TTSUtils implements TextToSpeech.OnInitListener {
    private static final String TAG = "TTSUtils";
    private static TTSUtils instance;

    // TTS核心组件
    private TextToSpeech tts;
    private Context context;
    private boolean isInitialized = false;

    // 队列相关
    private final Queue<String> messageQueue = new LinkedList<>();
    private final ReentrantLock queueLock = new ReentrantLock();
    private boolean isSpeaking = false;

    // 配置参数
    private float speechRate = 1.0f;
    private float pitch = 1.0f;
    private Locale language = Locale.getDefault();

    // 监听器
    public interface TTSListener {
        void onInitSuccess();
        void onInitFailure(String error);
        void onSpeechStart(String utteranceId);
        void onSpeechComplete(String utteranceId);
        void onSpeechError(String utteranceId);
        void onQueueEmpty();
    }
    private TTSListener listener;

    private TTSUtils(Context context) {
        this.context = context.getApplicationContext();
        initTTS();
    }

    public static synchronized TTSUtils getInstance(Context context) {
        if (instance == null) {
            instance = new TTSUtils(context);
        }
        return instance;
    }

    private void initTTS() {
        if (tts == null) {
            tts = new TextToSpeech(context, this);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    isSpeaking = true;
                    notifySpeechStart(utteranceId);
                }

                @Override
                public void onDone(String utteranceId) {
                    handleSpeechComplete(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    handleSpeechError(utteranceId);
                }
            });
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(language);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                notifyError("Language not supported");
                return;
            }
            isInitialized = true;
            tts.setSpeechRate(speechRate);
            tts.setPitch(pitch);
            notifyInitSuccess();
            checkAndPlayQueue(); // 初始化成功后检查队列
        } else {
            notifyError("TTS initialization failed");
        }
    }

    //------------------------ 队列管理核心方法 ------------------------
    public void addToQueue(String text) {
        queueLock.lock();
        try {
            if (text == null || text.isEmpty()) return;
            messageQueue.offer(text);
            if (!isSpeaking && isInitialized) {
                playNext();
            }
        } finally {
            queueLock.unlock();
        }
    }

    public void addUrgentMessage(String text) {
        queueLock.lock();
        try {
            messageQueue.clear();
            messageQueue.offer(text);
            if (isSpeaking) {
                tts.stop();
            }
            playNext();
        } finally {
            queueLock.unlock();
        }
    }

    private void playNext() {
        queueLock.lock();
        try {
            if (!messageQueue.isEmpty() && isInitialized) {
                String text = messageQueue.poll();
                String utteranceId = UUID.randomUUID().toString();
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
            } else {
                isSpeaking = false;
                notifyQueueEmpty();
            }
        } finally {
            queueLock.unlock();
        }
    }

    public void clearQueue() {
        queueLock.lock();
        try {
            messageQueue.clear();
            if (isSpeaking) {
                tts.stop();
            }
            isSpeaking = false;
        } finally {
            queueLock.unlock();
        }
    }

    //------------------------ 状态处理 ------------------------
    private void handleSpeechComplete(String utteranceId) {
        isSpeaking = false;
        notifySpeechComplete(utteranceId);
        checkAndPlayQueue();
    }

    private void handleSpeechError(String utteranceId) {
        isSpeaking = false;
        notifySpeechError(utteranceId);
        checkAndPlayQueue();
    }

    private void checkAndPlayQueue() {
        queueLock.lock();
        try {
            if (!messageQueue.isEmpty() && !isSpeaking) {
                playNext();
            }
        } finally {
            queueLock.unlock();
        }
    }

    //------------------------ 监听器通知 ------------------------
    private void notifyInitSuccess() {
        if (listener != null) {
            listener.onInitSuccess();
        }
    }

    private void notifyError(String error) {
        Log.e(TAG, error);
        if (listener != null) {
            listener.onInitFailure(error);
        }
    }

    private void notifySpeechStart(String utteranceId) {
        if (listener != null) {
            listener.onSpeechStart(utteranceId);
        }
    }

    private void notifySpeechComplete(String utteranceId) {
        if (listener != null) {
            listener.onSpeechComplete(utteranceId);
        }
    }

    private void notifySpeechError(String utteranceId) {
        if (listener != null) {
            listener.onSpeechError(utteranceId);
        }
    }

    private void notifyQueueEmpty() {
        if (listener != null) {
            listener.onQueueEmpty();
        }
    }

    //------------------------ 公开方法 ------------------------
    public void setTTSListener(TTSListener listener) {
        this.listener = listener;
    }

    public int getQueueSize() {
        return messageQueue.size();
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts != null) {
            tts.setSpeechRate(rate);
        }
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (tts != null) {
            tts.setPitch(pitch);
        }
    }

    public void setLanguage(Locale locale) {
        this.language = locale;
        if (tts != null) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                notifyError("Language not supported");
            }
        }
    }

    public void release() {
        clearQueue();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        instance = null;
    }
}