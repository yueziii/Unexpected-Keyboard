package juloo.keyboard2;

import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * Manages the "floating keyboard" mode.
 *
 * The IME window's width stays MATCH_PARENT (forced by the system), but its
 * height is WRAP_CONTENT (default for non-fullscreen IME). The window's
 * gravity is changed from BOTTOM to TOP|START and x/y position it freely.
 * onComputeInsets sets TOUCHABLE_INSETS_REGION so only the keyboard rectangle
 * is touchable — the rest of the window passes touches through.
 *
 * The keyboard container width is constrained to the floating width (e.g. 80%
 * of screen), centered within the full-width window.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private final Keyboard2 _ime;
  private final Config _config;

  private View _container;
  private View _handle;

  private int _screenWidth;
  private int _screenHeight;

  private float _posX;
  private float _posY;

  private float _dragStartRawX;
  private float _dragStartRawY;
  private float _dragStartPosX;
  private float _dragStartPosY;
  private boolean _isDragging;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    Resources res = ime.getResources();
    DisplayMetrics dm = res.getDisplayMetrics();
    _screenWidth = dm.widthPixels;
    _screenHeight = dm.heightPixels;
    _posX = _config.floating_pos_x;
    _posY = _config.floating_pos_y;
  }

  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
  }

  public int getKeyboardWidthPx()
  {
    int pct = _config.floating_keyboard_width;
    if (pct < 40) pct = 40;
    if (pct > 100) pct = 100;
    return _screenWidth * pct / 100;
  }

  public int getScreenWidthPx() { return _screenWidth; }
  public int getScreenHeightPx() { return _screenHeight; }

  /**
   * Position the IME window via its LayoutParams gravity + x/y.
   * Called whenever the view is set or size changes.
   */
  public void applyFloatingWindowLayout(Window window)
  {
    if (window == null)
      return;
    WindowManager.LayoutParams lp = window.getAttributes();
    if (lp == null)
      return;
    // MINIMAL TEST: just change window gravity from BOTTOM to TOP.
    // This should move the keyboard to the top of the screen.
    lp.gravity = Gravity.TOP | Gravity.START;
    float x = _posX;
    float y = _posY;
    if (x == Float.MIN_VALUE || y == Float.MIN_VALUE)
    {
      x = 0;
      y = _screenHeight * 0.3f;
    }
    lp.x = (int) x;
    lp.y = (int) y;
    window.setAttributes(lp);
    Logs.debug("Floating: applyFloatingWindowLayout gravity=" + lp.gravity
      + " x=" + lp.x + " y=" + lp.y + " w=" + lp.width + " h=" + lp.height);
  }

    /**
   * Compute insets: only the keyboard container's screen rectangle is
   * touchable. The rest of the full-screen window passes touches through.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + container.getWidth();
    int bottom = top + container.getHeight();
    if (right <= left) right = left + getKeyboardWidthPx();
    if (bottom <= top) bottom = top + (_screenHeight / 3);
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(left, top, right, bottom);
    // Don't resize the app — floating keyboard overlays content.
    outInsets.contentTopInsets = _screenHeight;
    outInsets.visibleTopInsets = _screenHeight;
  }

  private int getContainerWidth()
  {
    if (_container != null)
    {
      int w = _container.getWidth();
      if (w > 0) return w;
    }
    return getKeyboardWidthPx();
  }

  private int getContainerHeight()
  {
    if (_container != null)
    {
      int h = _container.getHeight();
      if (h > 0) return h;
    }
    return _screenHeight / 3;
  }

  private float clampX(float x)
  {
    // The window is full-width, so x=0 means keyboard is at left edge.
    // We don't need to clamp x much since the window covers full width.
    return Math.max(0, x);
  }

  private float clampY(float y)
  {
    int h = getContainerHeight();
    if (y + h > _screenHeight)
      y = _screenHeight - h;
    if (y < 0) y = 0;
    return y;
  }

  private void updateWindowPosition(float x, float y)
  {
    _posX = x;
    _posY = y;
    Window window = _ime.getWindow().getWindow();
    if (window == null) return;
    WindowManager.LayoutParams lp = window.getAttributes();
    if (lp == null) return;
    lp.x = (int) x;
    lp.y = (int) y;
    window.setAttributes(lp);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if (_container == null) return false;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        _dragStartRawX = event.getRawX();
        _dragStartRawY = event.getRawY();
        _dragStartPosX = _posX;
        _dragStartPosY = _posY;
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
          updateWindowPosition(
              clampX(_dragStartPosX + dx),
              clampY(_dragStartPosY + dy));
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
          _config.save_floating_pos(_posX, _posY);
        _isDragging = false;
        return true;
    }
    return false;
  }
}
