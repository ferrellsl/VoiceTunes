package com.ferrellsl.voicetunes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One big button: tap it, say what to play ("Dreams I'll Never See", "the playlist Cook
 * Islands", "shuffle 38 Special", "next"), and it plays from the Navidrome server. Below it what
 * was heard, what's playing, and back / play-pause / next.
 */
public final class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private Music music;
    private MediaController player;
    private ListenableFuture<MediaController> playerFuture;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, listening, resumeAfter;

    private Button mic, playPause, shuffle, handsFree;
    private TextView status, heard, nowTitle, nowArtist;
    private ImageView art;

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        music = Music.get(this);
        prefs = music.prefs;
        buildScreen();
        if (prefs.getBoolean("screenOn", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tts = new TextToSpeech(this, s -> ttsReady = s == TextToSpeech.SUCCESS);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 2);
        }
        takeSearch(getIntent());
        if (music.server == null) {
            status.setText("Set up your Navidrome server to start.");
            main.post(this::showSettings);
        } else {
            loadLibrary(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        playerFuture = new MediaController.Builder(this, token).buildAsync();
        playerFuture.addListener(() -> {
            try {
                player = playerFuture.get();
                player.addListener(new Player.Listener() {
                    @Override public void onEvents(Player p, Player.Events e) { showNowPlaying(); }
                });
                showNowPlaying();
                playSearch();
            } catch (Exception e) {
                status.setText("Can't start the player: " + e.getMessage());
            }
        }, MoreExecutors.directExecutor());
    }

    // ------------------------------------------------------------------ "play X on VoiceTunes"

    /** What an assistant asked for ("play X on VoiceTunes"), until the player is connected. */
    private String pendingSearch;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeSearch(intent);
        playSearch();
    }

    private void takeSearch(Intent intent) {
        if (intent == null || !android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction())) return;
        String q = intent.getStringExtra(SearchManager.QUERY);
        pendingSearch = q == null ? "" : q;
        intent.setAction(Intent.ACTION_MAIN);          // (not again when the screen is rebuilt)
    }

    /** The player service finds and plays it, as for Android Auto's searches. */
    private void playSearch() {
        if (pendingSearch == null || player == null || music.server == null) return;
        String q = pendingSearch;
        pendingSearch = null;
        heard.setText(q.isEmpty() ? "" : "“" + q + "”");
        player.setMediaItem(new MediaItem.Builder()
            .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setSearchQuery(q).build()).build());
        player.prepare();
        player.play();
    }

    @Override
    protected void onStop() {
        if (listening) stopListening();
        MediaController.releaseFuture(playerFuture);
        player = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        tts.shutdown();
        work.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the screen

    /** Builds the screen for the current orientation: in portrait the picture in the middle
     *  with everything else above and below it, in landscape the picture on the left and the
     *  rest in a column beside it. Rebuilt on rotation (the app isn't restarted for it). */
    private void buildScreen() {
        boolean landscape = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        CharSequence oldStatus = status == null ? "" : status.getText();
        CharSequence oldHeard = heard == null ? "" : heard.getText();
        int pad = dp(landscape ? 12 : 16);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("VoiceTunes", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // hands-free ("Hey VoiceTunes"): bright when it's on
        handsFree = flat("👂");
        handsFree.setAlpha(HandsFree.running ? 1f : 0.35f);
        handsFree.setOnClickListener(v -> toggleHandsFree());
        top.addView(handsFree);
        Button settings = flat("⚙");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings);

        status = text("", 14, Color.rgb(150, 160, 170));
        status.setText(oldStatus);

        // what's playing: its picture (the one in the file, if it has one), square and as big as
        // fits; tapping it plays or pauses
        art = new ImageView(this) {
            @Override protected void onMeasure(int w, int h) {
                int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
                int s = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
                super.onMeasure(s, s);
            }
        };
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(12));
        frame.setColor(Color.rgb(40, 46, 52));
        art.setBackground(frame);
        if (artBitmap != null) art.setImageBitmap(artBitmap); else art.setImageResource(R.drawable.ic_launcher);
        art.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
        });
        LinearLayout artBox = new LinearLayout(this);
        artBox.setGravity(Gravity.CENTER);
        artBox.addView(art, new LinearLayout.LayoutParams(-1, -1));

        nowTitle = text("", 22, Color.WHITE);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setGravity(Gravity.CENTER);
        nowTitle.setSingleLine();
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowArtist = text("", 17, Color.rgb(180, 190, 200));
        nowArtist.setGravity(Gravity.CENTER);
        nowArtist.setSingleLine();
        nowArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heard = text("", 16, Color.rgb(255, 213, 79));
        heard.setGravity(Gravity.CENTER);
        heard.setPadding(0, dp(6), 0, 0);
        heard.setText(oldHeard);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(landscape ? 4 : 12), 0, 0);
        Button prev = flat("⏮"), next = flat("⏭");
        playPause = flat("▶");
        prev.setOnClickListener(v -> { if (player != null) player.seekToPrevious(); });
        playPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
        });
        next.setOnClickListener(v -> { if (player != null) player.seekToNextMediaItem(); });
        // shuffle what's playing (from the song now playing on), or back to its own order
        shuffle = flat("🔀");
        shuffle.setOnClickListener(v -> {
            if (player == null) return;
            player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
            say(player.getShuffleModeEnabled() ? "Shuffle on." : "Shuffle off.");
        });
        for (Button b : new Button[] { prev, playPause, next, shuffle }) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
            controls.addView(b, new LinearLayout.LayoutParams(0, dp(64), 1));
        }

        // the microphone: small and round, centered below the controls
        mic = new Button(this);
        mic.setAllCaps(false);
        mic.setTextColor(Color.WHITE);
        mic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mic.setPadding(0, 0, 0, 0);
        if (listening) setMicListening(); else setMicIdle();
        mic.setOnClickListener(v -> onMic());
        LinearLayout.LayoutParams micAt = new LinearLayout.LayoutParams(dp(56), dp(56));
        micAt.gravity = Gravity.CENTER_HORIZONTAL;
        micAt.setMargins(0, dp(4), 0, dp(4));

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(16, 20, 24));
        root.setPadding(pad, pad, pad, pad);
        if (landscape) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(artBox, new LinearLayout.LayoutParams(-2, -1));
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(pad, 0, 0, 0);
            side.addView(top);
            side.addView(status);
            side.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            side.addView(nowTitle);
            side.addView(nowArtist);
            side.addView(heard);
            side.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            side.addView(controls);
            side.addView(mic, micAt);
            root.addView(side, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(top);
            root.addView(status);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, 0, 1);
            mp.setMargins(0, dp(10), 0, dp(10));
            root.addView(artBox, mp);
            root.addView(nowTitle);
            root.addView(nowArtist);
            root.addView(heard);
            root.addView(controls);
            root.addView(mic, micAt);
        }
        setContentView(root);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        buildScreen();
        showNowPlaying();
    }

    private void setMicIdle() {
        mic.setText("🎤");
        mic.setBackground(circle(Color.rgb(30, 136, 229)));
    }

    private void setMicListening() {
        mic.setText("…");
        mic.setBackground(circle(Color.rgb(229, 57, 53)));
    }

    private void showNowPlaying() {
        if (player == null) return;
        MediaMetadata m = player.getMediaMetadata();
        nowTitle.setText(m.title == null ? "" : m.title);
        nowArtist.setText(m.artist == null ? "" : m.artist);
        playPause.setText(player.isPlaying() ? "⏸" : "▶");
        shuffle.setAlpha(player.getShuffleModeEnabled() ? 1f : 0.35f);
        showArt(m.artworkUri);
    }

    private Uri artShown;
    private android.graphics.Bitmap artBitmap;    // (kept for when the screen is rebuilt)

    /** The picture for what's playing, fetched from the server once per song. */
    private void showArt(Uri uri) {
        if (uri == null) { artShown = null; artBitmap = null; art.setImageResource(R.drawable.ic_launcher); return; }
        if (uri.equals(artShown)) return;
        artShown = uri;
        images.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(music.resolve(uri).toString()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                try (java.io.InputStream in = c.getInputStream()) {
                    b = android.graphics.BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // (no picture, then)
            }
            android.graphics.Bitmap got = b;
            main.post(() -> {
                if (!uri.equals(artShown)) return;              // (another song by now)
                artBitmap = got;
                if (got == null) art.setImageResource(R.drawable.ic_launcher);
                else art.setImageBitmap(got);
            });
        });
    }

    // ------------------------------------------------------------------ listening

    private void onMic() {
        if (listening) { stopListening(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 1);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("This phone has no speech recognizer. Install or enable the Google app.");
            return;
        }
        if (music.library == null) {
            say("The library isn't loaded yet.");
            return;
        }
        if (tts.isSpeaking()) tts.stop();
        // (the music would drown out what's said)
        resumeAfter = player != null && player.isPlaying();
        if (resumeAfter) player.pause();
        if (recognizer == null) useRecognizer(Speech.chosen(prefs));
        retried = false;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        setMicListening();
        heard.setText("");
        listenIntent = i;
        recognizer.startListening(i);
    }

    private ComponentName recognizerService;    // (null: the phone's default)
    private Intent listenIntent;
    private boolean retried;

    private void useRecognizer(ComponentName service) {
        if (recognizer != null) recognizer.destroy();
        recognizerService = service;
        recognizer = Speech.create(this, service);
        recognizer.setRecognitionListener(listener);
    }

    private void stopListening() {
        listening = false;
        setMicIdle();
        if (recognizer != null) recognizer.cancel();
        if (resumeAfter && player != null) player.play();
        resumeAfter = false;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int t, Bundle b) {}

        @Override public void onPartialResults(Bundle b) {
            List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) heard.setText(r.get(0));
        }

        @Override public void onError(int error) {
            if (!listening) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !retried) {
                // (this recognizer refused: another, Google's first, if there is one)
                ComponentName other = Speech.instead(MainActivity.this, recognizerService);
                if (other != null) {
                    retried = true;
                    useRecognizer(other);
                    recognizer.startListening(listenIntent);
                    return;
                }
            }
            listening = false;
            setMicIdle();
            boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            heard.setText(nothing ? "Didn't catch that"
                : error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    ? "The speech recognizer isn't allowed the microphone (error 9): allow it in Android's app settings, or pick another one in Settings"
                    : "Speech recognizer error " + error);
            if (resumeAfter && player != null) player.play();
            resumeAfter = false;
        }

        @Override public void onResults(Bundle b) {
            if (!listening) return;
            listening = false;
            setMicIdle();
            List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
            act(r);
        }
    };

    /** Does what was said: the recognizer's alternatives are tried, and the one that names
     *  something in the library best wins. */
    private void act(List<String> alternatives) {
        heard.setText("“" + alternatives.get(0) + "”");
        Library lib = music.library;
        Matcher.Request bestRequest = null;
        Matcher.Result best = null;
        for (String a : alternatives) {
            Matcher.Request q = Matcher.parse(a);
            if (q.control != Matcher.Control.NONE) {
                if (best == null) { control(q.control); return; }
                continue;
            }
            Matcher.Result f = lib.matcher.find(q);
            if (f != null && (best == null || f.score > best.score + 0.02)) { best = f; bestRequest = q; }
        }
        if (best == null || best.score < Matcher.ACCEPT) {
            String said = alternatives.get(0);
            speakThen("I couldn't find " + said + ".", () -> { if (resumeAfter && player != null) player.play(); resumeAfter = false; });
            return;
        }
        resumeAfter = false;
        Matcher.Result found = best;
        heard.setText("“" + alternatives.get(0) + "” → " + found.describe());
        work.execute(() -> {
            try {
                List<Matcher.Song> queue = music.queueFor(found);
                main.post(() -> {
                    if (queue.isEmpty()) { speakThen("There's nothing in " + found.describe() + ".", null); return; }
                    String what = found.kind == Matcher.Kind.SONG ? found.describe()
                        : found.describe() + (music.shuffled(found) ? ", shuffled" : "");
                    speakThen("Playing " + what + ".", () -> play(queue));
                });
            } catch (Exception e) {
                main.post(() -> speakThen("I can't reach the music server.", null));
            }
        });
    }

    private void play(List<Matcher.Song> songs) {
        if (player == null) return;
        List<MediaItem> items = music.playable(songs);
        player.setShuffleModeEnabled(false);
        player.setMediaItems(items, 0, 0);
        player.prepare();
        player.play();
    }

    private void control(Matcher.Control c) {
        boolean wasPlaying = resumeAfter;
        resumeAfter = false;
        if (player == null) return;
        Runnable carryOn = () -> { if (wasPlaying && player != null) player.play(); };
        switch (c) {
            case WHATS_PLAYING: tellThen(Music.nowPlaying(player), carryOn); break;
            case SHUFFLE_ON: player.setShuffleModeEnabled(true); speakThen("Shuffle on.", carryOn); break;
            case SHUFFLE_OFF: player.setShuffleModeEnabled(false); speakThen("Shuffle off.", carryOn); break;
            case LOOP_ON: player.setRepeatMode(Player.REPEAT_MODE_ALL); speakThen("Looping on.", carryOn); break;
            case LOOP_OFF: player.setRepeatMode(Player.REPEAT_MODE_OFF); speakThen("Looping off.", carryOn); break;
            case SHUFFLE_ALL:
                work.execute(() -> {
                    List<Matcher.Song> queue = music.shuffleAll(500);
                    main.post(() -> speakThen("Shuffling everything.", () -> play(queue)));
                });
                break;
            case NEXT: player.seekToNextMediaItem(); player.play(); break;
            case PREVIOUS: player.seekToPrevious(); player.play(); break;
            case PAUSE: case STOP: player.pause(); break;
            case RESUME: player.play(); break;
            case REFRESH:
                loadLibrary(true);
                if (wasPlaying) player.play();
                break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ speaking

    private void say(String s) { speakThen(s, null); }

    /** Says something (when speaking is on), then runs `after` once it's said. */
    private void speakThen(String s, Runnable after) {
        if (prefs.getBoolean("speak", true)) { tellThen(s, after); return; }
        status.setText(s);
        if (after != null) after.run();
    }

    /** Says something even with "Say what's going to play" off (an answer to a question). */
    private void tellThen(String s, Runnable after) {
        status.setText(s);
        if (!ttsReady) {
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

    // ------------------------------------------------------------------ server and library

    /** The library from the phone's copy, fetched from the server when there's none, it's a
     *  day old, or `fresh` is asked for. */
    private void loadLibrary(boolean fresh) {
        if (music.server == null) return;
        Subsonic s = music.server;
        status.setText(fresh ? "Getting the library from the server…" : "Loading the library…");
        work.execute(() -> {
            Library lib = fresh ? null : Library.load(music.libraryFile);
            if (lib != null) {
                Library ready = lib;
                main.post(() -> { music.library = ready; status.setText(ready.summary()); });
                if (!lib.stale()) return;
            }
            try {
                if (lib == null) main.post(() -> status.setText("Getting the library from the server (the first time takes a minute)…"));
                Library got = Library.fetch(s);
                got.save(music.libraryFile);
                main.post(() -> {
                    music.library = got;
                    status.setText(got.summary());
                    if (fresh) say("The library is up to date.");
                });
            } catch (Exception e) {
                main.post(() -> status.setText((music.library == null ? "Can't get the library: " : "Using the saved library; the server said: ") + e.getMessage()));
            }
        });
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText url = field(box, "Navidrome address at home", prefs.getString("server", ""), InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("http://192.168.1.20:4533");
        EditText away = field(box, "Away address (optional; used when the home one doesn't answer)",
            prefs.getString("away", ""), InputType.TYPE_TEXT_VARIATION_URI);
        away.setHint("https://music.example.com");
        certStatus = new TextView(this);
        certStatus.setPadding(0, dp(4), 0, 0);
        box.addView(certStatus);
        LinearLayout certButtons = new LinearLayout(this);
        Button pickCert = new Button(this);
        pickCert.setText("Certificate file…");
        Button forgetCert = new Button(this);
        forgetCert.setText("Forget it");
        certButtons.addView(pickCert);
        certButtons.addView(forgetCert);
        box.addView(certButtons);
        showCertStatus();
        pickCert.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK_CERT);
        });
        forgetCert.setOnClickListener(v -> {
            prefs.edit().remove("awayCert").apply();
            Trust.install(prefs);
            showCertStatus();
        });
        EditText user = field(box, "User name", prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(box, prefs.getString("token", "").isEmpty() ? "Password" : "Password (leave empty to keep)", "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText rate = field(box, "Highest bit rate in kbps, for mobile data (0 = as stored)",
            Integer.toString(prefs.getInt("bitrate", 0)), InputType.TYPE_CLASS_NUMBER);
        CheckBox shufflePl = new CheckBox(this);
        shufflePl.setText("Shuffle playlists (start anywhere, random order)");
        shufflePl.setChecked(music.shufflePlaylists());
        box.addView(shufflePl);
        CheckBox speak = new CheckBox(this);
        speak.setText("Say what's going to play");
        speak.setChecked(prefs.getBoolean("speak", true));
        box.addView(speak);
        CheckBox screen = new CheckBox(this);
        screen.setText("Keep the screen on while VoiceTunes is open");
        screen.setChecked(prefs.getBoolean("screenOn", true));
        box.addView(screen);
        Button speech = new Button(this);
        speech.setText("Speech recognizer: " + recognizerName(Speech.chosen(prefs)));
        speech.setOnClickListener(v -> pickRecognizer(speech));
        box.addView(speech);
        Button refresh = new Button(this);
        refresh.setText("Get the library again now");
        box.addView(refresh);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        refresh.setOnClickListener(v -> { d.dismiss(); loadLibrary(true); });
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit();
            boolean serverChanged = !url.getText().toString().trim().equals(prefs.getString("server", ""))
                || !user.getText().toString().trim().equals(prefs.getString("user", ""));
            e.putString("server", url.getText().toString().trim());
            e.putString("away", away.getText().toString().trim());
            e.putString("user", user.getText().toString().trim());
            if (pass.getText().length() > 0) {
                String[] t = Subsonic.tokenFor(pass.getText().toString());
                e.putString("token", t[0]).putString("salt", t[1]);
                serverChanged = true;
            }
            try { e.putInt("bitrate", Integer.parseInt(rate.getText().toString().trim())); } catch (NumberFormatException ignored) {}
            e.putBoolean("speak", speak.isChecked());
            e.putBoolean("shufflePlaylists", shufflePl.isChecked());
            e.putBoolean("screenOn", screen.isChecked());
            e.apply();
            if (screen.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            music.reloadServer();
            if (music.server == null) { status.setText("Enter the server address, your user name and your password."); return; }
            d.dismiss();
            if (serverChanged) {
                Subsonic s = music.server;
                status.setText("Connecting…");
                work.execute(() -> {
                    try {
                        s.ping();
                        main.post(() -> loadLibrary(true));    // (another server or user: its library)
                    } catch (Exception ex) {
                        main.post(() -> status.setText("Can't log in: " + ex.getMessage()));
                    }
                });
            }
        }));
        d.show();
    }

    private String recognizerName(ComponentName c) {
        if (c == null) return "the phone's default";
        for (Speech.Service s : Speech.services(this)) if (s.component.equals(c)) return s.label;
        return c.getPackageName();
    }

    private void pickRecognizer(Button shown) {
        List<Speech.Service> all = Speech.services(this);
        String[] names = new String[all.size() + 1];
        names[0] = "The phone's default";
        for (int k = 0; k < all.size(); k++) names[k + 1] = all.get(k).label;
        new AlertDialog.Builder(this)
            .setTitle("Speech recognizer")
            .setItems(names, (x, k) -> {
                ComponentName c = k == 0 ? null : all.get(k - 1).component;
                prefs.edit().putString("recognizer", c == null ? "" : c.flattenToString()).apply();
                if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                shown.setText("Speech recognizer: " + recognizerName(c));
            })
            .show();
    }

    // ------------------------------------------------------------------ hands-free

    private void toggleHandsFree() {
        if (HandsFree.running) {
            stopService(new Intent(this, HandsFree.class));
            HandsFree.running = false;
            handsFree.setAlpha(0.35f);
            say("Hands-free off.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 3);
            return;
        }
        if (!HandsFree.haveModel(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Hands-free")
                .setMessage("With hands-free on, say \"Hey VoiceTunes\" with the phone in your pocket, wait for "
                    + "the beep, then say what to play or \"pause\", \"next\"... It listens through the phone's "
                    + "microphone and uses some battery while it's on."
                    + NL + NL + "It needs its wake-word model first: a one-time download of about 40 MB.")
                .setPositiveButton("Download", (x, y) -> downloadModel())
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        startHandsFree();
    }

    private void downloadModel() {
        status.setText("Downloading the hands-free model…");
        work.execute(() -> {
            try {
                HandsFree.downloadModel(this, p -> main.post(() -> status.setText("Downloading the hands-free model… " + p + "%")));
                main.post(() -> { status.setText(""); startHandsFree(); });
            } catch (Exception e) {
                main.post(() -> status.setText("Couldn't download the hands-free model: " + e.getMessage()));
            }
        });
    }

    private void startHandsFree() {
        if (music.server == null) { status.setText("Set up your Navidrome server first."); return; }
        startForegroundService(new Intent(this, HandsFree.class));
        HandsFree.running = true;
        handsFree.setAlpha(1f);
        say("Hands-free on. Say hey VoiceTunes.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (handsFree != null) handsFree.setAlpha(HandsFree.running ? 1f : 0.35f);
    }

    // ------------------------------------------------------------------ the away address's certificate

    private static final int PICK_CERT = 2;
    private static final String NL = System.lineSeparator();
    private TextView certStatus;

    private void showCertStatus() {
        if (certStatus == null) return;
        X509Certificate c = Trust.saved(prefs);
        certStatus.setText(c == null
            ? "Away certificate: the phone's usual ones. If your away address uses a self-signed certificate "
                + "or the reverse proxy's own authority (e.g. Caddy's \"tls internal\"), choose that certificate's file."
            : "Away certificate: " + c.getSubjectX500Principal().getName() + ", until "
                + java.text.DateFormat.getDateInstance().format(c.getNotAfter()));
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_CERT || result != RESULT_OK || data == null || data.getData() == null) return;
        X509Certificate c;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0 && b.size() < 1 << 20; ) b.write(buf, 0, n);
            c = Trust.parse(b.toByteArray());
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Not a certificate")
                .setMessage("That file isn't a certificate VoiceTunes can read (PEM .crt/.pem or DER .cer).")
                .setPositiveButton("OK", null).show();
            return;
        }
        // (the user checks it's the right one before it's trusted)
        new AlertDialog.Builder(this)
            .setTitle("Trust this certificate?")
            .setMessage(c.getSubjectX500Principal().getName()
                + NL + NL + "Valid until " + java.text.DateFormat.getDateInstance().format(c.getNotAfter())
                + NL + NL + "SHA-256 fingerprint:" + NL + Trust.fingerprint(c)
                + NL + NL + "It will be trusted only for the away address.")
            .setPositiveButton("Trust it", (x, y) -> {
                try {
                    prefs.edit().putString("awayCert", Trust.encode(c)).apply();
                } catch (Exception ignored) {
                    return;
                }
                Trust.install(prefs);
                showCertStatus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) onMic();
        if (code == 3 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleHandsFree();
    }

    // ------------------------------------------------------------------ small helpers

    private EditText field(LinearLayout box, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine();
        box.addView(e);
        return e;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button flat(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
