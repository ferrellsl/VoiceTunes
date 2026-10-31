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
 *
 * There can be two addresses for the one server: the home one, and an "away" one (HTTPS through
 * the router) for when the phone isn't on the home network. Calls use whichever answered last
 * and try the other when it doesn't answer; song and picture addresses are rewritten to it when
 * they're fetched (resolve), so a queue made at home keeps playing on the road.
 */
public final class Subsonic {

    public static final class ServerError extends IOException {
        public ServerError(String message) { super(message); }
    }

    private final String home, away, user, token, salt;
    private volatile String base;

    /** away: null or empty when there's only the home address. */
    public Subsonic(String server, String away, String user, String token, String salt) {
        this.home = address(server);
        this.away = away == null || away.trim().isEmpty() ? null : address(away);
        this.base = home; this.user = user.trim(); this.token = token; this.salt = salt;
    }

    private static String address(String server) {
        String b = server.trim();
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** The address in use now. */
    public String base() { return base; }

    /** A song or picture address made with either address, pointed at the one in use now. */
    public String resolve(String url) {
        String now = base;
        if (away != null && !now.equals(away) && url.startsWith(away + "/")) return now + url.substring(away.length());
        if (!now.equals(home) && url.startsWith(home + "/")) return now + url.substring(home.length());
        return url;
    }

    /** Works out which address answers (after the phone changes networks); off the main thread. */
    public void choose() {
        if (away == null) return;
        if (answers(home)) base = home;
        else if (answers(away)) base = away;
    }

    private boolean answers(String b) {
        try {
            open(b, "ping", "", 2500).disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
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

    public List<Matcher.Item> genres() throws IOException {
        JSONArray a = array(call("getGenres", "").optJSONObject("genres"), "genre");
        List<Matcher.Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject g = a.optJSONObject(i);
            String name = g.optString("value");
            if (!name.isEmpty()) out.add(new Matcher.Item(Matcher.Kind.GENRE, name, name, null, g.optInt("songCount")));
        }
        return out;
    }

    public List<Matcher.Song> genreSongs(String genre) throws IOException {
        return songs(array(call("getSongsByGenre", "&count=500&genre=" + Uri.encode(genre)).optJSONObject("songsByGenre"), "song"));
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
            s.optString("album"), s.optString("albumId"), s.optString("coverArt"));
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
        String now = base;
        HttpURLConnection c;
        try {
            c = open(now, method, params, away == null ? 8000 : 4000);
        } catch (IOException e) {
            if (away == null) throw e;
            String other = now.equals(home) ? away : home;    // (not answering: try the other address)
            c = open(other, method, params, 8000);
            base = other;
        }
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

    /** A connection that has answered (HTTP 200), or an IOException. */
    private HttpURLConnection open(String b, String method, String params, int connectTimeout) throws IOException {
        URL url = new URL(b + "/rest/" + method + ".view?" + auth() + "&f=json" + params);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("the server answered " + code);
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        return c;
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
