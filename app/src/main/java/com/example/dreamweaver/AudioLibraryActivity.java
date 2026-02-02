package com.example.dreamweaver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import android.provider.DocumentsContract;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * список всех аудиофайлов, сохранённых во внутренней папке приложения
 * загрузка аудиофайлов из памяти, переиминовывание, удаление
 * при загрузке предлагает удалить оригинал, чтобы не занимать лишнее место
 */
public class AudioLibraryActivity extends AppCompatActivity {

    /**
     * имена аудиофайлов, которые мы заранее кладём из assets во внутреннюю папку приложения (предзагруженные звуки)
     */
    private static final String[] PRELOADED_ASSET_FILES = new String[] {"rain.mp3", "tavern_music.mp3"};

    /**
     * список на экране в котором отображаются имена аудиофайлов.
     */
    private ListView audioList;

    /**
     * управляет отображением строк в ListView.
     */
    private ArrayAdapter<String> adapter;

    /**
     * список имён файлов, во внутренней папке приложения.
     */
    private List<String> audioFileNames;

    /**
     * открывает системный диалог выбора документа
     * и возвращает Uri выбранного аудифайла.
     */
    private ActivityResultLauncher<String[]> audioPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_audio_library);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.audio_library_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        audioList = findViewById(R.id.audio_list);
        Button loadAudioButton = findViewById(R.id.load_audio_button);

        // Инициализируем список имён файлов и адаптер для ListView
        audioFileNames = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, R.layout.item_audio_list, R.id.audio_item_text, audioFileNames);
        audioList.setAdapter(adapter);

        setupAudioPicker();

        ensurePreloadedAudio();

        loadAudioFileNames();

        // кнопка Загрузить аудиофайл  открывает диалог выбора файла
        loadAudioButton.setOnClickListener(v -> audioPickerLauncher.launch(new String[]{"audio/*"}));

        // нажатие по элементу списка — открывает диалог
        audioList.setOnItemClickListener((parent, view, position, id) -> {
            String fileName = audioFileNames.get(position);
            showRenameDialog(fileName);
        });
    }

    /**
     * что предзагруженные файлы из assets скопированы
     * во внутреннюю папку приложения. Если файл уже был скопирован ранее, повторно не копируем
     */
    private void ensurePreloadedAudio() {
        try {
            File audioDir = new File(getFilesDir(), "audio_files");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            for (String assetName : PRELOADED_ASSET_FILES) {
                File target = new File(audioDir, assetName);
                if (target.exists()) {
                    continue;
                }
                try (InputStream in = getAssets().open(assetName);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * лаунчер, который открывает системный диалог выбора документа.
     */
    private void setupAudioPicker() {
        audioPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            // Просим у системы долгосрочные права на чтение запись к этому Uri
                            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            getContentResolver().takePersistableUriPermission(uri, flags);
                        } catch (SecurityException ignored) {
                        }
                        // после выбора файла копируем его во внутреннюю папку приложения
                        saveAudioFile(uri);
                    }
                }
        );
    }

    /**
     * копирует выбранный пользователем файл по Uri во внутреннюю папку приложения
     */
    private void saveAudioFile(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Toast.makeText(this, "Ошибка чтения файла", Toast.LENGTH_SHORT).show();
                return;
            }

            // получаем имя файла, которым он будет сохранён внутри приложения
            String fileName = getFileName(uri);
            if (fileName == null || fileName.isEmpty()) {
                // если имя не удалось получить — генерируем своё
                fileName = "audio_" + System.currentTimeMillis() + ".mp3";
            }

            // создаём если ещё не создана внутреннюю директорию для аудиофайлов
            File audioDir = new File(getFilesDir(), "audio_files");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            // готовим файл-назначение внутри приложения
            File outputFile = new File(audioDir, fileName);
            FileOutputStream outputStream = new FileOutputStream(outputFile);

            // Перекачиваем данные из входного потока (uri) в выходной (наш файл)
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();

            Toast.makeText(this, "Аудиофайл загружен: " + fileName, Toast.LENGTH_SHORT).show();
            // Обновляем список файлов в ListView
            loadAudioFileNames();
            // После успешного копирования предлагаем удалить оригинал
            askToDeleteOriginal(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    /**
     * вопрос удалить ли оригинальный файл после импорта
     * если согласиться будет предпринята попытка удаления через DocumentsContract
     */
    private void askToDeleteOriginal(Uri originalUri) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Удалить оригинал?")
                .setMessage("Файл уже сохранён в приложении. Хотите удалить оригинал, чтобы не занимал место?")
                .setPositiveButton("Удалить", (dialog, which) -> deleteOriginalIfPossible(originalUri))
                .setNegativeButton("Оставить", null)
                .show();
    }

    /**
     * пытается удалить оригинальный файл по Uri, используя DocumentsContract
     */
    private void deleteOriginalIfPossible(Uri originalUri) {
        try {
            if (!DocumentsContract.isDocumentUri(this, originalUri)) {
                Toast.makeText(this, "Оригинал нельзя удалить: источник не поддерживает удаление", Toast.LENGTH_LONG).show();
                return;
            }
            boolean deleted = DocumentsContract.deleteDocument(getContentResolver(), originalUri);
            if (deleted) {
                Toast.makeText(this, "Оригинал удалён", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Не удалось удалить оригинал (права/источник)", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException se) {
            Toast.makeText(this, "Нет прав на удаление оригинала", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка удаления оригинала: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * извлекает читабильное имя файла по Uri:
     */
    private String getFileName(Uri uri) {
        String fileName = null;
        String scheme = uri.getScheme();
        if (scheme != null && scheme.equals("content")) {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        }
        if (fileName == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) {
                    fileName = path.substring(cut + 1);
                }
            }
        }
        return fileName;
    }

    /**
     * сканирует внутреннюю папку приложения и заполняет список audioFileNames
     * только теми файлами, которые распознаны как аудио
     * уведомляет адаптер, чтобы обновить ListView
     */
    private void loadAudioFileNames() {
        audioFileNames.clear();
        File audioDir = new File(getFilesDir(), "audio_files");
        if (audioDir.exists() && audioDir.isDirectory()) {
            File[] files = audioDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isAudioFile(file.getName())) {
                        audioFileNames.add(file.getName());
                    }
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * проверяет является ли имя файла аудиофайлом по его расширению
     */
    private boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || 
               lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac") || lower.endsWith(".flac");
    }

    /**
     * возвращает File для аудиофайла внутри внутренней папки приложения
     */
    public static File getAudioFile(android.content.Context context, String fileName) {
        File audioDir = new File(context.getFilesDir(), "audio_files");
        return new File(audioDir, fileName);
    }

    /**
     * диалог переименования
     */
    private void showRenameDialog(String oldFileName) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(oldFileName);
        input.setSelection(oldFileName.length());

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Переименовать файл")
                .setView(input)
                .setNeutralButton("Удалить", (dialog, which) -> showDeleteDialogFromRename(oldFileName))
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Имя не может быть пустым", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isAudioFile(newName)) {
                        Toast.makeText(this, "Сохраните расширение файла (.mp3/.wav/...)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newName.equals(oldFileName)) {
                        // Если имя не изменилось — ничего не делаем
                        return;
                    }

                    File dir = new File(getFilesDir(), "audio_files");
                    File from = new File(dir, oldFileName);
                    File to = new File(dir, newName);

                    if (!from.exists()) {
                        Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show();
                        loadAudioFileNames();
                        return;
                    }
                    if (to.exists()) {
                        Toast.makeText(this, "Файл с таким именем уже существует", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    boolean ok = from.renameTo(to);
                    if (ok) {
                        // Обновляем привязки всех кнопок, которые ссылались на старое имя
                        AudioButtonManager.replaceAudioFilenameInBindings(this, oldFileName, newName);
                        Toast.makeText(this, "Переименовано", Toast.LENGTH_SHORT).show();
                        loadAudioFileNames();
                    } else {
                        Toast.makeText(this, "Не удалось переименовать", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * диалог удаления файла из окна переименования
     */
    private void showDeleteDialogFromRename(String fileName) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Удалить файл?")
                .setMessage("Вы уверены, что хотите удалить " + fileName + "?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    File audioFile = getAudioFile(this, fileName);
                    if (!audioFile.exists()) {
                        Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show();
                        loadAudioFileNames();
                        return;
                    }
                    if (audioFile.delete()) {
                        AudioButtonManager.removeBindingsForFilename(this, fileName);
                        Toast.makeText(this, "Файл удален", Toast.LENGTH_SHORT).show();
                        loadAudioFileNames();
                    } else {
                        Toast.makeText(this, "Ошибка удаления файла", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}

