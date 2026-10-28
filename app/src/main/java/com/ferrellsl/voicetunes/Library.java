package com.ferrellsl.voicetunes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The names in the library (songs, albums, artists, playlists), kept on the phone so that what
 * was said is matched at once, without asking the server. Fetched from the server when there's
 * none yet, when asked to ("refresh library"), and once a day.
 */
public final class Library {

    public final List<Matcher.Song> songs;
    public final List<Matcher.Item> albums, artists, playlists;
    public final long fetched;          // System.currentTimeMillis() when fetched
    public final Matcher matcher;

    private Library(List<Matcher.Song> songs, List<Matcher.Item> albums, List<Matcher.Item> artists,
                    List<Matcher.Item> playlists, long fetched) {
        this.songs = songs; this.albums = albums; this.artists = artists; this.playlists = playlists;
        this.fetched = fetched;
        matcher = new Matcher(songs, albums, artists, playlists);
    }

    public boolean stale() { return System.currentTimeMillis() - fetched > 24L * 3600 * 1000; }

    public String summary() {
        return String.format(java.util.Locale.US, "%,d songs, %,d albums, %,d artists, %d playlists",
            songs.size(), albums.size(), artists.size(), playlists.size());
    }

    /** Every song by an artist, under any spelling the library has of the name ("Eagles" and
     *  "The Eagles"), in random order. */
    public List<Matcher.Song> songsBy(String artist) {
        String n = Matcher.normalize(artist);
        List<Matcher.Song> out = new ArrayList<>();
        for (Matcher.Song s : songs) {
            if (Matcher.normalize(s.artist).equals(n)) out.add(s);
        }
        Collections.shuffle(out);
        return out;
    }

    public static Library fetch(Subsonic server) throws IOException {
        return new Library(server.songs(), server.albums(), server.artists(), server.playlists(),
            System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ the copy on the phone

    public void save(File f) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("fetched", fetched);
            JSONArray s = new JSONArray();
            for (Matcher.Song x : songs) s.put(new JSONArray().put(x.id).put(x.title).put(x.artist).put(x.album).put(x.albumId));
            o.put("songs", s);
            o.put("albums", items(albums));
            o.put("artists", items(artists));
            o.put("playlists", items(playlists));
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new IOException("can't save " + f);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    public static Library load(File f) {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray s = o.getJSONArray("songs");
            List<Matcher.Song> songs = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                JSONArray x = s.getJSONArray(i);
                songs.add(new Matcher.Song(x.getString(0), x.getString(1), x.getString(2), x.getString(3), x.getString(4)));
            }
            return new Library(songs, items(o.getJSONArray("albums"), Matcher.Kind.ALBUM),
                items(o.getJSONArray("artists"), Matcher.Kind.ARTIST),
                items(o.getJSONArray("playlists"), Matcher.Kind.PLAYLIST), o.getLong("fetched"));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray items(List<Matcher.Item> list) {
        JSONArray a = new JSONArray();
        for (Matcher.Item x : list) {
            a.put(new JSONArray().put(x.id).put(x.name).put(x.artist == null ? "" : x.artist).put(x.count));
        }
        return a;
    }

    private static List<Matcher.Item> items(JSONArray a, Matcher.Kind kind) throws org.json.JSONException {
        List<Matcher.Item> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONArray x = a.getJSONArray(i);
            String artist = x.getString(2);
            out.add(new Matcher.Item(kind, x.getString(0), x.getString(1), artist.isEmpty() ? null : artist, x.getInt(3)));
        }
        return out;
    }
}
