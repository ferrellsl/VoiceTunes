package com.ferrellsl.voicetunes;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.media3.common.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Listening from the player service, for VoiceTunes' own microphone button on Android Auto's
 * playing screen. (Android Auto's microphone and the steering-wheel button go to Google's
 * assistant, which only plays music on its partners' apps.) A beep, then what's said is matched
 * as on the phone screen, said back, and played.
 */
final class CarVoice {

    private final Context context;
    private final Music music;
    private final Player player;
    private final ExecutorService work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, listening, resumeAfter;

    CarVoice(Context context, Music music, Player player, ExecutorService work) {
        this.context = context;
        this.music = music;
        this.player = player;
        this.work = work;
        tts = new TextToSpeech(context, s -> ttsReady = s == TextToSpeech.SUCCESS);
    }

    void release() {
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
        tts.shutdown();
    }

    /** The button was tapped (on the main thread). */
    void listen() {
        if (listening) {                                   // (a second tap: stop listening)
            recognizer.cancel();
            done();
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Open VoiceTunes on the phone once and allow the microphone.", null);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            say("Speech recognition isn't available on this phone.", null);
            return;
        }
        if (music.library() == null) {
            say("The library isn't loaded yet.", null);
            return;
        }
        if (tts.isSpeaking()) tts.stop();
        resumeAfter = player.isPlaying();
        if (resumeAfter) player.pause();
        if (recognizer == null || !java.util.Objects.equals(service, Speech.chosen(music.prefs))) use(Speech.chosen(music.prefs));
        retried = false;
        beep();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        listening = true;
        request = i;
        // (after the beep, so it isn't heard as the start of the request)
        main.postDelayed(() -> { if (listening) recognizer.startListening(i); }, 250);
    }

    private ComponentName service;    // (null: the phone's default)
    private Intent request;
    private boolean retried;

    private void use(ComponentName s) {
        if (recognizer != null) recognizer.destroy();
        service = s;
        recognizer = Speech.create(context, s);
        recognizer.setRecognitionListener(listener);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle b) {}
        @Override public void onEvent(int t, Bundle b) {}

        @Override public void onError(int error) {
            if (!listening) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !retried) {
                ComponentName other = Speech.instead(context, service);
                if (other != null) {           // (this recognizer refused: another, Google's first)
                    retried = true;
                    use(other);
                    recognizer.startListening(request);
                    return;
                }
            }
            listening = false;
            String what;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: what = "I didn't catch that."; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: what = "The microphone isn't allowed."; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: what = "Speech recognition needs the internet."; break;
                default: what = "Listening didn't work, error " + error + ".";
            }
            say(what, CarVoice.this::done);
        }

        @Override public void onResults(Bundle b) {
            if (!listening) return;
            listening = false;
            List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
            act(r);
        }
    };

    private void act(List<String> alternatives) {
        Matcher.Request q = Matcher.parse(alternatives.get(0));
        if (q.control != Matcher.Control.NONE) {
            resumeAfter = false;
            switch (q.control) {
                case NEXT: player.seekToNextMediaItem(); player.play(); break;
                case PREVIOUS: player.seekToPrevious(); player.play(); break;
                case PAUSE: case STOP: break;                  // (paused already)
                case RESUME: player.play(); break;
                default: done(); break;
            }
            return;
        }
        work.execute(() -> {
            try {
                Matcher.Result found = music.find(alternatives);
                if (found == null) {
                    main.post(() -> say("I couldn't find " + alternatives.get(0) + ".", this::done));
                    return;
                }
                List<Matcher.Song> queue = music.queueFor(found);
                main.post(() -> {
                    if (queue.isEmpty()) { say("There's nothing in " + found.describe() + ".", this::done); return; }
                    String what = found.kind == Matcher.Kind.SONG ? found.describe()
                        : found.describe() + (music.shuffled(found) ? ", shuffled" : "");
                    resumeAfter = false;
                    say("Playing " + what + ".", () -> {
                        player.setShuffleModeEnabled(false);
                        player.setMediaItems(music.playable(queue), 0, 0);
                        player.prepare();
                        player.play();
                    });
                });
            } catch (Exception e) {
                main.post(() -> say("I can't reach the music server.", this::done));
            }
        });
    }

    /** Back to what was playing before the button was tapped. */
    private void done() {
        listening = false;
        if (resumeAfter) player.play();
        resumeAfter = false;
    }

    private void beep() {
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(t::release, 400);
        } catch (RuntimeException ignored) {
            // (no beep, then)
        }
    }

    /** Says something (when speaking is on), then runs `after` on the main thread. */
    private void say(String s, Runnable after) {
        if (!music.prefs.getBoolean("speak", true) || !ttsReady) {
            if (after != null) after.run();
            return;
        }
        String id = Long.toString(System.nanoTime());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) {}
            @Override public void onDone(String u) { if (after != null && id.equals(u)) main.post(after); }
            @Override public void onError(String u) { if (after != null && id.equals(u)) main.post(after); }
        });
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, id);
    }
}
