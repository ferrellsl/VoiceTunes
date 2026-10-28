import com.ferrellsl.voicetunes.Matcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Tries the voice matching on a PC against a real library exported as tab-separated lines
 * (kind, id, name, artist, album, albumId, count), then runs the phrases given one per line in a
 * second file and prints what each would play.
 *   javac -d out ../app/src/main/java/com/ferrellsl/voicetunes/Matcher.java MatchTest.java
 *   java -cp out MatchTest library.tsv phrases.txt
 */
public class MatchTest {
    public static void main(String[] args) throws Exception {
        List<Matcher.Song> songs = new ArrayList<>();
        List<Matcher.Item> albums = new ArrayList<>(), artists = new ArrayList<>(), playlists = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "song": songs.add(new Matcher.Song(f[1], f[2], f[3], f[4], f[5])); break;
                case "album": albums.add(new Matcher.Item(Matcher.Kind.ALBUM, f[1], f[2], f[3], 0)); break;
                case "artist": artists.add(new Matcher.Item(Matcher.Kind.ARTIST, f[1], f[2], null, 0)); break;
                case "playlist": playlists.add(new Matcher.Item(Matcher.Kind.PLAYLIST, f[1], f[2], null, Integer.parseInt(f[6]))); break;
            }
        }
        Matcher m = new Matcher(songs, albums, artists, playlists);
        for (String phrase : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (phrase.isBlank()) continue;
            long t0 = System.nanoTime();
            Matcher.Request r = Matcher.parse(phrase);
            Matcher.Result res = m.find(r);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%-48s -> %-40s => %s%s  [%d ms]%n", phrase, r,
                res == null ? "-" : res, res != null && res.score < Matcher.ACCEPT ? "  (rejected)" : "", ms);
        }
    }
}
