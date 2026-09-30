package org.heatsync.titanhooks;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Environment;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// two jobs. holding notification access is what lets the hooks see media sessions at all.
// and while bound, it watches youtube music: a track that was actually heard (>= 80% of
// its length spent playing) is appended to Music/.wanted, which syncthing carries to the
// machine that runs `played`, which fetches it and syncs the file back. nothing polls:
// the system calls in on state changes only.
public final class MediaListener extends NotificationListenerService {
  static final String TAG = "titanhooks";
  static final String WATCH = "com.google.android.apps.youtube.music";
  static final String SOURCE = "youtube-music";
  static final File WANTED = new File(Environment.getExternalStorageDirectory(), "Music/.wanted");
  static final int THRESHOLD_PCT = 80;

  MediaSessionManager msm;
  MediaController watched;
  final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::sessions;
  final MediaController.Callback cb = new MediaController.Callback() {
    @Override public void onMetadataChanged(MediaMetadata md) { track(md); }
    @Override public void onPlaybackStateChanged(PlaybackState st) { state(st); }
    @Override public void onSessionDestroyed() { detach(); }
  };

  // the track being accounted for
  String key = "", artist = "", title = "", album = "";
  long durationMs, playedMs, playingSince = -1;

  @Override public void onListenerConnected() {
    msm = getSystemService(MediaSessionManager.class);
    ComponentName me = new ComponentName(this, MediaListener.class);
    msm.addOnActiveSessionsChangedListener(onSessions, me);
    sessions(msm.getActiveSessions(me));
  }

  @Override public void onListenerDisconnected() {
    detach();
    if (msm != null) msm.removeOnActiveSessionsChangedListener(onSessions);
  }

  void sessions(List<MediaController> all) {
    MediaController found = null;
    if (all != null) for (MediaController c : all) if (WATCH.equals(c.getPackageName())) found = c;
    if (found == null) { detach(); return; }
    if (watched != null && watched.getSessionToken().equals(found.getSessionToken())) return;
    detach();
    watched = found;
    watched.registerCallback(cb);
    track(watched.getMetadata());
    state(watched.getPlaybackState());
  }

  void detach() {
    settle();
    if (watched != null) watched.unregisterCallback(cb);
    watched = null;
    key = "";
  }

  void track(MediaMetadata md) {
    if (md == null) return;
    String a = str(md, MediaMetadata.METADATA_KEY_ARTIST), t = str(md, MediaMetadata.METADATA_KEY_TITLE);
    String k = a + "\u0000" + t;
    if (!k.equals(key)) {
      settle();
      key = k; artist = a; title = t;
      playedMs = 0;
      playingSince = playingSince < 0 ? -1 : SystemClock.elapsedRealtime();
    }
    album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
    durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
  }

  void state(PlaybackState st) {
    boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
    long now = SystemClock.elapsedRealtime();
    if (playing && playingSince < 0) playingSince = now;
    if (!playing && playingSince >= 0) { playedMs += now - playingSince; playingSince = -1; }
  }

  // close the books on the current track: heard enough of it -> it is wanted
  void settle() {
    if (playingSince >= 0) { long now = SystemClock.elapsedRealtime(); playedMs += now - playingSince; playingSince = now; }
    if (key.isEmpty() || title.isEmpty() || durationMs <= 0) return;
    long pct = playedMs * 100 / durationMs;
    if (pct < THRESHOLD_PCT) return;
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    String line = f.format(new Date()) + "\t" + SOURCE + "\t" + clean(artist) + "\t" + clean(title)
        + "\t" + clean(album) + "\t" + durationMs + "\n";
    try (FileWriter w = new FileWriter(WANTED, true)) {
      w.write(line);
    } catch (Exception e) {
      Log.w(TAG, "wanted: " + e);
    }
    playedMs = 0;  // a repeat of the same track has to earn it again
  }

  static String str(MediaMetadata md, String k) {
    CharSequence v = md.getText(k);
    return v == null ? "" : v.toString().trim();
  }

  // one record per line, tab separated: strip anything that could break that
  static String clean(String s) { return s.replaceAll("[\\p{Cntrl}]", " ").trim(); }
}
