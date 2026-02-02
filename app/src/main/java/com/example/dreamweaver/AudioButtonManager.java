package com.example.dreamweaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.widget.Button;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * привязку аудиофайлов к кнопкам (0–9)
 * создание и управление MediaPlayer для каждой кнопки
 * сохранение имя файла, громкость в SharedPreferences, чтобы настройки между запусками не слетали
 */
public class AudioButtonManager {
    /**
     * Имя файла настроек SharedPreferences, в котором все привязки кнопок
     */
    private static final String PREFS_NAME = "audio_button_prefs";

    /**
     * префикс ключа для хранения имени аудиофайла, привязанного к кнопке
     */
    private static final String KEY_PREFIX = "button_audio_";

    /**
     * префикс ключа для хранения громкости для каждой кнопки
     */
    private static final String VOLUME_PREFIX = "button_volume_";

    /**
     * громкость по умолчанию
     */
    private static final float DEFAULT_VOLUME = 1.0f;

    /**
     * доступ к файлам и SharedPreferences
     */
    private Context context;

    /**
     * Объект SharedPreferences, в котором мы храним все привязки и настройки
     */
    private SharedPreferences prefs;

    /**
     * Для каждой кнопки создаём свой MediaPlayer, чтобы можно было независимо управлять
     */
    private Map<Integer, MediaPlayer> players;

    /**
     * Словарь "номер кнопки сейчас проигрывается ли звук".
     */
    private Map<Integer, Boolean> isPlaying;

    /**
     * Инициализируем SharedPreferences и вспомогательные словари.
     * context контекст, через который получаем доступ к ресурсам приложения.
     */
    public AudioButtonManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.players = new HashMap<>();
        this.isPlaying = new HashMap<>();
    }

    /**
     * Привязка или отвязка аудиофайла к конкретной кнопке.
     */
    public void bindAudioToButton(int buttonNumber, String audioFileName) {
        if (audioFileName == null) {
            // Если передали null — это значит, что аудио нужно отвязать
            prefs.edit().remove(KEY_PREFIX + buttonNumber).apply();
            // При отвязке также освобождаем MediaPlayer, если он был создан
            releasePlayer(buttonNumber);
        } else {
            // Сохраняем имя аудиофайла для этой кнопки
            prefs.edit().putString(KEY_PREFIX + buttonNumber, audioFileName).apply();
            // Если для этой кнопки уже был MediaPlayer, его нужно освободить,
            // т.к. он ссылается на старый файл.
            releasePlayer(buttonNumber); // освобождаем старый плеер, если был
        }
    }

    /**
     * Получить имя файла, привязанного к кнопке.
     */
    public String getAudioForButton(int buttonNumber) {
        return prefs.getString(KEY_PREFIX + buttonNumber, null);
    }

    /**
     * Сохранить и применить громкость для конкретной кнопки.
     */
    public void setVolumeForButton(int buttonNumber, float volume) {
        // ограничиваем громкость от 0 до 1 на всякий случай
        float clamped = Math.max(0f, Math.min(1f, volume));
        // сохраняет значение в SharedPreferences
        prefs.edit().putFloat(VOLUME_PREFIX + buttonNumber, clamped).apply();
        // если MediaPlayer для этой кнопки уже создан — сразу применяем громкость к нему
        MediaPlayer player = players.get(buttonNumber);
        if (player != null) {
            player.setVolume(clamped, clamped);
        }
    }

    /**
     * Получить сохранённую громкость для кнопки.
     * Если пользователь ещё не менял громкость — вернётся DEFAULT_VOLUME.
     */
    public float getVolumeForButton(int buttonNumber) {
        return prefs.getFloat(VOLUME_PREFIX + buttonNumber, DEFAULT_VOLUME);
    }

    /**
     * Переключить состояние кнопки: начать или остановить воспроизведение.
     */
    public void toggleButton(int buttonNumber, Button buttonView) {
        String audioFileName = getAudioForButton(buttonNumber);
        if (audioFileName == null) {
            // Нна кнопку ещё не привязан файл
            return;
        }

        // берёь уже созданный MediaPlayer для этой кнопки, если он есть
        MediaPlayer player = players.get(buttonNumber);
        // играет ли сейчас звук
        boolean playing = isPlaying.getOrDefault(buttonNumber, false);

        if (playing) {
            // если сейчас играет останавливаем воспроизведение
            if (player != null) {
                // ставит на паузу и возвращаемся в начало
                player.pause();
                player.seekTo(0);
            }
            isPlaying.put(buttonNumber, false);
            updateButtonAppearance(buttonView, false);
        } else {
            // если не играет — запускаем воспроизведение
            try {
                if (player == null) {
                    // Создаём новый MediaPlayer, если его ещё не было
                    File audioFile = AudioLibraryActivity.getAudioFile(context, audioFileName);
                    if (!audioFile.exists()) {
                        // Если файл почему-то исчез — просто выходим
                        return;
                    }
                    player = new MediaPlayer();
                    player.setDataSource(audioFile.getAbsolutePath());
                    // подготавливает плеер
                    player.prepare();
                    // применяет сохранённую громкость для этой кнопки
                    float vol = getVolumeForButton(buttonNumber);
                    player.setVolume(vol, vol);
                    // сохраняет плеер в словарь по номеру кнопки
                    players.put(buttonNumber, player);

                    // вызывается, когда трек доиграл до конца
                    player.setOnCompletionListener(mp -> {
                        isPlaying.put(buttonNumber, false);
                        updateButtonAppearance(buttonView, false);
                    });
                } else {
                    // если плеер уже создан — просто перематывает в начало
                    player.seekTo(0);
                }
                // запускает воспроизведение
                player.start();
                isPlaying.put(buttonNumber, true);
                updateButtonAppearance(buttonView, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Обновление текста на кнопке в зависимости от состояния:
     * какая цифра и какое имя файла
     * показывает ли индикатор воспроизведения (▶)
     */
    private void updateButtonAppearance(Button button, boolean playing) {
        String currentText = button.getText().toString();
        // Разбиваем текущий текст на строки:
        // первая строка — номер кнопки, вторая — имя файла,
        // в скобках могут быть иконки состояния (▶).
        String[] lines = currentText.split("\n");
        // Берём базовый текст (номер кнопки) и удаляем хвост в скобках
        String baseText = lines[0].replaceAll("\\s*\\(.*\\)", "").trim();
        String fileName = "";
        if (lines.length > 1) {
            // Если есть вторая строка имя файла, также очищаем от иконок в скобках
            fileName = lines[1].replaceAll("\\s*\\(.*\\)", "").trim();
        }
        
        String text = baseText;
        if (!fileName.isEmpty()) {
            text += "\n" + fileName;
        }
        // индикатор состояния воспроизведения
        if (playing) {
            text += " (▶)";
        }
        button.setText(text);
    }

    /**
     * Освободить MediaPlayer для конкретной кнопки.
     * Вызывается при отвязке аудио или при смене файла.
     */
    public void releasePlayer(int buttonNumber) {
        MediaPlayer player = players.get(buttonNumber);
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
                player.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            players.remove(buttonNumber);
        }
        isPlaying.remove(buttonNumber);
    }

    /**
     * Освободить все MediaPlayer для всех кнопок.
     * Вызывается, например, при уничтожении активности.
     */
    public void releaseAll() {
        for (Map.Entry<Integer, MediaPlayer> entry : players.entrySet()) {
            entry.getValue().release();
        }
        players.clear();
        isPlaying.clear();
    }

    /**
     * Проверить, привязан ли к кнопке какой-либо аудиофайл.
     */
    public boolean hasAudio(int buttonNumber) {
        return getAudioForButton(buttonNumber) != null;
    }

    /**
     * переименовании файла в библиотеке, чтобы кнопки продолжали ссылаться на новый файл.
     */
    public static void replaceAudioFilenameInBindings(Context context, String oldName, String newName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (int i = 0; i <= 9; i++) {
            String key = KEY_PREFIX + i;
            String current = prefs.getString(key, null);
            if (oldName.equals(current)) {
                editor.putString(key, newName);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    /**
     * Удалить все привязки кнопок к файлу с указанным именем.
     * Используется при удалении файла из библиотеки, чтобы не осталось "битых" ссылок.
     */
    public static void removeBindingsForFilename(Context context, String fileName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (int i = 0; i <= 9; i++) {
            String key = KEY_PREFIX + i;
            String current = prefs.getString(key, null);
            if (fileName.equals(current)) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }
}

