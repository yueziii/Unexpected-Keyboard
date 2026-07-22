package juloo.keyboard2;

import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Floating keyboard manager.
 *
 * Strategy: the keyboard container is translated within the IME window using
 * setTranslationX/Y. The IME window keeps MATCH_PARENT width/height so the
 * container can be moved anywhere on screen without being clipped.
 *
 * onComputeInsets sets a touchableRegion matching the keyboard's on-screen
 * position so touches outside the keyboard pass through to the app behind.
 * contentTopInsets is set to 0 so the app window is NOT resized.
 *
 * Dragging is handled by a drag handle View at the top of the keyboard layout.
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
  /** Saved position: top-left corner in screen coordinates. */
  private float _posX;
  private float _posY;
  /** Floating width in pixels (0 = full width). */
  private int _floatWidth;
  /** Estimated keyboard height (used before actual height is known). */
  private int _estimatedHeight;
  private float _dragStartRawX;
  private float _dragStartRawY;
  private float _dragStartPosX;
  private float _dragStartPosY;
  private boolean _isDragging;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    DisplayMetrics dm = ime.getResources().getDisplayMetrics();
    _screenWidth = dm.widthPixels;
    _screenHeight = dm.heightPixels;
    _posX = _config.floating_pos_x;
    _posY = _config.floating_pos_y;
    // Estimate keyboard height as ~40% of screen height
    _estimatedHeight = _screenHeight * 40 / 100;
  }

  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
    {
      _handle.setOnTouchListener(this);
      _handle.setClickable(true);
    }
  }

  /** Compute floating width in pixels from config percentage. */
  private int computeFloatWidth()
  {
    int pct = Math.max(40, Math.min(100, _config.floating_keyboard_width));
    return _screenWidth * pct / 100;
  }

  /** Default Y position if none saved. */
  private float defaultPosY(int kbHeight)
  {
    return Math.max(0, _screenHeight - kbHeight - (int)(_screenHeight * 0.05f));
  }

  /** Default X position (centered). */
  private float defaultPosX(int kbWidth)
  {
    return Math.max(0, (_screenWidth - kbWidth) / 2f);
  }

  private float clampX(float x, int kbWidth)
  {
    return Math.max(0, Math.min(x, _screenWidth - kbWidth));
  }

  private float clampY(float y, int kbHeight)
  {
    return Math.max(0, Math.min(y, _screenHeight - kbHeight));
  }

  /**
   * Called when floating mode is enabled (from setInputView).
   * Sets width and schedules translation after layout.
   */
  public void applyPosition(final View container)
  {
    _container = container;
    _floatWidth = computeFloatWidth();
    // Ensure handle is attached (attach may not have been called yet)
    if (_handle == null && container instanceof ViewGroup)
    {
      View h = ((ViewGroup)container).findViewById(R.id.floating_drag_handle);
      if (h != null)
        attach(container, h);
    }
    applyContainerWidth(container);
    // Use OnPreDrawListener to catch layout completion reliably
    container.getViewTreeObserver().addOnPreDrawListener(
      new ViewTreeObserver.OnPreDrawListener()
      {
        @Override
        public boolean onPreDraw()
        {
          if (container.getHeight() > 0 && container.getWidth() > 0)
          {
            container.getViewTreeObserver().removeOnPreDrawListener(this);
            doTranslation(container);
            return true;
          }
          return true;
        }
      });
  }

  /**
   * Set container width to _floatWidth. Do NOT use leftMargin for positioning
   * — all positioning is done via setTranslationX/Y in doTranslation() to
   * avoid double-offset (margin + translation stacking).
   */
  private void applyContainerWidth(View container)
  {
    ViewGroup.LayoutParams lp = container.getLayoutParams();
    if (lp == null) return;
    int targetWidth = (_floatWidth < _screenWidth) ? _floatWidth
        : ViewGroup.LayoutParams.MATCH_PARENT;
    if (lp.width != targetWidth)
      lp.width = targetWidth;
    // Reset margins and gravity — positioning is via translation only
    if (lp instanceof FrameLayout.LayoutParams)
    {
      FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
      flp.gravity = Gravity.BOTTOM | Gravity.START;
      flp.leftMargin = 0;
      container.setLayoutParams(flp);
    }
    else if (lp instanceof LinearLayout.LayoutParams)
    {
      LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
      llp.gravity = Gravity.BOTTOM | Gravity.START;
      llp.leftMargin = 0;
      container.setLayoutParams(llp);
    }
    else
    {
      container.setLayoutParams(lp);
    }
  }

  /**
   * Apply translation to move the keyboard to (_posX, _posY).
   * Called after layout is complete.
   */
  private void doTranslation(View container)
  {
    int h = container.getHeight();
    int w = container.getWidth();
    if (h <= 0 || w <= 0) return;

    // Get natural position (where the container sits without translation)
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    float naturalLeft = loc[0] - container.getTranslationX();
    float naturalTop = loc[1] - container.getTranslationY();

    // Compute target position
    float targetX = (_posX != Float.MIN_VALUE)
        ? clampX(_posX, w) : defaultPosX(w);
    float targetY = (_posY != Float.MIN_VALUE)
        ? clampY(_posY, h) : defaultPosY(h);

    // Apply translation
    container.setTranslationX(targetX - naturalLeft);
    container.setTranslationY(targetY - naturalTop);

    // Request insets update so touchableRegion is recalculated
    container.requestApplyInsets();
  }

  /** Update the container's on-screen rect. */
  private void updateScreenRect(View container)
  {
    // No need to store rect — computeFloatingInsets reads it directly
  }

  /** Disable floating mode: reset width, translation, margins. */
  public void disableFloating(View container)
  {
    _container = container;
    if (container != null)
    {
      ViewGroup.LayoutParams lp = container.getLayoutParams();
      if (lp != null)
      {
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp instanceof FrameLayout.LayoutParams)
        {
          FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
          flp.gravity = Gravity.NO_GRAVITY;
          flp.leftMargin = 0;
          container.setLayoutParams(flp);
        }
        else if (lp instanceof LinearLayout.LayoutParams)
        {
          LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
          llp.gravity = Gravity.NO_GRAVITY;
          llp.leftMargin = 0;
          container.setLayoutParams(llp);
        }
        else
        {
          container.setLayoutParams(lp);
        }
      }
      container.setTranslationX(0f);
      container.setTranslationY(0f);
    }
  }

  /**
   * Compute insets for floating mode.
   * Sets touchableRegion to the keyboard's on-screen rect.
   * If layout hasn't happened yet, use estimated values.
   * contentTopInsets = 0 so the app window keeps full height.
   */
  public void computeFloatingInsets(InputMethodService.Insets outInsets, View container)
  {
    _container = container;
    int left, top, right, bottom;

    if (container.getWidth() > 0 && container.getHeight() > 0)
    {
      // Use actual on-screen position
      int[] loc = new int[2];
      container.getLocationOnScreen(loc);
      left = loc[0];
      top = loc[1];
      right = left + container.getWidth();
      bottom = top + container.getHeight();
    }
    else
    {
      // Layout not done yet — use estimated position
      int kbWidth = (_floatWidth > 0 && _floatWidth < _screenWidth)
          ? _floatWidth : _screenWidth;
      int kbHeight = _estimatedHeight;
      float tx = (_posX != Float.MIN_VALUE)
          ? clampX(_posX, kbWidth) : defaultPosX(kbWidth);
      float ty = (_posY != Float.MIN_VALUE)
          ? clampY(_posY, kbHeight) : defaultPosY(kbHeight);
      left = (int) tx;
      top = (int) ty;
      right = left + kbWidth;
      bottom = top + kbHeight;
    }

    // Floating keyboard: do NOT resize the app window.
    outInsets.contentTopInsets = 0;
    outInsets.visibleTopInsets = 0;
    // Restrict touch to the keyboard area so touches outside pass through
    // to the app behind.
    if (right > left && bottom > top)
    {
      outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
      outInsets.touchableRegion.set(left, top, right, bottom);
    }
  }

  // --- Drag handling ---

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if (_container == null) return false;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
      {
        int[] loc = new int[2];
        _container.getLocationOnScreen(loc);
        _dragStartRawX = event.getRawX();
        _dragStartRawY = event.getRawY();
        _dragStartPosX = loc[0];
        _dragStartPosY = loc[1];
        _isDragging = false;
        return true;
      }
      case MotionEvent.ACTION_MOVE:
      {
        float dx = event.getRawX() - _dragStartRawX;
        float dy = event.getRawY() - _dragStartRawY;
        if (!_isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8))
          _isDragging = true;
        if (_isDragging)
        {
          int w = _container.getWidth();
          int h = _container.getHeight();
          if (w <= 0 || h <= 0) return true;
          float newX = clampX(_dragStartPosX + dx, w);
          float newY = clampY(_dragStartPosY + dy, h);
          // Compute natural position and apply translation
          int[] curLoc = new int[2];
          _container.getLocationOnScreen(curLoc);
          float naturalLeft = curLoc[0] - _container.getTranslationX();
          float naturalTop = curLoc[1] - _container.getTranslationY();
          _container.setTranslationX(newX - naturalLeft);
          _container.setTranslationY(newY - naturalTop);
          _posX = newX;
          _posY = newY;
          _container.requestApplyInsets();
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
