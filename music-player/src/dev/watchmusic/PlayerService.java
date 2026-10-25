package dev.watchmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plays the library as a queue through MediaPlayer, in a mediaPlayback foreground service.
 * A MediaSession exposes it to the watch's own media controls; the screens read the static instance.
 */
public class PlayerService extends Service {
    static final String TAG = "WatchMusic";
    static final String ACTION_PLAY_TRACK = "dev.watchmusic.PLAY_TRACK", ACTION_TOGGLE = "dev.watchmusic.TOGGLE",
            ACTION_NEXT = "dev.watchmusic.NEXT", ACTION_PREV = "dev.watchmusic.PREV";
    static final String EXTRA_ID = "id", EXTRA_ARTIST = "artist", EXTRA_ALBUM = "album", EXTRA_SHUFFLE = "shuffle";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final int ART_PX = 256; // the cover handed to the system media controls
    private static final long IDLE_STOP_MS = 10 * 60_000; // paused this long: let the service go
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    /** The running player, or null. Only touched on the main thread. */
    static PlayerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = this::stopSelf;
    private List<Library.Track> queue = new ArrayList<>();
    private int index = -1;
    private MediaPlayer player;
    private boolean prepared, playWhenReady, resumeOnFocusGain, noisyRegistered;
    private int failures; // consecutive tracks that wouldn't play, so a bad library can't loop forever
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private Bitmap art; // the current song's cover, once loaded
    private long artId = -1;

    /** Headphones disconnected: pause rather than blast the speaker. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audio = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener(this::onFocusChange, handler)
                .build();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW));

        session = new MediaSession(this, TAG);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        }, handler);
        session.setSessionActivity(openNowPlaying());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY_TRACK.equals(action)) {
            // Queue the list the song was picked from: its album, its artist, or all songs.
            queue = Library.songs(Library.load(this),
                    intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_ALBUM));
            if (intent.getBooleanExtra(EXTRA_SHUFFLE, false)) Collections.shuffle(queue); // and start at the top
            long id = intent.getLongExtra(EXTRA_ID, -1);
            int i = 0;
            for (int k = 0; k < queue.size(); k++) if (queue.get(k).id == id) i = k;
            failures = 0;
            load(i, true);
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            previous();
        }
        if (queue.isEmpty()) stopSelf(); // nothing to play (e.g. restarted with no queue)
        return START_NOT_STICKY;
    }

    // --- State read by the screens ---

    Library.Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    boolean isPlaying() {
        return prepared && player.isPlaying();
    }

    /** Whether play is wanted, including while a track is still preparing. Drives the play/pause icon. */
    boolean isPlayingOrBuffering() {
        return playWhenReady;
    }

    /** Whether music goes (or would go) to Bluetooth headphones or a speaker rather than the watch speaker. */
    boolean onHeadphones() {
        return headphones() != null;
    }

    private AudioDeviceInfo headphones() {
        AudioDeviceInfo routed = prepared ? player.getRoutedDevice() : null;
        if (routed != null) return isHeadphones(routed) ? routed : null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (isHeadphones(d)) return d;
        return null;
    }

    private static boolean isHeadphones(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    long position() {
        return prepared ? player.getCurrentPosition() : 0;
    }

    long duration() {
        if (prepared && player.getDuration() > 0) return player.getDuration();
        Library.Track t = current();
        return t == null ? 0 : t.durationMs;
    }

    // --- Transport ---

    void toggle() {
        if (playWhenReady) pause(); else play();
    }

    void play() {
        if (current() == null) return;
        playWhenReady = true;
        if (player == null) load(index, true);
        else if (prepared) start();
        publish();
    }

    void pause() {
        playWhenReady = false;
        resumeOnFocusGain = false;
        if (isPlaying()) player.pause();
        audio.abandonAudioFocusRequest(focusRequest);
        setNoisyReceiver(false);
        handler.removeCallbacks(idleStop);
        handler.postDelayed(idleStop, IDLE_STOP_MS);
        publish();
    }

    void next() {
        if (index + 1 < queue.size()) load(index + 1, playWhenReady);
    }

    /** Like a CD player: restart the song if it's a few seconds in, otherwise go back one. */
    void previous() {
        if (position() > 3000 || index == 0) seekTo(0);
        else load(index - 1, playWhenReady);
    }

    void seekTo(long ms) {
        if (prepared) player.seekTo((int) ms);
        publish();
    }

    private void load(int i, boolean play) {
        if (i < 0 || i >= queue.size()) return;
        releasePlayer();
        index = i;
        playWhenReady = play;
        Library.Track t = queue.get(i);
        player = new MediaPlayer();
        player.setAudioAttributes(ATTRS);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        MediaPlayer mp = player;
        player.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            if (playWhenReady) start();
            publish();
        });
        player.setOnCompletionListener(p -> {
            failures = 0;
            if (index + 1 < queue.size()) load(index + 1, true);
            else load(0, false); // end of the queue: back to the top, paused
        });
        player.setOnErrorListener((p, what, extra) -> {
            Log.w(TAG, "can't play " + t.title + " (" + what + "/" + extra + ")");
            skipBroken();
            return true;
        });
        try {
            mp.setDataSource(this, t.uri());
            mp.prepareAsync();
            Log.i(TAG, "loading " + t.artist + " - " + t.title);
        } catch (Exception e) {
            Log.w(TAG, "can't open " + t.title, e);
            handler.post(this::skipBroken);
        }
        publish();
    }

    private void skipBroken() {
        if (++failures < queue.size() && index + 1 < queue.size()) load(index + 1, playWhenReady);
        else { releasePlayer(); playWhenReady = false; publish(); }
    }

    private void start() {
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "audio focus denied");
            playWhenReady = false;
            return;
        }
        handler.removeCallbacks(idleStop);
        player.start();
        setNoisyReceiver(true);
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: // e.g. a call ringing; ducking is left to the system
                if (playWhenReady) { // pause but keep the focus request, so GAIN comes back to us
                    playWhenReady = false;
                    if (isPlaying()) player.pause();
                    resumeOnFocusGain = true;
                    publish();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain) play();
                break;
        }
    }

    private void releasePlayer() {
        if (player != null) player.release();
        player = null;
        prepared = false;
    }

    private void setNoisyReceiver(boolean on) {
        if (on == noisyRegistered) return;
        if (on) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED);
        else unregisterReceiver(noisy);
        noisyRegistered = on;
    }

    // --- Session and notification ---

    private void publish() {
        Library.Track t = current();
        if (t == null) return;
        if (t.id != artId) { // new song: fetch its cover, then publish again with it
            artId = t.id;
            art = null;
            Art.load(this, t, ART_PX, b -> {
                if (b == null || current() != t) return;
                art = b;
                publish();
            });
        }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build());
        int state = !playWhenReady ? PlaybackState.STATE_PAUSED
                : prepared ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(state, position(), state == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Library.Track t = current();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(t == null ? "Music" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setContentIntent(openNowPlaying())
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV))
                .addAction(playWhenReady ? action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                        : action(R.drawable.ic_play, "Play", ACTION_TOGGLE))
                .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int icon, String label, String act) {
        PendingIntent pi = PendingIntent.getService(this, act.hashCode(),
                new Intent(this, PlayerService.class).setAction(act), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                label, pi).build();
    }

    private PendingIntent openNowPlaying() {
        return PendingIntent.getActivity(this, 0, new Intent(this, NowPlayingActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        setNoisyReceiver(false);
        audio.abandonAudioFocusRequest(focusRequest);
        releasePlayer();
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
