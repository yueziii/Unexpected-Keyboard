package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Minimal floating keyboard: just translate the keyboard container upward
 * using setTranslationY, and set TOUCHABLE_INSETS_REGION so touches outside
 * the keyboard pass through to the app behind.
 *
 * Does NOT modify the IME window LayoutParams at all. The window stays
 * MATCH_PARENT width, WRAP_CONTENT height, BOTTOM gravity (as the system
 * expects). The container is simply translated up.
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
  /** Desired Y of the keyboard top (from screen top). */
  private float _posY;
  private float _dragStartRawY;
  private float _dragStartTranslation;
  private boolean _isDragging;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    _screenWidth = ime.getResources().getDisplayMetrics().widthPixels;
    _screenHeight = ime.getResources().getDisplayMetrics().heightPixels;
    _posY = _config.floating_pos_y;
  }

  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
  }

  /**
   * Apply floating translation to the container.
   * Called from setInputView after the view has been added to the window.
   * We use addOnLayoutChangeListener to apply translation AFTER the container
   * has been measured and positioned (calling setTranslationY before layout
   * gets overridden by the layout pass).
   *
   * The listener removes itself after first successful application to avoid
   * infinite loops.
   */
  public void applyPosition(final View container)
  {
    _container = container;
    final View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener()
    {
      @Override
      public void onLayoutChange(View v, int left, int top, int right, int bottom,
          int oldLeft, int oldTop, int oldRight, int oldBottom)
      {
        int h = bottom - top;
        if (h <= 0) return;
        // Natural top = where the container is on screen without translation.
        // Since the IME window is BOTTOM gravity, the container's natural
        // position is at the bottom of the screen: naturalTop = screenHeight - h.
        float naturalTop = _screenHeight - h;
        // Default position: 30% from top
        float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.3f : _posY;
        // Clamp
        if (targetY < 0) targetY = 0;
        if (targetY + h > _screenHeight) targetY = _screenHeight - h;
        float translation = targetY - naturalTop;
        v.setTranslationY(translation);
        // Request inset update so touchable region matches new position
        v.requestApplyInsets();
        // Remove listener — we only need to apply once per setInputView call
        v.removeOnLayoutChangeListener(this);
        Log.d(TAG, "applyPosition h=" + h + " naturalTop=" + naturalTop
          + " targetY=" + targetY + " translationY=" + translation);
      }
    };
    container.addOnLayoutChangeListener(listener);
    // Fallback: also post() in case layout already happened
    container.post(new Runnable()
    {
      @Override
      public void run()
      {
        int h = container.getHeight();
        if (h > 0 && container.getTranslationY() == 0)
        {
          float naturalTop = _screenHeight - h;
          float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.3f : _posY;
          if (targetY < 0) targetY = 0;
          if (targetY + h > _screenHeight) targetY = _screenHeight - h;
          float translation = targetY - naturalTop;
          container.setTranslationY(translation);
          container.requestApplyInsets();
          Log.d(TAG, "applyPosition(post) h=" + h + " translationY=" + translation);
        }
      }
    });
  }

  /**
   * Reset floating state.
   */
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
      // Container not laid out yet — don't restrict, use defaults
      return;
    }
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + w;
    int bottom = top + h;
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(left, top, right, bottom);
    // contentTopInsets=0: don't push app content up (keyboard floats over app)
    outInsets.contentTopInsets = 0;
    outInsets.visibleTopInsets = 0;
    Log.d(TAG, "computeInsets region=[" + left + "," + top + "," + right + "," + bottom + "]");
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
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && Math.abs(dy) > 10)
          _isDragging = true;
        if (_isDragging)
        {
          float translation = _dragStartTranslation + dy;
          // Clamp so keyboard stays on screen
          int h = _container.getHeight();
          float naturalTop = _screenHeight - h;
          float currentTop = naturalTop + translation;
          if (currentTop < 0) translation = -naturalTop;
          if (currentTop + h > _screenHeight) translation = _screenHeight - h - naturalTop;
          _container.setTranslationY(translation);
          _posY = naturalTop + translation;
          _container.requestApplyInsets();
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
