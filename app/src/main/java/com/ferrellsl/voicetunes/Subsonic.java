package com.ferrellsl.voicetunes;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The few calls of the Subsonic API (as Navidrome serves it) the app needs. Logins use the API's
 * token form: md5(password + salt) with a random salt, worked out once when the settings are
 * saved, so the password itself is never stored or sent.
 */
public final class Subsonic {

    public static final class ServerError extends IOException {
        public ServerError(String message) { super(message); }
    }

    private final String base, user, token, salt;

    public Subsonic(String server, String user, String token, String salt) {
        String b = server.trim();
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b; this.user = user.trim(); this.token = token; this.salt = salt;
    }

    /** A new salt and the token for a password. */
    public static String[] tokenFor(String password) {
        byte[] r = new byte[8];
        new SecureRandom().nextBytes(r);
        String salt = hex(r);
        return new String[] { md5(password + salt), salt };
    }

    // ------------------------------------------------------------------ calls

    public void ping() throws IOException { call("ping", ""); }

    public List<Matcher.Item> playlists() throws IOException {
        JSONObject r = call("getPlaylists", "");
        List<Matcher.Item> out = new ArrayList<>();
        JSONArray a = array(r.optJSONObject("playlists"), "playlist");
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            out.add(new Matcher.Item(Matcher.Kind.PLAYLIST, p.optString("id"), p.optString("name"), null, p.optInt("songCount")));
        }
        return out;
    }

    public List<Matcher.Item> artists() throws IOException {
        JSONObject r = call("getArtists", "");
        List<Matcher.Item> out = new ArrayList<>();
        JSONArray idx = array(r.optJSONObject("artists"), "index");
        for (int i = 0; i < idx.length(); i++) {
            JSONArray a = array(idx.optJSONObject(i), "artist");
            for (int j = 0; j < a.length(); j++) {
                JSONObject x = a.optJSONObject(j);
                out.add(new Matcher.Item(Matcher.Kind.ARTIST, x.optString("id"), x.optString("name"), null, x.optInt("albumCount")));
            }
        }
        return out;
    }

    public List<Matcher.Item> albums() throws IOException {
        List<Matcher.Item> out = new ArrayList<>();
        for (int offset = 0; ; offset += 500) {
            JSONObject r = call("getAlbumList2", "&type=alphabeticalByName&size=500&offset=" + offset);
            JSONArray a = array(r.optJSONObject("albumList2"), "album");
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                out.add(new Matcher.Item(Matcher.Kind.ALBUM, x.optString("id"), x.optString("name"), x.optString("artist"), x.optInt("songCount")));
            }
            if (a.length() < 500) return out;
        }
    }

    /** Every song (Navidrome answers an empty search with the whole library, a page at a time). */
    public List<Matcher.Song> songs() throws IOException {
        List<Matcher.Song> out = new ArrayList<>();
        for (int offset = 0; ; offset += 500) {
            JSONObject r = call("search3", "&query=%22%22&artistCount=0&albumCount=0&songCount=500&songOffset=" + offset);
            JSONArray a = array(r.optJSONObject("searchResult3"), "song");
            for (int i = 0; i < a.length(); i++) out.add(song(a.optJSONObject(i)));
            if (a.length() < 500) return out;
        }
    }

    public List<Matcher.Song> playlistSongs(String id) throws IOException {
        return songs(array(call("getPlaylist", "&id=" + Uri.encode(id)).optJSONObject("playlist"), "entry"));
    }

    public List<Matcher.Song> albumSongs(String id) throws IOException {
        return songs(array(call("getAlbum", "&id=" + Uri.encode(id)).optJSONObject("album"), "song"));
    }

    /** Where a song streams from; maxBitRate 0 = as it is, otherwise transcoded down to it. */
    public String streamUrl(String id, int maxBitRate) {
        return base + "/rest/stream.view?" + auth() + "&id=" + Uri.encode(id)
            + (maxBitRate > 0 ? "&maxBitRate=" + maxBitRate + "&format=mp3" : "");
    }

    public String coverUrl(String id) {
        return base + "/rest/getCoverArt.view?" + auth() + "&size=600&id=" + Uri.encode(id);
    }

    // ------------------------------------------------------------------ plumbing

    private static Matcher.Song song(JSONObject s) {
        return new Matcher.Song(s.optString("id"), s.optString("title"), s.optString("artist"),
            s.optString("album"), s.optString("albumId"));
    }

    private static List<Matcher.Song> songs(JSONArray a) {
        List<Matcher.Song> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(song(a.optJSONObject(i)));
        return out;
    }

    private static JSONArray array(JSONObject o, String key) {
        if (o == null) return new JSONArray();
        JSONArray a = o.optJSONArray(key);
        if (a != null) return a;
        JSONObject one = o.optJSONObject(key);           // (a single item may come unwrapped)
        JSONArray w = new JSONArray();
        if (one != null) w.put(one);
        return w;
    }

    private String auth() {
        return "u=" + Uri.encode(user) + "&t=" + token + "&s=" + salt + "&v=1.16.1&c=VoiceTunes";
    }

    private JSONObject call(String method, String params) throws IOException {
        URL url = new URL(base + "/rest/" + method + ".view?" + auth() + "&f=json" + params);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONObject r = new JSONObject(b.toString("UTF-8")).getJSONObject("subsonic-response");
            if (!"ok".equals(r.optString("status"))) {
                JSONObject e = r.optJSONObject("error");
                throw new ServerError(e != null ? e.optString("message") : "the server refused");
            }
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException("not a Subsonic server: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    private static String md5(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
