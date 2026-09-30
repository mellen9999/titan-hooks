# titan-hooks

one tiny apk for the unihertz titan 2: things a physical side key should do that no
stock app does. each hook is an exported activity with no ui, so the phone's own
"shortcut keys" setting can launch it. no service, no polling: the one background piece
is a notification listener the system calls into on media state changes.

## hooks

**next track** — `org.heatsync.titanhooks.MediaNext`
sends the media-next key to whatever is playing. works for every player.

**music delete** — `org.heatsync.titanhooks.MusicDelete`
skips musicolet to the next track and deletes the one that was playing from the
`Music` folder, which syncthing then removes from every other machine. the file is
found by title + artist (+ album) in the media store, and it refuses when the match
is missing, ambiguous, or outside `Music`. every delete is appended to
`Music/.deleted.log`. a toast says what happened.

## the wanted list

while bound, the listener watches youtube music. a track that was actually heard (80% of
its length spent playing) is appended to `Music/.wanted`:

    2026-09-29T02:41:07Z	youtube-music	Duran Duran	Ordinary World	Duran Duran	340241

syncthing carries that file to a machine running [played](https://github.com/mellen9999/played),
which fetches the track and syncs the file back here. the phone spends no data or battery on
downloading, and never edits the file after appending.

## build

    ./build.sh          # needs android build-tools + platform android.jar, javac, zip

## install (once, over adb)

    adb install -r build/titan-hooks.apk
    adb shell cmd notification allow_listener org.heatsync.titanhooks/.MediaListener
    adb shell appops set --uid org.heatsync.titanhooks MANAGE_EXTERNAL_STORAGE allow
    adb shell pm grant org.heatsync.titanhooks android.permission.READ_MEDIA_AUDIO

the notification-listener grant is what lets the app see musicolet's media session;
it never reads notifications. all-files access is what lets it delete a file it did
not create.

then bind keys: settings > shortcut keys > pick the key and press type > apps > titan hooks.
or over adb (func1 = top key, func2 = bottom key; the bottom key can bounce into a
double press, so bind its double the same as its short):

    adb shell settings put system func1_double_press_package org.heatsync.titanhooks
    adb shell settings put system func1_double_press_activity org.heatsync.titanhooks.MusicDelete
    adb shell settings put system func2_short_press_package org.heatsync.titanhooks
    adb shell settings put system func2_short_press_activity org.heatsync.titanhooks.MediaNext
    adb shell settings put system func2_double_press_package org.heatsync.titanhooks
    adb shell settings put system func2_double_press_activity org.heatsync.titanhooks.MediaNext

the key handler reads these on every press. the mode radio (shortcut settings vs
programmable key) is only applied when saved from the phone's own settings screen.
