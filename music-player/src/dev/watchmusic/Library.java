package dev.watchmusic;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The songs MediaStore has indexed on the watch (files pushed to /sdcard/Music), and views over them. */
final class Library {
    private static final Uri AUDIO = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
    /** A–Z ignoring case; names differing only in case stay separate. */
    private static final Comparator<String> NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    static final class Track {
        final long id, durationMs;
        final String title, artist, album;
        final int number, year;

        Track(long id, String title, String artist, String album, int number, int year, long durationMs) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.number = number;
            this.year = year;
            this.durationMs = durationMs;
        }

        Uri uri() {
            return ContentUris.withAppendedId(AUDIO, id);
        }
    }

    static final class Artist {
        final String name;
        final int albums;
        final Track first; // its cover stands for the artist

        Artist(String name, int albums, Track first) {
            this.name = name;
            this.albums = albums;
            this.first = first;
        }
    }

    static final class Album {
        final String name;
        final int year;
        String artist; // "Various Artists" when the songs disagree
        int songs;
        Track first; // lowest track number: its cover stands for the album

        Album(Track t) {
            this.name = t.album;
            this.artist = t.artist;
            this.year = t.year;
            this.first = t;
        }
    }

    private Library() {}

    /** All music, in album order (artist, album, track number) so a queue plays albums through. */
    static List<Track> load(Context context) {
        List<Track> tracks = new ArrayList<>();
        String[] projection = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DURATION,
        };
        try (Cursor c = context.getContentResolver().query(AUDIO, projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
            if (c == null) return tracks;
            while (c.moveToNext()) {
                tracks.add(new Track(c.getLong(0), orElse(c.getString(1), "Unknown Song"),
                        orElse(c.getString(2), "Unknown Artist"), orElse(c.getString(3), "Unknown Album"),
                        c.getInt(4), // disc * 1000 + track, so it also orders multi-disc albums
                        c.getInt(5), c.getLong(6)));
            }
        } catch (SecurityException e) {
            Log.w(PlayerService.TAG, "no permission to read music", e);
        }
        tracks.sort(Comparator.<Track, String>comparing(t -> t.artist, NAME_ORDER)
                .thenComparing(t -> t.album, NAME_ORDER)
                .thenComparingInt(t -> t.number)
                .thenComparing(t -> t.title, NAME_ORDER));
        return tracks;
    }

    /** Artists A–Z, with how many albums each has. */
    static List<Artist> artists(List<Track> all) {
        Map<String, Set<String>> albums = new TreeMap<>(NAME_ORDER);
        Map<String, Track> first = new TreeMap<>(NAME_ORDER);
        for (Track t : all) {
            albums.computeIfAbsent(t.artist, k -> new HashSet<>()).add(t.album);
            first.putIfAbsent(t.artist, t); // all is in album order, so this opens their first album
        }
        List<Artist> out = new ArrayList<>();
        albums.forEach((name, set) -> out.add(new Artist(name, set.size(), first.get(name))));
        return out;
    }

    /** Albums A–Z, or one artist's albums oldest first when {@code artist} is set. */
    static List<Album> albums(List<Track> all, String artist) {
        Map<String, Album> map = new TreeMap<>(NAME_ORDER);
        for (Track t : all) {
            if (artist != null && !artist.equals(t.artist)) continue;
            Album a = map.get(t.album);
            if (a == null) map.put(t.album, a = new Album(t));
            else if (!a.artist.equals(t.artist)) a.artist = "Various Artists";
            if (t.number < a.first.number) a.first = t;
            a.songs++;
        }
        List<Album> out = new ArrayList<>(map.values());
        if (artist != null) out.sort(Comparator.comparingInt(a -> a.year)); // stable: same year stays A–Z
        return out;
    }

    /**
     * The songs of an album (track order), of an artist (album order), or all of them (A–Z by title).
     * This is also the play queue, so playing from a list carries on through that list.
     */
    static List<Track> songs(List<Track> all, String artist, String album) {
        List<Track> out = new ArrayList<>();
        for (Track t : all) {
            if ((artist == null || artist.equals(t.artist)) && (album == null || album.equals(t.album))) out.add(t);
        }
        if (album != null) out.sort(Comparator.comparingInt(t -> t.number)); // compilations: by track, not artist
        else if (artist == null) out.sort(Comparator.comparing(t -> t.title, NAME_ORDER));
        return out;
    }

    private static String orElse(String s, String fallback) {
        return s == null || s.isEmpty() || MediaStore.UNKNOWN_STRING.equals(s) ? fallback : s;
    }
}
