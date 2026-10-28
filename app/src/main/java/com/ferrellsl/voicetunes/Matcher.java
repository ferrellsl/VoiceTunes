package com.ferrellsl.voicetunes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what was said ("play the playlist Cook Islands", "Dreams I'll Never See by Molly
 * Hatchet", "shuffle 38 Special", "next") into what to play from the library. Plain Java, no
 * Android, so it can be tried on a PC against a real library (tools/MatchTest.java).
 *
 * Matching is loose on purpose: speech recognizers drop apostrophes, spell numbers out or not,
 * hear "Cook Island" for "Cook Islands" and leave out "the"; names in a library carry extras
 * like "(Remastered 2009)".
 */
public final class Matcher {

    public enum Kind { SONG, ALBUM, ARTIST, PLAYLIST }

    public enum Control { NONE, NEXT, PREVIOUS, PAUSE, RESUME, STOP, REFRESH }

    public static final class Song {
        public final String id, title, artist, album, albumId;
        final String nTitle, nArtist;
        public Song(String id, String title, String artist, String album, String albumId) {
            this.id = id; this.title = title; this.artist = artist; this.album = album; this.albumId = albumId;
            nTitle = normalize(stripExtras(title));
            nArtist = normalize(artist);
        }
    }

    public static final class Item {
        public final Kind kind;
        public final String id, name, artist;
        public final int count;
        final String nName, nArtist;
        public Item(Kind kind, String id, String name, String artist, int count) {
            this.kind = kind; this.id = id; this.name = name; this.artist = artist; this.count = count;
            nName = normalize(stripExtras(name));
            nArtist = artist == null ? "" : normalize(artist);
        }
    }

    /** What was asked for. */
    public static final class Request {
        public Control control = Control.NONE;
        public Kind kind;            // null: whichever fits best
        public String text = "";     // the name asked for
        public String byArtist;      // "... by <artist>"
        String byWord = "by";        // ("by" or "from", to put the phrase back together)
        public boolean shuffle;
        @Override public String toString() {
            return control != Control.NONE ? control.toString()
                : (shuffle ? "shuffle " : "") + (kind == null ? "any" : kind) + " '" + text + "'"
                  + (byArtist != null ? " by '" + byArtist + "'" : "");
        }
    }

    /** What was found. */
    public static final class Result {
        public final Kind kind;
        public final Item item;      // album, artist or playlist
        public final Song song;      // a song
        public final double score;
        public final boolean shuffle;
        Result(Kind kind, Item item, Song song, double score, boolean shuffle) {
            this.kind = kind; this.item = item; this.song = song; this.score = score; this.shuffle = shuffle;
        }
        public String describe() {
            switch (kind) {
                case SONG: return song.title + " by " + song.artist;
                case PLAYLIST: return "the playlist " + item.name;
                case ALBUM: return "the album " + item.name + (item.artist != null && !item.artist.isEmpty() ? " by " + item.artist : "");
                default: return item.name;
            }
        }
        @Override public String toString() { return kind + " " + describe() + String.format(Locale.US, " (%.2f)", score); }
    }

    /** Below this the best match is not good enough to play. */
    public static final double ACCEPT = 0.62;

    private final List<Song> songs;
    private final List<Item> albums, artists, playlists;

    public Matcher(List<Song> songs, List<Item> albums, List<Item> artists, List<Item> playlists) {
        this.songs = songs; this.albums = albums; this.artists = artists; this.playlists = playlists;
    }

    // ------------------------------------------------------------------ what was said

    private static final String[][] CONTROLS = {
        {"next", "NEXT"}, {"next song", "NEXT"}, {"skip", "NEXT"}, {"skip it", "NEXT"}, {"skip this", "NEXT"},
        {"skip song", "NEXT"}, {"skip this song", "NEXT"}, {"next track", "NEXT"},
        {"previous", "PREVIOUS"}, {"previous song", "PREVIOUS"}, {"go back", "PREVIOUS"}, {"back", "PREVIOUS"},
        {"last song", "PREVIOUS"}, {"play the last song", "PREVIOUS"}, {"previous track", "PREVIOUS"},
        {"pause", "PAUSE"}, {"pause the music", "PAUSE"}, {"pause music", "PAUSE"},
        {"stop", "STOP"}, {"stop the music", "STOP"}, {"stop music", "STOP"}, {"stop playing", "STOP"},
        {"resume", "RESUME"}, {"continue", "RESUME"}, {"play", "RESUME"}, {"unpause", "RESUME"},
        {"resume music", "RESUME"}, {"keep playing", "RESUME"},
        {"refresh", "REFRESH"}, {"refresh library", "REFRESH"}, {"update library", "REFRESH"},
    };

    public static Request parse(String spoken) {
        Request r = new Request();
        String s = spoken.trim().toLowerCase(Locale.US).replaceAll("[.!?,]+$", "").trim();
        String plain = normalize(s);
        for (String[] c : CONTROLS) {
            if (plain.equals(normalize(c[0]))) {
                r.control = Control.valueOf(c[1]);
                return r;
            }
        }
        // leading verbs and fillers
        s = s.replaceFirst("^(hey |ok |okay )?(please )?", "");
        if (s.matches("^(shuffle|mix|randomly play|play shuffled)\\b.*")) {
            r.shuffle = true;
            s = s.replaceFirst("^(shuffle|mix|randomly play|play shuffled)\\s*", "");
        }
        s = s.replaceFirst("^(play|put on|start|listen to|i want to hear|let me hear|i wanna hear)\\s+", "");
        s = s.replaceFirst("^(me\\s+)?(some\\s+)?", "");
        if (s.matches("^(shuffle|mixed|shuffled)\\s.*")) {
            r.shuffle = true;
            s = s.replaceFirst("^(shuffle|mixed|shuffled)\\s+", "");
        }
        if (s.matches(".*\\s(on shuffle|shuffled|in shuffle|randomly|in random order)$")) {
            r.shuffle = true;
            s = s.replaceFirst("\\s(on shuffle|shuffled|in shuffle|randomly|in random order)$", "");
        }
        // kind
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("^(the |my )?play ?list (called |named )?(.+)$").matcher(s)).matches()) {
            r.kind = Kind.PLAYLIST; s = m.group(3);
        } else if ((m = java.util.regex.Pattern.compile("^(.+?) play ?list$").matcher(s)).matches()) {
            r.kind = Kind.PLAYLIST; s = m.group(1).replaceFirst("^(the|my) ", "");
        } else if ((m = java.util.regex.Pattern.compile("^(the )?album (called |named )?(.+)$").matcher(s)).matches()) {
            r.kind = Kind.ALBUM; s = m.group(3);
        } else if ((m = java.util.regex.Pattern.compile("^(the )?(artist|band|group) (.+)$").matcher(s)).matches()) {
            r.kind = Kind.ARTIST; s = m.group(3);
        } else if ((m = java.util.regex.Pattern.compile("^(songs|music|anything|something|stuff|tracks|hits) (by|from) (.+)$").matcher(s)).matches()) {
            r.kind = Kind.ARTIST; s = m.group(3);
        } else if ((m = java.util.regex.Pattern.compile("^(the )?song (called |named )?(.+)$").matcher(s)).matches()) {
            r.kind = Kind.SONG; s = m.group(3);
        }
        if ((r.kind == null || r.kind == Kind.SONG || r.kind == Kind.ALBUM)
                && (m = java.util.regex.Pattern.compile("^(.+?) (by|from) (.+)$").matcher(s)).matches()) {
            r.text = m.group(1);
            r.byWord = m.group(2);
            r.byArtist = m.group(3);
        } else {
            r.text = s;
        }
        return r;
    }

    // ------------------------------------------------------------------ finding it

    /** The best match for one of the recognizer's alternatives, or null. */
    public Result find(Request r) {
        if (r.control != Control.NONE || r.text.isEmpty()) return null;
        String q = normalize(stripExtras(r.text));
        if (q.isEmpty()) return null;
        String by = r.byArtist == null ? null : normalize(r.byArtist);
        List<Result> found = new ArrayList<>();

        // ("<x> by <y>" names a song or an album by an artist, so playlists and artists are only
        // looked for in the whole phrase, below)
        if (by == null && (r.kind == null || r.kind == Kind.PLAYLIST)) {
            Result b = best(Kind.PLAYLIST, playlists, q, null, r.shuffle);
            if (b != null) found.add(b);
        }
        if (by == null && (r.kind == null || r.kind == Kind.ARTIST)) {
            Result b = best(Kind.ARTIST, artists, q, null, true);
            if (b != null) found.add(b);
        }
        if (r.kind == null || r.kind == Kind.ALBUM) {
            Result b = best(Kind.ALBUM, albums, q, by, r.shuffle);
            if (b != null) found.add(b);
        }
        if (r.kind == null || r.kind == Kind.SONG) {
            Result b = bestSong(q, by, r.shuffle);
            if (b != null) found.add(b);
        }
        // "by"/"from" may also be part of the name ("Stand by Me", "Away from the Sun"): the whole
        // phrase is tried too, and whichever reading fits better wins
        if (by != null) {
            Request whole = new Request();
            whole.kind = r.kind; whole.text = r.text + " " + r.byWord + " " + r.byArtist; whole.shuffle = r.shuffle;
            Result w = find(whole);
            if (w != null) found.add(w);
        }
        Result best = null;
        for (Result f : found) {
            if (best == null || rank(f) > rank(best)) best = f;
        }
        return best;
    }

    /** Ties go to playlists, then artists, then songs, then albums: a playlist or artist named
     *  exactly is what's usually meant, and a song named like its album ("Hotel California")
     *  plays with the rest of that album after it anyway. */
    private static double rank(Result f) {
        switch (f.kind) {
            case PLAYLIST: return f.score + 0.03;
            case ARTIST: return f.score + 0.02;
            case SONG: return f.score + 0.01;
            default: return f.score;
        }
    }

    private Result best(Kind kind, List<Item> items, String q, String by, boolean shuffle) {
        Item bestItem = null;
        double bestScore = 0;
        for (Item it : items) {
            double s = similarity(q, it.nName);
            if (by != null) s = 0.65 * s + 0.35 * similarity(by, it.nArtist);
            if (s > bestScore) { bestScore = s; bestItem = it; }
        }
        return bestItem == null ? null : new Result(kind, bestItem, null, bestScore, shuffle);
    }

    private Result bestSong(String q, String by, boolean shuffle) {
        Song bestSong = null;
        double bestScore = 0;
        for (Song s : songs) {
            double t = similarity(q, s.nTitle);
            if (by != null) t = 0.65 * t + 0.35 * similarity(by, s.nArtist);
            if (t > bestScore) { bestScore = t; bestSong = s; }
        }
        return bestSong == null ? null : new Result(Kind.SONG, null, bestSong, bestScore, shuffle);
    }

    // ------------------------------------------------------------------ text

    /** Drops the extras names carry: "(Remastered 2009)", "[Live]", " - Single Version". */
    static String stripExtras(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s*[(\\[{][^)\\]}]*[)\\]}]", " ")
                    .replaceAll("\\s+-\\s+(\\d{4} )?(remaster(ed)?|single|radio|album|live|mono|stereo|edit|version|mix)\\b.*$", "");
        t = t.trim();
        return t.isEmpty() ? s : t;
    }

    private static final Map<String, Integer> NUMBERS = new HashMap<>();
    static {
        String[] ones = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < ones.length; i++) NUMBERS.put(ones[i], i);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) NUMBERS.put(tens[i], 20 + 10 * i);
    }

    /** Lower case, no accents or punctuation, "&" as "and", numbers as digits ("thirty eight" ->
     *  "38", "nineteen sixty one" -> "1961"), no leading "the". */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.US).replace("&", " and ").replace("'", "").replace("’", "")
             .replaceAll("[^a-z0-9]+", " ").trim();
        String[] w = t.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            String word = w[i];
            Integer n = NUMBERS.get(word);
            if (n != null) {
                int value = n;
                // twenty one, thirty eight
                if (value >= 20 && i + 1 < w.length && NUMBERS.containsKey(w[i + 1]) && NUMBERS.get(w[i + 1]) < 10) {
                    value += NUMBERS.get(w[++i]);
                }
                // nineteen sixty one -> 1961; two thousand -> left as words
                if (value >= 10 && value < 100 && i + 1 < w.length && NUMBERS.containsKey(w[i + 1])
                        && NUMBERS.get(w[i + 1]) >= 10) {
                    int next = NUMBERS.get(w[++i]);
                    if (next >= 20 && i + 1 < w.length && NUMBERS.containsKey(w[i + 1]) && NUMBERS.get(w[i + 1]) < 10) {
                        next += NUMBERS.get(w[++i]);
                    }
                    value = value * 100 + next;
                }
                word = Integer.toString(value);
            }
            if (out.length() == 0 && word.equals("the") && i + 1 < w.length) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.toString();
    }

    /** How alike two normalized names are, 0..1: words matched loosely in both directions, and
     *  the whole strings compared, so missing or extra words and misheard ones cost a little. */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        String sa = a.replace(" ", ""), sb = b.replace(" ", "");
        if (sa.equals(sb)) return 0.98;                      // "38 special" / "38special"
        String[] wa = a.split(" "), wb = b.split(" ");
        double inA = 0, inB = 0;
        for (String x : wa) inA += bestWord(x, wb);
        for (String y : wb) inB += bestWord(y, wa);
        double recall = inA / wa.length;                     // how much of what was said is there
        double precision = inB / wb.length;                  // how much of the name was said
        double words = (recall + precision) == 0 ? 0 : 2 * recall * precision / (recall + precision);
        double whole = 1 - (double) levenshtein(sa, sb) / Math.max(sa.length(), sb.length());
        return 0.75 * words + 0.25 * whole;
    }

    private static double bestWord(String w, String[] in) {
        double best = 0;
        for (String x : in) {
            if (x.equals(w)) return 1;
            int max = Math.max(w.length(), x.length());
            double r = 1 - (double) levenshtein(w, x) / max;
            // a plural or a dropped letter in a longer word is nearly the same word
            if (r > best) best = r;
        }
        return best >= 0.75 ? best : 0;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
