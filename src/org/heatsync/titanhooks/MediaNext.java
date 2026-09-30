package org.heatsync.titanhooks;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.KeyEvent;

// side key -> next track in whatever is playing. the system routes the media key to the
// app that last played, so this works for every player without naming any.
public final class MediaNext extends Activity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    AudioManager am = getSystemService(AudioManager.class);
    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
    finish();
  }
}
