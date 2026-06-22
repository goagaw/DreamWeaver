package com.example.dreamweaver;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * сетка из 10 кнопок привязка аудиофайлов, их громкость и воспроизведение
 */
public class MainActivity extends AppCompatActivity {

    /**
     * привязки аудиофайлов к кнопкам и управление MediaPlayer
     */
    private AudioButtonManager audioManager;

    private BluetoothHc05Manager bluetoothManager;

    private TextView bluetoothStatusText;

    /**
     * лаунчер для запуска активности библиотеки аудио и получения результата
     */
    private ActivityResultLauncher<Intent> audioLibraryLauncher;

    private ActivityResultLauncher<String[]> bluetoothPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        audioManager = new AudioButtonManager(this);
        bluetoothManager = new BluetoothHc05Manager(this);
        bluetoothStatusText = findViewById(R.id.bluetooth_status_text);

        setupButtons();

        setupAudioLibraryLauncher();
        setupBluetoothPermissionLauncher();

        Button connectBluetoothButton = findViewById(R.id.connect_bluetooth_button);
        connectBluetoothButton.setOnClickListener(v -> handleBluetoothConnectClick());

        Button sendConfigButton = findViewById(R.id.send_config_button);
        sendConfigButton.setOnClickListener(v -> sendButtonConfigToDevice());

        setupDeviceCommandButtons();

        // Кнопка Загрузить аудио открывает библиотеку файлов
        Button audioLibraryButton = findViewById(R.id.audio_library_button);
        audioLibraryButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AudioLibraryActivity.class);
            audioLibraryLauncher.launch(intent);
        });

        Button guideButton = findViewById(R.id.guide_button);
        guideButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, GuideActivity.class);
            startActivity(intent);
        });
    }

    /**
     * находит все кнопки 0–9 в разметке и навешивает на них обработчики.
     * при нажатии на кнопку открывается меню с настройками этой кнопки.
     */
    private void setupButtons() {
        for (int i = 1; i <= 9; i++) {
            // Собираем id по имени вида button_0, button_1, ...
            int buttonId = getResources().getIdentifier("button_" + i, "id", getPackageName());
            Button button = findViewById(buttonId);
            if (button != null) {
                final int buttonNumber = i;
                // Обновляем текст кнопки (номер + имя файла, если привязано)
                updateButtonText(button, buttonNumber);
                
                button.setOnClickListener(v -> {
                    // При коротком нажатии сразу открываем диалог настроек этой кнопки
                    showButtonMenuDialog(buttonNumber, button);
                });
            }
        }
    }

    /**
     * Обновить текст на конкретной кнопке:
     * первая строка номер кнопки
     * вторая строка имя файла, если привязан
     * индикатор воспроизведения (▶) обновляется через AudioButtonManager
     */
    private void updateButtonText(Button button, int buttonNumber) {
        String audioFileName = audioManager.getAudioForButton(buttonNumber);

        if (audioFileName == null) {
            button.setText(String.valueOf(buttonNumber));
        } else {
            button.setText(getShortFileName(removeExtension(audioFileName)));
            button.setTextSize(16);
        }
    }

    private String removeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * укорачивает имя файла чтобы оно влезало на кнопку
     */
    private String getShortFileName(String fileName) {
        if (fileName.length() > 15) {
            return fileName.substring(0, 12) + "...";
        }
        return fileName;
    }

    /**
     * диалог выбора аудиофайла из уже загруженных в папку приложения
     */
    private void showBindAudioDialog(int buttonNumber, Button buttonView) {
        List<String> audioFiles = getAvailableAudioFiles();
        if (audioFiles.isEmpty()) {
            // если в библиотеке ещё нет файлов перенаправляет пользователя на экран библиотеки
            Toast.makeText(this, "Нет загруженных аудиофайлов. Загрузите файлы в библиотеке.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, AudioLibraryActivity.class);
            startActivity(intent);
            return;
        }

        String[] fileArray = audioFiles.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Привязать аудио к кнопке " + buttonNumber)
                .setItems(fileArray, (dialog, which) -> {
                    String selectedFile = audioFiles.get(which);
                    audioManager.bindAudioToButton(buttonNumber, selectedFile);
                    updateButtonText(buttonView, buttonNumber);
                    Toast.makeText(this, "Аудио привязано к кнопке " + buttonNumber, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Диалог настроек кнопки
     */
    private void showButtonMenuDialog(int buttonNumber, Button buttonView) {
        // имя файла и громкость для этой кнопки
        String audio = audioManager.getAudioForButton(buttonNumber);
        float currentVolume = audioManager.getVolumeForButton(buttonNumber);

        // корневой лейаут для содержимого диалога
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        android.widget.TextView bound = new android.widget.TextView(this);
        bound.setText(audio == null ? "Аудио не привязано" : ("Привязано: " + audio));
        root.addView(bound);

        // ползунок громкости
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(100);
        seek.setProgress(Math.round(currentVolume * 100f));
        root.addView(seek);

        android.widget.TextView volText = new android.widget.TextView(this);
        volText.setText("Громкость: " + seek.getProgress() + "%");
        root.addView(volText);

        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                volText.setText("Громкость: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        Button playOnDeviceButton = new Button(this);
        playOnDeviceButton.setText("Воспроизвести на устройстве");
        playOnDeviceButton.setOnClickListener(v ->
                sendDeviceCommand("PLAY:" + buttonNumber, "Команда PLAY отправлена"));
        root.addView(playOnDeviceButton);

        // собиранный диалог
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Кнопка " + buttonNumber)
                .setView(root)
                .setPositiveButton(audio == null ? "Привязать" : "Изменить аудио", (d, w) -> showBindAudioDialog(buttonNumber, buttonView))
                .setNeutralButton(audio == null ? "Плей" : "Плей/Стоп", (d, w) -> {
                    if (audioManager.hasAudio(buttonNumber)) {
                        audioManager.toggleButton(buttonNumber, buttonView);
                    } else {
                        Toast.makeText(this, "Сначала привяжите аудио", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .create();

        dialog.setOnShowListener(dlg -> {
            // меняем кнопку на Отвязать
            if (audioManager.hasAudio(buttonNumber)) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Отвязать");
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    // освобождаем плеер для этой кнопки
                    audioManager.releasePlayer(buttonNumber);
                    // удаляем привязку файла
                    audioManager.bindAudioToButton(buttonNumber, null);
                    // обновляем текст на кнопке
                    updateButtonText(buttonView, buttonNumber);
                    Toast.makeText(this, "Аудио отвязано", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            }
        });

        dialog.setOnDismissListener(d -> {
            // при закрытии диалога сохраняем выбранную громкость для этой кнопки
            float vol = seek.getProgress() / 100f;
            audioManager.setVolumeForButton(buttonNumber, vol);
        });

        dialog.show();
    }

    /**
     * список всех доступных аудиофайлов во внутренней папке приложения.
     */
    private List<String> getAvailableAudioFiles() {
        List<String> audioFiles = new ArrayList<>();
        File audioDir = new File(getFilesDir(), "audio_files");
        if (audioDir.exists() && audioDir.isDirectory()) {
            File[] files = audioDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isAudioFile(file.getName())) {
                        audioFiles.add(file.getName());
                    }
                }
            }
        }
        return audioFiles;
    }

    /**
     * проверка расширения файла
     */
    private boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ||
               lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac") || lower.endsWith(".flac");
    }

    /**
     * ActivityResultLauncher для открытия библиотеки аудио
     */
    private void setupAudioLibraryLauncher() {
        audioLibraryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // обновляем текст всех кнопок после возвращения из библиотеки
                    for (int i = 1; i <= 9; i++) {
                        int buttonId = getResources().getIdentifier("button_" + i, "id", getPackageName());
                        Button button = findViewById(buttonId);
                        if (button != null) {
                            updateButtonText(button, i);
                        }
                    }
                }
        );
    }

    private void setupBluetoothPermissionLauncher() {
        bluetoothPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    if (bluetoothManager.hasBluetoothConnectPermission()
                            && bluetoothManager.hasBluetoothScanPermission()) {
                        connectToHc05();
                    } else {
                        bluetoothStatusText.setText("Bluetooth: нет разрешения");
                        Toast.makeText(
                                this,
                                "Нет разрешения на поиск Bluetooth-устройств",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
    }

    private void handleBluetoothConnectClick() {
        if (!bluetoothManager.hasBluetoothConnectPermission()
                || !bluetoothManager.hasBluetoothScanPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothPermissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
            } else {
                bluetoothPermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                });
            }
            return;
        }

        if (!bluetoothManager.isBluetoothAvailable()) {
            bluetoothStatusText.setText("Bluetooth: недоступен");
            Toast.makeText(this, "Bluetooth недоступен или выключен", Toast.LENGTH_SHORT).show();
            return;
        }

        connectToHc05();
    }

    private void connectToHc05() {
        bluetoothStatusText.setText("Bluetooth: подключение...");
        bluetoothManager.connectToPairedDevice("HC-05", new BluetoothHc05Manager.ConnectionCallback() {
            @Override
            public void onConnected(String deviceName) {
                runOnUiThread(() -> {
                    bluetoothStatusText.setText("Bluetooth: подключено к HC-05");
                    Toast.makeText(MainActivity.this, "HC-05 подключён", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnectionFailed(String message) {
                runOnUiThread(() -> {
                    bluetoothStatusText.setText("Bluetooth: ошибка подключения");
                    Toast.makeText(MainActivity.this, "Ошибка подключения: " + message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> bluetoothStatusText.setText("Bluetooth: отключено"));
            }
        });
    }

    private void sendButtonConfigToDevice() {
        if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            Toast.makeText(this, "Сначала подключитесь к HC-05", Toast.LENGTH_SHORT).show();
            return;
        }

        for (int buttonNumber = 1; buttonNumber <= 9; buttonNumber++) {
            String trackNumber = String.format(java.util.Locale.US, "%03d", buttonNumber);
            int volume = Math.round(audioManager.getVolumeForButton(buttonNumber) * 100f);
            bluetoothManager.sendCommand("SET:" + buttonNumber + ":" + trackNumber + ":" + volume);
        }
        bluetoothManager.sendCommand("SAVE", createCommandCallback());
    }

    private void setupDeviceCommandButtons() {
        Button stopButton = findViewById(R.id.button_0);
        Button volumeDownButton = findViewById(R.id.button_star);
        Button volumeUpButton = findViewById(R.id.button_hash);

        stopButton.setOnClickListener(v -> sendDeviceCommand("STOP", "Команда остановки отправлена"));
        volumeDownButton.setOnClickListener(v -> sendDeviceCommand("VOLDOWN", "Громкость уменьшена"));
        volumeUpButton.setOnClickListener(v -> sendDeviceCommand("VOLUP", "Громкость увеличена"));
    }

    private void sendDeviceCommand(String command, String successMessage) {
        if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            Toast.makeText(this, "Сначала подключитесь к HC-05", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothManager.sendCommand(command, createCommandCallback());
    }

    private BluetoothHc05Manager.CommandCallback createCommandCallback() {
        return new BluetoothHc05Manager.CommandCallback() {
            @Override
            public void onCommandSent() {
                Toast.makeText(MainActivity.this, "Команда отправлена", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCommandFailed(String message) {
                Toast.makeText(
                        MainActivity.this,
                        "Команда не отправлена: " + message,
                        Toast.LENGTH_SHORT
                ).show();
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioManager != null) {
            // освобождаем все MediaPlayer
            audioManager.releaseAll();
        }
        if (bluetoothManager != null) {
            bluetoothManager.close();
        }
    }

}
