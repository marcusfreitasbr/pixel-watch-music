package dev.watchmusic;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * watchOS-style Now Playing: title and artist, back / play-pause / forward, with song progress as a
 * ring around play-pause, over the album cover blurred and darkened. The crown changes the volume,
 * shown as an arc beside it.
 */
public class NowPlayingActivity extends Activity {
    /** Crown travel (in AXIS_SCROLL units) per volume step. */
    private static final float CROWN_STEP = 0.5f;
    /** Play/pause sits on see-through white so the cover shows through, like watchOS. */
    private static final int PLAY_BUTTON = 0x33FFFFFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;
    private FrameLayout root;
    private TextView title, artist;
    private ImageView backdrop, playIcon, output;
    private long artId = -1;
    private Boolean shownHeadphones;
    private ProgressRing ring;
    private VolumeArc volumeArc;
    private AudioManager audio;
    private float crown;
    private boolean shownPlaying;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        audio = getSystemService(AudioManager.class);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // The cover, small and blurred, fills the screen; the scrim keeps text readable, darkest at the bottom.
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setRenderEffect(RenderEffect.createBlurEffect(16 * dp, 16 * dp, Shader.TileMode.CLAMP));
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x73000000, 0x4D000000, 0xA6000000 }));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Laid out top to bottom like watchOS: title and artist, the controls, then the output glyph.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, px(32), 0, 0);

        title = new TextView(this);
        title.setTextSize(16);
        title.setTextColor(Ui.LABEL);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE); // long titles scroll, like watchOS
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        col.addView(title, new LinearLayout.LayoutParams(px(136), -2));

        artist = new TextView(this);
        artist.setTextSize(13);
        artist.setTextColor(0xB3FFFFFF); // grey that takes on the cover's tint
        artist.setGravity(Gravity.CENTER);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(artist, new LinearLayout.LayoutParams(px(128), -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(skipButton(R.drawable.ic_prev, () -> act(PlayerService::previous)),
                new LinearLayout.LayoutParams(px(42), px(42)));

        FrameLayout play = new FrameLayout(this);
        ring = new ProgressRing(this, 3 * dp, Ui.PINK);
        play.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        playIcon = new ImageView(this);
        playIcon.setImageResource(R.drawable.ic_play);
        playIcon.setPadding(px(15), px(15), px(15), px(15));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -1);
        il.setMargins(px(6), px(6), px(6), px(6)); // inside the ring
        play.addView(playIcon, il);
        Ui.pressable(playIcon, Ui.oval(PLAY_BUTTON), () -> act(PlayerService::toggle));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(px(62), px(62));
        pl.setMargins(px(8), 0, px(8), 0);
        controls.addView(play, pl);

        controls.addView(skipButton(R.drawable.ic_next, () -> act(PlayerService::next)),
                new LinearLayout.LayoutParams(px(42), px(42)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = px(6);
        col.addView(controls, cl);

        // Always headphones, a bare glyph like AirPlay on watchOS: white, or green while connected.
        // Opens the system output switcher, to connect a paired device or pair a new one.
        output = new ImageView(this);
        output.setImageResource(R.drawable.ic_headphones);
        output.setPadding(px(8), px(8), px(8), px(8));
        Ui.pressable(output, Ui.oval(Color.TRANSPARENT), this::openOutputSwitcher);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(px(38), px(38));
        ol.topMargin = px(4);
        col.addView(output, ol);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        volumeArc = new VolumeArc(this, 4 * dp);
        root.addView(volumeArc, new FrameLayout.LayoutParams(-1, -1));

        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setOnGenericMotionListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_SCROLL
                    || !e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false;
            onCrown(-e.getAxisValue(MotionEvent.AXIS_SCROLL));
            return true;
        });
        setContentView(root);
    }

    private ImageView skipButton(int icon, Runnable onClick) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(px(8), px(8), px(8), px(8));
        Ui.pressable(b, Ui.oval(Color.TRANSPARENT), onClick); // bare glyphs, as on watchOS
        return b;
    }

    private void openOutputSwitcher() {
        try {
            startActivity(new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", getPackageName()));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private interface Command { void run(PlayerService p); }

    private void act(Command c) {
        if (PlayerService.instance != null) c.run(PlayerService.instance);
        show();
    }

    private void onCrown(float delta) {
        crown += delta;
        int steps = (int) (crown / CROWN_STEP);
        if (steps == 0) return;
        crown -= steps * CROWN_STEP;
        changeVolume(steps);
    }

    private void changeVolume(int steps) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int after = Math.max(0, Math.min(max, before + steps));
        if (after != before) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, after, 0);
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        volumeArc.show(after / (float) max);
    }

    private void show() {
        PlayerService p = PlayerService.instance;
        Library.Track t = p == null ? null : p.current();
        if (t == null) {
            finish(); // nothing loaded any more (player stopped after idling)
            return;
        }
        if (!t.title.contentEquals(title.getText())) title.setText(t.title);
        if (t.id != artId) showArt(t);
        artist.setText(t.artist);
        boolean playing = p.isPlayingOrBuffering();
        if (playing != shownPlaying) {
            shownPlaying = playing;
            playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        boolean headphones = p.onHeadphones();
        if (!Boolean.valueOf(headphones).equals(shownHeadphones)) { // null until first shown
            shownHeadphones = headphones;
            output.setColorFilter(headphones ? Ui.GREEN : Ui.LABEL);
        }
        long d = p.duration();
        ring.setProgress(d > 0 ? p.position() / (float) d : 0);
    }

    private void showArt(Library.Track t) {
        artId = t.id;
        Art.load(this, t, 128, art -> { // it's blurred, so a small one does
            if (artId != t.id) return;
            if (art == null) {
                backdrop.animate().alpha(0).setDuration(300).start();
                return;
            }
            backdrop.setImageBitmap(art);
            // Fade in from black; between songs of one album the cover just stays.
            if (backdrop.getAlpha() < 1) backdrop.animate().alpha(1).setDuration(300).start();
        });
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownPlaying = false;
        playIcon.setImageResource(R.drawable.ic_play);
        handler.post(refresh);
        root.requestFocus(); // the crown goes to the focused view
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /** Thin grey track with a coloured arc: song progress. */
    private static final class ProgressRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float progress;

        ProgressRing(Context c, float stroke, int color) {
            super(c);
            for (Paint p : new Paint[] { track, arc }) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            track.setColor(0x40FFFFFF);
            arc.setColor(color);
        }

        void setProgress(float p) {
            if (Math.abs(p - progress) < 0.001f) return;
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = track.getStrokeWidth() / 2;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawOval(box, track);
            if (progress > 0) canvas.drawArc(box, -90, 360 * Math.min(1, progress), false, arc);
        }
    }

    /** Volume as a short green arc at the screen edge by the crown; fades out after a moment. */
    private static final class VolumeArc extends View {
        private static final float SPAN = 60; // degrees, centred on 3 o'clock
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), level = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Runnable hide = () -> animate().alpha(0).setDuration(300).start();
        private float volume;

        VolumeArc(Context c, float stroke) {
            super(c);
            for (Paint p : new Paint[] { track, level }) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            track.setColor(0x40FFFFFF);
            level.setColor(Ui.GREEN);
            setAlpha(0);
        }

        void show(float v) {
            volume = v;
            invalidate();
            animate().cancel();
            setAlpha(1);
            removeCallbacks(hide);
            postDelayed(hide, 1200);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = track.getStrokeWidth() / 2 + 3 * getResources().getDisplayMetrics().density;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            // Angles run clockwise from 3 o'clock; the level fills upward from the bottom end (+SPAN/2).
            canvas.drawArc(box, -SPAN / 2, SPAN, false, track);
            if (volume > 0) canvas.drawArc(box, SPAN / 2 - SPAN * volume, SPAN * volume, false, level);
        }
    }
}
