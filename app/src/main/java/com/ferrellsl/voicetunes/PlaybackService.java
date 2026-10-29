package com.ferrellsl.voicetunes;

import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CacheBitmapLoader;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;

/**
 * Plays the queue in the background, with the system's media notification and lock-screen
 * controls, and answers Bluetooth, headset and car buttons. The phone screen controls it through
 * a MediaController; Android Auto browses it (Playlists, Artists, Albums, Shuffle everything),
 * searches it - what was said is matched the same way as on the phone - and plays from it.
 *
 * Media ids: "root", "playlists", "artists", "artists/A", "albums", "albums/A", "shuffle",
 * "playlist:<id>", "artist:<name>", "album:<id>", "song:<id>", and for a song picked inside a
 * playlist or album "in:playlist:<id>:<n>" / "in:album:<id>:<n>" (that list from its n-th song).
 */
public final class PlaybackService extends MediaLibraryService {

    private MediaLibrarySession session;
    private Music music;
    private final ListeningExecutorService work = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));

    @Override
    public void onCreate() {
        super.onCreate();
        music = Music.get(this);
        // songs and pictures are fetched from whichever server address answers at the time
        DataSource.Factory net = new ResolvingDataSource.Factory(new DefaultDataSource.Factory(this),
            spec -> spec.withUri(music.resolve(spec.uri)));
        ExoPlayer player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(net))
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)           // (pause when headphones come out)
            .setWakeMode(C.WAKE_MODE_NETWORK)            // (keep streaming with the screen off)
            .build();
        session = new MediaLibrarySession.Builder(this, player, new Library())
            .setBitmapLoader(new CacheBitmapLoader(new DataSourceBitmapLoader(work, net)))
            .build();
    }

    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        // swiped away while paused or stopped: end; while playing, play on
        Player p = session.getPlayer();
        if (!p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        session.getPlayer().release();
        session.release();
        session = null;
        work.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ what Android Auto sees

    private final class Library implements MediaLibrarySession.Callback {

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                MediaLibrarySession s, MediaSession.ControllerInfo browser, LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(folder("root", "VoiceTunes", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params));
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                MediaLibrarySession s, MediaSession.ControllerInfo browser, String mediaId) {
            return work.submit(() -> {
                MediaItem item = itemFor(mediaId);
                return item != null ? LibraryResult.ofItem(item, null) : LibraryResult.<MediaItem>ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
            });
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                MediaLibrarySession s, MediaSession.ControllerInfo browser, String parentId,
                int page, int pageSize, LibraryParams params) {
            return work.submit(() -> {
                List<MediaItem> all = children(parentId);
                if (all == null) return LibraryResult.<ImmutableList<MediaItem>>ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
                return LibraryResult.ofItemList(pageOf(all, page, pageSize), params);
            });
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
                MediaLibrarySession s, MediaSession.ControllerInfo browser, String query, LibraryParams params) {
            work.execute(() -> s.notifySearchResultChanged(browser, query, searchResults(query).size(), params));
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                MediaLibrarySession s, MediaSession.ControllerInfo browser, String query,
                int page, int pageSize, LibraryParams params) {
            return work.submit(() -> LibraryResult.ofItemList(pageOf(searchResults(query), page, pageSize), params));
        }

        /** What's picked (an item, or a spoken request: "play Cook Islands on VoiceTunes")
         *  becomes the songs to play. */
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                MediaSession s, MediaSession.ControllerInfo controller, List<MediaItem> items,
                int startIndex, long startPositionMs) {
            return work.submit(() -> {
                List<Matcher.Song> songs = new ArrayList<>();
                int start = 0;
                if (items.size() == 1) {
                    MediaItem only = items.get(0);
                    String query = only.requestMetadata.searchQuery;
                    if (query != null) {
                        songs = forQuery(query);
                    } else if (only.localConfiguration != null) {
                        return new MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs);
                    } else {
                        String id = only.mediaId;
                        if (id.startsWith("in:")) {             // a song inside a playlist or album: that list from it
                            int colon = id.lastIndexOf(':');
                            String list = id.substring(3, colon);
                            songs = list.startsWith("playlist:") ? music.server.playlistSongs(list.substring(9)) : songsFor(list);
                            start = Math.min(Integer.parseInt(id.substring(colon + 1)), Math.max(0, songs.size() - 1));
                            if (list.startsWith("playlist:") && music.shufflePlaylists() && !songs.isEmpty()) {
                                // (the song picked first, then the rest of the playlist shuffled)
                                Matcher.Song picked = songs.remove(start);
                                Collections.shuffle(songs);
                                songs.add(0, picked);
                                start = 0;
                            }
                        } else {
                            songs = songsFor(id);
                        }
                    }
                } else {
                    for (MediaItem m : items) {                // (already songs: keep them)
                        if (m.localConfiguration != null) continue;
                        Matcher.Song song = m.mediaId.startsWith("song:") ? music.song(m.mediaId.substring(5)) : null;
                        if (song != null) songs.add(song);
                    }
                    if (songs.isEmpty()) return new MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs);
                    start = startIndex;
                }
                return new MediaSession.MediaItemsWithStartPosition(music.playable(songs), start, C.TIME_UNSET);
            });
        }

        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                MediaSession s, MediaSession.ControllerInfo controller, List<MediaItem> items) {
            return work.submit(() -> {
                List<MediaItem> out = new ArrayList<>();
                for (MediaItem m : items) {
                    if (m.localConfiguration != null) { out.add(m); continue; }
                    String query = m.requestMetadata.searchQuery;
                    out.addAll(music.playable(query != null ? forQuery(query) : songsFor(m.mediaId)));
                }
                return out;
            });
        }
    }

    // ------------------------------------------------------------------ the tree

    private List<MediaItem> children(String id) throws Exception {
        Music m = music;
        com.ferrellsl.voicetunes.Library lib = m.library();
        if (lib == null || m.server == null) return new ArrayList<>();
        List<MediaItem> out = new ArrayList<>();
        switch (id) {
            case "root":
                out.add(playableFolder("shuffle", "Shuffle everything", MediaMetadata.MEDIA_TYPE_PLAYLIST, false));
                out.add(folder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS));
                out.add(folder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS));
                out.add(folder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS));
                return out;
            case "playlists":
                for (Matcher.Item p : sorted(lib.playlists)) {
                    out.add(playableFolder("playlist:" + p.id, p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, true, p.count + " songs"));
                }
                return out;
            case "artists":
            case "albums":
                for (String letter : letters(id.equals("artists") ? lib.artists : lib.albums)) {
                    out.add(folder(id + "/" + letter, letter, id.equals("artists") ? MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS : MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS));
                }
                return out;
            default:
                break;
        }
        if (id.startsWith("artists/") || id.startsWith("albums/")) {
            boolean artists = id.startsWith("artists/");
            String letter = id.substring(id.indexOf('/') + 1);
            for (Matcher.Item it : sorted(artists ? lib.artists : lib.albums)) {
                if (!letterOf(it.name).equals(letter)) continue;
                if (artists) out.add(playableFolder("artist:" + it.name, it.name, MediaMetadata.MEDIA_TYPE_ARTIST, false, "Shuffled"));
                else out.add(playableFolder("album:" + it.id, it.name, MediaMetadata.MEDIA_TYPE_ALBUM, true, it.artist));
            }
            return out;
        }
        if (id.startsWith("playlist:") || id.startsWith("album:")) {
            // (in their own order: "in:...:n" counts in it)
            List<Matcher.Song> songs = id.startsWith("playlist:") ? m.server.playlistSongs(id.substring(9)) : songsFor(id);
            for (int i = 0; i < songs.size(); i++) {
                Matcher.Song s = songs.get(i);
                out.add(new MediaItem.Builder().setMediaId("in:" + id + ":" + i)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
                        .setAlbumTitle(s.album).setArtworkUri(Uri.parse(m.server.coverUrl(s.coverArt)))
                        .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
                    .build());
            }
            return out;
        }
        return null;
    }

    private MediaItem itemFor(String id) {
        switch (id) {
            case "root": return folder("root", "VoiceTunes", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
            case "playlists": return folder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS);
            case "artists": return folder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS);
            case "albums": return folder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS);
            case "shuffle": return playableFolder("shuffle", "Shuffle everything", MediaMetadata.MEDIA_TYPE_PLAYLIST, false);
            default: break;
        }
        if (id.startsWith("song:")) {
            Matcher.Song s = music.song(id.substring(5));
            return s == null ? null : music.playable(s);
        }
        if (id.startsWith("artist:")) return playableFolder(id, id.substring(7), MediaMetadata.MEDIA_TYPE_ARTIST, false);
        return null;
    }

    /** The songs for a playable id. */
    private List<Matcher.Song> songsFor(String id) throws Exception {
        if (id.equals("shuffle")) return music.shuffleAll(500);
        if (id.startsWith("playlist:")) return music.playlist(id.substring(9));
        if (id.startsWith("album:")) return music.server.albumSongs(id.substring(6));
        if (id.startsWith("artist:")) return music.library().songsBy(id.substring(7));
        if (id.startsWith("song:")) {
            Matcher.Song s = music.song(id.substring(5));
            return s == null ? new ArrayList<>() : music.songThenAlbum(s);
        }
        return new ArrayList<>();
    }

    /** The songs for a spoken request, matched as on the phone. */
    private List<Matcher.Song> forQuery(String query) throws Exception {
        if (query.trim().isEmpty()) return music.shuffleAll(500);  // ("play VoiceTunes": something)
        Matcher.Result f = music.find(Collections.singletonList(query));
        return f == null ? new ArrayList<>() : music.queueFor(f);
    }

    /** What a search finds: the best match first, then the next best of each kind. */
    private List<MediaItem> searchResults(String query) {
        List<MediaItem> out = new ArrayList<>();
        com.ferrellsl.voicetunes.Library lib = music.library();
        if (lib == null || music.server == null) return out;
        for (Matcher.Kind kind : new Matcher.Kind[] { null, Matcher.Kind.PLAYLIST, Matcher.Kind.ARTIST, Matcher.Kind.ALBUM, Matcher.Kind.SONG }) {
            Matcher.Request r = Matcher.parse(query);
            if (r.control != Matcher.Control.NONE) return out;
            if (kind != null) r.kind = kind;
            Matcher.Result f = lib.matcher.find(r);
            if (f == null || f.score < Matcher.ACCEPT) continue;
            MediaItem item = itemForResult(f);
            boolean dup = false;
            for (MediaItem o : out) if (o.mediaId.equals(item.mediaId)) dup = true;
            if (!dup) out.add(item);
        }
        return out;
    }

    private MediaItem itemForResult(Matcher.Result f) {
        switch (f.kind) {
            case PLAYLIST: return playableFolder("playlist:" + f.item.id, f.item.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, true, "Playlist");
            case ARTIST: return playableFolder("artist:" + f.item.name, f.item.name, MediaMetadata.MEDIA_TYPE_ARTIST, false, "Artist, shuffled");
            case ALBUM: return playableFolder("album:" + f.item.id, f.item.name, MediaMetadata.MEDIA_TYPE_ALBUM, true, f.item.artist);
            default: return music.playable(f.song);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static MediaItem folder(String id, String title, int type) {
        return new MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build())
            .build();
    }

    private static MediaItem playableFolder(String id, String title, int type, boolean browsable) {
        return playableFolder(id, title, type, browsable, null);
    }

    private static MediaItem playableFolder(String id, String title, int type, boolean browsable, String subtitle) {
        MediaMetadata.Builder m = new MediaMetadata.Builder().setTitle(title)
            .setIsBrowsable(browsable).setIsPlayable(true).setMediaType(type);
        if (subtitle != null) m.setSubtitle(subtitle).setArtist(subtitle);
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(m.build()).build();
    }

    private static List<Matcher.Item> sorted(List<Matcher.Item> items) {
        List<Matcher.Item> s = new ArrayList<>(items);
        s.sort((a, b) -> Matcher.normalize(a.name).compareTo(Matcher.normalize(b.name)));
        return s;
    }

    /** "A".."Z", or "#" for names starting with a digit or anything else ("The" doesn't count). */
    private static String letterOf(String name) {
        String n = Matcher.normalize(name);
        char c = n.isEmpty() ? '#' : Character.toUpperCase(n.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }

    private static List<String> letters(List<Matcher.Item> items) {
        TreeSet<String> set = new TreeSet<>();
        for (Matcher.Item it : items) set.add(letterOf(it.name));
        List<String> out = new ArrayList<>(set);
        if (out.remove("#")) out.add(0, "#");
        return out;
    }

    private static ImmutableList<MediaItem> pageOf(List<MediaItem> all, int page, int pageSize) {
        if (pageSize <= 0 || pageSize == Integer.MAX_VALUE) return ImmutableList.copyOf(all);
        int from = Math.min(all.size(), page * pageSize);
        return ImmutableList.copyOf(all.subList(from, Math.min(all.size(), from + pageSize)));
    }
}
