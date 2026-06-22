package com.example.dreamweaver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * здесь находится вся работа с Bluetooth-модулем и отправкой команд на Arduino
 *
 * сначала приложение пробует обычное подключение HC-05, а если оно не подошло,
 * пробует BLE UART по сохранённому MAC-адресу
 * uuid ниже нужны для классического SPP и BLE UART-сервисов
 */
public class BluetoothHc05Manager {
    private static final String TAG = "DreamWeaverBluetooth";
    private static final String TARGET_BLE_ADDRESS = "EC:04:0C:AF:3B:01";
    private static final long BLE_SCAN_TIMEOUT_MS = 15_000L;
    private static final long BLE_CONNECT_TIMEOUT_MS = 15_000L;
    private static final long BLE_WRITE_TIMEOUT_MS = 5_000L;

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID NORDIC_UART_SERVICE_UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NORDIC_UART_WRITE_UUID =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID HM10_UART_SERVICE_UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID HM10_UART_WRITE_UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<PendingCommand> bleWriteQueue = new ArrayDeque<>();

    private BluetoothSocket socket;
    private OutputStream outputStream;
    private Thread connectionThread;
    private ConnectionCallback activeCallback;

    private BluetoothLeScanner bleScanner;
    private ScanCallback bleScanCallback;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writableCharacteristic;
    private Runnable bleTimeoutRunnable;
    private Runnable bleWriteTimeoutRunnable;
    private boolean bleConnected;
    private boolean bleWriteInProgress;
    private boolean targetSeenDuringScan;
    private PendingCommand activeBleCommand;
    private int connectionGeneration;
    private String lastBleError;
    private String activeClassicError;
    private ConnectionCallback bleConnectionCallback;

    public BluetoothHc05Manager(Context context) {
        this.context = context.getApplicationContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothAvailable() {
        if (bluetoothAdapter == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            return false;
        }
        // состояние Bluetooth читается через системный API, поэтому защищаемся от SecurityException
        try {
            return bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void connectToPairedDevice(String deviceName, ConnectionCallback callback) {
        // без этих разрешений Android не даст искать устройство и открывать соединение
        if (!hasBluetoothConnectPermission()) {
            notifyConnectionFailed(callback, "Нет разрешения BLUETOOTH_CONNECT");
            return;
        }
        if (!hasBluetoothScanPermission()) {
            notifyConnectionFailed(callback, "Нет разрешения на поиск Bluetooth-устройств");
            return;
        }
        if (!isBluetoothAvailable()) {
            notifyConnectionFailed(callback, "Bluetooth недоступен или выключен");
            return;
        }

        close();
        final int generation;
        synchronized (this) {
            // generation нужен, чтобы старые потоки подключения не могли перезаписать новое состояние
            activeCallback = callback;
            generation = connectionGeneration;
        }

        connectionThread = new Thread(() -> {
            // сначала ищем уже сопряжённый HC-05, потому что это основной вариант подключения
            BluetoothDevice bondedDevice = findPairedDevice(deviceName);
            Exception classicException = null;
            if (bondedDevice != null) {
                Log.i(TAG, "Selected paired device: " + describeDevice(bondedDevice));
                classicException = connectClassic(bondedDevice, callback, generation);
                if (classicException == null || !isCurrentGeneration(generation)) {
                    return;
                }
            }

            String classicError = bondedDevice == null
                    ? "сопряжённое устройство HC-05 не найдено"
                    : getExceptionMessage(classicException);
            // если обычный HC-05 не подключился, пробуем BLE-подключение по MAC-адресу
            startBleScan(deviceName, classicError, callback, generation);
        }, "HC-05-Connection");
        connectionThread.start();
    }

    private Exception connectClassic(
            BluetoothDevice device,
            ConnectionCallback callback,
            int generation
    ) {
        Exception lastException = null;
        // uuid SPP_UUID используется для классического serial-подключения HC-05
        // несколько вариантов сокета нужны из-за различий в прошивках модулей
        SocketFactory[] socketFactories = new SocketFactory[]{
                () -> device.createRfcommSocketToServiceRecord(SPP_UUID),
                () -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID),
                () -> createChannelOneSocket(device)
        };

        // перебираем варианты создания сокета, пока один из них не даст рабочее подключение
        // если все варианты не подошли, ошибка вернётся выше и начнётся BLE-попытка
        for (SocketFactory socketFactory : socketFactories) {
            if (!isCurrentGeneration(generation) || Thread.currentThread().isInterrupted()) {
                return new IOException("Подключение отменено");
            }
            BluetoothSocket connectedSocket = null;
            // каждая попытка подключения может упасть из-за занятого канала, прав или недоступного модуля
            // поэтому отдельная попытка изолирована в try/catch
            try {
                connectedSocket = connectSocket(socketFactory, generation);
                OutputStream connectedOutputStream = connectedSocket.getOutputStream();
                synchronized (this) {
                    if (generation != connectionGeneration) {
                        closeSocketQuietly(connectedSocket);
                        return new IOException("Подключение отменено");
                    }
                    socket = connectedSocket;
                    outputStream = connectedOutputStream;
                }
                Log.i(TAG, "Connected using Classic SPP: " + describeDevice(device));
                notifyConnected(callback, getDeviceName(device), generation);
                return null;
            } catch (Exception e) {
                closeAttemptSocket(connectedSocket);
                lastException = unwrapException(e);
            }
        }
        return lastException;
    }

    private BluetoothSocket connectSocket(SocketFactory socketFactory, int generation)
            throws Exception {
        // объект BluetoothSocket это канал для классического Bluetooth-соединения
        // через него приложение получает OutputStream и отправляет Arduino текстовые команды
        BluetoothSocket candidateSocket = socketFactory.create();
        synchronized (this) {
            if (generation != connectionGeneration) {
                closeSocketQuietly(candidateSocket);
                throw new IOException("Подключение отменено");
            }
            socket = candidateSocket;
            outputStream = null;
        }
        try {
            // перед соединением отключаем discovery, потому что поиск устройств мешает стабильному подключению
            bluetoothAdapter.cancelDiscovery();

            // connect открывает реальное соединение с модулем, дальше можно получать поток вывода
            candidateSocket.connect();
            return candidateSocket;
        } catch (Exception e) {
            // если попытка не удалась, временный сокет закрывается и не остаётся в состоянии менеджера
            closeAttemptSocket(candidateSocket);
            throw e;
        }
    }

    private void startBleScan(
            String requestedName,
            String classicError,
            ConnectionCallback callback,
            int generation
    ) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        // запуск BLE-сканирования требует разрешений и может упасть на уровне Android
        // поэтому весь блок находится в try/catch
        try {
            BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                notifyConnectionFailed(
                        callback,
                        "Classic: " + classicError + "; BLE-сканер недоступен",
                        generation
                );
                return;
            }

            resetBleCandidates(callback, classicError);
            ScanCallback scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    handleBleScanResult(result, requestedName, callback, generation);
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) {
                        handleBleScanResult(result, requestedName, callback, generation);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    stopBleScan();
                    lastBleError = "ошибка BLE-сканирования: " + errorCode;
                    connectDirectGatt(callback, generation);
                }
            };

            synchronized (this) {
                if (generation != connectionGeneration) {
                    return;
                }
                bleScanner = scanner;
                bleScanCallback = scanCallback;
                bleTimeoutRunnable = () -> {
                    stopBleScan();
                    connectDirectGatt(callback, generation);
                };
            }
            // ble-сканирование ищет именно модуль с TARGET_BLE_ADDRESS
            scanner.startScan(scanCallback);
            mainHandler.postDelayed(bleTimeoutRunnable, BLE_SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            notifyConnectionFailed(
                    callback,
                    "Нет разрешения на поиск Bluetooth-устройств",
                    generation
            );
        }
    }

    private void handleBleScanResult(
            ScanResult result,
            String requestedName,
            ConnectionCallback callback,
            int generation
    ) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String deviceName = getDeviceName(device);
        String advertisedName = result.getScanRecord() == null
                ? null
                : result.getScanRecord().getDeviceName();
        String displayName = firstNonEmpty(advertisedName, deviceName, "Unknown");
        String address = getDeviceAddress(device);

        Log.d(TAG, "BLE found: name=" + displayName
                + ", address=" + address + ", rssi=" + result.getRssi());

        // mac нужен, чтобы не подключиться к чужому BLE-устройству с похожим именем
        if (TARGET_BLE_ADDRESS.equalsIgnoreCase(address)) {
            synchronized (this) {
                if (targetSeenDuringScan) {
                    return;
                }
                targetSeenDuringScan = true;
            }
            stopBleScan();
            connectGattCandidate(device, callback, generation);
        }
    }

    private synchronized void resetBleCandidates(
            ConnectionCallback callback,
            String classicError
    ) {
        targetSeenDuringScan = false;
        lastBleError = null;
        activeClassicError = classicError;
        bleConnectionCallback = callback;
    }

    private void connectDirectGatt(ConnectionCallback callback, int generation) {
        if (!isCurrentGeneration(generation) || targetSeenDuringScan) {
            return;
        }
        // если сканирование не увидело модуль, пробуем прямое GATT-подключение по сохранённому MAC
        try {
            BluetoothDevice target = bluetoothAdapter.getRemoteDevice(TARGET_BLE_ADDRESS);
            Log.i(TAG, "BLE target not seen in scan; trying direct GATT: "
                    + describeDevice(target));
            connectGattCandidate(target, callback, generation);
        } catch (IllegalArgumentException | SecurityException e) {
            lastBleError = getExceptionMessage(e);
            notifyTargetConnectionFailed(generation);
        }
    }

    private void connectGattCandidate(
            BluetoothDevice device,
            ConnectionCallback callback,
            int generation
    ) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        cancelBleTimeout();
        closeGatt(bluetoothGatt);
        Log.i(TAG, "Selected BLE device: " + describeDevice(device));

        // после BLE-подключения ищем writable characteristic, через неё уходят команды
        // объект BluetoothGatt это BLE-соединение, в нём команды пишутся не в stream, а в characteristic
        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (!isActiveGatt(gatt, generation)) {
                    closeGattQuietly(gatt);
                    return;
                }
                if (status == BluetoothGatt.GATT_SUCCESS
                        && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) {
                        failCurrentCandidate(gatt, "не удалось запустить discoverServices", generation);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                        || status != BluetoothGatt.GATT_SUCCESS) {
                    boolean wasConnected;
                    synchronized (BluetoothHc05Manager.this) {
                        wasConnected = bleConnected && bluetoothGatt == gatt;
                    }
                    if (wasConnected) {
                        failBleWrites("BLE-соединение разорвано");
                    }
                    closeGatt(gatt);
                    if (wasConnected) {
                        notifyDisconnected(callback, generation);
                    } else {
                        lastBleError = "GATT status=" + status;
                        notifyTargetConnectionFailed(generation);
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (!isActiveGatt(gatt, generation)) {
                    closeGattQuietly(gatt);
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failCurrentCandidate(
                            gatt,
                            "ошибка discoverServices: " + status,
                            generation
                    );
                    return;
                }

                logGattDatabase(gatt);
                BluetoothGattCharacteristic characteristic = findWritableCharacteristic(gatt);
                if (characteristic == null) {
                    failCurrentCandidate(
                            gatt,
                            "writable characteristic не найдена у " + describeDevice(device),
                            generation
                    );
                    return;
                }

                synchronized (BluetoothHc05Manager.this) {
                    if (generation != connectionGeneration || bluetoothGatt != gatt) {
                        closeGattQuietly(gatt);
                        return;
                    }
                    writableCharacteristic = characteristic;
                    bleConnected = true;
                }
                Log.i(TAG, "Connected using BLE UART: " + describeDevice(device));
                Log.i(TAG, "Selected writable characteristic: uuid="
                        + characteristic.getUuid()
                        + ", properties=0x"
                        + Integer.toHexString(characteristic.getProperties()));
                cancelBleTimeout();
                notifyConnected(callback, firstNonEmpty(getDeviceName(device), "HC-05"), generation);
            }

            @Override
            public void onCharacteristicWrite(
                    BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic,
                    int status
            ) {
                onBleWriteComplete(gatt, characteristic, status);
            }
        };

        // создание GATT-соединения зависит от версии Android и разрешений Bluetooth
        // если соединение не создастся, дальше будет вызвана ошибка подключения
        try {
            // здесь создаётся BLE GATT-соединение с найденным устройством
            // callback выше получит событие подключения и потом запустит поиск сервисов
            BluetoothGatt gatt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(context, false, gattCallback);
            if (gatt == null) {
                lastBleError = "не удалось создать GATT для " + describeDevice(device);
                notifyTargetConnectionFailed(generation);
                return;
            }
            synchronized (this) {
                if (generation != connectionGeneration) {
                    closeGattQuietly(gatt);
                    return;
                }
                bluetoothGatt = gatt;
                bleTimeoutRunnable = () -> failCurrentCandidate(
                        gatt,
                        "таймаут GATT для " + describeDevice(device),
                        generation
                );
            }
            mainHandler.postDelayed(bleTimeoutRunnable, BLE_CONNECT_TIMEOUT_MS);
        } catch (SecurityException e) {
            notifyConnectionFailed(
                    callback,
                    "Нет разрешения на поиск Bluetooth-устройств",
                    generation
            );
        }
    }

    private void failCurrentCandidate(BluetoothGatt gatt, String error, int generation) {
        lastBleError = error;
        cancelBleTimeout();
        // если текущий BLE-кандидат не подошёл, GATT нужно закрыть, чтобы не держать соединение
        closeGatt(gatt);
        notifyTargetConnectionFailed(generation);
    }

    private synchronized boolean isActiveGatt(BluetoothGatt gatt, int generation) {
        return generation == connectionGeneration && bluetoothGatt == gatt;
    }

    private void notifyTargetConnectionFailed(int generation) {
        String message = "Classic: " + activeClassicError
                + "; BLE " + TARGET_BLE_ADDRESS + ": "
                + firstNonEmpty(lastBleError, "подключение не удалось");
        notifyConnectionFailed(bleConnectionCallback, message, generation);
    }

    private void logGattDatabase(BluetoothGatt gatt) {
        // выводим найденные сервисы и характеристики в лог, чтобы на защите и отладке было видно, что нашёл модуль
        for (BluetoothGattService service : gatt.getServices()) {
            Log.i(TAG, "GATT service: " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                Log.i(TAG, "GATT characteristic: uuid=" + characteristic.getUuid()
                        + ", properties=0x"
                        + Integer.toHexString(characteristic.getProperties()));
            }
        }
    }

    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGatt gatt) {
        // сначала ищем известные UART UUID, затем запасной вариант с любой writable-характеристикой
        BluetoothGattCharacteristic characteristic =
                findCharacteristic(gatt, HM10_UART_SERVICE_UUID, HM10_UART_WRITE_UUID);
        if (isWritable(characteristic)) {
            return characteristic;
        }
        characteristic = findCharacteristic(gatt, NORDIC_UART_SERVICE_UUID, NORDIC_UART_WRITE_UUID);
        if (isWritable(characteristic)) {
            return characteristic;
        }
        // если известные UUID не подошли, перебираем все сервисы и ищем любую характеристику с записью
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic candidate : service.getCharacteristics()) {
                if (isWritable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private BluetoothGattCharacteristic findCharacteristic(
            BluetoothGatt gatt,
            UUID serviceUuid,
            UUID characteristicUuid
    ) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        return service == null ? null : service.getCharacteristic(characteristicUuid);
    }

    private boolean isWritable(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return false;
        }
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    public void sendCommand(String command) {
        sendCommand(command, null);
    }

    public synchronized void sendCommand(String command, CommandCallback callback) {
        if (command == null) {
            notifyCommandFailed(callback, "пустая команда");
            return;
        }
        // arduino читает команды строками, поэтому к каждой команде добавляется перевод строки
        // bluetooth передаёт байты, поэтому строковая команда переводится в UTF-8 byte[]
        byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
        if (outputStream != null) {
            // блок classic-отправки обёрнут в try/catch, потому что поток может закрыться во время записи
            try {
                // при классическом подключении строка команды пишется прямо в OutputStream
                // именно здесь байты команды уходят на Arduino через HC-05
                outputStream.write(data);
                outputStream.flush();
                Log.i(TAG, "Classic command written: " + command);
                notifyCommandSent(callback);
            } catch (IOException e) {
                notifyCommandFailed(callback, getExceptionMessage(e));
                closeInternal(true);
            }
            return;
        }
        if (bleConnected && bluetoothGatt != null && writableCharacteristic != null) {
            // в BLE нельзя надёжно писать несколько команд сразу, поэтому используется очередь
            bleWriteQueue.offer(new PendingCommand(command, data, callback));
            writeNextBleCommand();
        } else {
            notifyCommandFailed(callback, "Bluetooth-соединение не готово");
        }
    }

    private synchronized void writeNextBleCommand() {
        if (bleWriteInProgress
                || !bleConnected
                || bluetoothGatt == null
                || writableCharacteristic == null) {
            return;
        }
        PendingCommand pendingCommand = bleWriteQueue.peek();
        if (pendingCommand == null) {
            return;
        }
        byte[] data = pendingCommand.data;
        int properties = writableCharacteristic.getProperties();
        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        // запись в BLE characteristic может не стартовать из-за прав, разрыва соединения или состояния GATT
        // поэтому запуск записи находится в try/catch
        try {
            boolean started;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // на новых версиях Android значение characteristic передаётся прямо в writeCharacteristic
                started = bluetoothGatt.writeCharacteristic(
                        writableCharacteristic,
                        data,
                        writeType
                ) == BluetoothStatusCodes.SUCCESS;
            } else {
                // на старых версиях Android данные сначала кладутся в characteristic, потом запускается запись
                writableCharacteristic.setWriteType(writeType);
                writableCharacteristic.setValue(data);
                started = bluetoothGatt.writeCharacteristic(writableCharacteristic);
            }
            if (started) {
                activeBleCommand = pendingCommand;
                bleWriteInProgress = true;
                Log.i(TAG, "BLE write started: command=" + pendingCommand.command
                        + ", characteristic=" + writableCharacteristic.getUuid()
                        + ", writeType=" + writeType);
                scheduleBleWriteTimeout(pendingCommand);
            } else {
                failBleWrites("writeCharacteristic не запустил запись");
            }
        } catch (SecurityException e) {
            failBleWrites(getExceptionMessage(e));
        }
    }

    private synchronized void onBleWriteComplete(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            int status
    ) {
        if (gatt != bluetoothGatt || characteristic != writableCharacteristic) {
            return;
        }
        cancelBleWriteTimeout();
        PendingCommand completedCommand = activeBleCommand;
        bleWriteQueue.poll();
        activeBleCommand = null;
        bleWriteInProgress = false;
        Log.i(TAG, "BLE write completed: command="
                + (completedCommand == null ? "unknown" : completedCommand.command)
                + ", status=" + status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (completedCommand != null) {
                notifyCommandSent(completedCommand.callback);
            }
            writeNextBleCommand();
        } else {
            if (completedCommand != null) {
                notifyCommandFailed(
                        completedCommand.callback,
                        "ошибка BLE-записи, status=" + status
                );
            }
            failBleWrites("ошибка BLE-записи, status=" + status);
        }
    }

    private synchronized void scheduleBleWriteTimeout(PendingCommand pendingCommand) {
        cancelBleWriteTimeout();
        bleWriteTimeoutRunnable = () -> {
            synchronized (BluetoothHc05Manager.this) {
                if (activeBleCommand != pendingCommand) {
                    return;
                }
                Log.e(TAG, "BLE write timeout: " + pendingCommand.command);
                failBleWrites("таймаут BLE-записи");
            }
        };
        mainHandler.postDelayed(bleWriteTimeoutRunnable, BLE_WRITE_TIMEOUT_MS);
    }

    private synchronized void cancelBleWriteTimeout() {
        if (bleWriteTimeoutRunnable != null) {
            mainHandler.removeCallbacks(bleWriteTimeoutRunnable);
            bleWriteTimeoutRunnable = null;
        }
    }

    private synchronized void failBleWrites(String reason) {
        cancelBleWriteTimeout();
        Log.e(TAG, "BLE command failed: " + reason);
        // если BLE-запись сломалась, ошибку получают все команды, которые ещё ждали отправки
        while (!bleWriteQueue.isEmpty()) {
            PendingCommand failedCommand = bleWriteQueue.poll();
            notifyCommandFailed(failedCommand.callback, reason);
        }
        activeBleCommand = null;
        bleWriteInProgress = false;
    }

    public synchronized boolean isConnected() {
        boolean classicConnected;
        try {
            classicConnected = socket != null && socket.isConnected() && outputStream != null;
        } catch (Exception e) {
            classicConnected = false;
        }
        return classicConnected
                || (bleConnected && bluetoothGatt != null && writableCharacteristic != null);
    }

    public synchronized void close() {
        // увеличение generation отменяет старые попытки подключения, которые ещё могут работать в фоне
        connectionGeneration++;
        if (connectionThread != null && connectionThread != Thread.currentThread()) {
            connectionThread.interrupt();
        }
        connectionThread = null;
        closeInternal(true);
    }

    private synchronized void closeInternal(boolean notifyDisconnected) {
        boolean wasConnected = isConnected();
        ConnectionCallback callback = activeCallback;
        // при закрытии соединения нужно остановить сканирование, таймауты, потоки и GATT
        // иначе приложение может держать Bluetooth-ресурсы даже после ухода с экрана
        stopBleScan();
        cancelBleTimeout();
        cancelBleWriteTimeout();
        closeOutputStream();
        closeSocket();
        closeGatt(bluetoothGatt);
        activeCallback = null;
        if (notifyDisconnected && wasConnected && callback != null) {
            callback.onDisconnected();
        }
    }

    private BluetoothDevice findPairedDevice(String deviceName) {
        // paired-устройства читаются из системного BluetoothAdapter
        // сначала ищем точный MAC, потом запасной вариант по имени HC-05
        try {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            if (bondedDevices == null) {
                return null;
            }
            BluetoothDevice nameMatch = null;
            // перебираем все сопряжённые устройства, чтобы найти нужный модуль
            for (BluetoothDevice device : bondedDevices) {
                if (TARGET_BLE_ADDRESS.equalsIgnoreCase(getDeviceAddress(device))) {
                    return device;
                }
                if (nameMatch == null && deviceName.equals(getDeviceName(device))) {
                    nameMatch = device;
                }
            }
            return nameMatch;
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private String getDeviceName(BluetoothDevice device) {
        if (device == null) {
            return null;
        }
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private String getDeviceAddress(BluetoothDevice device) {
        if (device == null) {
            return null;
        }
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return null;
        }
    }

    private String describeDevice(BluetoothDevice device) {
        return firstNonEmpty(getDeviceName(device), "Unknown")
                + " (" + firstNonEmpty(getDeviceAddress(device), "без адреса") + ")";
    }

    private BluetoothSocket createChannelOneSocket(BluetoothDevice device) throws Exception {
        Method method = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) method.invoke(device, 1);
    }

    private void closeAttemptSocket(BluetoothSocket attemptedSocket) {
        if (attemptedSocket == null) {
            return;
        }
        closeSocketQuietly(attemptedSocket);
        synchronized (this) {
            if (socket == attemptedSocket) {
                socket = null;
                outputStream = null;
            }
        }
    }

    private void closeOutputStream() {
        if (outputStream != null) {
            try {
                // закрываем поток, через который уходили команды при classic-подключении
                outputStream.close();
            } catch (IOException ignored) {
            }
            outputStream = null;
        }
    }

    private void closeSocket() {
        if (socket != null) {
            closeSocketQuietly(socket);
            socket = null;
        }
    }

    private void closeSocketQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket == null) {
            return;
        }
        try {
            bluetoothSocket.close();
        } catch (IOException ignored) {
        }
    }

    private synchronized void stopBleScan() {
        if (bleScanner != null && bleScanCallback != null) {
            try {
                bleScanner.stopScan(bleScanCallback);
            } catch (SecurityException ignored) {
            }
        }
        bleScanner = null;
        bleScanCallback = null;
        cancelBleTimeout();
    }

    private synchronized void cancelBleTimeout() {
        if (bleTimeoutRunnable != null) {
            mainHandler.removeCallbacks(bleTimeoutRunnable);
            bleTimeoutRunnable = null;
        }
    }

    private synchronized void closeGatt(BluetoothGatt gatt) {
        if (gatt == null) {
            return;
        }
        try {
            // disconnect разрывает BLE-соединение, а close ниже освобождает объект GATT
            gatt.disconnect();
        } catch (SecurityException ignored) {
        }
        closeGattQuietly(gatt);
        if (bluetoothGatt == gatt) {
            // после закрытия GATT очищаем BLE-состояние, чтобы новые команды не ушли в старое соединение
            bluetoothGatt = null;
            writableCharacteristic = null;
            bleConnected = false;
            bleWriteInProgress = false;
            activeBleCommand = null;
            bleWriteQueue.clear();
        }
    }

    private void closeGattQuietly(BluetoothGatt gatt) {
        if (gatt != null) {
            gatt.close();
        }
    }

    private synchronized boolean isCurrentGeneration(int generation) {
        return generation == connectionGeneration;
    }

    private void notifyConnected(
            ConnectionCallback callback,
            String deviceName,
            int generation
    ) {
        if (callback != null && isCurrentGeneration(generation)) {
            callback.onConnected(firstNonEmpty(deviceName, "HC-05"));
        }
    }

    private void notifyConnectionFailed(
            ConnectionCallback callback,
            String message,
            int generation
    ) {
        if (callback != null && isCurrentGeneration(generation)) {
            callback.onConnectionFailed(message);
        }
    }

    private void notifyConnectionFailed(ConnectionCallback callback, String message) {
        if (callback != null) {
            callback.onConnectionFailed(message);
        }
    }

    private void notifyDisconnected(ConnectionCallback callback, int generation) {
        if (callback != null && isCurrentGeneration(generation)) {
            callback.onDisconnected();
        }
    }

    private void notifyCommandSent(CommandCallback callback) {
        if (callback != null) {
            mainHandler.post(callback::onCommandSent);
        }
    }

    private void notifyCommandFailed(CommandCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCommandFailed(message));
        }
    }

    private Exception unwrapException(Exception exception) {
        if (exception instanceof InvocationTargetException
                && ((InvocationTargetException) exception).getCause() instanceof Exception) {
            return (Exception) ((InvocationTargetException) exception).getCause();
        }
        return exception;
    }

    private String getExceptionMessage(Exception exception) {
        if (exception == null) {
            return "Не удалось подключиться";
        }
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private interface SocketFactory {
        BluetoothSocket create() throws Exception;
    }

    private static class PendingCommand {
        final String command;
        final byte[] data;
        final CommandCallback callback;

        PendingCommand(String command, byte[] data, CommandCallback callback) {
            this.command = command;
            this.data = data;
            this.callback = callback;
        }
    }

    public interface CommandCallback {
        void onCommandSent();

        void onCommandFailed(String message);
    }

    public interface ConnectionCallback {
        void onConnected(String deviceName);

        void onConnectionFailed(String message);

        void onDisconnected();
    }
}
