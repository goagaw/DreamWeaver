package com.example.dreamweaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.widget.Button;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * здесь хранится информация о кнопках и привязанных аудиофайлах
 *
 * для каждой кнопки сохраняется имя файла и громкость, а MediaPlayer создаётся
 * только когда пользователь запускает звук
 */
public class AudioButtonManager {
    private static final String PREFS_NAME = "audio_button_prefs";

    private static final String KEY_PREFIX = "button_audio_";

    private static final String VOLUME_PREFIX = "button_volume_";

    private static final float DEFAULT_VOLUME = 1.0f;

    private Context context;

    // в SharedPreferences хранятся привязки кнопок к файлам и громкость каждой кнопки
    // это нужно, чтобы сценарий не сбрасывался после закрытия приложения
    private SharedPreferences prefs;

    // в этой map лежат только уже созданные MediaPlayer
    // ключом является номер кнопки, чтобы у каждой кнопки был свой отдельный плеер
    private Map<Integer, MediaPlayer> players;

    // отдельно храним состояние воспроизведения, потому что MediaPlayer не используется как единственный источник состояния кнопки
    private Map<Integer, Boolean> isPlaying;

    public AudioButtonManager(Context context) {
        this.context = context;
        // здесь открываем SharedPreferences, где будут храниться выбранные файлы и громкость кнопок
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.players = new HashMap<>();
        this.isPlaying = new HashMap<>();
    }

    /**
     * привязка хранится в SharedPreferences, поэтому она остаётся после перезапуска приложения
     */
    public void bindAudioToButton(int buttonNumber, String audioFileName) {
        // если audioFileName равен null, значит пользователь отвязывает файл от кнопки
        // если имя не null, сохраняем новую привязку и очищаем старый плеер
        if (audioFileName == null) {

            prefs.edit().remove(KEY_PREFIX + buttonNumber).apply();
            // если файл отвязали, старый MediaPlayer больше не нужен и его надо освободить
            releasePlayer(buttonNumber);
        } else {
            // sharedPreferences сохраняет имя файла по ключу конкретной кнопки
            prefs.edit().putString(KEY_PREFIX + buttonNumber, audioFileName).apply();
            // при смене файла старый MediaPlayer нельзя оставлять, потому что он был создан для другого аудио
            releasePlayer(buttonNumber);
        }
    }

    public String getAudioForButton(int buttonNumber) {
        return prefs.getString(KEY_PREFIX + buttonNumber, null);
    }

    /**
     * громкость тоже хранится отдельно для каждой кнопки сценария
     */
    public void setVolumeForButton(int buttonNumber, float volume) {
        // ограничиваем значение диапазоном MediaPlayer от 0 до 1, чтобы не сохранить некорректную громкость
        float clamped = Math.max(0f, Math.min(1f, volume));
        prefs.edit().putFloat(VOLUME_PREFIX + buttonNumber, clamped).apply();

        // если плеер для кнопки уже создан, применяем громкость сразу к текущему предпрослушиванию
        MediaPlayer player = players.get(buttonNumber);
        if (player != null) {
            player.setVolume(clamped, clamped);
        }
    }

    public float getVolumeForButton(int buttonNumber) {
        return prefs.getFloat(VOLUME_PREFIX + buttonNumber, DEFAULT_VOLUME);
    }

    /**
     * запускает или останавливает локальное воспроизведение для кнопки 1-9
     */
    public void toggleButton(int buttonNumber, Button buttonView) {
        // сначала берём имя файла из сохранённых настроек кнопки
        // если файла нет, локально проигрывать нечего
        String audioFileName = getAudioForButton(buttonNumber);
        if (audioFileName == null) {
            return;
        }

        // получаем MediaPlayer, который уже был создан для этой кнопки
        // если его нет, ниже создадим новый и положим в players
        MediaPlayer player = players.get(buttonNumber);
        boolean playing = isPlaying.getOrDefault(buttonNumber, false);

        if (playing) {
            if (player != null) {
                // для предпрослушивания повторное нажатие останавливает звук и возвращает трек в начало
                player.pause();
                player.seekTo(0);
            }

            // состояние кнопки меняем отдельно, чтобы интерфейс сразу убрал индикатор воспроизведения
            isPlaying.put(buttonNumber, false);
            updateButtonAppearance(buttonView, false);
        } else {
            // работа с MediaPlayer и файлом может дать ошибку, поэтому запуск обёрнут в try/catch
            try {
                if (player == null) {
                    // плеер создаётся только при первом запуске, чтобы не держать лишние ресурсы
                    File audioFile = AudioLibraryActivity.getAudioFile(context, audioFileName);
                    if (!audioFile.exists()) {
                        return;
                    }

                    // объект MediaPlayer работает с конкретным файлом, поэтому создаём его только после проверки файла
                    player = new MediaPlayer();
                    player.setDataSource(audioFile.getAbsolutePath());

                    // prepare загружает данные файла и подготавливает плеер к запуску
                    // без этого start может упасть или не начать воспроизведение
                    player.prepare();

                    // перед запуском берём сохранённую громкость именно этой кнопки
                    float vol = getVolumeForButton(buttonNumber);
                    player.setVolume(vol, vol);

                    // сохраняем MediaPlayer в map, чтобы повторный запуск не создавал новый объект каждый раз
                    players.put(buttonNumber, player);

                    // когда аудио дошло до конца, нужно сбросить состояние и убрать значок ▶ с кнопки
                    player.setOnCompletionListener(mp -> {
                        isPlaying.put(buttonNumber, false);
                        updateButtonAppearance(buttonView, false);
                    });
                } else {
                    // если плеер уже есть, начинаем предпрослушивание заново с начала файла
                    player.seekTo(0);
                }

                // start запускает локальное предпрослушивание на телефоне, не на Arduino
                player.start();
                isPlaying.put(buttonNumber, true);
                updateButtonAppearance(buttonView, true);
            } catch (Exception e) {
                // при проблемах с файлом или MediaPlayer выводим ошибку в лог для отладки
                e.printStackTrace();
            }
        }
    }


    /**
     * индикатор ▶ добавляется к тексту кнопки только во время локального воспроизведения
     */
    private void updateButtonAppearance(Button button, boolean playing) {
        String currentText = button.getText().toString();
        String[] lines = currentText.split("\n");
        String baseText = lines[0].replaceAll("\\s*\\(.*\\)", "").trim();
        String fileName = "";
        if (lines.length > 1) {
            fileName = lines[1].replaceAll("\\s*\\(.*\\)", "").trim();
        }
        
        String text = baseText;
        if (!fileName.isEmpty()) {
            text += "\n" + fileName;
        }
        if (playing) {
            text += " (▶)";
        }
        button.setText(text);
    }

    public void releasePlayer(int buttonNumber) {
        // освобождаем MediaPlayer для конкретной кнопки
        // это нужно, чтобы приложение не держало аудиоресурсы после остановки или удаления файла
        // получаем MediaPlayer, который связан с конкретной кнопкой
        MediaPlayer player = players.get(buttonNumber);
        if (player != null) {
            // stop и release могут выбросить исключение, если плеер уже в неправильном состоянии
            // поэтому освобождение делаем через try/catch и не даём приложению упасть
            try {
                // если аудио сейчас проигрывается, сначала останавливаем его
                if (player.isPlaying()) {
                    player.stop();
                }

                // release освобождает ресурсы MediaPlayer, чтобы приложение не держало звук и системные ресурсы
                player.release();
            } catch (Exception e) {
                // выводим ошибку в лог, чтобы при отладке было видно проблему с освобождением плеера
                e.printStackTrace();
            }

            // после release объект MediaPlayer использовать уже нельзя, поэтому удаляем его из map
            players.remove(buttonNumber);
        }

        // удаляем состояние воспроизведения для этой кнопки, потому что плеер уже освобождён
        isPlaying.remove(buttonNumber);
    }

    public void releaseAll() {
        // при закрытии экрана проходим по всем созданным MediaPlayer и освобождаем каждый из них
        // цикл нужен, потому что у разных кнопок могут быть разные активные плееры
        for (Map.Entry<Integer, MediaPlayer> entry : players.entrySet()) {
            entry.getValue().release();
        }

        // очищаем map плееров, потому что все объекты MediaPlayer уже освобождены
        players.clear();

        // очищаем состояния воспроизведения, чтобы не осталось старых данных после закрытия экрана
        isPlaying.clear();
    }

    public boolean hasAudio(int buttonNumber) {
        return getAudioForButton(buttonNumber) != null;
    }

    /**
     * после переименования файла в библиотеке кнопки должны ссылаться на новое имя
     */
    public static void replaceAudioFilenameInBindings(Context context, String oldName, String newName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        // проверяем все кнопки, потому что один и тот же файл мог быть привязан к нескольким сценариям
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
     * после удаления файла очищаем все кнопки, которые на него ссылались
     */
    public static void removeBindingsForFilename(Context context, String fileName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        // при удалении файла проходим по всем кнопкам и убираем ссылки на несуществующее аудио
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
