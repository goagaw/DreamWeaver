package com.example.dreamweaver;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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
 * сетка из 10 кнопок
 * привязка аудиофайлов, их громкость и воспроизведение
 * открывается экран библиотеки аудио
 */
public class MainActivity extends AppCompatActivity {

    /**
     *привязки аудиофайлов к кнопкам и управление MediaPlayer.
     */
    private AudioButtonManager audioManager;

    /**
     * Лаунчер для запуска активности библиотеки аудио и получения результата
     */
    private ActivityResultLauncher<Intent> audioLibraryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        // Обработчик системных отступов
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Инициализируем менеджер аудио-кнопок
        audioManager = new AudioButtonManager(this);

        // Настраиваем сами числовые кнопки
        setupButtons();
        // Готовим лаунчер для открытия библиотеки аудио
        setupAudioLibraryLauncher();

        // Кнопка "Загрузить аудио" внизу экрана — открывает библиотеку файлов
        Button audioLibraryButton = findViewById(R.id.audio_library_button);
        audioLibraryButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AudioLibraryActivity.class);
            audioLibraryLauncher.launch(intent);
        });
    }

    /**
     * Находит все кнопки 0–9 в разметке и навешивает на них обработчики.
     * При нажатии на кнопку открывается меню с настройками этой кнопки.
     */
    private void setupButtons() {
        for (int i = 0; i <= 9; i++) {
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
     * - первая строка: номер кнопки
     * - вторая строка: (сокращённое) имя файла, если привязан
     * - индикатор воспроизведения (▶) обновляется через AudioButtonManager
     */
    private void updateButtonText(Button button, int buttonNumber) {
        String audioFileName = audioManager.getAudioForButton(buttonNumber);
        
        String text = String.valueOf(buttonNumber);
        if (audioFileName != null) {
            text += "\n" + getShortFileName(audioFileName);
        }
        // Note: playing state indicator (▶) is managed by AudioButtonManager and will be updated via updateButtonAppearance
        button.setText(text);
    }

    /**
     * Укоротить имя файла, если оно слишком длинное,
     * чтобы оно нормально влезало на кнопку.
     */
    private String getShortFileName(String fileName) {
        if (fileName.length() > 15) {
            return fileName.substring(0, 12) + "...";
        }
        return fileName;
    }

    /**
     * Диалог выбора аудиофайла из уже загруженных в папку приложения.
     * Показывается, когда пользователь хочет привязать/поменять файл для кнопки.
     */
    private void showBindAudioDialog(int buttonNumber, Button buttonView) {
        List<String> audioFiles = getAvailableAudioFiles();
        if (audioFiles.isEmpty()) {
            // Если в библиотеке ещё нет файлов — перенаправляем пользователя на экран библиотеки
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
     * Диалог настроек конкретной кнопки.
     * Включает:
     * - информацию о привязанном файле
     * - ползунок громкости для этой кнопки
     * - действия: Привязать/Изменить аудио, Плей/Стоп, Отвязать аудио.
     */
    private void showButtonMenuDialog(int buttonNumber, Button buttonView) {
        // Текущее имя файла и громкость для этой кнопки
        String audio = audioManager.getAudioForButton(buttonNumber);
        float currentVolume = audioManager.getVolumeForButton(buttonNumber);

        // Корневой layout для содержимого диалога
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        // Текст с информацией: привязан ли файл
        android.widget.TextView bound = new android.widget.TextView(this);
        bound.setText(audio == null ? "Аудио не привязано" : ("Привязано: " + audio));
        root.addView(bound);

        // Ползунок громкости 0–100%
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(100);
        seek.setProgress(Math.round(currentVolume * 100f));
        root.addView(seek);

        // Текстовое отображение текущего значения громкости (в процентах)
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

        // Собираем диалог
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Кнопка " + buttonNumber)
                .setView(root)
                // Кнопка "Привязать"/"Изменить аудио" — открывает диалог выбора файла
                .setPositiveButton(audio == null ? "Привязать" : "Изменить аудио", (d, w) -> showBindAudioDialog(buttonNumber, buttonView))
                // Кнопка "Плей"/"Плей/Стоп" — управляет воспроизведением
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
            // При открытии диалога подменяем кнопку "Закрыть" на "Отвязать",
            // если к кнопке уже привязан файл.
            if (audioManager.hasAudio(buttonNumber)) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Отвязать");
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    // Останавливаем и освобождаем плеер для этой кнопки
                    audioManager.releasePlayer(buttonNumber);
                    // Удаляем привязку файла
                    audioManager.bindAudioToButton(buttonNumber, null);
                    // Обновляем текст на кнопке (убираем имя файла и индикаторы)
                    updateButtonText(buttonView, buttonNumber);
                    Toast.makeText(this, "Аудио отвязано", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            }
        });

        dialog.setOnDismissListener(d -> {
            // При закрытии диалога сохраняем выбранную громкость для этой кнопки
            float vol = seek.getProgress() / 100f;
            audioManager.setVolumeForButton(buttonNumber, vol);
        });

        dialog.show();
    }

    /**
     * Собрать список всех доступных аудиофайлов во внутренней папке приложения.
     * Эта папка пополняется:
     * - предзагруженными файлами из assets
     * - файлами, которые пользователь импортировал через библиотеку.
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
     * Проверка расширения файла: считаем аудио допустимым,
     * если оно имеет одно из распространённых аудио-расширений.
     */
    private boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ||
               lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac") || lower.endsWith(".flac");
    }

    /**
     * Настраиваем ActivityResultLauncher для открытия библиотеки аудио.
     * После возврата с экрана библиотеки обновляем подписи на кнопках, чтобы
     * сразу увидеть новые/переименованные/удалённые файлы.
     */
    private void setupAudioLibraryLauncher() {
        audioLibraryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Обновляем текст всех кнопок после возвращения из библиотеки
                    for (int i = 0; i <= 9; i++) {
                        int buttonId = getResources().getIdentifier("button_" + i, "id", getPackageName());
                        Button button = findViewById(buttonId);
                        if (button != null) {
                            updateButtonText(button, i);
                        }
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioManager != null) {
            // При уничтожении активности освобождаем все MediaPlayer,
            // чтобы не было утечек ресурсов.
            audioManager.releaseAll();
        }
    }

}