package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.content.res.Resources;

/**
 * Minimal floating keyboard: moves the keyboard container up via translationY
 * and controls touch pass-through via onComputeInsets.
 *
 * The IME window itself is not modified at all — it stays BOTTOM gravity,
 * MATCH_PARENT width, WRAP_CONTENT height (exactly as the system expects).
 * The keyboard container is translated upward via setTranslationY so it
 * appears in the middle of the screen. onComputeInsets sets
 * TOUCHABLE_INSETS_REGION to only the keyboard's screen rectangle so the
 * rest of the window passes touches through to the app behind.
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
  private float _posY;
  private float _dragStartRawY;
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
   * Move the keyboard container to the saved floating Y position.
   * The container normally sits at the bottom of the window (which is at
   * the bottom of the screen). We translate it upward by the difference
   * between its default bottom position and the desired floating position.
   */
  public void applyPosition(View container)
  {
    _container = container;
    final float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.4f : _posY;
    // Use a layout change listener so we get the correct height after layout.
    container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
      @Override
      public void onLayoutChange(View v, int left, int top, int right, int bottom,
          int oldLeft, int oldTop, int oldRight, int oldBottom)
      {
        int ch = bottom - top;
        if (ch <= 0) return;
        // Container's default position (bottom of screen): screenHeight - ch
        // Desired Y: targetY
        // Translation needed: targetY - (screenHeight - ch)
        float translation = targetY - (_screenHeight - ch);
        v.setTranslationY(translation);
        debug("applyPosition containerH=" + ch + " screenH=" + _screenHeight
          + " targetY=" + targetY + " translation=" + translation);
        // Remove listener after first successful layout.
        v.removeOnLayoutChangeListener(this);
      }
    });
  }

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
    // Keep contentTopInsets/visibleTopInsets from super.onComputeInsets (default).
    // Setting them to screenHeight was causing the system to not show the IME.
    debug("onComputeInsets loc=[" + left + "," + top + "] w=" + container.getWidth()
      + " h=" + container.getHeight() + " region=[" + left + "," + top + "," + right + "," + bottom + "]");
  }

  private void debug(String msg)
  {
    Logs.debug("Floating: " + msg);
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
        _dragStartRawY = event.getRawY();
        _dragStartPosY = _posY == Float.MIN_VALUE ? _screenHeight * 0.4f : _posY;
        _isDragging = false;
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && Math.abs(dy) > 8)
          _isDragging = true;
        if (_isDragging)
        {
          float ny = _dragStartPosY + dy;
          int h = _container.getHeight();
          if (h <= 0) h = _screenHeight / 3;
          if (ny + h > _screenHeight) ny = _screenHeight - h;
          if (ny < 0) ny = 0;
          _posY = ny;
          float sh = _screenHeight;
          float translation = ny - (sh - h);
          _container.setTranslationY(translation);
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
