package dev.watchmusic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScrollFeedbackProvider;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Library browser, styled after the watchOS Music app. Each level is its own screen (swipe right to go back):
 * Music → Artists → an artist's albums → songs, Music → Albums → songs, and Music → Songs.
 * Tapping a song plays the list it's in. An album's screen leads with its cover, Play and Shuffle.
 */
public class MainActivity extends Activity {
    private static final String EXTRA_VIEW = "view", EXTRA_ARTIST = "artist", EXTRA_ALBUM = "album";
    private static final String ARTISTS = "artists", ALBUMS = "albums", SONGS = "songs";
    private static final int ROW = 0, ACTION = 1, TRACK = 2;
    /** Every card of a kind is the same height (dp); long names shrink a little, then end in "…". */
    private static final int ROW_HEIGHT = 56, TRACK_HEIGHT = 46;

    /** A row: title, grey line under it, what a tap does, and whether it holds the playing song. */
    private static final class Item {
        final String title, subtitle;
        final Runnable onTap;
        final Predicate<Library.Track> holds;
        Library.Track cover; // album, artist and song rows: show this song's cover
        boolean round;       // artists: the cover in a circle, as Apple Music does
        int icon;            // the top level: a pink glyph instead of a cover
        int number;          // a song on its album's screen: a compact row led by its track number

        Item(String title, String subtitle, Runnable onTap, Predicate<Library.Track> holds) {
            this.title = title;
            this.subtitle = subtitle;
            this.onTap = onTap;
            this.holds = holds;
        }

        int type() {
            return number > 0 ? TRACK : subtitle == null ? ACTION : ROW;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;
    private String view, artist, album; // which level this screen shows; view == null is the top
    private List<Item> items = new ArrayList<>();
    private ListView list;
    private View header, nowPlaying;
    private TextView emptyView, albumTitle, albumArtist, nowPlayingTitle;
    private ImageView albumCover, nowPlayingCover;
    private Library.Track playing, albumFirst;
    private ScrollFeedbackProvider crownFeedback;
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int i) { return items.get(i).type(); }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Item item = items.get(i);
            switch (item.type()) {
                case ACTION: return actionRow(item, convert);
                case TRACK: return trackRow(item, convert);
                default: return row(item, convert);
            }
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            Library.Track t = PlayerService.instance == null ? null : PlayerService.instance.current();
            nowPlaying.setVisibility(t == null ? View.GONE : View.VISIBLE);
            if (t != playing) {
                playing = t;
                if (t != null) {
                    nowPlayingTitle.setText(t.title);
                    Art.show(nowPlayingCover, t, px(32));
                }
                adapter.notifyDataSetChanged(); // re-tint what's playing
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        view = getIntent().getStringExtra(EXTRA_VIEW);
        artist = getIntent().getStringExtra(EXTRA_ARTIST);
        album = getIntent().getStringExtra(EXTRA_ALBUM);

        list = new ListView(this);
        list.setBackgroundColor(Color.BLACK);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        // Side padding keeps rows inside the round screen; the bottom padding lets the last row scroll to centre.
        list.setPadding(px(14), 0, px(14), px(48));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, px(24), 0, px(6));
        this.header = header;

        if (SONGS.equals(view) && album != null) {
            buildAlbumHeader(header);
        } else {
            TextView title = new TextView(this);
            title.setText(screenTitle());
            title.setTextSize(16);
            title.setTextColor(Ui.PINK);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setGravity(Gravity.CENTER);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(title, new LinearLayout.LayoutParams(px(120), -2));
        }

        // Now Playing, shown while the player has a song loaded: a mini player row with the cover and title.
        LinearLayout np = new LinearLayout(this);
        np.setGravity(Gravity.CENTER_VERTICAL);
        np.setPadding(px(12), 0, px(14), 0);
        nowPlayingCover = new ImageView(this);
        nowPlayingCover.setBackground(Ui.pill(Ui.FILL, px(6)));
        nowPlayingCover.setClipToOutline(true);
        np.addView(nowPlayingCover, new LinearLayout.LayoutParams(px(32), px(32)));
        LinearLayout npText = new LinearLayout(this);
        npText.setOrientation(LinearLayout.VERTICAL);
        npText.setPadding(px(8), 0, 0, 0);
        TextView npLabel = new TextView(this);
        npLabel.setText("Now Playing");
        npLabel.setTextSize(12);
        npLabel.setTextColor(Ui.PINK);
        npText.addView(npLabel);
        nowPlayingTitle = new TextView(this);
        nowPlayingTitle.setTextSize(14);
        nowPlayingTitle.setTextColor(Ui.LABEL);
        nowPlayingTitle.setSingleLine(true);
        nowPlayingTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE); // long titles scroll, like watchOS
        nowPlayingTitle.setMarqueeRepeatLimit(-1);
        nowPlayingTitle.setSelected(true);
        nowPlayingTitle.setHorizontalFadingEdgeEnabled(true); // soft ends while it scrolls
        nowPlayingTitle.setFadingEdgeLength(px(10));
        npText.addView(nowPlayingTitle);
        np.addView(npText, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.pressable(np, Ui.pill(0xFF1C1C1E, px(26)),
                () -> startActivity(new Intent(this, NowPlayingActivity.class)));
        LinearLayout.LayoutParams npl = new LinearLayout.LayoutParams(-1, px(ROW_HEIGHT));
        npl.topMargin = px(8);
        header.addView(np, npl);
        nowPlaying = np;

        // All songs, or one artist's: shuffle the lot.
        if (SONGS.equals(view) && album == null || ALBUMS.equals(view) && artist != null) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(120), px(36));
            sl.topMargin = px(8);
            header.addView(actionButton(R.drawable.ic_shuffle, "Shuffle", () -> play(null, true)), sl);
        }

        emptyView = new TextView(this);
        emptyView.setText("No music yet.");
        emptyView.setTextSize(13);
        emptyView.setTextColor(Ui.SECONDARY);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
        el.topMargin = px(16);
        header.addView(emptyView, el);

        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { shapeRows(); }
        });
        crownFeedback = ScrollFeedbackProvider.createProvider(list);
        list.setOnGenericMotionListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_SCROLL
                    || !e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false;
            onCrown(e);
            return true;
        });
        setContentView(list);

        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    /** An album's screen: its cover, name and artist, then Play and Shuffle. Filled in by reload(). */
    private void buildAlbumHeader(LinearLayout header) {
        albumCover = new ImageView(this);
        albumCover.setBackground(Ui.pill(Ui.FILL, px(10)));
        albumCover.setClipToOutline(true); // round the cover's corners to the background's
        header.addView(albumCover, new LinearLayout.LayoutParams(px(84), px(84)));

        albumTitle = new TextView(this);
        albumTitle.setText(album);
        albumTitle.setTextSize(15);
        albumTitle.setTextColor(Ui.LABEL);
        albumTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        albumTitle.setGravity(Gravity.CENTER);
        albumTitle.setMaxLines(2);
        albumTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px(150), -2);
        tl.topMargin = px(8);
        header.addView(albumTitle, tl);

        albumArtist = new TextView(this);
        albumArtist.setTextSize(13);
        albumArtist.setTextColor(Ui.PINK);
        albumArtist.setGravity(Gravity.CENTER);
        albumArtist.setSingleLine(true);
        albumArtist.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(albumArtist, new LinearLayout.LayoutParams(px(150), -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(actionButton(R.drawable.ic_play, "Play", () -> play(albumFirst, false)),
                new LinearLayout.LayoutParams(px(76), px(36)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(76), px(36));
        bl.setMarginStart(px(8));
        buttons.addView(actionButton(R.drawable.ic_shuffle, "Shuffle", () -> play(null, true)), bl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = px(10);
        header.addView(buttons, rl);
    }

    /** A grey pill with a pink icon and label, like the Play and Shuffle buttons in Apple Music. */
    private View actionButton(int icon, String label, Runnable onTap) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setColorFilter(Ui.PINK);
        b.addView(i, new LinearLayout.LayoutParams(px(16), px(16)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(Ui.PINK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(4), 0, 0, 0);
        b.addView(t);
        Ui.pressable(b, Ui.pill(Ui.FILL, px(20)), onTap);
        return b;
    }

    private String screenTitle() {
        if (view == null) return "Music";
        if (ARTISTS.equals(view)) return "Artists";
        if (ALBUMS.equals(view)) return artist != null ? artist : "Albums";
        return "Songs";
    }

    /** Builds this screen's rows from the library. */
    private List<Item> buildItems(List<Library.Track> all) {
        List<Item> out = new ArrayList<>();
        if (view == null) {
            if (!all.isEmpty()) {
                out.add(new Item("Artists", count(Library.artists(all).size(), "artist"),
                        () -> open(ARTISTS, null, null), t -> false));
                out.add(new Item("Albums", count(Library.albums(all, null).size(), "album"),
                        () -> open(ALBUMS, null, null), t -> false));
                out.add(new Item("Songs", count(all.size(), "song"),
                        () -> open(SONGS, null, null), t -> false));
                int[] icons = { R.drawable.ic_artist, R.drawable.ic_album, R.drawable.ic_note };
                for (int i = 0; i < icons.length; i++) out.get(i).icon = icons[i];
            }
            out.add(new Item("Add Music", null, // no subtitle: drawn as an action button, not a library row
                    () -> startActivity(new Intent(this, UploadActivity.class)), t -> false));
        } else if (ARTISTS.equals(view)) {
            for (Library.Artist a : Library.artists(all)) {
                Item item = new Item(a.name, count(a.albums, "album"),
                        () -> open(ALBUMS, a.name, null), t -> t.artist.equals(a.name));
                item.cover = a.first;
                item.round = true;
                out.add(item);
            }
        } else if (ALBUMS.equals(view)) {
            for (Library.Album a : Library.albums(all, artist)) {
                // All albums: say whose it is. One artist's albums: the year, as that's what tells them apart.
                String sub = artist == null ? a.artist : a.year > 0 ? String.valueOf(a.year) : count(a.songs, "song");
                Item item = new Item(a.name, sub, () -> open(SONGS, artist, a.name),
                        t -> t.album.equals(a.name) && (artist == null || t.artist.equals(artist)));
                item.cover = a.first;
                out.add(item);
            }
        } else {
            List<Library.Track> songs = Library.songs(all, artist, album);
            for (int i = 0; i < songs.size(); i++) {
                Library.Track s = songs.get(i);
                Item item = new Item(s.title, s.artist, () -> play(s, false), t -> t.id == s.id);
                if (album != null) item.number = s.number % 1000 > 0 ? s.number % 1000 : i + 1; // untagged: list order
                else item.cover = s;
                out.add(item);
            }
        }
        return out;
    }

    private void open(String view, String artist, String album) {
        startActivity(new Intent(this, MainActivity.class).putExtra(EXTRA_VIEW, view)
                .putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_ALBUM, album));
    }

    /** Plays this screen's list from {@code t} (or the top), shuffled if asked. The playing song just opens Now Playing. */
    private void play(Library.Track t, boolean shuffle) {
        PlayerService p = PlayerService.instance;
        Library.Track current = p == null ? null : p.current();
        if (shuffle || t == null || current == null || current.id != t.id) {
            startForegroundService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_PLAY_TRACK).putExtra(PlayerService.EXTRA_ID, t == null ? -1 : t.id)
                    .putExtra(PlayerService.EXTRA_ARTIST, artist).putExtra(PlayerService.EXTRA_ALBUM, album)
                    .putExtra(PlayerService.EXTRA_SHUFFLE, shuffle));
        }
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    /** A name on one line at 15sp, shrinking to 12sp before it's cut short. */
    private static void fitOneLine(TextView name) {
        name.setMaxLines(1); // not setSingleLine: that scrolls sideways, so the text always "fits" and never shrinks
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(15);
        name.setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The crown scrolls the list with the system's rotary haptic ticks, and a bump at either end. */
    private void onCrown(MotionEvent e) {
        int dy = Math.round(-e.getAxisValue(MotionEvent.AXIS_SCROLL)
                * ViewConfiguration.get(this).getScaledVerticalScrollFactor());
        if (dy == 0) return;
        if (list.canScrollList(dy)) {
            list.scrollListBy(dy);
            crownFeedback.onScrollProgress(e.getDeviceId(), e.getSource(), MotionEvent.AXIS_SCROLL, dy);
        } else {
            crownFeedback.onScrollLimit(e.getDeviceId(), e.getSource(), MotionEvent.AXIS_SCROLL, dy < 0);
        }
    }

    /** Rows shrink and fade as they near the top or bottom of the round screen, like watchOS lists. */
    private void shapeRows() {
        int h = list.getHeight();
        if (h == 0) return;
        float edge = h * 0.25f;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c == header) continue;
            float mid = (c.getTop() + c.getBottom()) / 2f;
            float near = Math.max(0, 1 - Math.max(0, Math.min(mid, h - mid)) / edge); // 0 clear of the edge, 1 at it
            float scale = 1 - 0.15f * near;
            c.setScaleX(scale);
            c.setScaleY(scale);
            c.setAlpha(1 - 0.6f * near);
        }
    }

    private View row(Item item, View convert) {
        FrameLayout cell = (FrameLayout) convert;
        if (cell == null) {
            cell = new FrameLayout(this);
            cell.setPadding(0, px(3), 0, px(3));
            LinearLayout box = new LinearLayout(this);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(px(12), 0, px(14), 0);
            ImageView cover = new ImageView(this);
            cover.setClipToOutline(true); // rounds the cover to its background (set per row)
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(px(32), px(32));
            cl.setMarginEnd(px(8));
            box.addView(cover, cl);
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(px(4), 0, 0, 0); // with no cover, lines up with the other rows' text
            TextView name = new TextView(this);
            fitOneLine(name);
            text.addView(name, new LinearLayout.LayoutParams(-1, px(20)));
            TextView sub = new TextView(this);
            sub.setTextSize(12);
            sub.setTextColor(Ui.SECONDARY);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sub);
            box.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            cell.addView(box, new FrameLayout.LayoutParams(-1, px(ROW_HEIGHT)));
        }
        LinearLayout box = (LinearLayout) cell.getChildAt(0);
        ImageView cover = (ImageView) box.getChildAt(0);
        LinearLayout text = (LinearLayout) box.getChildAt(1);
        TextView name = (TextView) text.getChildAt(0);
        TextView sub = (TextView) text.getChildAt(1);
        cover.setVisibility(item.cover == null && item.icon == 0 ? View.GONE : View.VISIBLE);
        if (item.icon != 0) {
            cover.setTag(null); // drop any cover still loading for this recycled row
            cover.setBackground(null);
            cover.setScaleType(ImageView.ScaleType.CENTER);
            cover.setImageResource(item.icon);
            cover.setColorFilter(Ui.PINK);
        } else if (item.cover != null) {
            cover.setBackground(item.round ? Ui.oval(Ui.FILL) : Ui.pill(Ui.FILL, px(6)));
            Art.show(cover, item.cover, px(32));
        }
        text.setPadding(cover.getVisibility() == View.VISIBLE ? 0 : px(4), 0, 0, 0);
        name.setText(item.title);
        name.setTextColor(playing != null && item.holds.test(playing) ? Ui.PINK : Ui.LABEL);
        sub.setText(item.subtitle);
        Ui.pressable(box, Ui.pill(0xFF1C1C1E, px(26)), item.onTap);
        return cell;
    }

    /** A song on its album's screen: track number (a pink speaker while it plays) and the title. */
    private View trackRow(Item item, View convert) {
        FrameLayout cell = (FrameLayout) convert;
        if (cell == null) {
            cell = new FrameLayout(this);
            cell.setPadding(0, px(2), 0, px(2));
            LinearLayout box = new LinearLayout(this);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(px(8), 0, px(14), 0);
            FrameLayout slot = new FrameLayout(this);
            TextView number = new TextView(this);
            number.setTextSize(13);
            number.setTextColor(Ui.SECONDARY);
            number.setGravity(Gravity.CENTER);
            slot.addView(number, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            ImageView speaker = new ImageView(this);
            speaker.setImageResource(R.drawable.ic_volume);
            speaker.setColorFilter(Ui.PINK);
            slot.addView(speaker, new FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER));
            box.addView(slot, new LinearLayout.LayoutParams(px(24), -2));
            TextView name = new TextView(this);
            fitOneLine(name);
            name.setPadding(px(6), 0, 0, 0);
            box.addView(name, new LinearLayout.LayoutParams(0, px(20), 1));
            cell.addView(box, new FrameLayout.LayoutParams(-1, px(TRACK_HEIGHT)));
        }
        LinearLayout box = (LinearLayout) cell.getChildAt(0);
        FrameLayout slot = (FrameLayout) box.getChildAt(0);
        TextView number = (TextView) slot.getChildAt(0);
        View speaker = slot.getChildAt(1);
        TextView name = (TextView) box.getChildAt(1);
        boolean on = playing != null && item.holds.test(playing);
        number.setText(String.valueOf(item.number));
        number.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        speaker.setVisibility(on ? View.VISIBLE : View.GONE);
        name.setText(item.title);
        name.setTextColor(on ? Ui.PINK : Ui.LABEL);
        Ui.pressable(box, Ui.pill(0xFF1C1C1E, px(22)), item.onTap);
        return cell;
    }

    /** An action rather than a place in the library: a centred pink-tinted pill with an icon, set apart above. */
    private View actionRow(Item item, View convert) {
        if (convert != null) return convert; // only Add Music uses it, and it never changes
        FrameLayout cell = new FrameLayout(this);
        cell.setPadding(px(14), px(12), px(14), px(3));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Ui.PINK);
        pill.addView(plus, new LinearLayout.LayoutParams(px(20), px(20)));
        TextView label = new TextView(this);
        label.setText(item.title);
        label.setTextSize(15);
        label.setTextColor(Ui.PINK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(px(6), 0, 0, 0);
        pill.addView(label);
        Ui.pressable(pill, Ui.pill(0x33FF2D55, px(24)), item.onTap);
        cell.addView(pill, new FrameLayout.LayoutParams(-1, px(48)));
        return cell;
    }

    private void reload() {
        List<Library.Track> all = Library.load(this);
        items = buildItems(all);
        emptyView.setVisibility(view == null && all.isEmpty() ? View.VISIBLE : View.GONE);
        if (albumCover != null) {
            for (Library.Album a : Library.albums(all, artist)) {
                if (!a.name.equals(album)) continue;
                albumFirst = a.first;
                albumArtist.setText(a.artist);
                Art.show(albumCover, a.first, px(84));
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        reload();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        handler.post(refresh);
        list.requestFocus(); // so the crown scrolls the list
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
