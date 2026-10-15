package dev.g992.rcviewer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * BLE HID-over-GATT (HOGP) gamepad peripheral driven by the live RC state.
 *
 * <p>No root: public {@link BluetoothGattServer} / {@link BluetoothLeAdvertiser} APIs only
 * (classic BluetoothHidDevice is disabled on this DJI build). HOGP details that hosts
 * (Windows in particular) are strict about:
 * <ul>
 *   <li>HID service characteristics require an ENCRYPTED link, so the host bonds; Android's
 *       own pairing dialog (com.android.settings BluetoothPairingDialog) asks the user on the
 *       RC screen to confirm.</li>
 *   <li>Battery Service + Device Information Service are present (HOGP-mandated).</li>
 *   <li>Services are added one at a time (next one in onServiceAdded): adding them
 *       back-to-back can silently drop services on Android 10.</li>
 *   <li>Input reports are only notified to a host that enabled the Report CCCD.</li>
 * </ul>
 * Not settable through public API on Android 10: the GAP Appearance value and the
 * advertising Appearance field (both owned by the Bluetooth stack). The adapter name is
 * NOT changed by this class.
 */
final class BleGamepad {
    private static final String TAG = "BleGamepad";

    private static UUID uuid16(int id) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", id));
    }
    private static final UUID HID_SERVICE = uuid16(0x1812);
    private static final UUID HID_INFORMATION = uuid16(0x2A4A);
    private static final UUID REPORT_MAP = uuid16(0x2A4B);
    private static final UUID HID_CONTROL_POINT = uuid16(0x2A4C);
    private static final UUID REPORT = uuid16(0x2A4D);
    private static final UUID PROTOCOL_MODE = uuid16(0x2A4E);
    private static final UUID REPORT_REFERENCE = uuid16(0x2908);
    private static final UUID CCCD = uuid16(0x2902);
    private static final UUID DEVICE_INFO_SERVICE = uuid16(0x180A);
    private static final UUID MANUFACTURER_NAME = uuid16(0x2A29);
    private static final UUID PNP_ID = uuid16(0x2A50);
    private static final UUID BATTERY_SERVICE = uuid16(0x180F);
    private static final UUID BATTERY_LEVEL = uuid16(0x2A19);

    /**
     * Report map: Game Pad with Report ID 1 (what the Report Reference descriptor points
     * at). Left stick X,Y + right stick Z,Rx, 16-bit -660..660, HID sign convention (up =
     * negative). Buttons 1-4 = RTH, CamHalf, CamFull, Rec; buttons 5-7 = flight-mode
     * switch C/N/S (exactly one held); 1 padding bit. 9-byte input report.
     */
    private static final byte[] REPORT_MAP_VALUE = {
            0x05, 0x01,                     // Usage Page (Generic Desktop)
            0x09, 0x05,                     // Usage (Game Pad)
            (byte) 0xA1, 0x01,              // Collection (Application)
            (byte) 0x85, 0x01,              //   Report ID (1)
            0x05, 0x01,                     //   Usage Page (Generic Desktop)
            0x09, 0x30, 0x09, 0x31,         //   Usage (X), Usage (Y)      = left stick
            0x09, 0x32, 0x09, 0x33,         //   Usage (Z), Usage (Rx)     = right stick
            0x16, 0x6C, (byte) 0xFD,        //   Logical Minimum (-660)
            0x26, (byte) 0x94, 0x02,        //   Logical Maximum (660)
            0x75, 0x10,                     //   Report Size (16)
            (byte) 0x95, 0x04,              //   Report Count (4)
            (byte) 0x81, 0x02,              //   Input (Data,Var,Abs)
            0x05, 0x09,                     //   Usage Page (Button)
            0x19, 0x01, 0x29, 0x07,         //   Usage Minimum (1) .. Maximum (7)
            0x15, 0x00, 0x25, 0x01,         //   Logical Minimum (0) .. Maximum (1)
            0x75, 0x01,                     //   Report Size (1)
            (byte) 0x95, 0x07,              //   Report Count (7)
            (byte) 0x81, 0x02,              //   Input (Data,Var,Abs)
            0x75, 0x01,                     //   Report Size (1)
            (byte) 0x95, 0x01,              //   Report Count (1)  (padding)
            (byte) 0x81, 0x03,              //   Input (Const,Var,Abs)
            (byte) 0xC0                     // End Collection
    };
    private static final int REPORT_LEN = 9;

    private static final int RD_ENC = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
    private static final int WR_ENC = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;

    interface StatusSink { void onBleStatus(String msg, boolean active); }

    private final Context ctx;
    private final StatusSink sink;

    private volatile boolean on;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothGattCharacteristic reportChar;
    private BluetoothGattCharacteristic batteryChar;
    private final ArrayDeque<BluetoothGattService> pendingServices = new ArrayDeque<>();
    /** Hosts that enabled notifications on the input Report characteristic. */
    private final Set<BluetoothDevice> subscribed = Collections.synchronizedSet(new HashSet<>());
    /** Hosts currently connected to the GATT server (status text reflects all of them). */
    private final Set<BluetoothDevice> connected = Collections.synchronizedSet(new HashSet<>());
    private final byte[] report = new byte[REPORT_LEN];
    private boolean bondReceiverRegistered;

    BleGamepad(Context ctx, StatusSink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }

    boolean isOn() { return on; }

    void update(Duml.RcState st) {
        if (!on || st == null) return;
        int[] a = st.axes;
        byte[] snap = new byte[REPORT_LEN];
        synchronized (report) {
            // HID convention: Y grows DOWNWARD, so stick-up (+660 in the DJI decode) is
            // negated. Right stick is Z (horizontal) + Rx (vertical): hosts that index axes
            // by usage (Chrome's Gamepad API on Windows) then read axes 0..3 as
            // LX, LY, RX, RY; hosts that list axes in order (DirectInput) are unaffected.
            putAxis(report, 0, a[Duml.AX_LH]);
            putAxis(report, 2, -a[Duml.AX_LV]);
            putAxis(report, 4, a[Duml.AX_RH]);
            putAxis(report, 6, -a[Duml.AX_RV]);
            int b = 0;
            for (int i = 0; i < Duml.ALL_BUTTONS.length && i < 4; i++) if (st.buttons[i]) b |= (1 << i);
            // Flight-mode switch C/N/S as buttons 5/6/7, exactly one held (ch1 & 3:
            // 0 Sport, 1 Normal, 2 Cinema; 3 = unknown -> none).
            switch (st.ch1 & 0x03) {
                case 2: b |= 1 << 4; break; // C (Cinema)
                case 1: b |= 1 << 5; break; // N (Normal)
                case 0: b |= 1 << 6; break; // S (Sport)
                default: break;
            }
            report[8] = (byte) b;
            System.arraycopy(report, 0, snap, 0, REPORT_LEN);
        }
        BluetoothGattCharacteristic rc = reportChar;
        BluetoothGattServer srv = server;
        if (rc == null || srv == null) return;
        BluetoothDevice[] targets;
        synchronized (subscribed) { targets = subscribed.toArray(new BluetoothDevice[0]); }
        if (targets.length == 0) return;
        try {
            rc.setValue(snap);
            for (BluetoothDevice d : targets) srv.notifyCharacteristicChanged(d, rc, false);
        } catch (Exception e) {
            Log.w(TAG, "notify failed: " + e);
        }
    }

    private static void putAxis(byte[] r, int off, int v) {
        if (v > 660) v = 660; else if (v < -660) v = -660;
        r[off] = (byte) (v & 0xFF);
        r[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    void start() {
        if (on) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            sink.onBleStatus("BLE: Bluetooth выключен", false);
            return;
        }
        advertiser = ad.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            sink.onBleStatus("BLE: реклама не поддерживается", false);
            return;
        }
        on = true;
        registerBondReceiver();
        try {
            server = bm.openGattServer(ctx, gattCallback);
            if (server == null) { fail("BLE: GATT-сервер не открылся"); return; }
            pendingServices.clear();
            pendingServices.add(buildHidService());
            pendingServices.add(buildBatteryService());
            pendingServices.add(buildDeviceInfoService());
            sink.onBleStatus("BLE: запуск...", true);
            addNextService();
        } catch (SecurityException se) {
            fail("BLE: нет разрешения Bluetooth");
        } catch (Exception e) {
            fail("BLE: ошибка запуска: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        Log.w(TAG, msg);
        stopInternal();
        sink.onBleStatus(msg, false);
    }

    /** Adds the next queued service; advertising starts only after the last one is added. */
    private void addNextService() {
        BluetoothGattService s = pendingServices.poll();
        if (s == null) { startAdvertising(); return; }
        if (!server.addService(s)) fail("BLE: addService отклонён (" + s.getUuid() + ")");
    }

    void stop() {
        if (!on && server == null) return;
        stopInternal();
        sink.onBleStatus("BLE: выключен", false);
    }

    private void stopInternal() {
        on = false;
        try { if (advertiser != null) advertiser.stopAdvertising(advCallback); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        advertiser = null;
        reportChar = null;
        batteryChar = null;
        subscribed.clear();
        connected.clear();
        pendingServices.clear();
        if (bondReceiverRegistered) {
            try { ctx.unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
            bondReceiverRegistered = false;
        }
    }

    // ------------------------------------------------------------ GATT database

    private BluetoothGattService buildHidService() {
        BluetoothGattService s = new BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic info = new BluetoothGattCharacteristic(HID_INFORMATION,
                BluetoothGattCharacteristic.PROPERTY_READ, RD_ENC);
        // bcdHID 1.11, country 0, flags: RemoteWake(0x01) | NormallyConnectable(0x02)
        info.setValue(new byte[]{0x11, 0x01, 0x00, 0x03});
        s.addCharacteristic(info);

        BluetoothGattCharacteristic map = new BluetoothGattCharacteristic(REPORT_MAP,
                BluetoothGattCharacteristic.PROPERTY_READ, RD_ENC);
        map.setValue(REPORT_MAP_VALUE);
        s.addCharacteristic(map);

        BluetoothGattCharacteristic proto = new BluetoothGattCharacteristic(PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                RD_ENC | WR_ENC);
        proto.setValue(new byte[]{0x01}); // Report Protocol Mode
        s.addCharacteristic(proto);

        BluetoothGattCharacteristic ctl = new BluetoothGattCharacteristic(HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, WR_ENC);
        ctl.setValue(new byte[]{0x00});
        s.addCharacteristic(ctl);

        BluetoothGattCharacteristic rep = new BluetoothGattCharacteristic(REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                RD_ENC);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED);
        cccd.setValue(new byte[]{0x00, 0x00});
        rep.addDescriptor(cccd);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED);
        ref.setValue(new byte[]{0x01, 0x01}); // Report ID 1, Input report
        rep.addDescriptor(ref);
        rep.setValue(new byte[REPORT_LEN]);
        s.addCharacteristic(rep);
        reportChar = rep;
        return s;
    }

    private BluetoothGattService buildBatteryService() {
        BluetoothGattService s = new BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic lvl = new BluetoothGattCharacteristic(BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(new byte[]{0x00, 0x00});
        lvl.addDescriptor(cccd);
        lvl.setValue(new byte[]{100});
        s.addCharacteristic(lvl);
        batteryChar = lvl;
        return s;
    }

    private BluetoothGattService buildDeviceInfoService() {
        BluetoothGattService s = new BluetoothGattService(DEVICE_INFO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic mfr = new BluetoothGattCharacteristic(MANUFACTURER_NAME,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        mfr.setValue("RM330".getBytes());
        s.addCharacteristic(mfr);
        BluetoothGattCharacteristic pnp = new BluetoothGattCharacteristic(PNP_ID,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        // Vendor ID source 0x02 (USB-IF), VID 0x2ca3, PID 0x1023, version 0x0001 (LE fields).
        pnp.setValue(new byte[]{0x02, (byte) 0xa3, 0x2c, 0x23, 0x10, 0x01, 0x00});
        s.addCharacteristic(pnp);
        return s;
    }

    // ------------------------------------------------------------ advertising

    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        // Advertising packet: flags (added by the stack) + HID service UUID, so hosts can
        // classify it during discovery. Name goes in the scan response (31-byte budget).
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(HID_SERVICE))
                .build();
        AdvertiseData scanResp = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        try {
            advertiser.startAdvertising(settings, data, scanResp, advCallback);
        } catch (Exception e) {
            fail("BLE: реклама не запустилась: " + e.getMessage());
        }
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) {
            Log.i(TAG, "advertising started");
            sink.onBleStatus("BLE: ждёт подключения ПК", true);
        }

        @Override public void onStartFailure(int errorCode) {
            fail("BLE: реклама не началась (код " + errorCode + ")");
        }
    };

    // ------------------------------------------------------------ bonding visibility

    private void registerBondReceiver() {
        if (bondReceiverRegistered) return;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        ctx.registerReceiver(bondReceiver, f);
        bondReceiverRegistered = true;
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String who = d == null ? "?" : d.getAddress();
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(i.getAction())) {
                int variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                Log.i(TAG, "pairing request from " + who + " variant=" + variant);
                // Stock Settings only pops its confirmation dialog when Settings itself is in
                // the foreground; otherwise it posts a notification that times out in 30 s
                // (observed live). Open the same dialog directly with the same extras.
                try {
                    Intent dlg = new Intent(i);
                    dlg.setComponent(new android.content.ComponentName("com.android.settings",
                            "com.android.settings.bluetooth.BluetoothPairingDialog"));
                    dlg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(dlg);
                    sink.onBleStatus("BLE: подтвердите сопряжение на пульте", true);
                } catch (Exception e) {
                    Log.w(TAG, "cannot open pairing dialog: " + e);
                    sink.onBleStatus("BLE: подтвердите в шторке уведомлений (30 с)", true);
                }
                return;
            }
            int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
            Log.i(TAG, "bond " + who + " " + prev + " -> " + st);
            if (st == BluetoothDevice.BOND_BONDED) sink.onBleStatus("BLE: сопряжено с " + who, true);
            else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING)
                sink.onBleStatus("BLE: сопряжение не удалось (" + who + ")", on);
        }
    };

    // ------------------------------------------------------------ GATT callbacks

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            Log.i(TAG, "service added " + service.getUuid() + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE: сервис не добавлен (" + service.getUuid() + ", " + status + ")");
                return;
            }
            if (on) addNextService();
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "conn " + device.getAddress() + " status=" + status + " state=" + newState
                    + " bond=" + device.getBondState());
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected.add(device);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connected.remove(device);
                subscribed.remove(device);
            }
            sink.onBleStatus(linkStatus(), on);
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic ch) {
            Log.i(TAG, "read " + ch.getUuid() + " off=" + offset);
            respond(device, requestId, offset, ch.getValue());
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattDescriptor d) {
            Log.i(TAG, "read desc " + d.getUuid() + " of " + d.getCharacteristic().getUuid());
            byte[] v = d.getValue();
            if (CCCD.equals(d.getUuid()) && d.getCharacteristic() == reportChar)
                v = subscribed.contains(device) ? new byte[]{0x01, 0x00} : new byte[]{0x00, 0x00};
            respond(device, requestId, offset, v);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor d, boolean prep, boolean rsp, int offset, byte[] value) {
            Log.i(TAG, "write desc " + d.getUuid() + " of " + d.getCharacteristic().getUuid()
                    + " = " + Arrays.toString(value));
            if (CCCD.equals(d.getUuid()) && d.getCharacteristic() == reportChar) {
                boolean notify = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                if (notify) {
                    subscribed.add(device);
                    sink.onBleStatus("BLE: активен, ПК подключён", true);
                } else {
                    subscribed.remove(device);
                }
            }
            if (rsp) sendOk(device, requestId, offset, value);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic ch, boolean prep, boolean rsp, int offset, byte[] value) {
            Log.i(TAG, "write " + ch.getUuid() + " = " + Arrays.toString(value));
            if (PROTOCOL_MODE.equals(ch.getUuid()) && value != null && value.length > 0) ch.setValue(value);
            if (rsp) sendOk(device, requestId, offset, value);
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.i(TAG, "mtu " + mtu);
        }
    };

    private String linkStatus() {
        int c = connected.size(), sub = subscribed.size();
        if (sub > 0) return "BLE: активен, ПК подключён";
        if (c > 0) return "BLE: ПК подключается...";
        return "BLE: ждёт подключения ПК";
    }

    private void respond(BluetoothDevice device, int requestId, int offset, byte[] v) {
        BluetoothGattServer srv = server;
        if (srv == null) return;
        if (v == null) v = new byte[0];
        try {
            if (offset > v.length) {
                srv.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(v, offset, v.length));
            }
        } catch (Exception e) {
            Log.w(TAG, "sendResponse failed: " + e);
        }
    }

    private void sendOk(BluetoothDevice device, int requestId, int offset, byte[] value) {
        BluetoothGattServer srv = server;
        if (srv == null) return;
        try { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value); }
        catch (Exception e) { Log.w(TAG, "sendResponse failed: " + e); }
    }
}
