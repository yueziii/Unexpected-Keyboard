package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * Floating keyboard implementation.
 *
 * Strategy:
 * - The IME window stays MATCH_PARENT × WRAP_CONTENT at BOTTOM (system enforces this).
 * - The keyboard container is narrowed (width set via LayoutParams) and centered.
 * - A translationY moves it upward so it appears mid-screen.
 * - onComputeInsets sets TOUCHABLE_INSETS_REGION to only the container's screen
 *   rect, so touches outside the keyboard pass through to the app behind.
 *
 * Critical: do NOT call requestApplyInsets() from within onGlobalLayout or
 * applyTranslation — that creates an infinite layout → insets → layout loop
 * that prevents the keyboard from rendering. Insets are recomputed only by
 * the system's own layout passes and explicitly after drag.
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
  /** Desired Y of the keyboard top (from top of screen). Float.MIN_VALUE = default. */
  private float _posY;
  private float _dragStartRawY;
  private float _dragStartTranslation;
  private boolean _isDragging;
  /** Whether floating mode is active and translation has been applied. */
  private boolean _floatingActive = false;
  /** One-shot flag to break the layout-insets cycle. */
  private boolean _suppressInsetRequest = false;

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
   * Called from setInputView to apply floating layout.
   * Uses view.post() to apply translation AFTER layout (calling before layout
   * gets overridden by the next measure/layout pass).
   */
  public void applyPosition(final View container)
  {
    _container = container;
    _floatingActive = true;
    // Apply width constraint immediately (affects measurement)
    applyFloatingWidth(container);
    // Apply translation after layout completes via post()
    container.post(new Runnable()
    {
      @Override
      public void run()
      {
        if (_floatingActive && container.getWidth() > 0 && container.getHeight() > 0)
        {
          applyTranslation(container);
          // Request inset update ONCE after translation so touchable region
          // matches the new position. This is safe because post() runs once.
          container.requestApplyInsets();
        }
      }
    });
    debug("applyPosition called, width=" + getKeyboardWidthPx()
      + " screenHeight=" + _screenHeight);
  }

  /**
   * Narrow the container to the configured floating width and center it.
   * The container is inside a FrameLayout (inputArea), so FrameLayout.LayoutParams
   * with CENTER_HORIZONTAL gravity works.
   */
  private void applyFloatingWidth(View container)
  {
    int widthPx = getKeyboardWidthPx();
    ViewGroup.LayoutParams lp = container.getLayoutParams();
    if (lp == null)
    {
      // Container not yet added to parent; will be set when SDK adds it.
      // We set it in post() as fallback below.
      container.post(new Runnable()
      {
        @Override
        public void run()
        {
          if (_floatingActive)
            applyFloatingWidth(container);
        }
      });
      return;
    }
    boolean changed = false;
    if (lp.width != widthPx)
    {
      lp.width = widthPx;
      changed = true;
    }
    if (lp instanceof FrameLayout.LayoutParams)
    {
      FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
      int targetGravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
      if (flp.gravity != targetGravity)
      {
        flp.gravity = targetGravity;
        changed = true;
      }
    }
    else if (lp instanceof LinearLayout.LayoutParams)
    {
      LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
      if (llp.gravity != Gravity.CENTER_HORIZONTAL)
      {
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        changed = true;
      }
    }
    if (changed)
      container.setLayoutParams(lp);
  }

  /**
   * Translate the container so its top is at _posY on screen.
   * Must be called after the container has been measured and positioned.
   */
  private void applyTranslation(View container)
  {
    int h = container.getHeight();
    if (h <= 0) return;
    // The container's on-screen position WITHOUT translation (its natural
    // position at the bottom of the screen). We use getLocationOnScreen then
    // subtract the current translation to get the un-translated position.
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    float naturalTop = loc[1] - container.getTranslationY();
    // Default position: 40% from top
    float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.4f : _posY;
    // Clamp so keyboard stays on screen
    if (targetY + h > _screenHeight - 20) targetY = _screenHeight - h - 20;
    if (targetY < 0) targetY = 0;
    float translation = targetY - naturalTop;
    container.setTranslationY(translation);
    debug("applyTranslation naturalTop=" + naturalTop + " targetY=" + targetY
      + " h=" + h + " translationY=" + translation
      + " loc[1]=" + loc[1] + " curTranslationY=" + container.getTranslationY());
  }

  /**
   * Called from Keyboard2.onComputeInsets to restrict touchable area.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    int h = container.getHeight();
    int w = container.getWidth();
    // If the container hasn't been laid out yet (w=0 or h=0), DON'T restrict
    // the touchable region — let the default behavior (full window touchable)
    // apply. Restricting with a zero-size region would make the IME
    // unresponsive.
    if (w <= 0 || h <= 0)
    {
      debug("computeInsets: container not laid out yet (w=" + w + " h=" + h + "), skipping");
      return;
    }
    // The container's actual on-screen position (including translation)
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + w;
    int bottom = top + h;
    // Clamp to screen bounds
    if (right > _screenWidth) right = _screenWidth;
    if (bottom > _screenHeight) bottom = _screenHeight;
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    // Only set touchable region if we have a valid rect
    if (right > left && bottom > top)
    {
      outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
      outInsets.touchableRegion.set(left, top, right, bottom);
    }
    // DO NOT set contentTopInsets/visibleTopInsets — leave them as the
    // defaults from super.onComputeInsets(). Setting them to screenHeight
    // caused the system to think the IME covers the whole screen and it
    // stopped rendering the keyboard.
    debug("computeInsets loc=[" + left + "," + top + "] w=" + w
      + " h=" + h + " region=[" + left + "," + top + "," + right + "," + bottom + "]");
  }

  /**
   * Reset container to normal (non-floating) state.
   */
  public void disableFloating(View container)
  {
    _floatingActive = false;
    _container = container;
    if (container == null) return;
    container.setTranslationY(0f);
    ViewGroup.LayoutParams lp = container.getLayoutParams();
    if (lp != null && lp.width != ViewGroup.LayoutParams.MATCH_PARENT)
    {
      lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
      if (lp instanceof FrameLayout.LayoutParams)
        ((FrameLayout.LayoutParams) lp).gravity = Gravity.BOTTOM;
      else if (lp instanceof LinearLayout.LayoutParams)
        ((LinearLayout.LayoutParams) lp).gravity = Gravity.NO_GRAVITY;
      container.setLayoutParams(lp);
    }
    debug("disableFloating: reset to MATCH_PARENT");
  }

  /** Called from drag to trigger inset recomputation. */
  public void requestInsetsUpdate()
  {
    if (_container != null)
      _container.requestApplyInsets();
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
        _dragStartRawY = event.getRawY();
        _dragStartTranslation = _container.getTranslationY();
        _isDragging = false;
        debug("drag DOWN rawY=" + event.getRawY() + " startTranslation=" + _dragStartTranslation);
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
          int[] loc = new int[2];
          _container.getLocationOnScreen(loc);
          _posY = loc[1];
          debug("drag MOVE dy=" + dy + " translation=" + translation + " posY=" + _posY);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
        {
          _config.save_floating_pos(0, _posY);
          // Request inset update so touchable region matches new position
          requestInsetsUpdate();
          debug("drag UP saved posY=" + _posY);
        }
        _isDragging = false;
        return true;
    }
    return false;
  }
}
