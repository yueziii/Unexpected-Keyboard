package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.content.res.Resources;

/**
 * Floating keyboard: moves the keyboard container upward via translationY,
 * narrows its width via LayoutParams, and restricts touchable area via
 * onComputeInsets so touches pass through to the app behind.
 *
 * The IME window itself is NOT modified — it stays MATCH_PARENT width,
 * WRAP_CONTENT height, BOTTOM gravity (as the system expects). Instead:
 * - The container's width is set to the configured percentage.
 * - The container is translated upward so it floats mid-screen.
 * - onComputeInsets reports only the container's on-screen rectangle as
 *   touchable, so the rest of the window passes touches through.
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
  /** Desired Y position (from top of screen). Float.MIN_VALUE = default. */
  private float _posY;
  private float _dragStartRawY;
  private float _dragStartTranslation;
  private boolean _isDragging;
  /** Listener used to apply translation after layout is complete. */
  private ViewTreeObserver.OnGlobalLayoutListener _layoutListener;

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
   * Called from setInputView / onStartInputView to apply floating layout.
   * We use view.post() to ensure the container has been measured and laid out
   * before we translate it (translation applied before layout gets overridden).
   */
  public void applyPosition(final View container)
  {
    _container = container;
    // Apply width constraint
    applyFloatingWidth(container);
    // Remove any previous layout listener
    if (_layoutListener != null)
    {
      container.getViewTreeObserver().removeOnGlobalLayoutListener(_layoutListener);
    }
    // Create a one-shot layout listener that applies translation after layout
    _layoutListener = new ViewTreeObserver.OnGlobalLayoutListener()
    {
      @Override
      public void onGlobalLayout()
      {
        if (container.getWidth() > 0 && container.getHeight() > 0)
        {
          applyTranslation(container);
          // Keep listener for re-applying on config changes / layout invalidations
          // (don't remove — layout can happen multiple times)
        }
      }
    };
    container.getViewTreeObserver().addOnGlobalLayoutListener(_layoutListener);
    // Also post a delayed apply as a fallback
    container.post(new Runnable()
    {
      @Override
      public void run()
      {
        if (container.getWidth() > 0)
          applyTranslation(container);
      }
    });
  }

  /**
   * Set the container width to the configured floating width and center it
   * horizontally.
   */
  private void applyFloatingWidth(View container)
  {
    int widthPx = getKeyboardWidthPx();
    ViewGroup.LayoutParams lp = container.getLayoutParams();
    if (lp == null) return;
    boolean changed = false;
    if (lp.width != widthPx)
    {
      lp.width = widthPx;
      changed = true;
    }
    // Center horizontally via gravity if possible
    if (lp instanceof FrameLayout.LayoutParams)
    {
      FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
      if (flp.gravity != (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM))
      {
        flp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
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
   * Calculate and apply the translationY so the keyboard floats at _posY.
   * The container normally sits at the bottom of the screen. We translate it
   * up so its top is at _posY (or a default mid-screen position).
   */
  private void applyTranslation(View container)
  {
    int h = container.getHeight();
    if (h <= 0) return;
    // Get container's current Y position on screen (before translation)
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    float currentTop = loc[1] - container.getTranslationY(); // undo current translation
    // Default position: 40% down from top
    float targetY = (_posY == Float.MIN_VALUE) ? _screenHeight * 0.4f : _posY;
    // Clamp so keyboard stays on screen
    if (targetY + h > _screenHeight) targetY = _screenHeight - h;
    if (targetY < 0) targetY = 0;
    float translation = targetY - currentTop;
    container.setTranslationY(translation);
    // Request new insets so touchable region matches the new position
    _ime.requestInsets();
  }

  /**
   * Called from Keyboard2.onComputeInsets to set the touchable region.
   * Only the container's on-screen rectangle is touchable; everything else
   * passes through to the app behind.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    // Reset any previous translation to get the raw location
    float savedTranslation = container.getTranslationY();
    int h = container.getHeight();
    int w = container.getWidth();
    int widthPx = (w > 0) ? w : getKeyboardWidthPx();
    int heightPx = (h > 0) ? h : _screenHeight / 3;
    // Compute the container's actual on-screen position including translation
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    int left = loc[0];
    int top = loc[1];
    int right = left + widthPx;
    int bottom = top + heightPx;
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(left, top, right, bottom);
    // Keep contentTopInsets/visibleTopInsets from super (default = 0 for non-fullscreen)
    debug("computeInsets loc=[" + left + "," + top + "] w=" + widthPx
      + " h=" + heightPx + " translationY=" + savedTranslation
      + " region=[" + left + "," + top + "," + right + "," + bottom + "]");
  }

  /**
   * Called when floating mode is turned off — reset container to normal.
   */
  public void disableFloating(View container)
  {
    _container = container;
    // Remove layout listener
    if (_layoutListener != null && container != null)
    {
      container.getViewTreeObserver().removeOnGlobalLayoutListener(_layoutListener);
      _layoutListener = null;
    }
    if (container == null) return;
    // Reset translation
    container.setTranslationY(0f);
    // Reset width to MATCH_PARENT
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
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && Math.abs(dy) > 8)
          _isDragging = true;
        if (_isDragging)
        {
          float translation = _dragStartTranslation + dy;
          _container.setTranslationY(translation);
          // Update saved position and request inset update
          int[] loc = new int[2];
          _container.getLocationOnScreen(loc);
          _posY = loc[1];
          _ime.requestInsets();
          debug("drag dy=" + dy + " translation=" + translation + " posY=" + _posY);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isDragging)
        {
          _config.save_floating_pos(0, _posY);
        }
        _isDragging = false;
        return true;
    }
    return false;
  }
}
