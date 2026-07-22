package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Minimal floating keyboard: translate the keyboard container upward using
 * setTranslationY, and restrict touchable area via onComputeInsets.
 *
 * The IME window is NOT modified at all. The container is simply translated.
 * naturalTop (the container's on-screen Y without translation) is obtained
 * from getLocationOnScreen() AFTER layout completes, so it's always accurate
 * regardless of system bar heights, navigation bar, etc.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private static final String TAG = "FloatingKeyboard";

  private final Keyboard2 _ime;
  private final Config _config;
  private View _container;
  private View _handle;
  private int _screenWidth;
  private int _screenHeight;
  private float _posY;
  private float _dragStartRawY;
  private float _dragStartTranslation;
  private boolean _isDragging;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    // Use display metrics for screen dimensions
    DisplayMetrics dm = ime.getResources().getDisplayMetrics();
    _screenWidth = dm.widthPixels;
    _screenHeight = dm.heightPixels;
    _posY = _config.floating_pos_y;
    Log.d(TAG, "init screenWidth=" + _screenWidth + " screenHeight=" + _screenHeight);
  }

  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
  }

  /**
   * Apply floating translation. Uses OnLayoutChangeListener (one-shot, removes
   * itself after applying) to ensure translation is applied AFTER layout.
   * Also uses post() as a fallback.
   *
   * naturalTop is obtained from getLocationOnScreen() (accurate) minus the
   * current translationY. This avoids any dependency on _screenHeight for
   * computing the natural position.
   */
  public void applyPosition(final View container)
  {
    _container = container;
    Log.d(TAG, "applyPosition CALLED container=" + container
      + " w=" + container.getWidth() + " h=" + container.getHeight()
      + " floating_keyboard=" + _config.floating_keyboard);
    // One-shot layout listener
    final View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener()
    {
      @Override
      public void onLayoutChange(View v, int left, int top, int right, int bottom,
          int oldLeft, int oldTop, int oldRight, int oldBottom)
      {
        int h = bottom - top;
        if (h <= 0) return;
        v.removeOnLayoutChangeListener(this);
        doTranslation(v);
      }
    };
    container.addOnLayoutChangeListener(listener);
    // Fallback
    container.post(new Runnable()
    {
      @Override
      public void run()
      {
        if (container.getHeight() > 0 && container.getTranslationY() == 0)
          doTranslation(container);
      }
    });
  }

  /**
   * Actually compute and apply the translation.
   */
  private void doTranslation(View container)
  {
    int h = container.getHeight();
    if (h <= 0)
    {
      Log.d(TAG, "doTranslation: h=0, skipping");
      return;
    }
    // Get the container's actual on-screen position, then undo current
    // translation to find the natural (un-translated) position.
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    float naturalTop = loc[1] - container.getTranslationY();
    Log.d(TAG, "doTranslation: loc[1]=" + loc[1] + " curTranslationY=" + container.getTranslationY()
      + " naturalTop=" + naturalTop + " h=" + h + " screenHeight=" + _screenHeight);
    // Sanity check: if naturalTop is 0 or negative, the window may not be
    // positioned yet. Use screenHeight - h as fallback.
    if (naturalTop <= 0 || naturalTop > _screenHeight)
    {
      Log.d(TAG, "doTranslation: naturalTop looks wrong, using fallback screenHeight-h");
      naturalTop = _screenHeight - h;
    }
    // Default: 30% from top
    float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.3f : _posY;
    if (targetY < 0) targetY = 0;
    if (targetY + h > _screenHeight) targetY = _screenHeight - h;
    float translation = targetY - naturalTop;
    Log.d(TAG, "doTranslation: targetY=" + targetY + " translation=" + translation);
    container.setTranslationY(translation);
    container.requestApplyInsets();
  }

  public void disableFloating(View container)
  {
    _container = container;
    if (container != null)
      container.setTranslationY(0f);
  }

  /**
   * Set touchable region to the container's on-screen rect.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    int w = container.getWidth();
    int h = container.getHeight();
    if (w <= 0 || h <= 0)
    {
      Log.d(TAG, "computeInsets: container not ready w=" + w + " h=" + h);
      return;
    }
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + w;
    int bottom = top + h;
    // DON'T set contentTopInsets/visibleTopInsets — let super.onComputeInsets
    // keep its default values. Setting contentTopInsets=0 caused the system
    // to think the IME covers the entire screen, which may have caused the
    // system to shrink/hide the IME window.
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(left, top, right, bottom);
    Log.d(TAG, "computeInsets region=[" + left + "," + top + "," + right + "," + bottom
      + "] translationY=" + container.getTranslationY());
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if (_container == null) return false;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        _dragStartRawY = event.getRawY();
        _dragStartTranslation = _container.getTranslationY();
        _isDragging = false;
        Log.d(TAG, "drag DOWN rawY=" + event.getRawY()
          + " startTranslation=" + _dragStartTranslation);
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && Math.abs(dy) > 10)
          _isDragging = true;
        if (_isDragging)
        {
          float translation = _dragStartTranslation + dy;
          _container.setTranslationY(translation);
          _container.requestApplyInsets();
          // Update saved position
          int h = _container.getHeight();
          int[] loc = new int[2];
          _container.getLocationOnScreen(loc);
          _posY = loc[1];
          Log.d(TAG, "drag MOVE dy=" + dy + " translation=" + translation + " posY=" + _posY);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
          _config.save_floating_pos(0, _posY);
        _isDragging = false;
        return true;
    }
    return false;
  }
}
