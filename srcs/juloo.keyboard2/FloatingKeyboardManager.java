package juloo.keyboard2;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * Manages the "floating keyboard" mode: the keyboard is displayed as a
 * movable, free-positioned window instead of being docked at the bottom.
 *
 * How it works:
 *  - The IME window's own LayoutParams are set to WRAP_CONTENT height so the
 *    window only covers the keyboard area (not the whole screen), letting
 *    touches pass through to the app behind it.
 *  - The window is positioned via WindowManager.LayoutParams.gravity
 *    (TOP|START) + x/y, which moves the entire window freely on screen.
 *  - A drag handle view at the top of the keyboard lets the user move it.
 *  - The position is persisted in the config and restored next time.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private final Keyboard2 _ime;
  private final Config _config;

  /** The keyboard container (used for measuring size). */
  private View _container;

  /** The drag handle view. */
  private View _handle;

  /** Screen dimensions used for clamping. */
  private int _screenWidth;
  private int _screenHeight;

  /** Current floating position in screen coordinates. */
  private float _posX;
  private float _posY;

  /** Touch tracking for dragging. */
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

  /** Bind the container and drag handle. Attaches the touch listener. */
  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
  }

  /** Whether floating mode is active. */
  public boolean isEnabled()
  {
    return _config.floating_keyboard;
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

  /**
   * Configure the IME window for floating mode: WRAP_CONTENT height so the
   * window only covers the keyboard, and position via gravity + x/y.
   */
  public void applyFloatingWindowLayout(Window window)
  {
    if (window == null)
      return;
    WindowManager.LayoutParams lp = window.getAttributes();
    if (lp == null)
      return;
    // Window wraps content so it only covers the keyboard area, not the
    // whole screen. This lets touches pass through to the app behind it.
    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
    // Position from top-left corner.
    lp.gravity = Gravity.TOP | Gravity.START;
    // Compute position (default: centered horizontally, lower part of screen).
    float x = _posX;
    float y = _posY;
    if (x == Float.MIN_VALUE || y == Float.MIN_VALUE)
    {
      int w = getKeyboardWidthPx();
      x = Math.max(0, (_screenWidth - w) / 2.f);
      y = _screenHeight * 0.5f;
    }
    x = clampX(x);
    y = clampY(y);
    _posX = x;
    _posY = y;
    lp.x = (int) x;
    lp.y = (int) y;
    window.setAttributes(lp);
  }

  /** Reset the floating position to the default (centered). */
  public void resetPosition()
  {
    _posX = Float.MIN_VALUE;
    _posY = Float.MIN_VALUE;
    _config.save_floating_pos(Float.MIN_VALUE, Float.MIN_VALUE);
    applyFloatingWindowLayout(getWindow());
  }

  private Window getWindow()
  {
    return _ime.getWindow().getWindow();
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

  /** Update the window position without re-reading config (for dragging). */
  private void updateWindowPosition(float x, float y)
  {
    _posX = x;
    _posY = y;
    Window window = getWindow();
    if (window == null)
      return;
    WindowManager.LayoutParams lp = window.getAttributes();
    if (lp == null)
      return;
    lp.x = (int) x;
    lp.y = (int) y;
    window.setAttributes(lp);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
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
        {
          _config.save_floating_pos(_posX, _posY);
        }
        _isDragging = false;
        return true;
    }
    return false;
  }
}
