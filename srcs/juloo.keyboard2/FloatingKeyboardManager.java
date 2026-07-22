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
 * movable, free-positioned window instead of being docked at the bottom of
 * the screen.
 *
 * How it works:
 *  - The IME window (a Dialog) always covers the whole screen height.
 *  - The container view holding the keyboard is laid out at the top-left of
 *    the window (Gravity.TOP|START) with WRAP_CONTENT height, instead of
 *    Gravity.BOTTOM.
 *  - The keyboard container is then translated via [setX]/[setY] to the
 *    desired floating position.
 *  - A drag handle view at the top of the keyboard lets the user move it.
 *  - The position is persisted in the config and restored next time.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private final Keyboard2 _ime;
  private final Config _config;

  /** The keyboard container that will be translated. */
  private View _container;

  /** The drag handle view. */
  private View _handle;

  /** Screen dimensions used for clamping. */
  private int _screenWidth;
  private int _screenHeight;

  /** Touch tracking for dragging. */
  private float _dragStartRawX;
  private float _dragStartRawY;
  private float _dragStartContainerX;
  private float _dragStartContainerY;
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

  /** Bind the container and drag handle. Attaches the touch listener. */
  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
      _handle.setOnTouchListener(this);
    applyPosition();
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
   * Apply the floating window layout: make the window's inputArea parent use
   * WRAP_CONTENT height and TOP gravity so the container can be freely
   * positioned via translation.
   */
  public void applyFloatingWindowLayout(Window window)
  {
    if (window == null)
      return;
    final View inputArea = window.findViewById(android.R.id.inputArea);
    if (inputArea == null)
      return;
    final View parent = (View) inputArea.getParent();
    if (parent == null)
      return;
    // Wrap content height so the keyboard doesn't stretch to full screen.
    updateLayoutHeightOf(parent, ViewGroup.LayoutParams.WRAP_CONTENT);
    // Top-left gravity so setX/setY map to absolute screen coordinates.
    updateLayoutGravityOf(parent, Gravity.TOP | Gravity.START);
  }

  /** Apply the saved (or default) floating position to the container. */
  public void applyPosition()
  {
    if (_container == null)
      return;
    float x = _config.floating_pos_x;
    float y = _config.floating_pos_y;
    if (x == Float.MIN_VALUE || y == Float.MIN_VALUE)
    {
      // Default: centered horizontally, near the bottom.
      int w = getKeyboardWidthPx();
      x = Math.max(0, (_screenWidth - w) / 2.f);
      y = _screenHeight * 0.4f;
    }
    x = clampX(x);
    y = clampY(y);
    _container.setX(x);
    _container.setY(y);
  }

  /** Reset the floating position to the default (centered). */
  public void resetPosition()
  {
    _config.save_floating_pos(Float.MIN_VALUE, Float.MIN_VALUE);
    applyPosition();
  }

  private float clampX(float x)
  {
    if (_container == null)
      return x;
    int w = _container.getWidth();
    if (w <= 0)
      w = getKeyboardWidthPx();
    if (x + w > _screenWidth)
      x = _screenWidth - w;
    if (x < 0)
      x = 0;
    return x;
  }

  private float clampY(float y)
  {
    if (_container == null)
      return y;
    int h = _container.getHeight();
    if (h <= 0)
      h = _screenHeight / 3;
    if (y + h > _screenHeight)
      y = _screenHeight - h;
    if (y < 0)
      y = 0;
    return y;
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        _dragStartRawX = event.getRawX();
        _dragStartRawY = event.getRawY();
        _dragStartContainerX = _container.getX();
        _dragStartContainerY = _container.getY();
        _isDragging = false;
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dx = event.getRawX() - _dragStartRawX;
        float dy = event.getRawY() - _dragStartRawY;
        // Only start dragging after a small movement to avoid accidental moves.
        if (!_isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8))
          _isDragging = true;
        if (_isDragging)
        {
          float nx = _dragStartContainerX + dx;
          float ny = _dragStartContainerY + dy;
          _container.setX(clampX(nx));
          _container.setY(clampY(ny));
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

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof android.widget.FrameLayout.LayoutParams) {
      final android.widget.FrameLayout.LayoutParams params =
        (android.widget.FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }
}
