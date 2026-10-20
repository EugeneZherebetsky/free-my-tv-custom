package io.freemytv;

/**
 * The screensaver on Android 11, in the app's main process. Android 11 opens a screensaver's window (DreamActivity) in
 * the app's main process and hands it the service directly, which crashes when the service runs in :home (fixed in
 * Android 12). Only one of the two is enabled on each TV (the bools in res/values and res/values-v31).
 */
public class Screensaver11 extends Screensaver {
}
