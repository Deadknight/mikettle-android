package org.sombrenuit.dk.kettleboy;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class MiKettle
{
    private final String _mac;
    private final byte[] _reversed_mac;
    private final HashMap<String, String> _cache = new HashMap<>();
    private final Context ctx;
    private final BluetoothAdapter bluetoothAdapter;
    private long _last_read;
    private final int retries;
    private final int ble_timeout;
    private final byte _product_id;
    private Object _iface;
    private final long _cache_timeout;
    private byte[] _token;

    byte[] _KEY1 = new byte[]{(byte) 0x90, (byte) 0xCA, (byte) 0x85, (byte) 0xDE};
    byte[] _KEY2 = new byte[]{(byte) 0x92, (byte) 0xAB, (byte) 0x54, (byte) 0xFA};

    int _HANDLE_READ_FIRMWARE_VERSION = 26;
    int _HANDLE_READ_NAME = 20;
    int _HANDLE_AUTH_INIT = 44;
    int _HANDLE_AUTH = 37;
    int _HANDLE_VERSION = 42;
    int _HANDLE_STATUS = 61;

    String _UUID_SERVICE_AUTH = "fe95";
    String _UUID_SERVICE_HARDWARE = "180a";
    String _UUID_SERVICE_DATA = "4736";

    private final String notificationDescriptorUUID = "2902";

    private final String authInitCharacteristicUUID = "0010";
    private final String authCharacteristicUUID = "0001";
    private final String verCharacteristicsUUID = "0004";
    private final String nameCharacteristicsUUID = "2a24";
    private final String firmwareCharacteristicsUUID = "2a26";
    private final String setupCharacteristicUUID = "aa01";
    private final String statusCharacteristicUUID = "aa02";
    private final String timeCharacteristicUUID = "aa04";
    private final String boilModeCharacteristicUUID = "aa05";
    private final String mcuVersionCharacteristicUUID = "2a28";

    byte[] _SUBSCRIBE_TRUE = new byte[] { 0x01, 0x00 };

    public static final String MI_ACTION = "action";
    public static final String MI_MODE = "mode";
    public static final String MI_SET_TEMPERATURE = "set temperature";
    public static final String MI_CURRENT_TEMPERATURE = "current temperature";
    public static final String MI_KW_TYPE = "keep warm type";
    public static final String MI_KW_TIME = "keep warm time";

    HashMap<Byte, String> MI_ACTION_MAP = new HashMap<Byte, String>()
    {{
        put((byte) 0, "idle");
        put((byte) 1, "heating");
        put((byte) 2, "cooling");
        put((byte) 3, "keeping warm");
    }};

    HashMap<Byte, String> MI_MODE_MAP = new HashMap<Byte, String>()
    {{
        put((byte) 255, "none");
        put((byte) 1, "boil");
        put((byte) 3, "keep warm");
    }};

    HashMap<Byte, String> MI_KW_TYPE_MAP = new HashMap<Byte, String>()
    {{
        put((byte) 0, "warm up");
        put((byte) 1, "cool down");
    }};

    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattService auth_service;
    private BluetoothGattService hardware_service;
    private BluetoothGattService data_service;
    private BluetoothGattCharacteristic authInitCharacteristic;
    private BluetoothGattCharacteristic authCharacteristic;
    private BluetoothGattCharacteristic verCharacteristic;
    private BluetoothGattCharacteristic nameCharacteristic;
    private boolean discovered = false;

    private CountDownLatch latch = new CountDownLatch(1);
    private int state = 0;
    private IOnComplete<BluetoothGattCharacteristic> characteristicComplete;
    private BluetoothGattCharacteristic firmwareCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;
    private BluetoothGattCharacteristic timeCharacteristic;
    private IOnData<byte[]> onCharacteristicDataChanged;

    public ArrayList<BluetoothGattDescriptor> getDescriptors(BluetoothGattService service)
    {
        ArrayList<BluetoothGattDescriptor> lst = new ArrayList<>();
        for(BluetoothGattCharacteristic characteristic : service.getCharacteristics())
        {
            for(BluetoothGattDescriptor descriptor : characteristic.getDescriptors())
            {
                lst.add(descriptor);
            }
        }
        return lst;
    }

    public BluetoothGattDescriptor getDescriptor(BluetoothGattCharacteristic characteristic, String uuid)
    {
        for(BluetoothGattDescriptor descriptor : characteristic.getDescriptors())
        {
            if(checkUUID(descriptor.getUuid().toString(), uuid))
                return descriptor;
        }
        return null;
    }

    public BluetoothGattDescriptor getDescriptor(BluetoothGattService service, String uuid)
    {
        for(BluetoothGattCharacteristic characteristic : service.getCharacteristics())
        {
            for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors())
            {
                if (checkUUID(descriptor.getUuid().toString(), uuid))
                    return descriptor;
            }
        }
        return null;
    }

    public MiKettle(Context ctx, String mac, byte productId)
    {
        this(ctx, mac, productId, 600, 3, null, null);
    }
    public MiKettle(Context ctx, String mac, byte productId, long cache_timeout, int retries, String iface, String token)
    {
        this.ctx = ctx;

        this._mac = mac;
        this._reversed_mac = reverseMac(mac);

        //self._cache_timeout = timedelta(seconds=cache_timeout)
        this._last_read = 0;
        this.retries = retries;
        this.ble_timeout = 10;

        this._product_id = productId;
        this._cache_timeout = cache_timeout;
        //this._iface = _iface;

        this._token = null;
        if(_token == null)
        {
            _token = generateRandomToken();
        }

        final BluetoothManager bluetoothManager =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    private void connect(final IOnComplete<Void> onServiceComplete)
    {
        device = bluetoothAdapter.getRemoteDevice(_mac);
        gatt = device.connectGatt(ctx, false, new BluetoothGattCallback()
        {
            @Override
            public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status)
            {
                super.onPhyUpdate(gatt, txPhy, rxPhy, status);
            }

            @Override
            public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status)
            {
                super.onPhyRead(gatt, txPhy, rxPhy, status);
            }

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
            {
                super.onConnectionStateChange(gatt, status, newState);
                //Log.d("asd", "state change " + (newState == 2 ? "connected" : "disconnected"));
                if(newState == 2 && !discovered)
                {
                    discovered = true;
                    //Log.d("asd", "discover services");
                    gatt.discoverServices();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status)
            {
                //Log.d("asd", "services discovered");
                onServiceComplete.onComplete(null);
                super.onServicesDiscovered(gatt, status);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
            {
                //Log.d("asd", "characteristic read " + state);
                super.onCharacteristicRead(gatt, characteristic, status);
                if(characteristicComplete != null)
                {
                    characteristicComplete.onComplete(characteristic);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
            {
                //Log.d("asd", "characteristic write " + state);
                super.onCharacteristicWrite(gatt, characteristic, status);
                if(state == 0)
                    authd();
                else if(state == 1)
                    autha();
                else if(state == 3)
                    authb();
                else if(state == 4)
                    authc();
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
            {
                //Log.d("asd", "characteristic change " + state);
                super.onCharacteristicChanged(gatt, characteristic);
                if(state == 2)
                {
                    authb();
                }
                else
                {
                    if(onCharacteristicDataChanged != null)
                        onCharacteristicDataChanged.onData(characteristic.getValue());
                }
            }

            @Override
            public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
            {
                //Log.d("asd", "descriptor read " + state);
                super.onDescriptorRead(gatt, descriptor, status);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
            {
                //Log.d("asd", "descriptor write " + state);
                super.onDescriptorWrite(gatt, descriptor, status);
                if(state == 0)
                    authd();
                else if(state == 1)
                    autha();
                else if(state == 3)
                    authb();
                else if(state == 4)
                    authc();
            }

            @Override
            public void onReliableWriteCompleted(BluetoothGatt gatt, int status)
            {
                super.onReliableWriteCompleted(gatt, status);
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)
            {
                super.onReadRemoteRssi(gatt, rssi, status);
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status)
            {
                super.onMtuChanged(gatt, mtu, status);
            }
        });
    }

    public void name(final IOnComplete<String> onComplete) throws Exception
    {
        connect(new IOnComplete<Void>()
        {
            @Override
            public void onComplete(Void val)
            {
                try
                {
                    auth(new IOnComplete<BluetoothGattCharacteristic>()
                    {
                        @Override
                        public void onComplete(BluetoothGattCharacteristic val)
                        {
                            characteristicComplete = new IOnComplete<BluetoothGattCharacteristic>()
                            {
                                @Override
                                public void onComplete(BluetoothGattCharacteristic val)
                                {
                                    onComplete.onComplete(val.getStringValue(0));
                                }

                                @Override
                                public void onError()
                                {

                                }
                            };
                            gatt.readCharacteristic(nameCharacteristic);
                        }

                        @Override
                        public void onError()
                        {

                        }
                    });
                }
                catch (Exception ex)
                {
                    Log.e("asd", ex.toString());
                }
            }

            @Override
            public void onError()
            {

            }
        });

    }

    private void firmwareVersion(final IOnComplete<String> onComplete)
    {
        connect(new IOnComplete<Void>()
        {
            @Override
            public void onComplete(Void val)
            {
                try
                {
                    auth(new IOnComplete<BluetoothGattCharacteristic>()
                    {
                        @Override
                        public void onComplete(BluetoothGattCharacteristic val)
                        {
                            characteristicComplete = new IOnComplete<BluetoothGattCharacteristic>()
                            {
                                @Override
                                public void onComplete(BluetoothGattCharacteristic val)
                                {
                                    onComplete.onComplete(val.getStringValue(0));
                                }

                                @Override
                                public void onError()
                                {

                                }
                            };
                            gatt.readCharacteristic(firmwareCharacteristic);
                        }

                        @Override
                        public void onError()
                        {

                        }
                    });

                }
                catch (Exception e)
                {

                }
            }

            @Override
            public void onError()
            {

            }
        });

    }

    /*private String parameter_value(String parameter) throws Exception
    {
        return parameter_value(parameter, true);
    }*/

    /*private String parameter_value(String parameter, boolean read_cached) throws Exception
    {
        synchronized (this)
        {
            if(!read_cached || _last_read == 0 || System.currentTimeMillis() - _cache_timeout > _last_read)
                subscribe();
            else
            {
                if(cache_available())
                    return _cache.get(parameter);
                else
                    throw new Exception(String.format("Could not read data from MiKettle %s", _mac));
            }
        }

        return null;
    }*/

    private boolean cache_available()
    {
        return _cache != null;
    }

    public void subscribe(final IOnData<byte[]> data)
    {
        try
        {
            connect(new IOnComplete<Void>()
            {
                @Override
                public void onComplete(Void val)
                {
                    auth(new IOnComplete<BluetoothGattCharacteristic>()
                    {
                        @Override
                        public void onComplete(BluetoothGattCharacteristic val)
                        {
                            onCharacteristicDataChanged = data;
                            subscribeData();
                        }

                        @Override
                        public void onError()
                        {

                        }
                    });
                }

                @Override
                public void onError()
                {

                }
            });

        }
        catch (Exception ex)
        {
            _last_read = System.currentTimeMillis() - _cache_timeout + 300000;
        }
    }

    private void clear_cache()
    {
        _cache.clear();
        _last_read = 0;
    }

    public HashMap<String, String> parse_data(byte []data)
    {
        HashMap<String, String> map = new HashMap<>();
        map.put(MI_ACTION, MI_ACTION_MAP.get(data[0]));
        map.put(MI_MODE, MI_MODE_MAP.get(data[1]));
        map.put(MI_SET_TEMPERATURE, String.valueOf(data[4]));
        map.put(MI_CURRENT_TEMPERATURE, String.valueOf(data[5]));
        map.put(MI_KW_TYPE, MI_KW_TYPE_MAP.get(data[6]));
        map.put(MI_KW_TIME, String.valueOf(MiKettle.bytes_to_int(new byte[] { data[7], data[8] })));

        return map;
    }

    private static int bytes_to_int(byte[] bytes)
    {
        int result = 0;
        for(byte b : bytes)
        {
            result = result * 256 + (int)b;
        }
        return result;
    }

    //00000010-0000-1000-8000-00805f9b34fb
    private static boolean checkUUID(String uuid, String smallVal)
    {
        if(uuid == null || uuid.isEmpty() || smallVal == null || smallVal.isEmpty())
        {
            //// TODO: 29.07.2020 return some err
            return false;
        }

        String substr = uuid.substring(4, 8);

        return smallVal.equalsIgnoreCase(substr);
    }

    private void authd()
    {
        state++;
        BluetoothGattDescriptor notificationDescriptor = getDescriptor(authCharacteristic, notificationDescriptorUUID);
        boolean notif = gatt.setCharacteristicNotification(authCharacteristic, true);
        //Log.d("asd", "auth subscribe notif result " + notif);
        //authCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        notif = notificationDescriptor.setValue(_SUBSCRIBE_TRUE);
        //Log.d("asd", "auth subscribe value result " + notif);
        gatt.writeDescriptor(notificationDescriptor);
        //Log.d("asd", "auth subscribe");
    }

    private void autha()
    {
        state++;
        authCharacteristic.setValue(cipher(mixA(_reversed_mac, _product_id), _token));
        gatt.writeCharacteristic(authCharacteristic);
        //Log.d("asd", "auth send cipher");
    }

    private void authb()
    {
        state++;
        //authCharacteristic.setValue(_KEY2);
        authCharacteristic.setValue(cipher(_token, _KEY2));
        gatt.writeCharacteristic(authCharacteristic);
        //Log.d("asd", "auth send key2");
    }

    private void authc()
    {
        state++;
        gatt.readCharacteristic(verCharacteristic);
        //Log.d("asd", "get version");
    }

    private void auth(IOnComplete<BluetoothGattCharacteristic> onComplete)
    {
        characteristicComplete = onComplete;
        state = 0;
        List<BluetoothGattService> services = gatt.getServices();
        for(BluetoothGattService bgs : services)
        {
            if(checkUUID(bgs.getUuid().toString(), _UUID_SERVICE_AUTH))
            {
                auth_service = bgs;
            }
            else if(checkUUID(bgs.getUuid().toString(), _UUID_SERVICE_HARDWARE))
            {
                hardware_service = bgs;
            }
            else if(checkUUID(bgs.getUuid().toString(), _UUID_SERVICE_DATA))
            {
                data_service = bgs;
            }
        }
        if(auth_service == null)
            return;

        authInitCharacteristic = null;
        authCharacteristic = null;
        verCharacteristic = null;
        nameCharacteristic = null;
        firmwareCharacteristic = null;
        statusCharacteristic = null;
        for(BluetoothGattCharacteristic characteristic : auth_service.getCharacteristics())
        {
            if(checkUUID(characteristic.getUuid().toString(), authInitCharacteristicUUID))
            {
                authInitCharacteristic = characteristic;
            }
            else if(checkUUID(characteristic.getUuid().toString(), authCharacteristicUUID))
            {
                authCharacteristic = characteristic;
            }
            else if(checkUUID(characteristic.getUuid().toString(), verCharacteristicsUUID))
            {
                verCharacteristic = characteristic;
            }
        }
        for(BluetoothGattCharacteristic characteristic : hardware_service.getCharacteristics())
        {
            if(checkUUID(characteristic.getUuid().toString(), nameCharacteristicsUUID))
            {
                nameCharacteristic = characteristic;
            }
            else if(checkUUID(characteristic.getUuid().toString(), firmwareCharacteristicsUUID))
            {
                firmwareCharacteristic = characteristic;
            }
        }
        for(BluetoothGattCharacteristic characteristic : data_service.getCharacteristics())
        {
            if(checkUUID(characteristic.getUuid().toString(), statusCharacteristicUUID))
            {
                statusCharacteristic = characteristic;
            }
            else if(checkUUID(characteristic.getUuid().toString(), timeCharacteristicUUID))
            {
                timeCharacteristic = characteristic;
            }
        }

        //Log.d("asd", "auth init key1");
        authInitCharacteristic.setValue(_KEY1);
        gatt.writeCharacteristic(authInitCharacteristic);
    }

    public void finishSubscription()
    {
        if(descriptorsToSubscribe.size() == 0)
            return;
        BluetoothGattDescriptor descriptor = descriptorsToSubscribe.get(0);
        descriptorsToSubscribe.remove(0);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(descriptor);
    }

    ArrayList<BluetoothGattDescriptor> descriptorsToSubscribe = new ArrayList<>();
    private void subscribeData()
    {
        gatt.setCharacteristicNotification(statusCharacteristic, true);
        //statusCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        BluetoothGattDescriptor notificationDescriptor = getDescriptor(statusCharacteristic, notificationDescriptorUUID);
        notificationDescriptor.setValue(_SUBSCRIBE_TRUE);
        gatt.writeDescriptor(notificationDescriptor);
        //Log.d("asd", "subscribe");
    }

    private static byte[] generateRandomToken()
    {
        return new byte[] { 0x01, 0x5C, (byte) 0xCB, (byte) 0xA8, (byte) 0x80, 0x0A, (byte) 0xBD, (byte) 0xC1, 0x2E, (byte) 0xB8, (byte) 0xED, (byte) 0x82};
    }

    private static byte[] reverseMac(String mac)
    {
        String[] parts = mac.split(":");
        byte[] reversedMac = new byte[parts.length];
        for(int i = 1; i < parts.length + 1; i++)
        {
            reversedMac[i - 1] = Integer.decode("0x" + parts[parts.length - i]).byteValue();
        }

        return reversedMac;
    }

    private static byte[] mixA(byte []mac, byte productID)
    {
        return new byte[] { mac[0], mac[2], mac[5], (byte) (productID & 0xff), (byte) (productID & 0xff), mac[4], mac[5], mac[1] };
    }

    private static byte[] mixB(byte []mac, byte productID)
    {
        return new byte[] { mac[0], mac[2], mac[5], (byte) ((productID >> 8) & 0xff), mac[4], mac[0], mac[5], (byte) (productID & 0xff)};
    }

    private static byte[] _cipherInit(byte[] key)
    {
        byte[] perm = new byte[256];
        for(int i = 0; i < 256; i++)
        /*byte[] perm = new byte[key.length];
        for(int i = 0; i < perm.length; i++)*/
        {
            perm[i] = (byte) (i & 0xff);
        }
        int keyLen = key.length;
        int j = 0;
        for(int i = 0; i < perm.length; i++)
        {
            j += perm[i] + key[i % keyLen];
            j = j & 0xff;
            byte tempI = perm[i];
            byte tempJ = perm[j];
            perm[i] = tempJ;
            perm[j] = tempI;
        }
        return perm;
    }

    private static byte[] _cipherCrypt(byte[] input, byte[] permP)
    {
        int index1 = 0;
        int index2 = 0;
        byte[] output = new byte[input.length];
        byte[] perm = permP;

        for(int i = 0; i < input.length; i++)
        {
            index1 = index1 + 1;
            index1 = index1 & 0xff;
            index2 += perm[index1];
            index2 = index2 & 0xff;
            byte tempI1 = perm[index1];
            byte tempI2 = perm[index2];
            perm[index1] = tempI2;
            perm[index2] = tempI1;
            int idx = perm[index1] + perm[index2];
            idx = idx & 0xff;
            byte outputByte = (byte) (input[i] ^ perm[idx]);
            output[i] = (byte) (outputByte & 0xff);
        }

        return output;
    }

    private static byte[] cipher(byte[] key, byte[] input)
    {
        byte[] perm = _cipherInit(key);
        return _cipherCrypt(input, perm);
    }

    public void destroy()
    {
        gatt.close();
    }
}
