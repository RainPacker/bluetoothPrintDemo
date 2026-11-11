package com.weifu.scanplugin.device;

/**
 * 设备扫码统一接口（F-05）
 * <p>
 * 所有扫码设备（斑马 DataWedge / iData / 扫码枪）实现此接口，
 * ScanPlugin 门面通过该接口统一调度，H5 无需关心设备型号。
 */
public interface DeviceScanner {

    /**
     * 设备类型标识（zebra / idata / gun）
     */
    String getType();

    /**
     * 启动一次扫码（软触发）
     */
    void start();

    /**
     * 停止扫码
     */
    void stop();

    /**
     * 设置扫码结果监听
     */
    void setListener(OnDeviceScanListener listener);

    /**
     * 释放资源（注销广播等）
     */
    void release();

    /**
     * 设备扫码结果监听
     */
    interface OnDeviceScanListener {
        /**
         * @param text   码值
         * @param format 码制（设备可识别时返回，否则 null）
         */
        void onResult(String text, String format);
    }
}
