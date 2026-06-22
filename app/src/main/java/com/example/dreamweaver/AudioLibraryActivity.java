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
 * здесь находится библиотека аудиофайлов приложения
 *
 * на этом экране можно загрузить аудио во внутреннюю папку приложения,
 * переименовать файл или удалить его из библиотеки
 */
public class AudioLibraryActivity extends AppCompatActivity {

    /**
     * имена аудиофайлов, которые мы заранее кладём из assets во внутреннюю папку приложения (предзагруженные звуки)
     */
    private static final String[] PRELOADED_ASSET_FILES = new String[] {"rain.mp3", "tavern_music.mp3"};

    private ListView audioList;

    private ArrayAdapter<String> adapter;

    private List<String> audioFileNames;

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

        audioFileNames = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, R.layout.item_audio_list, R.id.audio_item_text, audioFileNames);
        audioList.setAdapter(adapter);

        setupAudioPicker();

        // предзагруженные mp3 нужны в библиотеке сразу после первого запуска приложения
        // поэтому перед показом списка проверяем и копируем их из assets во внутреннюю папку
        ensurePreloadedAudio();

        // список на экране собирается из реальных файлов во внутренней папке
        // так библиотека сразу показывает импортированные и предзагруженные аудиофайлы
        loadAudioFileNames();

        // обработчик кнопки открывает системный выбор файла
        // результат выбора придёт в audioPickerLauncher, а не прямо в onCreate
        loadAudioButton.setOnClickListener(v -> audioPickerLauncher.launch(new String[]{"audio/*"}));

        // выбранный файл из списка можно переименовать или удалить из библиотеки
        audioList.setOnItemClickListener((parent, view, position, id) -> {
            String fileName = audioFileNames.get(position);
            showRenameDialog(fileName);
        });
    }

    /**
     * проверяет, что предзагруженные файлы из assets уже лежат во внутренней папке
     * если файл уже есть, повторно его не копируем
     */
    private void ensurePreloadedAudio() {
        // весь блок работает с файлами, поэтому оборачиваем его в try/catch
        // если assets или внутренняя папка недоступны, ошибка попадёт в лог, а приложение не упадёт
        try {
            File audioDir = new File(getFilesDir(), "audio_files");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            // проходим по всем предзагруженным mp3 из assets и проверяем, есть ли они уже во внутренней папке
            // если файла ещё нет, копируем его, чтобы он появился в библиотеке аудиофайлов
            for (String assetName : PRELOADED_ASSET_FILES) {
                File target = new File(audioDir, assetName);
                // проверка нужна, чтобы не перезаписывать файл, который уже был скопирован раньше
                if (target.exists()) {
                    continue;
                }
                // inputStream читает файл из assets, а fileOutputStream записывает копию во внутреннюю папку
                // try-with-resources сам закроет оба потока после копирования
                try (InputStream in = getAssets().open(assetName);
                     FileOutputStream out = new FileOutputStream(target)) {
                    // buffer нужен, чтобы читать mp3 частями, а не загружать весь файл в память сразу
                    byte[] buffer = new byte[4096];
                    int read;
                    // цикл while читает очередной кусок файла и сразу записывает его в target
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
     * системный выбор возвращает Uri файла, после этого приложение копирует аудио в свою папку
     */
    private void setupAudioPicker() {
        audioPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        // после выбора файла Android возвращает Uri, а не обычный путь к файлу
                        // поэтому сначала просим разрешение на чтение этого Uri
                        try {
                            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            getContentResolver().takePersistableUriPermission(uri, flags);
                        } catch (SecurityException ignored) {
                        }
                        // uri не сохраняем, копируем файл во внутреннюю папку приложения
                        saveAudioFile(uri);
                    }
                }
        );
    }

    /**
     * главный экран работает с копиями файлов во внутренней папке audio_files
     */
    private void saveAudioFile(Uri uri) {
        // импорт аудио работает через потоки, поэтому ошибки чтения или записи обрабатываем здесь
        try {
            // открываем поток чтения из Uri, который вернул системный выбор файла
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Toast.makeText(this, "Ошибка чтения файла", Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = getFileName(uri);
            if (fileName == null || fileName.isEmpty()) {
                // если система не дала имя, создаём своё, чтобы файл всё равно можно было сохранить
                fileName = "audio_" + System.currentTimeMillis() + ".mp3";
            }

            File audioDir = new File(getFilesDir(), "audio_files");
            if (!audioDir.exists()) {
                // audio_files создаётся во внутреннем хранилище приложения и не требует отдельных разрешений
                audioDir.mkdirs();
            }

            File outputFile = new File(audioDir, fileName);
            // fileOutputStream создаёт файл-копию внутри папки приложения
            // дальше в него будут записываться байты из выбранного пользователем файла
            FileOutputStream outputStream = new FileOutputStream(outputFile);

            // копируем файл кусками, чтобы не загружать весь аудиофайл в память сразу
            byte[] buffer = new byte[4096];
            int bytesRead;
            // while работает до конца файла, каждый проход переносит очередной кусок из inputStream в outputStream
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            // закрываем оба потока после копирования, чтобы не держать файловые ресурсы
            outputStream.close();
            inputStream.close();

            Toast.makeText(this, "Аудиофайл загружен: " + fileName, Toast.LENGTH_SHORT).show();

            // обновляем список на экране, чтобы новый файл сразу появился в библиотеке
            loadAudioFileNames();
            askToDeleteOriginal(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    /**
     * после импорта можно удалить оригинал, чтобы не хранить две копии одного файла
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
     * удаление оригинала возможно только для Uri, которые поддерживает DocumentsContract
     */
    private void deleteOriginalIfPossible(Uri originalUri) {
        // удаление исходного файла зависит от того, какие права и тип Uri дала система
        // поэтому весь блок выполняется через try/catch
        try {
            // не каждый Uri можно удалить, поэтому сначала проверяем поддержку DocumentsContract
            if (!DocumentsContract.isDocumentUri(this, originalUri)) {
                Toast.makeText(this, "Оригинал нельзя удалить: источник не поддерживает удаление", Toast.LENGTH_LONG).show();
                return;
            }
            // удаляется только исходный файл, копия внутри приложения остаётся в audio_files
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
     * имя файла берём из DISPLAY_NAME, а если его нет — из пути Uri
     */
    private String getFileName(Uri uri) {
        String fileName = null;
        String scheme = uri.getScheme();
        if (scheme != null && scheme.equals("content")) {
            // для content uri имя файла обычно хранится в метаданных, поэтому читаем его через cursor
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
     * в список попадают только файлы с поддерживаемыми аудио-расширениями
     */
    private void loadAudioFileNames() {
        // список полностью пересобирается из папки audio_files, чтобы он совпадал с реальными файлами
        audioFileNames.clear();
        File audioDir = new File(getFilesDir(), "audio_files");
        if (audioDir.exists() && audioDir.isDirectory()) {
            File[] files = audioDir.listFiles();
            if (files != null) {
                // проходим по всем файлам во внутренней папке и добавляем в список только аудио
                for (File file : files) {
                    if (file.isFile() && isAudioFile(file.getName())) {
                        // в библиотеку добавляем только поддерживаемые аудиоформаты
                        audioFileNames.add(file.getName());
                    }
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private boolean isAudioFile(String fileName) {
        // проверка расширения защищает библиотеку от случайных неаудиофайлов во внутренней папке
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || 
               lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac") || lower.endsWith(".flac");
    }

    public static File getAudioFile(android.content.Context context, String fileName) {
        // этот метод нужен другим классам, чтобы все брали аудио из одной и той же внутренней папки
        File audioDir = new File(context.getFilesDir(), "audio_files");
        return new File(audioDir, fileName);
    }

    /**
     * открывает диалог переименования файла в библиотеке
     *
     * если имя изменилось успешно, обновляем привязки кнопок на новое имя
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
                    // новое имя берём из поля ввода и проверяем перед переименованием файла
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
                        // после переименования обновляем привязки кнопок, иначе они будут ссылаться на старое имя
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
     * при удалении файла нужно также очистить привязки кнопок к этому имени
     */
    private void showDeleteDialogFromRename(String fileName) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Удалить файл?")
                .setMessage("Вы уверены, что хотите удалить " + fileName + "?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    // удаляем файл из внутренней папки приложения, а не из исходного места выбора
                    File audioFile = getAudioFile(this, fileName);
                    if (!audioFile.exists()) {
                        Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show();
                        loadAudioFileNames();
                        return;
                    }
                    if (audioFile.delete()) {
                        // после удаления очищаем сохранённые привязки, чтобы кнопки не ссылались на отсутствующий файл
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
