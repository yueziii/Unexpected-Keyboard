package juloo.keyboard2.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import juloo.keyboard2.R;

/** Offline speech recognition with sherpa-onnx, running a streaming model
    (streaming Paraformer or streaming Zipformer) downloaded on demand by
    [VoiceModelManager].

    Audio is captured with [AudioRecord] on a dedicated thread and fed to
    the recognizer in 100 ms chunks; interim results update the composing
    text while the mic key is held. The built-in endpoint detection closes
    a sentence with a full stop after ~1.4 s of trailing silence, giving
    punctuation like mainstream IMEs. Releasing the key flushes the
    recognizer and delivers the final transcript. */
final class SherpaVoiceEngine implements VoiceEngine
{
  static final int SAMPLE_RATE = 16000;
  /** 100 ms of audio per recognizer feed. */
  static final int CHUNK = SAMPLE_RATE / 10;
  /** Directory under [Context.getFilesDir()] holding the installed models. */
  public static final String MODELS_DIR = "voice";

  final VoiceModelManager.ModelSpec _spec;
  Thread _thread = null;
  volatile boolean _stop_requested = false;
  volatile boolean _cancelled = false;
  Callback _cb = null;

  SherpaVoiceEngine(VoiceModelManager.ModelSpec spec)
  {
    _spec = spec;
  }

  /** Engine for the selected installed model, or null when none is
      installed. The model is loaded later, on the recording thread. */
  public static VoiceEngine create_if_installed(Context ctx)
  {
    VoiceModelManager.ModelSpec spec = VoiceModelManager.load_selected_spec(ctx);
    if (spec == null)
      return null;
    try
    {
      return new SherpaVoiceEngine(spec);
    }
    catch (Throwable _e)
    {
      // E.g. the native library is unavailable on this device.
      return null;
    }
  }

  public boolean is_available(Context ctx)
  {
    return true;
  }

  public boolean needs_record_audio()
  {
    return true;
  }

  public void start(Context ctx, Callback cb)
  {
    _cb = cb;
    _stop_requested = false;
    _cancelled = false;
    _thread = new Thread(new Runnable()
      {
        public void run() { record_loop(); }
      });
    _thread.setPriority(Thread.MAX_PRIORITY);
    _thread.start();
  }

  public void stop()
  {
    _stop_requested = true;
  }

  public void cancel()
  {
    _cancelled = true;
    _stop_requested = true;
  }

  public void release()
  {
    // The recording thread frees the recorder and the recognizer; wait for
    // it so the microphone is released when this returns.
    Thread t = _thread;
    if (t != null)
    {
      try { t.join(1000); } catch (InterruptedException e) { }
      _thread = null;
    }
  }

  /** Feed audio to the recognizer until [stop] or [cancel], then finalize.
      Runs on the recording thread; callbacks are marshalled to the main
      thread by [VoiceInputController]. */
  void record_loop()
  {
    AudioRecord recorder = null;
    OnlineRecognizer recognizer = null;
    OnlineStream stream = null;
    try
    {
      recognizer = new OnlineRecognizer(null, build_config());
      stream = recognizer.createStream("");
      int min_buf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
          SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT, Math.max(min_buf * 4, CHUNK * 4));
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
        throw new Exception("AudioRecord initialization failed");
      recorder.startRecording();
      short[] samples = new short[CHUNK];
      float[] floats = new float[CHUNK];
      // Text of the sentences already closed by endpoint detection.
      String committed = "";
      String last_partial = null;
      while (!_stop_requested)
      {
        int n = recorder.read(samples, 0, CHUNK);
        if (n < 0)
          throw new Exception("AudioRecord read failed: " + n);
        if (n == 0)
          continue;
        for (int i = 0; i < n; i++)
          floats[i] = samples[i] / 32768.f;
        stream.acceptWaveform(floats, n);
        while (recognizer.isReady(stream))
          recognizer.decode(stream);
        if (recognizer.isEndpoint(stream))
        {
          // A sentence ended with trailing silence: close it with a full
          // stop and start a new segment, like mainstream IMEs.
          committed = committed + recognizer.getResult(stream).getText() + "。";
          recognizer.reset(stream);
        }
        String partial = committed + recognizer.getResult(stream).getText();
        if (!partial.equals(last_partial))
        {
          last_partial = partial;
          _cb.on_partial(partial);
        }
      }
      // The mic key was released: flush the recognizer for the final text.
      stream.inputFinished();
      while (recognizer.isReady(stream))
        recognizer.decode(stream);
      if (!_cancelled)
        _cb.on_final(committed + recognizer.getResult(stream).getText());
    }
    catch (Throwable e)
    {
      if (!_cancelled)
        _cb.on_error(R.string.voice_error_local);
    }
    finally
    {
      if (recorder != null)
      {
        try { recorder.stop(); } catch (Exception _e) {}
        recorder.release();
      }
      if (stream != null)
        stream.release();
      if (recognizer != null)
        recognizer.release();
    }
  }

  OnlineRecognizerConfig build_config()
  {
    FeatureConfig feat = new FeatureConfig();
    feat.setSampleRate(_spec.sampleRate);
    feat.setFeatureDim(_spec.featureDim);
    OnlineModelConfig model = new OnlineModelConfig();
    model.setTokens(_spec.file("tokens").getAbsolutePath());
    model.setNumThreads(2);
    if ("streaming_paraformer".equals(_spec.type))
    {
      OnlineParaformerModelConfig p = new OnlineParaformerModelConfig();
      p.setEncoder(_spec.file("encoder").getAbsolutePath());
      p.setDecoder(_spec.file("decoder").getAbsolutePath());
      model.setParaformer(p);
    }
    else
    {
      // streaming_zipformer (transducer)
      OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
      t.setEncoder(_spec.file("encoder").getAbsolutePath());
      t.setDecoder(_spec.file("decoder").getAbsolutePath());
      t.setJoiner(_spec.file("joiner").getAbsolutePath());
      model.setTransducer(t);
    }
    OnlineRecognizerConfig config = new OnlineRecognizerConfig();
    config.setFeatConfig(feat);
    config.setModelConfig(model);
    // Endpoint detection with the default rules (a sentence closes after
    // ~1.4 s of trailing silence) provides the punctuation above.
    config.setEnableEndpoint(true);
    return config;
  }
}
