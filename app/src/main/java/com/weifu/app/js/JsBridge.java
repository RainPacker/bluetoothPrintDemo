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

        BleConnectOptions options = new BleConnectOptions.Builder()

                .setConnectRetry(3)   // 连接如果失败重试3次

                .setConnectTimeout(30000)   // 连接超时30s

                .setServiceDiscoverRetry(3)  // 发现服务如果失败重试3次

                .setServiceDiscoverTimeout(20000)  // 发现服务超时20s

                .build();
        mClient.connect(addrs,options, new BleConnectResponse() {
            @Override
            public void onResponse(int code, BleGattProfile profile) {

                if (code == REQUEST_SUCCESS) {
                    showToast("蓝牙连接成功");
                    if(type.equals("2")){
                        // connectDevice(deviceList.get(0),2);
                        UUID uuid = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b");
                        UUID uui2d = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b");
                      //  String data = "~hi^XA^FO20,20^BY3^B3N,N,150,Y,N^FDHello Yangyang!^FS^XZ\\r\\nZ";
                     //   String data = "<hr>11111<hr>";
                        String data = "\n" +
                                "^XA\n" +
                                "^CW1,E:MSUNG.FNT \n" +
                                "^CI28  \n" +
                                "^PW837\n" +
                                "^LL1427\n" +
                                "^LS0\n" +
                                "^FO12,835^GB884,588,5^FS\n" +
                                "^FT39,919^A1N,23,23^FH\\^CI28^FD描述1：^FS^CI27\n" +
                                "^FT36,999^A1N,23,23^FH\\^CI28^FD旧物料号2：^FS^CI27\n" +
                                "^FT39,1087^A1N,23,23^FH\\^CI28^FD批次号3：^FS^CI27\n" +
                                "^FT39,1154^A1N,23,23^FH\\^CI28^FD仓位4:^FS^CI27\n" +
                                "^FT36,1226^A1N,23,23^FH\\^CI28^FD成本中心代码5:^FS^CI27\n" +
                                "^FT39,1322^A1N,23,23^FH\\^CI28^FD成本中心描述6:^FS^CI27\n" +
                                "^FT39,1396^A1N,23,23^FH\\^CI28^FD数量7:^FS^CI27\n" +
                                "^FT120,1396^A1N,23,23^FH\\^CI28^FD{qty}^FS^CI27\n" +
                                "^FT235,1396^A1N,23,23^FH\\^CI28^FD供应商8：^FS^CI27\n" +
                                "^FT313,1396^A0N,23,23^FH\\^CI28^FD${sup}^FS^CI27\n" +
                                "^FO420,933\n" +
                                "^BQN,2,8,N,Y,Y^FDMM,A37-40f-4644-8-a^FS\n" +
                                "^PQ1,0,1,Y\n" +
                                "^XZ\n";
//                        String data ="~hi^XA"+
//                                "^FX Top section with logo, name and address." +
//                                "^CF0,60" +
//                                "^FO50,50^GB100,100,100^FS" +
//                                "^FO75,75^FR^GB100,100,100^FS" +
//                                "^FO93,93^GB40,40,40^FS" +
//                                "^FO220,50^FDIntershipping, Inc.^FS" +
//                                "^CF0,30" +
//                                "^FO220,115^FD1000 Shipping Lane^FS" +
//                                "^FO220,155^FDShelbyville TN 38102^FS" +
//                                "^FO220,195^FDUnited States (USA)^FS" +
//                                "^FO50,250^GB700,3,3^FS" +
//                                "" +
//                                "^FX Second section with recipient address and permit information." +
//                                "^CFA,30" +
//                                "^FO50,300^FDJohn Doe^FS" +
//                                "^FO50,340^FD100 Main Street^FS" +
//                                "^FO50,380^FDSpringfield TN 39021^FS" +
//                                "^FO50,420^FDUnited States (USA)^FS" +
//                                "^CFA,15" +
//                                "^FO600,300^GB150,150,3^FS" +
//                                "^FO638,340^FDPermit^FS" +
//                                "^FO638,390^FD123456^FS" +
//                                "^FO50,500^GB700,3,3^FS" +
//                                "" +
//                                "^FX Third section with bar code." +
//                                "^BY5,2,270" +
//                                "^FO100,550^BC^FD12345678^FS" +
//                                "" +
//                                "^FX Fourth section (the two boxes on the bottom)." +
//                                "^FO50,900^GB700,250,3^FS" +
//                                "^FO400,900^GB3,250,3^FS" +
//                                "^CF0,40" +
//                                "^FO100,960^FDCtr. X34B-1^FS" +
//                                "^FO100,1010^FDREF1 F00B47^FS" +
//                                "^FO100,1060^FDREF2 BL4H8^FS" +
//                                "^CF0,190" +
//                                "^FO470,955^FDCA^FS"+
//                                "^XZ\\r\\nZ";
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                       List<Byte> byteList = new ArrayList<Byte>(Arrays.asList(PrintUtil.toObjects(bytes)));
                        List<List<Byte>> lists = PrintUtil.spliceArrays(byteList, 24);
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
                        mClient.write(addrs,uuid,uui2d , data.getBytes(StandardCharsets.UTF_8), new BleWriteResponse() {
                            @Override
                            public void onResponse(int code) {
                                if (code == REQUEST_SUCCESS) {

                                }
                            }
                        });

                    }
                }else {
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


}