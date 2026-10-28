package com.ferrellsl.voicetunes;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the phone screen and the player service (and through it Android Auto) share: the
 * settings, the server, the library, and turning what was asked for into songs to play.
 */
public final class Music {

    public static final String DEFAULT_SERVER = "http://192.168.2.166:4533";

    private static Music instance;

    public static synchronized Music get(Context c) {
        if (instance == null) instance = new Music(c.getApplicationContext());
        return instance;
    }

    public final SharedPreferences prefs;
    public final File libraryFile;
    public volatile Subsonic server;
    public volatile Library library;
    private Map<String, Matcher.Song> byId;
    private Library byIdFor;

    private Music(Context c) {
        prefs = c.getSharedPreferences("voicetunes", Context.MODE_PRIVATE);
        libraryFile = new File(c.getFilesDir(), "library.json");
        reloadServer();
    }

    public void reloadServer() {
        String token = prefs.getString("token", "");
        server = token.isEmpty() ? null : new Subsonic(prefs.getString("server", DEFAULT_SERVER),
            prefs.getString("user", ""), token, prefs.getString("salt", ""));
    }

    /** The library, from the phone's copy if it isn't loaded yet (off the main thread). */
    public Library library() {
        Library lib = library;
        if (lib == null) {
            lib = Library.load(libraryFile);
            if (lib != null && library == null) library = lib;
        }
        return library;
    }

    public synchronized Matcher.Song song(String id) {
        Library lib = library();
        if (lib == null) return null;
        if (byIdFor != lib) {
            byId = new HashMap<>();
            for (Matcher.Song s : lib.songs) byId.put(s.id, s);
            byIdFor = lib;
        }
        return byId.get(id);
    }

    // ------------------------------------------------------------------ what to play

    /** The best thing in the library for what was said, or null. */
    public Matcher.Result find(List<String> alternatives) {
        Library lib = library();
        if (lib == null) return null;
        Matcher.Result best = null;
        for (String a : alternatives) {
            Matcher.Request q = Matcher.parse(a);
            if (q.control != Matcher.Control.NONE) continue;
            Matcher.Result f = lib.matcher.find(q);
            if (f != null && (best == null || f.score > best.score + 0.02)) best = f;
        }
        return best == null || best.score < Matcher.ACCEPT ? null : best;
    }

    /** Whether playlists play shuffled without being asked (the "Shuffle playlists" setting). */
    public boolean shufflePlaylists() { return prefs.getBoolean("shufflePlaylists", true); }

    /** Whether what was found will play shuffled. */
    public boolean shuffled(Matcher.Result f) {
        return f.shuffle || f.kind == Matcher.Kind.ARTIST || (f.kind == Matcher.Kind.PLAYLIST && shufflePlaylists());
    }

    /** A playlist's songs, shuffled when the setting says so. */
    public List<Matcher.Song> playlist(String id) throws IOException {
        List<Matcher.Song> q = server.playlistSongs(id);
        if (shufflePlaylists()) Collections.shuffle(q);
        return q;
    }

    /** The songs to play for what was found. A song is followed by the rest of its album. */
    public List<Matcher.Song> queueFor(Matcher.Result f) throws IOException {
        List<Matcher.Song> q;
        switch (f.kind) {
            case PLAYLIST:
                q = playlist(f.item.id);
                break;
            case ALBUM:
                q = server.albumSongs(f.item.id);
                break;
            case ARTIST:
                return library().songsBy(f.item.name);
            default:
                return songThenAlbum(f.song);
        }
        if (f.shuffle) Collections.shuffle(q);
        return q;
    }

    public List<Matcher.Song> songThenAlbum(Matcher.Song song) {
        List<Matcher.Song> q = new ArrayList<>();
        q.add(song);
        try {
            List<Matcher.Song> album = server.albumSongs(song.albumId);
            int at = -1;
            for (int i = 0; i < album.size(); i++) if (album.get(i).id.equals(song.id)) at = i;
            if (at >= 0) q.addAll(album.subList(at + 1, album.size()));
        } catch (IOException ignored) {
            // (just the song, then)
        }
        return q;
    }

    /** Some of everything, shuffled. */
    public List<Matcher.Song> shuffleAll(int count) {
        List<Matcher.Song> all = new ArrayList<>(library().songs);
        Collections.shuffle(all);
        return new ArrayList<>(all.subList(0, Math.min(count, all.size())));
    }

    // ------------------------------------------------------------------ for the player

    public MediaItem playable(Matcher.Song s) {
        return new MediaItem.Builder()
            .setMediaId("song:" + s.id)
            .setUri(server.streamUrl(s.id, prefs.getInt("bitrate", 0)))
            .setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                .setArtworkUri(Uri.parse(server.coverUrl(s.coverArt)))
                .setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .build();
    }

    public List<MediaItem> playable(List<Matcher.Song> songs) {
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Matcher.Song s : songs) items.add(playable(s));
        return items;
    }
}
