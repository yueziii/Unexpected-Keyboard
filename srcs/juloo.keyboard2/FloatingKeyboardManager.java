package juloo.keyboard2;

import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

/**
 * Manages the "floating keyboard" mode: the keyboard is displayed as a
 * movable, free-positioned overlay instead of being docked at the bottom.
 *
 * How it works:
 *  - The IME window stays full-screen (TYPE_INPUT_METHOD is forced to
 *    MATCH_PARENT by the system), but the input area parent uses
 *    WRAP_CONTENT + Gravity.TOP|START so the keyboard container starts at
 *    the top-left.
 *  - The keyboard container is translated via setX/setY to the desired
 *    floating position.
 *  - onComputeInsets sets TOUCHABLE_INSETS_REGION to only the keyboard's
 *    screen rectangle, so touches outside the keyboard pass through to the
 *    app behind it (the window doesn't block the rest of the screen).
 *  - A drag handle view at the top of the keyboard lets the user move it.
 *  - The position is persisted and restored across sessions.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private final Keyboard2 _ime;
  private final Config _config;

  /** The current view being floated (keyboard container or emoji/clipboard pane). */
  private View _container;

  /** The drag handle view (only present on the main keyboard). */
  private View _handle;

  /** Screen dimensions used for clamping and default positioning. */
  private int _screenWidth;
  private int _screenHeight;

  /** Touch tracking for dragging. */
  private float _dragStartRawX;
  private float _dragStartRawY;
  private float _dragStartX;
  private float _dragStartY;
  private boolean _isDragging;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    Resources res = ime.getResources();
    DisplayMetrics dm = res.getDisplayMetrics();
    _screenWidth = dm.widthPixels;
    _screenHeight = dm.heightPixels;
  }

  /** Bind a container view and its drag handle (handle may be null). */
  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
  }

  /** Desired keyboard width in pixels, based on the config percentage. */
  public int getKeyboardWidthPx()
  {
    int pct = _config.floating_keyboard_width;
    if (pct < 40) pct = 40;
    if (pct > 100) pct = 100;
    return _screenWidth * pct / 100;
  }

  public int getScreenWidthPx() { return _screenWidth; }
  public int getScreenHeightPx() { return _screenHeight; }

  /** Apply the saved (or default) floating position to the given container. */
  public void applyPosition(View container)
  {
    _container = container;
    float x = _config.floating_pos_x;
    float y = _config.floating_pos_y;
    if (x == Float.MIN_VALUE || y == Float.MIN_VALUE)
    {
      int w = getKeyboardWidthPx();
      x = Math.max(0, (_screenWidth - w) / 2.f);
      y = _screenHeight * 0.5f;
    }
    x = clampX(x);
    y = clampY(y);
    container.setX(x);
    container.setY(y);
  }

  /**
   * Compute insets for floating mode: set the touchable region to only the
   * keyboard container's screen rectangle, so the rest of the window passes
   * touches through to the app behind.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    // Get the container's position in screen coordinates.
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + container.getWidth();
    int bottom = top + container.getHeight();
    // Ensure the region is valid.
    if (right <= left) right = left + getKeyboardWidthPx();
    if (bottom <= top) bottom = top + (_screenHeight / 3);
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(left, top, right, bottom);
    // Don't let the system resize the app for the IME — the floating keyboard
    // floats over the app content.
    outInsets.contentTopInsets = _screenHeight;
    outInsets.visibleTopInsets = _screenHeight;
  }

  private int getContainerWidth()
  {
    if (_container != null)
    {
      int w = _container.getWidth();
      if (w > 0)
        return w;
    }
    return getKeyboardWidthPx();
  }

  private int getContainerHeight()
  {
    if (_container != null)
    {
      int h = _container.getHeight();
      if (h > 0)
        return h;
    }
    return _screenHeight / 3;
  }

  private float clampX(float x)
  {
    int w = getContainerWidth();
    if (x + w > _screenWidth)
      x = _screenWidth - w;
    if (x < 0)
      x = 0;
    return x;
  }

  private float clampY(float y)
  {
    int h = getContainerHeight();
    if (y + h > _screenHeight)
      y = _screenHeight - h;
    if (y < 0)
      y = 0;
    return y;
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if (_container == null)
      return false;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        _dragStartRawX = event.getRawX();
        _dragStartRawY = event.getRawY();
        _dragStartX = _container.getX();
        _dragStartY = _container.getY();
        _isDragging = false;
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dx = event.getRawX() - _dragStartRawX;
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8))
          _isDragging = true;
        if (_isDragging)
        {
          _container.setX(clampX(_dragStartX + dx));
          _container.setY(clampY(_dragStartY + dy));
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
        {
          _config.save_floating_pos(_container.getX(), _container.getY());
        }
        _isDragging = false;
        return true;
    }
    return false;
  }
}
