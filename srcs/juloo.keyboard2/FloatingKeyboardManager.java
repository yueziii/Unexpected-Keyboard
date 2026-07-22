package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.content.res.Resources;

/**
 * Floating keyboard: moves the IME window itself (not a view inside it) by
 * changing the window's LayoutParams — width, gravity, and x/y offsets.
 *
 * Previous approaches used setTranslationY on the container view, but that
 * was unreliable (overridden by layout passes, getLocationOnScreen returning
 * 0 before window positioning, etc.).
 *
 * This approach changes the actual WindowManager.LayoutParams of the IME
 * window:
 * - width: set to the configured percentage of screen width
 * - gravity: TOP|START (so x/y offsets position the window)
 * - x, y: pixel offsets from the gravity anchor
 * - height: stays WRAP_CONTENT (keyboard determines its own height)
 *
 * onComputeInsets sets contentTopInsets=0 (keyboard doesn't push app content)
 * and TOUCHABLE_INSETS_REGION to the window's screen rect.
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
  /** Desired X of the keyboard left edge (from screen left). Float.MIN_VALUE = default. */
  private float _posX;
  /** Desired Y of the keyboard top edge (from screen top). Float.MIN_VALUE = default. */
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

  /**
   * Apply floating layout to the IME window.
   * Called from Keyboard2.updateSoftInputWindowLayoutParams().
   * Modifies the window's LayoutParams: width, gravity, x, y.
   */
  public void applyFloatingWindow(Window window)
  {
    int widthPx = getKeyboardWidthPx();
    // Default position: centered horizontally, 30% from top
    float targetX = (_posX == Float.MIN_VALUE)
      ? (_screenWidth - widthPx) / 2f : _posX;
    float targetY = (_posY == Float.MIN_VALUE)
      ? _screenHeight * 0.3f : _posY;
    // Clamp
    if (targetX < 0) targetX = 0;
    if (targetX + widthPx > _screenWidth) targetX = _screenWidth - widthPx;
    if (targetY < 0) targetY = 0;

    WindowManager.LayoutParams lp = window.getAttributes();
    boolean changed = false;
    // Set width to configured %
    if (lp.width != widthPx) { lp.width = widthPx; changed = true; }
    // Try TOP|START gravity with x/y offsets. If the system ignores this for
    // TYPE_INPUT_METHOD, the keyboard will stay at bottom but narrowed.
    int targetGravity = Gravity.TOP | Gravity.START;
    if (lp.gravity != targetGravity) { lp.gravity = targetGravity; changed = true; }
    if (lp.x != (int)targetX) { lp.x = (int)targetX; changed = true; }
    if (lp.y != (int)targetY) { lp.y = (int)targetY; changed = true; }
    // Set height to WRAP_CONTENT explicitly
    if (lp.height != WindowManager.LayoutParams.WRAP_CONTENT)
    { lp.height = WindowManager.LayoutParams.WRAP_CONTENT; changed = true; }
    if (changed)
    {
      window.setAttributes(lp);
    }
    // Also set the container translationY as a fallback in case the system
    // ignores TOP|START gravity for TYPE_INPUT_METHOD windows. We compute the
    // translation from the saved _posY (same as targetY, but a field).
    if (_container != null)
    {
      final View container = _container;
      final float savedTargetY = targetY;
      container.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (container.getHeight() > 0) {
            float naturalTop = _screenHeight - container.getHeight();
            float trans = savedTargetY - naturalTop;
            container.setTranslationY(trans);
            debug("fallback translationY=" + trans + " naturalTop=" + naturalTop);
          }
        }
      }, 200);
    }
    debug("applyFloatingWindow width=" + widthPx + " x=" + (int)targetX
      + " y=" + (int)targetY + " gravity=TOP|START changed=" + changed
      + " lp.gravity=" + lp.gravity + " lp.width=" + lp.width
      + " lp.height=" + lp.height);
  }

  /**
   * Reset the IME window to normal (non-floating) LayoutParams.
   * Called from Keyboard2.updateSoftInputWindowLayoutParams() when floating is off.
   */
  public void disableFloatingWindow(Window window)
  {
    WindowManager.LayoutParams lp = window.getAttributes();
    boolean changed = false;
    if (lp.width != WindowManager.LayoutParams.MATCH_PARENT)
    { lp.width = WindowManager.LayoutParams.MATCH_PARENT; changed = true; }
    if (lp.gravity != Gravity.BOTTOM)
    { lp.gravity = Gravity.BOTTOM; changed = true; }
    if (lp.x != 0) { lp.x = 0; changed = true; }
    if (lp.y != 0) { lp.y = 0; changed = true; }
    if (changed)
      window.setAttributes(lp);
    // Reset translationY on container
    if (_container != null)
      _container.setTranslationY(0f);
    debug("disableFloatingWindow: reset to MATCH_PARENT/BOTTOM");
  }

  /**
   * Called from Keyboard2.onComputeInsets.
   * In floating mode: contentTopInsets=0 (don't push app), and
   * touchable region = entire window (since the window is now sized to
   * the keyboard).
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    // In floating mode, the window is sized to the keyboard, so the entire
    // window is touchable. Set contentTopInsets=0 so the app content isn't
    // pushed up.
    outInsets.contentTopInsets = 0;
    outInsets.visibleTopInsets = 0;
    // Use TOUCHABLE_INSETS_FRAME so the whole window frame is touchable.
    // This is simpler and more reliable than TOUCHABLE_INSETS_REGION.
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_FRAME;
    outInsets.touchableRegion.setEmpty();
    debug("computeInsets: TOUCHABLE_INSETS_FRAME, contentTopInsets=0");
  }

  private void debug(String msg)
  {
    Log.d(TAG, msg);
    try {
      java.io.FileWriter fw = new java.io.FileWriter(
        new java.io.File(_ime.getFilesDir(), "floating_debug.txt"), true);
      fw.write(msg + "\n");
      fw.close();
    } catch (Exception e) {}
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
        // Read current position from saved values
        _dragStartPosX = (_posX == Float.MIN_VALUE) ? 0 : _posX;
        _dragStartPosY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.4f : _posY;
        _isDragging = false;
        debug("drag DOWN rawX=" + event.getRawX() + " rawY=" + event.getRawY()
          + " startPosX=" + _dragStartPosX + " startPosY=" + _dragStartPosY);
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dx = event.getRawX() - _dragStartRawX;
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10))
          _isDragging = true;
        if (_isDragging)
        {
          _posX = _dragStartPosX + dx;
          _posY = _dragStartPosY + dy;
          // Clamp X
          int widthPx = getKeyboardWidthPx();
          if (_posX < 0) _posX = 0;
          if (_posX + widthPx > _screenWidth) _posX = _screenWidth - widthPx;
          if (_posY < 0) _posY = 0;
          // Apply new window position
          Window window = _ime.getWindow().getWindow();
          WindowManager.LayoutParams lp = window.getAttributes();
          lp.x = (int)_posX;
          lp.y = (int)_posY;
          window.setAttributes(lp);
          debug("drag MOVE dx=" + dx + " dy=" + dy + " posX=" + _posX + " posY=" + _posY);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
        {
          _config.save_floating_pos(_posX, _posY);
          debug("drag UP saved posX=" + _posX + " posY=" + _posY);
        }
        _isDragging = false;
        return true;
    }
    return false;
  }
}
