package juloo.keyboard2.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;
import juloo.keyboard2.R;

/** The system speech recognition service, through [SpeechRecognizer]. Free
    and without extra permissions, but it only works on devices shipping a
    recognition service: GMS devices (Google), some vendor ROMs. Chinese
    ROMs without GMS (vivo, ...) usually have none — [is_available] returns
    false there and the offline engine is used instead. */
final class SystemVoiceEngine implements VoiceEngine
{
  SpeechRecognizer _recognizer = null;
  Callback _cb = null;

  public boolean is_available(Context ctx)
  {
    return SpeechRecognizer.isRecognitionAvailable(ctx);
  }

  public boolean needs_record_audio()
  {
    // Recording happens in the recognition service's process.
    return false;
  }

  public void start(Context ctx, Callback cb)
  {
    _cb = cb;
    _recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
    _recognizer.setRecognitionListener(new Listener());
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    // Follow the system locale so English devices dictate English and
    // Chinese devices dictate Chinese.
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
        Locale.getDefault().toString());
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    _recognizer.startListening(intent);
  }

  public void stop()
  {
    if (_recognizer != null)
      // The service finalizes and delivers the final result; some services
      // answer with an error instead, handled in [Listener.onError].
      _recognizer.stopListening();
  }

  public void cancel()
  {
    if (_recognizer != null)
      _recognizer.cancel();
    _cb = null;
  }

  public void release()
  {
    if (_recognizer != null)
    {
      _recognizer.destroy();
      _recognizer = null;
    }
    _cb = null;
  }

  class Listener implements RecognitionListener
  {
    public void onPartialResults(Bundle partialResults)
    {
      if (_cb != null)
      {
        String text = first_result(partialResults);
        if (text != null)
          _cb.on_partial(text);
      }
    }

    public void onResults(Bundle results)
    {
      if (_cb != null)
      {
        String text = first_result(results);
        _cb.on_final(text == null ? "" : text);
      }
      _cb = null;
    }

    public void onError(int error)
    {
      Callback cb = _cb;
      _cb = null;
      if (cb == null)
        return;
      // Nothing recognizable was said; not worth an error message.
      if (error == SpeechRecognizer.ERROR_NO_MATCH
          || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
      {
        cb.on_final("");
        return;
      }
      cb.on_error(R.string.voice_error_system);
    }

    public void onReadyForSpeech(Bundle params) {}
    public void onBeginningOfSpeech() {}
    public void onRmsChanged(float rmsdB) {}
    public void onBufferReceived(byte[] buffer) {}
    public void onEndOfSpeech() {}
    public void onEvent(int eventType, Bundle params) {}
  }

  static String first_result(Bundle results)
  {
    ArrayList<String> strings =
      results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (strings == null || strings.isEmpty())
      return null;
    return strings.get(0);
  }
}
