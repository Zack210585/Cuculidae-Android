package com.example.cuculidae;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothClassicService extends Service {
    private BluetoothAdapter bluetoothAdapter;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private boolean isConnected = false;
    private boolean isConnecting = false;
    private final IBinder binder = new LocalBinder();

    private static final String TARGET_DEVICE_NAME = "Cuculidae";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public class LocalBinder extends Binder {
        public BluetoothClassicService getService() { return BluetoothClassicService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public boolean initialize() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return bluetoothAdapter == null || !bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() { return isConnected; }


    public synchronized void disconnectAndClear() {
        android.util.Log.d("Cuculidae_BLE", "Cleaning up active socket connection threads...");
        isConnected = false;
        isConnecting = false;

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    public synchronized boolean startAutoConnectLoop() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return false;
        if (isConnected || connectThread != null || isConnecting) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (TARGET_DEVICE_NAME.equals(device.getName())) {
                android.util.Log.d("Cuculidae_BLE", "Found paired target device. Starting thread connection sequence...");
                isConnecting = true;
                connectToDevice(device);
                return true;
            }
        }
        return false;
    }

    private synchronized void connectToDevice(BluetoothDevice device) {
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    public void sendDataToESP32(String dataString) {
        ConnectedThread r;
        synchronized (this) {
            if (!isConnected) return;
            r = connectedThread;
        }
        r.write(dataString.getBytes());
    }
    public interface OnDataReceivedListener {
        void onDataReceived(String data);
    }
    private OnDataReceivedListener dataListener;
    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataListener = listener;
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ActivityCompat.checkSelfPermission(BluetoothClassicService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
                }
            } catch (IOException e) {
                android.util.Log.e("Cuculidae_BLE", "Socket creation failed", e);
            }
            socket = tmp;
        }


        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        public void run() {
            if (ActivityCompat.checkSelfPermission(BluetoothClassicService.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            if (bluetoothAdapter.isDiscovering()) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ActivityCompat.checkSelfPermission(BluetoothClassicService.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.cancelDiscovery();
                }
            }
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ActivityCompat.checkSelfPermission(BluetoothClassicService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Cuculidae_BLE", "Attempting socket connection to hardware...");
                    socket.connect();
                } else {
                    connectionFailed();
                    return;
                }
            } catch (IOException connectException) {
                Log.e("Cuculidae_BLE", "Socket connect hardware exception thrown: ", connectException);
                try { socket.close(); } catch (IOException closeException) { closeException.printStackTrace(); }
                connectionFailed();
                return;
            }
            synchronized (BluetoothClassicService.this) { connectThread = null; }
            connected(socket);
        }

        public void cancel() { try { socket.close(); } catch (IOException e) { e.printStackTrace(); } }
    }

    private synchronized void connected(BluetoothSocket socket) {
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        isConnecting = false;
        isConnected = true;
        android.util.Log.d("Cuculidae_BLE", "Connection successful! Flags updated.");
    }

    private void connectionFailed() {
        isConnected = false;
        isConnecting = false;
        android.util.Log.w("Cuculidae_BLE", "Connection milestone failed. Waiting to loop/retry...");
        try { Thread.sleep(5000); } catch (InterruptedException e) { e.printStackTrace(); }
        startAutoConnectLoop();
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                android.util.Log.e("Cuculidae_BLE", "Stream creation exception", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            android.util.Log.d("Cuculidae_BLE", "Connected worker thread active. Listening for incoming bytes...");
            while (true) {
                try {
                    bytes = mmInStream.read(buffer);
                    String incomingString = new String(buffer, 0, bytes).trim();

                    // 🚀 FIX: Broadcast the incoming text string locally across the app environment
                    Intent intent = new Intent("CUCULIDAE_BLUETOOTH_DATA");
                    intent.putExtra("raw_text", incomingString);
                    sendBroadcast(intent);

                } catch (IOException e) {
                    break;
                }
            }

        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
                android.util.Log.d("Cuculidae_BLE", "Bytes written out to Bluetooth socket successfully.");
            } catch (IOException e) {
                android.util.Log.e("Cuculidae_BLE", "Write stream exception", e);
            }
        }

        public void cancel() { try { mmSocket.close(); } catch (IOException e) { e.printStackTrace(); } }
    }

    private void connectionLost() {
        isConnected = false;
        isConnecting = false;
        android.util.Log.w("Cuculidae_BLE", "Active session closed by remote hardware host.");
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }
        startAutoConnectLoop();
    }
}
