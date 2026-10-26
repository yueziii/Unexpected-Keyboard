package juloo.keyboard2.voice;

import android.content.Context;

/** A speech-to-text engine. Implementations record audio while the user
    holds the mic key and deliver recognition results through a [Callback].

    An engine instance is used for a single recording session: [start] is
    called when the user presses the mic key, [stop] when they release it.
    Callbacks are always delivered on the main thread. */
public interface VoiceEngine
{
  /** Whether the engine can run on this device right now (recognition
      service present, model installed). */
  boolean is_available(Context ctx);

  /** Whether the engine records audio in this app's process and thus
      needs the RECORD_AUDIO permission. The system engine delegates
      recording to the recognition service and does not. */
  boolean needs_record_audio();

  /** Start recording; results are delivered to [cb] on the main thread. */
  void start(Context ctx, Callback cb);

  /** Stop recording and finalize the recognition. The final transcript
      arrives via [Callback.on_final]. */
  void stop();

  /** Abort the session; no further callbacks are delivered. */
  void cancel();

  /** Free the engine resources (recognizer, model memory). */
  void release();

  public interface Callback
  {
    /** Interim transcript while the user is still holding the key. */
    void on_partial(String text);
    /** Final transcript, possibly empty when nothing was recognized.
        Always the last callback of a session. */
    void on_final(String text);
    /** The session failed; [message_res] is a string resource explaining
        why. Always the last callback of a session. */
    void on_error(int message_res);
  }
}
