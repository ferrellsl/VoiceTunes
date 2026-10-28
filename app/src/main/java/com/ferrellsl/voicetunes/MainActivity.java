package com.ferrellsl.voicetunes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
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

    private static final String DEFAULT_SERVER = "http://192.168.2.166:4533";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private Subsonic server;
    private volatile Library library;
    private MediaController player;
    private ListenableFuture<MediaController> playerFuture;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, listening, resumeAfter;

    private Button mic, playPause;
    private TextView status, heard, nowTitle, nowArtist;
    private ImageView art;

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("voicetunes", MODE_PRIVATE);
        buildScreen();
        if (prefs.getBoolean("screenOn", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tts = new TextToSpeech(this, s -> ttsReady = s == TextToSpeech.SUCCESS);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 2);
        }
        makeServer();
        if (server == null) {
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
            } catch (Exception e) {
                status.setText("Can't start the player: " + e.getMessage());
            }
        }, MoreExecutors.directExecutor());
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

    private void buildScreen() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 20, 24));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("VoiceTunes", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = flat("⚙");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings);
        root.addView(top);

        status = text("", 14, Color.rgb(150, 160, 170));
        root.addView(status);

        mic = new Button(this) {
            @Override protected void onMeasure(int w, int h) {      // a round button as big as fits
                int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
                int s = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
                super.onMeasure(s, s);
            }
        };
        mic.setAllCaps(false);
        mic.setTextColor(Color.WHITE);
        mic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        setMicIdle();
        mic.setOnClickListener(v -> onMic());
        LinearLayout middle = new LinearLayout(this);
        middle.setGravity(Gravity.CENTER);
        middle.addView(mic, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, 0, 1);
        mp.setMargins(0, dp(12), 0, dp(12));
        root.addView(middle, mp);

        heard = text("", 18, Color.rgb(255, 213, 79));
        heard.setGravity(Gravity.CENTER);
        root.addView(heard);
        // what's playing: its picture (the one in the file, if it has one), title and artist
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setPadding(0, dp(12), 0, 0);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(8));
        frame.setColor(Color.rgb(40, 46, 52));
        art.setBackground(frame);
        art.setVisibility(View.GONE);
        now.addView(art, new LinearLayout.LayoutParams(dp(120), dp(120)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        nowTitle = text("", 22, Color.WHITE);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(nowTitle);
        nowArtist = text("", 17, Color.rgb(180, 190, 200));
        words.addView(nowArtist);
        now.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(now);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(12), 0, 0);
        Button prev = flat("⏮"), next = flat("⏭");
        playPause = flat("▶");
        prev.setOnClickListener(v -> { if (player != null) player.seekToPrevious(); });
        playPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
        });
        next.setOnClickListener(v -> { if (player != null) player.seekToNextMediaItem(); });
        for (Button b : new Button[] { prev, playPause, next }) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
            controls.addView(b, new LinearLayout.LayoutParams(0, dp(72), 1));
        }
        root.addView(controls);
        setContentView(root);
    }

    private void setMicIdle() {
        mic.setText("🎤\nTap and say\nwhat to play");
        mic.setBackground(circle(Color.rgb(30, 136, 229)));
    }

    private void setMicListening() {
        mic.setText("🎤\nListening…");
        mic.setBackground(circle(Color.rgb(229, 57, 53)));
    }

    private void showNowPlaying() {
        if (player == null) return;
        MediaMetadata m = player.getMediaMetadata();
        nowTitle.setText(m.title == null ? "" : m.title);
        nowArtist.setText(m.artist == null ? "" : m.artist);
        playPause.setText(player.isPlaying() ? "⏸" : "▶");
        showArt(m.artworkUri);
    }

    private Uri artShown;

    /** The picture for what's playing, fetched from the server once per song. */
    private void showArt(Uri uri) {
        if (uri == null) { artShown = null; art.setVisibility(View.GONE); return; }
        if (uri.equals(artShown)) return;
        artShown = uri;
        images.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
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
                if (got == null) { art.setVisibility(View.GONE); return; }
                art.setImageBitmap(got);
                art.setVisibility(View.VISIBLE);
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
        if (library == null) {
            say("The library isn't loaded yet.");
            return;
        }
        if (tts.isSpeaking()) tts.stop();
        // (the music would drown out what's said)
        resumeAfter = player != null && player.isPlaying();
        if (resumeAfter) player.pause();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        setMicListening();
        heard.setText("");
        recognizer.startListening(i);
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
            listening = false;
            setMicIdle();
            boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            heard.setText(nothing ? "Didn't catch that" : "Speech recognizer error " + error);
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
        Library lib = library;
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
                List<Matcher.Song> queue = queueFor(found);
                main.post(() -> {
                    if (queue.isEmpty()) { speakThen("There's nothing in " + found.describe() + ".", null); return; }
                    String what = found.kind == Matcher.Kind.SONG ? found.describe()
                        : found.describe() + (found.shuffle || found.kind == Matcher.Kind.ARTIST ? ", shuffled" : "");
                    speakThen("Playing " + what + ".", () -> play(queue));
                });
            } catch (Exception e) {
                main.post(() -> speakThen("I can't reach the music server.", null));
            }
        });
    }

    /** The songs to play for what was found. A song is followed by the rest of its album. */
    private List<Matcher.Song> queueFor(Matcher.Result f) throws Exception {
        List<Matcher.Song> q;
        switch (f.kind) {
            case PLAYLIST:
                q = server.playlistSongs(f.item.id);
                break;
            case ALBUM:
                q = server.albumSongs(f.item.id);
                break;
            case ARTIST:
                return library.songsBy(f.item.name);
            default:
                q = new ArrayList<>();
                q.add(f.song);
                try {
                    List<Matcher.Song> album = server.albumSongs(f.song.albumId);
                    int at = -1;
                    for (int i = 0; i < album.size(); i++) if (album.get(i).id.equals(f.song.id)) at = i;
                    if (at >= 0) q.addAll(album.subList(at + 1, album.size()));
                } catch (Exception ignored) {
                    // (just the song, then)
                }
                return q;
        }
        if (f.shuffle) Collections.shuffle(q);
        return q;
    }

    private void play(List<Matcher.Song> songs) {
        if (player == null) return;
        int rate = prefs.getInt("bitrate", 0);
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Matcher.Song s : songs) {
            items.add(new MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(server.streamUrl(s.id, rate))
                .setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                    .setArtworkUri(Uri.parse(server.coverUrl(s.coverArt))).build())
                .build());
        }
        player.setShuffleModeEnabled(false);
        player.setMediaItems(items, 0, 0);
        player.prepare();
        player.play();
    }

    private void control(Matcher.Control c) {
        boolean wasPlaying = resumeAfter;
        resumeAfter = false;
        if (player == null) return;
        switch (c) {
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
        status.setText(s);
        if (!prefs.getBoolean("speak", true) || !ttsReady) {
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

    private void makeServer() {
        String token = prefs.getString("token", "");
        server = token.isEmpty() ? null : new Subsonic(prefs.getString("server", DEFAULT_SERVER),
            prefs.getString("user", ""), token, prefs.getString("salt", ""));
    }

    private File libraryFile() { return new File(getFilesDir(), "library.json"); }

    /** The library from the phone's copy, fetched from the server when there's none, it's a
     *  day old, or `fresh` is asked for. */
    private void loadLibrary(boolean fresh) {
        if (server == null) return;
        Subsonic s = server;
        status.setText(fresh ? "Getting the library from the server…" : "Loading the library…");
        work.execute(() -> {
            Library lib = fresh ? null : Library.load(libraryFile());
            if (lib != null) {
                Library ready = lib;
                main.post(() -> { library = ready; status.setText(ready.summary()); });
                if (!lib.stale()) return;
            }
            try {
                if (lib == null) main.post(() -> status.setText("Getting the library from the server (the first time takes a minute)…"));
                Library got = Library.fetch(s);
                got.save(libraryFile());
                main.post(() -> {
                    library = got;
                    status.setText(got.summary());
                    if (fresh) say("The library is up to date.");
                });
            } catch (Exception e) {
                main.post(() -> status.setText((library == null ? "Can't get the library: " : "Using the saved library; the server said: ") + e.getMessage()));
            }
        });
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText url = field(box, "Navidrome address", prefs.getString("server", DEFAULT_SERVER), InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field(box, "User name", prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(box, prefs.getString("token", "").isEmpty() ? "Password" : "Password (leave empty to keep)", "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText rate = field(box, "Highest bit rate in kbps, for mobile data (0 = as stored)",
            Integer.toString(prefs.getInt("bitrate", 0)), InputType.TYPE_CLASS_NUMBER);
        CheckBox speak = new CheckBox(this);
        speak.setText("Say what's going to play");
        speak.setChecked(prefs.getBoolean("speak", true));
        box.addView(speak);
        CheckBox screen = new CheckBox(this);
        screen.setText("Keep the screen on while VoiceTunes is open");
        screen.setChecked(prefs.getBoolean("screenOn", true));
        box.addView(screen);
        Button refresh = new Button(this);
        refresh.setText("Get the library again now");
        box.addView(refresh);

        AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        refresh.setOnClickListener(v -> { d.dismiss(); loadLibrary(true); });
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit();
            boolean serverChanged = !url.getText().toString().trim().equals(prefs.getString("server", ""))
                || !user.getText().toString().trim().equals(prefs.getString("user", ""));
            e.putString("server", url.getText().toString().trim());
            e.putString("user", user.getText().toString().trim());
            if (pass.getText().length() > 0) {
                String[] t = Subsonic.tokenFor(pass.getText().toString());
                e.putString("token", t[0]).putString("salt", t[1]);
                serverChanged = true;
            }
            try { e.putInt("bitrate", Integer.parseInt(rate.getText().toString().trim())); } catch (NumberFormatException ignored) {}
            e.putBoolean("speak", speak.isChecked());
            e.putBoolean("screenOn", screen.isChecked());
            e.apply();
            if (screen.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            makeServer();
            if (server == null) { status.setText("Enter your user name and password."); return; }
            d.dismiss();
            if (serverChanged) {
                Subsonic s = server;
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

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) onMic();
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
