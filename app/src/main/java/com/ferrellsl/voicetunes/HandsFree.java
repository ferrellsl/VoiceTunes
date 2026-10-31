package com.ferrellsl.voicetunes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hands-free: with the phone in a pocket, "Hey VoiceTunes" (or just "VoiceTunes") and then what
 * to play or do, as with the microphone button. The wake words are listened for on the phone
 * with Vosk, an offline recognizer, through the phone's own microphone (an earbud microphone
 * would put Bluetooth in its phone-call mode and the music would go with it). What's said after
 * the beep goes to the phone's usual speech recognizer, as in the car (CarVoice).
 *
 * It runs as its own foreground service with a notification (Android requires one to use the
 * microphone in the background), started from the app's ear button and stopped from there or
 * from the notification.
 */
public final class HandsFree extends Service {

    static final String MODEL = "vosk-model-small-en-us-0.15";
    static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL + ".zip";
    private static final String STOP = "com.ferrellsl.voicetunes.HANDS_FREE_STOP";
    private static final String CHANNEL = "handsfree";
    private static final int NOTIFICATION = 2;
    // (Vosk listens only for these; anything else comes out as [unk])
    /** The commands that can follow the wake words in one breath, with no beep in between. */
    private static final String[] ONE_BREATH = {
        "what's playing", "what is playing", "what's this", "what song is this", "who is this",
        "next", "next song", "skip", "skip this", "back", "go back", "previous", "pause", "stop",
        "play", "resume", "shuffle on", "shuffle off", "turn shuffle on", "turn shuffle off",
        "turn looping on", "turn looping off", "shuffle everything",
    };
    private static final String GRAMMAR = grammar();    // (after ONE_BREATH, which it reads)

    private static String grammar() {
        StringBuilder g = new StringBuilder("[\"hey voice tunes\", \"voice tunes\", \"[unk]\"");
        for (String c : ONE_BREATH) g.append(", \"hey voice tunes ").append(c).append("\", \"voice tunes ").append(c).append('"');
        return g.append(']').toString();
    }

    /** Whether it's running, for the app's ear button. */
    static volatile boolean running;

    static File modelDir(Context c) { return new File(c.getFilesDir(), MODEL); }

    static boolean haveModel(Context c) { return new File(modelDir(c), "conf/model.conf").isFile(); }

    /** Downloads and unpacks the wake-word model (about 40 MB) off the main thread. */
    static void downloadModel(Context c, java.util.function.IntConsumer percent) throws IOException {
        File zip = new File(c.getCacheDir(), MODEL + ".zip");
        HttpURLConnection h = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            long total = h.getContentLengthLong(), got = 0;
            byte[] buf = new byte[65536];
            int last = -1;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                got += n;
                int p = total > 0 ? (int) (got * 100 / total) : 0;
                if (p != last) { percent.accept(p); last = p; }
            }
        } finally {
            h.disconnect();
        }
        File dir = c.getFilesDir();
        File tmp = new File(dir, MODEL + ".part");
        deleteTree(tmp);
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            byte[] buf = new byte[65536];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                // entries are "vosk-model-small-en-us-0.15/..."; keep what's below that
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0 || name.contains("..")) continue;
                File f = new File(tmp, name.substring(slash + 1));
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    for (int n; (n = z.read(buf)) > 0; ) out.write(buf, 0, n);
                }
            }
        } finally {
            zip.delete();
        }
        File done = modelDir(c);
        deleteTree(done);
        if (!tmp.renameTo(done)) throw new IOException("couldn't unpack the model");
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ the service

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private CarVoice voice;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hands-free listening", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIFICATION, n);

        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Exception e) {
                stopSelf();
                return;
            }
            voice = new CarVoice(this, Music.get(this), controller, work);
            voice.setOnFinished(() -> main.postDelayed(this::startWake, 400));
            work.execute(() -> {
                try {
                    Model m = new Model(modelDir(this).getAbsolutePath());
                    main.post(() -> { model = m; startWake(); });
                } catch (Exception e) {
                    main.post(this::stopSelf);
                }
            });
        }, MoreExecutors.directExecutor());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, HandsFree.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Hands-free is on")
            .setContentText("Say \"Hey VoiceTunes\", wait for the beep, then what to play")
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build();
    }

    /** Waits for the wake words (on the main thread). */
    private void startWake() {
        if (stopping || model == null || speech != null) return;
        try {
            recognizer = new Recognizer(model, 16000f, GRAMMAR);
            speech = new SpeechService(recognizer, 16000f);
            speech.startListening(wake);
        } catch (Exception e) {
            stopWake();
            main.postDelayed(this::startWake, 5000);       // (the microphone busy: try again)
        }
    }

    /** Lets go of the microphone, so the request after the beep can have it. */
    private void stopWake() {
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
    }

    private final RecognitionListener wake = new RecognitionListener() {
        @Override public void onPartialResult(String hypothesis) {}
        @Override public void onResult(String hypothesis) { heard(hypothesis); }
        @Override public void onFinalResult(String hypothesis) { heard(hypothesis); }
        @Override public void onError(Exception e) { stopWake(); main.postDelayed(HandsFree.this::startWake, 2000); }
        @Override public void onTimeout() { stopWake(); startWake(); }
    };

    private void heard(String json) {
        String text;
        try {
            text = new JSONObject(json).optString("text", "");
        } catch (Exception e) {
            return;
        }
        int at = text.indexOf("voice tunes");
        if (at < 0 || voice == null) return;
        String rest = text.substring(at + "voice tunes".length()).trim();
        stopWake();
        // a command said in one breath is done at once; anything else gets the beep
        if (rest.isEmpty() || rest.contains("[unk]")) main.post(voice::listen);
        else main.post(() -> voice.command(rest));
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        stopWake();
        if (voice != null) voice.release();
        if (model != null) model.close();
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        work.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
