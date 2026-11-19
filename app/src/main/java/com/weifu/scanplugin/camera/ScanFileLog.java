package com.weifu.scanplugin.camera;

import com.weifu.app.BuildConfig;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 扫码调试文件日志：解码耗时/命中/确认信息落盘到应用外部目录，
 * 便于真机排查（logcat 过滤不便时直接拉取文件）。
 * 路径：/sdcard/Android/data/{pkg}/files/logs/scan_log.txt（超过 512KB 自动截断重写）
 * <p>
 * 异步缓冲写入：解码线程每帧调用 4~8 次 log，原实现每次 open/write/close + f.length()
 * stat，文件 IO 在解码线程上每帧额外消耗 20~80ms（有效帧率减半的元凶之一）。
 * 改为内存队列 + 单后台守护线程持有常驻 FileWriter：调用方只做无阻塞 offer
 * （队列满直接丢弃，绝不拖慢解码），后台线程批量落盘。
 */
final class ScanFileLog {

    private static final int MAX_BYTES = 512 * 1024;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final java.util.concurrent.BlockingQueue<String> QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>(256);
    private static volatile boolean started = false;

    private ScanFileLog() {
    }

    static void log(String msg) {
        if (!started) {
            startWriter();
        }
        // offer 非阻塞：队列满（磁盘慢/积压）时丢弃日志，保证解码线程零等待
        QUEUE.offer(FMT.format(new Date()) + " " + msg + "\n");
    }

    private static synchronized void startWriter() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(ScanFileLog::writeLoop, "scan-file-log");
        t.setDaemon(true);
        t.start();
    }

    private static void writeLoop() {
        File f = new File("/sdcard/Android/data/" + BuildConfig.APPLICATION_ID + "/files/logs",
                "scan_log.txt");
        FileWriter fw = null;
        long bytes = 0;
        while (true) {
            try {
                String msg = QUEUE.take();
                if (fw == null) {
                    File dir = f.getParentFile();
                    if (dir != null && !dir.exists() && !dir.mkdirs()) {
                        continue;
                    }
                    boolean append = f.exists() && f.length() <= MAX_BYTES;
                    fw = new FileWriter(f, append);
                    bytes = append ? f.length() : 0;
                }
                fw.write(msg);
                bytes += msg.length();
                if (bytes > MAX_BYTES) {
                    // 超限关闭，下次循环以截断模式重建（省去每次写前的 f.length() stat）
                    fw.close();
                    fw = null;
                    f.delete();
                } else {
                    fw.flush();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                try {
                    if (fw != null) {
                        fw.close();
                    }
                } catch (Throwable ignored2) {
                }
                fw = null;
            }
        }
    }
}
