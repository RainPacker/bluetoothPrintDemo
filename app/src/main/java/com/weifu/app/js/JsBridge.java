package com.weifu.app.js;


import static com.inuker.bluetooth.library.Code.REQUEST_SUCCESS;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.google.gson.Gson;
import com.inuker.bluetooth.library.BluetoothClient;
import com.inuker.bluetooth.library.connect.options.BleConnectOptions;
import com.inuker.bluetooth.library.connect.response.BleConnectResponse;
import com.inuker.bluetooth.library.connect.response.BleReadResponse;
import com.inuker.bluetooth.library.connect.response.BleWriteResponse;
import com.inuker.bluetooth.library.model.BleGattProfile;
import com.inuker.bluetooth.library.receiver.listener.BluetoothBondListener;
import com.symbol.emdk.barcode.Scanner;
import com.symbol.emdk.barcode.ScannerException;
import com.weifu.app.MainActivity;
import com.weifu.app.R;
import com.weifu.utils.BluetoothUtil;
import com.weifu.utils.EscPosUtils;
import com.weifu.utils.PrintUtil;

import net.posprinter.posprinterface.ProcessData;
import net.posprinter.posprinterface.TaskCallback;
import net.posprinter.utils.DataForSendToPrinterPos80;
import net.posprinter.utils.StringUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class JsBridge implements ProcessData {
    String TAG = getClass().getSimpleName();
    private MainActivity activity;
    private ProgressDialog progressDialog;
    private boolean btConnected = false;

    private AsyncTask mConnectTask;

    private BluetoothSocket mSocket;

    final static int TASK_TYPE_CONNECT = 1;
    final static int TASK_TYPE_PRINT = 2;
    /**
     * 已经配对的蓝牙设备
     */
    private List<BluetoothDevice> devices;


    public JsBridge(MainActivity mainActivity) {
        this.activity = mainActivity;
    }

    public List<BluetoothDevice> getDevices() {
        return devices;
    }

    public void setDevices(List<BluetoothDevice> devices) {
        this.devices = devices;
    }

    @JavascriptInterface
    public void showToast(String msg) {

        activity.runOnUiThread(() -> Toast.makeText(activity, msg == null ? "" : msg, Toast.LENGTH_SHORT).show());

    }

    @JavascriptInterface
    public boolean isBluetoothEnabled() {
        return BluetoothUtil.isBluetoothOn();
    }

    @JavascriptInterface
    public String getPairedDevice() {
        if(!BluetoothUtil.isBluetoothOn()){
            showToast("请打开蓝牙");
            return "[]";
        }
        List<BluetoothDevice> devices = BluetoothUtil.getPairedDevice();
        this.setDevices(devices);
        List<BluetoothEntity> entities = new ArrayList<>();
        for (BluetoothDevice device : devices) {
            BluetoothEntity entity = new BluetoothEntity();
            String name = device.getName();
            if (name == null) {
                name = "";
            }
            entity.setName(name);
            entity.setAddress(device.getAddress());
            entities.add(entity);
        }
        String json = new Gson().toJson(entities);
        Log.d(TAG, json);
        return json;
    }

    @JavascriptInterface
    public void showProgress(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }

                progressDialog = ProgressDialog.show(activity, "提示", msg == null ? "" : msg);
            }
        });
    }

    @JavascriptInterface
    public void connectPrinter(final String address) {
        if (!BluetoothUtil.isBluetoothOn()) {
            showToast("蓝牙未打开");
            return;
        }

        this.showProgress("请稍等");
        activity.getPrinterBinder().ConnectBtPort(address, new TaskCallback() {
            @Override
            public void OnSucceed() {
                btConnected = true;
                closeProgress();
                showToast("连接打印机成功");
            }

            @Override
            public void OnFailed() {
                btConnected = false;
                closeProgress();
                showToast("连接打印机失败");
            }
        });
    }

    @JavascriptInterface
    public void closeProgress() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                    progressDialog = null;
                }
            }
        });
    }

    @JavascriptInterface
    public void disconnectPrinter() {
        if (!btConnected) {
            showToast("还未连接打印机");
            return;
        }
        activity.getPrinterBinder().DisconnectCurrentPort(new TaskCallback() {
            @Override
            public void OnSucceed() {
                btConnected = false;
                showToast("打印机连接已断开");
            }

            @Override
            public void OnFailed() {
                showToast("打印机断连失败");
            }
        });
    }


    @JavascriptInterface
    public void printPaper(final String json) {
        if (!btConnected) {
            showToast("还未连接打印机");
            return;
        }
        activity.getPrinterBinder().WriteSendData(new TaskCallback() {
            @Override
            public void OnSucceed() {
                showToast("打印成功");
            }

            @Override
            public void OnFailed() {
                showToast("打印失败");
            }
        }, this);
    }

    @Override
    public List<byte[]> processDataBeforeSend() {
        List<byte[]> list = new ArrayList<>();

        list.add(EscPosUtils.CUT_PAPER);
        list.add(EscPosUtils.RESET);
        list.add(EscPosUtils.LINE_SPACING_DEFAULT);
        list.add(EscPosUtils.ALIGN_CENTER);
        list.add(EscPosUtils.DOUBLE_HEIGHT_WIDTH);
        list.add(StringUtils.strTobytes("发货单"));

        list.add(EscPosUtils.NORMAL);
        list.add(EscPosUtils.ALIGN_LEFT);
        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(EscPosUtils.BOLD);
        list.add(StringUtils.strTobytes(EscPosUtils.format3Column("商品名称", "数量/单价", "金额")));
        list.add(EscPosUtils.BOLD_CANCEL);
        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(StringUtils.strTobytes(EscPosUtils.format3Column("地方33地方", "4/1.00", "4.00")));
        list.add(StringUtils.strTobytes(EscPosUtils.format3Column("sf面啊啊啊牛肉面啊啊啊", "888/10", "8880.00")));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("合计", "8884.00")));

        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(EscPosUtils.BOLD);
        list.add(EscPosUtils.ALIGN_CENTER);
        list.add(StringUtils.strTobytes("买家信息"));
        list.add(EscPosUtils.BOLD_CANCEL);
        list.add(EscPosUtils.ALIGN_LEFT);
        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("姓名", "穿青人")));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("收货地址", "贵州省贵阳市花溪区xx都是x非得让他")));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("联系方式", "5449856555556")));

        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(EscPosUtils.BOLD);
        list.add(EscPosUtils.ALIGN_CENTER);
        list.add(StringUtils.strTobytes("卖家信息"));
        list.add(EscPosUtils.BOLD_CANCEL);
        list.add(EscPosUtils.ALIGN_LEFT);
        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("姓名", "张三")));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("收货地址", "浙江省杭州市滨江区中威大厦11楼")));
        list.add(StringUtils.strTobytes(EscPosUtils.format2Column("联系方式", "5449856555556")));
        list.add(StringUtils.strTobytes(EscPosUtils.formatDividerLine()));

        list.add(EscPosUtils.ALIGN_CENTER);
        list.add(DataForSendToPrinterPos80.setBarcodeWidth(2));
        list.add(DataForSendToPrinterPos80.setBarcodeHeight(80));
        list.add(DataForSendToPrinterPos80.printBarcode(73, 10, "{B12345678"));

        list.add(EscPosUtils.ALIGN_LEFT);
        list.add(StringUtils.strTobytes(""));

        return list;
    }


    /**
     * 检查蓝牙状态，如果已打开，则查找已绑定设备
     *
     * @return
     */
    public boolean checkBluetoothState() {
        if (BluetoothUtil.isBluetoothOn()) {
            return true;
        } else {
            BluetoothUtil.openBluetooth(activity);
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @JavascriptInterface
    public void connectBluetooth(String addrs,String type) {

        if (BluetoothUtil.isBluetoothOn()) {
            getPairedDevice();
            List<BluetoothDevice> deviceList = this.getDevices();
            List<BluetoothDevice> filteredDevice = deviceList.stream().filter(item -> item.getAddress().equals(addrs)).collect(Collectors.toList());
            if (filteredDevice == null || filteredDevice.isEmpty()) {
                showToast("蓝牙设备不存在或未配对");
                return;
            }

          connectBle4(addrs, type);
          //  connectDevice(filteredDevice.get(0), 2);




        } else {
            showToast("请打开蓝牙");
        }

    }

    public void connectBle4(String addrs,String type){
        BluetoothClient mClient = activity.getClient();
        showProgress("连接中...");
        BleConnectOptions options = new BleConnectOptions.Builder()

                .setConnectRetry(3)   // 连接如果失败重试3次

                .setConnectTimeout(3000)   // 连接超时3s

                .setServiceDiscoverRetry(3)  // 发现服务如果失败重试3次

                .setServiceDiscoverTimeout(20000)  // 发现服务超时20s

                .build();
        mClient.connect(addrs,options, new BleConnectResponse() {
            @Override
            public void onResponse(int code, BleGattProfile profile) {

                if (code == REQUEST_SUCCESS) {
                    Log.d(TAG, "onResponse: code:"+code);
                    progressDialog.dismiss();
                    showToast("蓝牙连接成功");
                    if(type.equals("2")){
                        // connectDevice(deviceList.get(0),2);
                        UUID uuid = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b");
                        UUID uui2d = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b");
                        UUID read = UUID.fromString("38eb4a81-c570-11e3-9507-0002a5d5c51b");
//                        String data ="\n" +
//                                "^XA\n" +
//                                "^CW0,E:HANS.TTF ^CI28\n" +
//                                "^PW591\n" +
//                                "^LL827\n" +
//                                "^LS0\n" +
//                                "^FT491,10^A0R,25^FH\\^CI28^FD旧物料号:123456789^FS^CI28\n" +
//                                "^FT400,10^A0R,25^FH\\^CI28^FD批次号:12345679^FS^CI28\n" +
//                                "^FT318,10^A0R,25^FH\\^CI28^FD仓位:A1-01-01^FS^CI28\n" +
//                                "^FT216,10^A0R,25^FH\\^CI28^FD成本中心代码:12345678^FS^CI28\n" +
//                                "^FT133,10^A0R,25^FH\\^CI28^FD成本中心描述:测试物料1^FS^CI28\n" +
//                                "^FT56,10^A0R,25^FH\\^CI28^FD数量:1^FS^CI28\n" +
//                                "^FT56,264^A0R,25^FH\\^CI28^FD供应商:12345678^FS^CI28\n" +
//                                "^FO183,349^BQN,2,8,N,Y,Y^FDMA,BCPN2-B/20190902/024%M000000101104000151%Q24%C20016003_23%C20016041_1^FS\n" +
//                                "^PQ1,0,1,Y\n" +
//                                "^FO472,275^GFA,1029,3376,8,:Z64:eJzF17FO3EAQBuBxHMlQmS6JFLGvkC5QmUfJI1wXIp1uF1HcY/Aqjigo8wgYpaCMEUWMZN1kZ+Zf4zMcAQTJCfSh8956d3ZmfBDJK+8UZtGz6FhenedXMYgrvVnQW3tu4QpyDXVtshwZmUcbWyv3Y2WAWEwHVLhQ4kLGmNKPLtT4RLqXmsM40nQqv1x8Wl0Er8WhgX2KQ4AN7NPi6+GwbPuM7etIfuhCj7jI3OlClj5RwSLFYxqXYiLxWnzkgkncLdSq87rqorNzzjqc7954H5zsYZwG8WqeHedr2JbQ9je2T+aQ3D8zhxKkDVKSNlpC2qP7XoXlVEzoALs1sw3SVL/B6o5zrM9GRDX3RFlDqeeTXEHuCpzbGTyd+LPTLOWLztI2quV7hbxtUdhm3Ab22R3aurYgJfc2+H7i1tQ5HCIx1O1Qr/qH7Q/53KW8blNe10/N5xza/mVGB+mOV7AtoOXL/9ZB2nkorzU381vdqGs32GWMsOXDilJcClhOdBOrvxj7cWFqhnVeDjH2N+tjHmYwvvNU7TFHVZKTAVo+OTwHHPq/rL8my3qtJNv+kA6IQ5thH0lKVtBN8iibOGRskfLJQZli/bkYC80KKtavLXvL2yNVZkkOz60+1UOb6qGBfaqLQZxXwBshHfzMdhoWtsDgQ1aXpuxRZPkRlyrz8nv0KHaXm+gx86V4Bn9Bvt9qooMl36hFrLaleqGW0MEK130c/ygv4Sk8hpm5IHNO38ydffXw4wd1tmt+cdvqgTtSydX+SG1c2Be7PGyLvZxjSHXkNtbFM20KScrg6wxS0ke7aCVWdewP0aKmUsxqyls8j9ScaKbW9ElttR4pl0IqQ0wLSUhINPKttvOc49S7auxDYWwvrSnK5bnpH+8BnMFDuIAx3wqeu9afRukVvI5mJ22Mt93viVa3LsUSxnI7Y38i+zzjA43PJdOav1/cGZxDu/9XxDv5eaQ0vnew2GDOTqWqOJc+RyX9kD5Hb/T7k9P+FX9p+H+I6M73qj8PogXh:0B11"+
//                     //           "^FO472,275^GFA,1665,3376,8,:Z64:eJydVs1q21gUPpIjMA7YLlSr2ZjMJjhQb0sFsQKTvQK+L9FF/QbOnWRjOtC8gmg3Qh1olyKCtExfxEsTBmsrhkGe83ftxNTTmdwEbr58Ovf8n3sBaL3NeDs3CYA/LMuyAM/QSkYVr6WRlUQlr8LhWcOr3sdPvyufDwiQysvGsmpjYt6j8lYx04D6l7QH+o8WSqf6QUI7Kac93PcBnrCyegLp8Nk9kZjQ3l0360cSJ/qBpxg+iQ14BMUnfxifF0/yv8wEkv/r5t6SblD/U/VfVE/FeFDjkZcAufjN1H9yf6Lu8xFGvaEE0hHkPh9Rqv+hHnGIFqwXDyQi/cBz8ai2OUg0xI/joynAIwW3hMcjk9ds/WgpTJCof4Wa3yf/8T9to/ZzfG7FGDpe45c9uf7uNf5JIHwq/uHRFIDkcgGnf9+ta8ITEL6Aw0JwmEwXMPoTekuHd/kZyUO33sfvkw8Vd1i+xykkTCEg3uE2FtzvKC/xDJMj3g8Lh9vzOUa4I/HHeB7hGsB2IT+fX80V/QLEH/m59LsHcDzP5pnNs1j4eJi/xwOUh35/mOfZfO4w8bjSoWIPGGfDLOZ1IHw+zJKtPK5OJrXneMJSEa/ze/x5K/ndyF+5/ug7HMoHsc/4fSb9d4YndPCANNPyZDeGeZppePq8d7JjeLx+hNs/won+MZPMtB/Mr5R2LWeuX+lv8VjqM9X6Ntz/+POf54evvJs/AHmjI8D1r2twkwiuFh3uXypB5lOpH4ebhdTfPr5aSP39i3z3/8gfpg/qV/ZevcUD4Pr22PYE2PbOkrIbS4C3841GCLf/X9bT+LQ0PoHinsbP4Z+UDxWPNf4OR2WuvMy/0Qq1U/wpmzj/I56+J4VkF/qzWvMvmJqXl05CmGrn7vKh4nGjeDP/rWKpp55ePaG6f6juB1pgXXFfxjsqVf9TFw9Q/zcDNdL6CRV3xf9NPfnKywcJey/zP3A96+7Hzbz3m1qs9Wi+0Tp299mUq3/bD6Q+d+mLQes/U/Mmat7tpj80f1btBbXP9sVgOMXHC0bAYrpiNBAiCwUaSJh/R9ZW72yF2JjgDPlfy/JDUdoQxc8vDIyv1k1Vr605R5wg/1F4Opx4fv8sKxsqJvtYXnG3qZtV3djAXJgA5VtlWl4jH5gzE1yQ/YvqhuUvGEdlwbzDY5S/If0q73g6nPjRtyXLG0/lQfjnIPgU6uYd6k/6z1j+Zfsl8/Fzj/lBt83yEArG7oss8hDGATxD3K19215bKUiD4b3N849l5vrFvf+2/fHE91+MDe1RfjBCgP7FwPjyK80PnH8xNT/fvzgfsH9jfCzQDPgK/gKkCRj7NCFo/mHzMSbqbgkeNyi+PahBfSsYV2sluzc5QAniWxZeEUYVIdVudwlj+j7A+zYm/vak/MzYoEmtVW81vVluMfLRAx7lm9ldHSseKJ8ofqPyhvAE7/9r5CH6bM5YX3L5B8rDuHZ4l59+Q3l6P+zhZyx/upUvRT/2EuvH96PoV7zLjxuy/wvNP8Scv2viA8bo36fqZvWG/Dfn/D5plR9I/wb7zYr07+Vb+Pyk+b9Xfr0i/x/xA+UnHP9K43+u8f95J/4vKP47+Sm+kz+YhMzDq9ZvxMMBlhheIFhSdP88p/qh9z0VjU5pcNf85n3wD46oNXg=:E1D0"+
//                                "^XZ";


                        String data =  "" +
                                "^XA\n" +
                                "^CW0,E:HANS.TTF ^CI\n" +
                                "^FO25,3^GB523,762,5^FS\n" +
                                "^FO402,3^GB0,360,3^FS\n" +
                                "^FO162,3^GB0,756,2^FS\n" +
                                "^FO209,4^GB0,758,2^FS\n" +
                                "^FO257,4^GB0,761,2^FS\n" +
                                "^FO301,5^GB0,760,2^FS\n" +
                                "^FO348,4^GB0,761,2^FS\n" +
                                "^FO118,4^GB0,758,2^FS\n" +
                                "^FO80,3^GB0,759,2^FS\n" +
                                "^FO80,141^GB323,0,3^FS\n" +
                                "^FO211,361^GB90,0,3^FS\n" +
                                "^FO211,502^GB92,0,3^FS\n" +
                                "^FT377,725^BQN,2,5\n" +
                                "^FH^FDLA,B6222%M1019160712311%Q150%C20230606001_100%C20230606002_50^FS\n" +
                                "^FO407,239^GB139,0,3^FS\n" +
                                "^FO348,361^GB198,0,3^FS\n" +
                                "^FT465,257^A0R,25^FH^CI28^FD分拣单^FS^CI28\n" +
                                "^FT315,18^A0R,22^FH^CI28^FD物料描述^FS^CI28\n" +
                                "^FT272,18^A0R,22^FH^CI28^FD仓位^FS^CI28\n" +
                                "^FT232,18^A0R,22^FH^CI28^FD批次号^FS^CI28\n" +
                                "^FT184,8^A0R,22^FH^CI28^FD成本中心描述^FS^CI28\n" +
                                "^FT137,18^A0R,22^FH^CI28^FD供应商^FS^CI28\n" +
                                "^FT88,18^A0R,22^FH^CI28^FD备注^FS^CI28\n" +
                                "^FT368,18^A0R,22^FH^CI28^FD物料代码^FS^CI28\n" +
                                "^FT365,152^A0R,27^FH^CI28^FD10080300104^FS^CI28\n" +
                                "^FT315,152^A0R,22^FH^CI28^FD2^FS^CI28\n" +
                                "^FT272,152^A0R,27^FH^CI28^FDA0-01-01^FS^CI28\n" +
                                "^FT224,152^A0R,22^FH^CI28^FD4^FS^CI28\n" +
                                "^FT177,152^A0R,22^FH^CI28^FD5^FS^CI28\n" +
                                "^FT129,152^A0R,22^FH^CI28^FD6^FS^CI28\n" +
                                "^FT90,152^A0R,22^FH^CI28^FD7^FS^CI28\n" +
                                "^FT269,371^A0R,22^FH^CI28^FD数量^FS^CI28\n" +
                                "^FT224,371^A0R,22^FH^CI28^FD生产订单号^FS^CI28\n" +
                                "^FT224,518^A0R,22^FH^CI28^FD10^FS^CI28\n" +
                                "^FT517,407^A0R,22^FH^CI28^FD分拣单号^FS^CI28\n" +
                                "^FT479,416^A0R,22^FH^CI28^FDLXJN2012^FS^CI28\n" +
                                "^FT506,51^A0R,22^FH^CI28^FD配送计划号^FS^CI28\n" +
                                "^FT434,51^A0R,22^FH^CI28^FD12345678^FS^CI28\n" +
                                "^FT272,518^A0R,22^FH^CI28^FD9^FS^CI28\n" +
                                "^FT40,490^A0R,18^FH^CI28^FD打印时间：2023/06/13 12:13:13^FS^CI28\n" +
                                "^FT40,358^A0R,18^FH^CI28^FD操作人：张杨杨^FS^CI28\n" +
                                "^PQ1,0,1,Y\n" +
                                "^XZ";
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                       List<Byte> byteList = new ArrayList<Byte>(Arrays.asList(PrintUtil.toObjects(bytes)));
                        List<List<Byte>> lists = PrintUtil.spliceArrays(byteList, 60);
                        for (int i = 0; i < lists.size(); i++) {
                            List<Byte> byteList1 = lists.get(i);
                            Byte[] spilce = byteList1.toArray(new Byte[byteList1.size()]);
                            mClient.write(addrs,uuid,uui2d ,PrintUtil.toPrimitives(spilce), new BleWriteResponse() {
                                @Override
                                public void onResponse(int code) {
                                    if (code == REQUEST_SUCCESS) {

                                    }
                                }
                            });

                        }

                        //  String data = "~hi^XA^FO20,20^BY3^B3N,N,150,Y,N^FDHello WeChat!^FS^XZ\\r\\nZ";
                        Log.d(TAG, "onResponse: "+uuid);
                        UUID cuuid = profile.getServices().get(3).getCharacters().get(0).getUuid();
//                        mClient.write(addrs,uuid,uui2d , data.getBytes(StandardCharsets.UTF_8), new BleWriteResponse() {
//                            @Override
//                            public void onResponse(int code) {
//                                if (code == REQUEST_SUCCESS) {
//
//                                }
//                            }
//                        });
                     //   mClient.disconnect(addrs);


                    }
                }else {
                    progressDialog.dismiss();
                    showToast("蓝牙连接失败");

                }
            }
        });
    }


    public void connectBle4New(String addrs,String type,String data){
        String printData = data;
        BluetoothClient mClient = activity.getClient();
        showProgress("连接中...");
        BleConnectOptions options = new BleConnectOptions.Builder()

                .setConnectRetry(3)   // 连接如果失败重试3次

                .setConnectTimeout(4000)   // 连接超时4s

                .setServiceDiscoverRetry(3)  // 发现服务如果失败重试3次

                .setServiceDiscoverTimeout(20000)  // 发现服务超时20s

                .build();
        mClient.connect(addrs,options, new BleConnectResponse() {
            @Override
            public void onResponse(int code, BleGattProfile profile) {

                if (code == REQUEST_SUCCESS) {
                    showToast("蓝牙连接成功");
                    progressDialog.dismiss();
                    if(type.equals("2")){
                        // connectDevice(deviceList.get(0),2);
                        UUID uuid = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b");
                        // read
                        UUID readUUID = UUID.fromString("38eb4a81-c570-11e3-9507-0002a5d5c51b");

                        UUID uui2d = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b");
                        String data = printData;

                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                        List<Byte> byteList = new ArrayList<Byte>(Arrays.asList(PrintUtil.toObjects(bytes)));
                        List<List<Byte>> lists = PrintUtil.spliceArrays(byteList, 60);
                        for (int i = 0; i < lists.size(); i++) {
                            List<Byte> byteList1 = lists.get(i);
                            Byte[] spilce = byteList1.toArray(new Byte[byteList1.size()]);
                            mClient.write(addrs,uuid,uui2d ,PrintUtil.toPrimitives(spilce), new BleWriteResponse() {
                                @Override
                                public void onResponse(int code) {
                                    if (code == REQUEST_SUCCESS) {

                                    }
                                }
                            });

                        }

                        //  String data = "~hi^XA^FO20,20^BY3^B3N,N,150,Y,N^FDHello WeChat!^FS^XZ\\r\\nZ";
                        Log.d(TAG, "onResponse: "+uuid);
                        UUID cuuid = profile.getServices().get(3).getCharacters().get(0).getUuid();
//                        mClient.write(addrs,uuid,uui2d , data.getBytes(StandardCharsets.UTF_8), new BleWriteResponse() {
//                            @Override
//                            public void onResponse(int code) {
//                                if (code == REQUEST_SUCCESS) {
//
//                                }
//                            }
//                        });
//                        mClient.read(addrs, uuid, readUUID, new BleReadResponse() {
//                            @Override
//                            public void onResponse(int code, byte[] data) {
//                                String s = new String(data);
//                                Log.d(TAG, "read onResponse: "+s);
//                            }
//                        });

                    }
                }else {
                    progressDialog.dismiss();
                    showToast("蓝牙连接失败");

                }
            }
        });
    }
    public void connectDevice(BluetoothDevice device, int taskType) {
        if (checkBluetoothState() && device != null) {
            mConnectTask = new ConnectBluetoothTask(taskType).execute(device);
        }
    }

    class ConnectBluetoothTask extends AsyncTask<BluetoothDevice, Integer, BluetoothSocket> {

        int mTaskType;

        public ConnectBluetoothTask(int taskType) {
            this.mTaskType = taskType;
        }

        @Override
        protected void onPreExecute() {
            showProgress("请稍候...");
            super.onPreExecute();
        }

        @Override
        protected BluetoothSocket doInBackground(BluetoothDevice... params) {
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            Log.d(TAG, "doInBackground: "+params[0].toString());
            showToast(params[0].toString());
            mSocket = BluetoothUtil.connectDevice(params[0]);
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            showToast("mSocket"+String.valueOf(mSocket==null));
           onConnected(mSocket, mTaskType);
            return mSocket;
        }

        public void onConnected(BluetoothSocket socket, int taskType) {
            switch (taskType) {
                case TASK_TYPE_PRINT:
                    Bitmap bitmap = BitmapFactory.decodeResource(activity.getResources(), R.drawable.logo_00b09c96);
                    PrintUtil.printTest(socket, bitmap);
                    break;
            }
        }

        @Override
        protected void onPostExecute(BluetoothSocket socket) {
            progressDialog.dismiss();
            if (socket == null || !socket.isConnected()) {
                showToast("连接打印机失败");
            } else {
                showToast("成功！");
            }

            super.onPostExecute(socket);
        }
    }

    /**
     * 条码枪回传
     * @param res
     * @throws InterruptedException
     */
    @JavascriptInterface
    public  void onScaned(String res) throws InterruptedException {
        Log.d(TAG, "onScaned: "+ res);
        String callBack = "onScaned";
        Thread.sleep(3000);
        activity.runOnUiThread(()->{
            activity.getWebView().loadUrl("javascript:" + callBack + "(" + res + ");");
        });

    }

    @JavascriptInterface
    public void mScan(){
        Scanner scanner = activity.getScanner();
        if (scanner != null) {
            if (scanner.isReadPending()) {
                try {
                    scanner.cancelRead();
                } catch (ScannerException e) {
                    Log.d(TAG, "cancelRead: "+e.getMessage());
                }
            }
        }
    }

    /**
     * js 打印
     * @param addr
     * @param zpl
     */
    @JavascriptInterface
    public  void printZpl(String addr,String zpl){
         connectBle4New(addr,"2",zpl);
    }


}