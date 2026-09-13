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
 * contentTopInsets is set to screenHeight so the system computes
 * IME height = 0, preventing app window resize/pan.
 *
 * Dragging is handled by a drag handle View at the bottom of the keyboard
 * layout, alongside the close button and resize handle.
 */
public final class FloatingKeyboardManager
  implements View.OnTouchListener
{
  private final Keyboard2 _ime;
  private final Config _config;
  private View _container;
  private View _handle;
  private View _resizeHandle;
  private int _screenWidth;
  private int _screenHeight;
  /** Top offset to avoid the status/notification bar area. */
  private int _statusBarHeight;
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

  /** Minimum floating width as percentage of screen width. */
  private static final int MIN_WIDTH_PCT = 40;
  /** Maximum floating width as percentage of screen width (not 100 to avoid edge system gestures). */
  private static final int MAX_WIDTH_PCT = 90;
  /** Minimum floating height as percentage of screen height. */
  private static final int MIN_HEIGHT_PCT = 10;
  /** Maximum floating height as percentage of screen height. */
  private static final int MAX_HEIGHT_PCT = 80;

  /** Minimum top margin is defined in FloatingPositionHelper.MIN_TOP_PCT. */

  // Resize state
  private boolean _isResizing;
  private float _resizeStartRawX;
  private float _resizeStartRawY;
  private int _resizeStartWidthPx;
  private int _resizeStartHeightPx;
  /** Ratio between the container's full height and
      [Config.keyboard_rows_height_pixels], measured at gesture start. Used
      to convert the dragged height into a rows height so the achieved
      keyboard size tracks the finger (the container also holds the
      candidates bar and margins, which scale with the rows height). */
  private float _rowsHeightScale = 4.7f;
  /** While resizing, re-applies the translation after every layout pass so
      the keyboard stays pinned to (_posX,_posY) while its size changes.
      Without this, the bottom-anchored container visibly jumps whenever a
      new height applies (the natural top shifts) and gets corrected at the
      next move event, alternating at frame rate. */
  private ViewTreeObserver.OnPreDrawListener _resizePreDrawListener;

  public FloatingKeyboardManager(Keyboard2 ime, Config config)
  {
    _ime = ime;
    _config = config;
    updateScreenSize();
    _posX = _config.floating_pos_x;
    _posY = _config.floating_pos_y;
  }

  public void attach(View container, View handle)
  {
    _container = container;
    _handle = handle;
    if (_handle != null)
    {
      // The handle is a LinearLayout containing a drag area and a close button.
      // Find the drag area inside it.
      View dragArea = _handle;
      if (handle instanceof ViewGroup)
      {
        View found = ((ViewGroup)handle).findViewById(R.id.floating_drag_handle_area);
        if (found != null)
          dragArea = found;
      }
      dragArea.setOnTouchListener(this);
      dragArea.setClickable(true);
      // Find and wire the close button
      if (handle instanceof ViewGroup)
      {
        View closeBtn = ((ViewGroup)handle).findViewById(R.id.floating_close_btn);
        if (closeBtn != null)
        {
          closeBtn.setOnClickListener(new View.OnClickListener()
          {
            @Override
            public void onClick(View v)
            {
              _ime.requestHideSelf(0);
            }
          });
        }
      }
    }
    // Find and wire the resize handle
    if (container instanceof ViewGroup)
    {
      _resizeHandle = ((ViewGroup)container).findViewById(R.id.floating_resize_handle);
      if (_resizeHandle != null)
      {
        _resizeHandle.setOnTouchListener(new View.OnTouchListener()
        {
          @Override
          public boolean onTouch(View v, MotionEvent event)
          {
            return onResizeTouch(event);
          }
        });
      }
    }
  }

  /** Update screen dimensions and reload fold-state-specific size/position.
   * Called on config changes (e.g. fold/unfold). */
  public void updateScreenSize()
  {
    DisplayMetrics dm = _ime.getResources().getDisplayMetrics();
    _screenWidth = dm.widthPixels;
    _screenHeight = dm.heightPixels;
    // Estimate status bar height to avoid the notification shade area
    int rawStatusBarHeight;
    int resourceId = _ime.getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resourceId > 0)
      rawStatusBarHeight = _ime.getResources().getDimensionPixelSize(resourceId);
    else
      rawStatusBarHeight = (int)(_screenHeight * 0.03);
    _statusBarHeight = FloatingPositionHelper.computeMinTopOffset(_screenHeight, rawStatusBarHeight);
    _estimatedHeight = _screenHeight * 40 / 100;
    // Reload position and width from config so fold-state-specific values are
    // used (folded and unfolded states have separate saved values).
    _posX = _config.floating_pos_x;
    _posY = _config.floating_pos_y;
    _floatWidth = computeFloatWidth();
  }

  /** Compute floating width in pixels from config percentage. */
  private int computeFloatWidth()
  {
    int pct = Math.max(MIN_WIDTH_PCT, Math.min(MAX_WIDTH_PCT, _config.floating_keyboard_width));
    return _screenWidth * pct / 100;
  }

  /** Default Y position if none saved. */
  private float defaultPosY(int kbHeight)
  {
    return FloatingPositionHelper.defaultPosY(kbHeight, _screenHeight, _statusBarHeight);
  }

  /** Default X position (centered). */
  private float defaultPosX(int kbWidth)
  {
    return FloatingPositionHelper.defaultPosX(kbWidth, _screenWidth);
  }

  private float clampX(float x, int kbWidth)
  {
    return FloatingPositionHelper.clampX(x, kbWidth, _screenWidth);
  }

  private float clampY(float y, int kbHeight)
  {
    return FloatingPositionHelper.clampY(y, kbHeight, _screenHeight, _statusBarHeight);
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
      View h = ((ViewGroup)container).findViewById(R.id.floating_bottom_handle);
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
    applyContainerWidth(container, _floatWidth);
  }

  /** Set the container width to [targetWidthPx]. Only calls
      [setLayoutParams] when a value actually changed — it triggers a full
      layout pass, which must not happen on every resize move event. */
  private void applyContainerWidth(View container, int targetWidthPx)
  {
    ViewGroup.LayoutParams lp = container.getLayoutParams();
    if (lp == null) return;
    int targetWidth = (targetWidthPx > 0 && targetWidthPx < _screenWidth)
      ? targetWidthPx : ViewGroup.LayoutParams.MATCH_PARENT;
    boolean changed = (lp.width != targetWidth);
    lp.width = targetWidth;
    // Reset margins and gravity — positioning is via translation only
    if (lp instanceof FrameLayout.LayoutParams)
    {
      FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
      if (flp.gravity != (Gravity.BOTTOM | Gravity.START))
      {
        flp.gravity = Gravity.BOTTOM | Gravity.START;
        changed = true;
      }
      flp.leftMargin = 0;
    }
    else if (lp instanceof LinearLayout.LayoutParams)
    {
      LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
      if (llp.gravity != (Gravity.BOTTOM | Gravity.START))
      {
        llp.gravity = Gravity.BOTTOM | Gravity.START;
        changed = true;
      }
      llp.leftMargin = 0;
    }
    if (changed)
      container.setLayoutParams(lp);
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

  /** While resizing, keep applying the translation after every layout pass
      with the fresh (post-layout) container size. Computing the translation
      from the stale pre-layout size instead makes the keyboard jump by the
      height delta every frame. */
  private void start_resize_translation_sync()
  {
    if (_resizePreDrawListener != null || _container == null)
      return;
    _resizePreDrawListener = new ViewTreeObserver.OnPreDrawListener()
    {
      @Override
      public boolean onPreDraw()
      {
        if (_container != null)
          sync_translation(_container);
        return true;
      }
    };
    _container.getViewTreeObserver().addOnPreDrawListener(_resizePreDrawListener);
  }

  private void stop_resize_translation_sync()
  {
    if (_resizePreDrawListener != null && _container != null)
      _container.getViewTreeObserver().removeOnPreDrawListener(
          _resizePreDrawListener);
    _resizePreDrawListener = null;
  }

  /** Apply the translation so the container's top-left lands on the clamped
      (_posX,_posY), using the container's current (post-layout) size. */
  private void sync_translation(View container)
  {
    int w = container.getWidth();
    int h = container.getHeight();
    if (w <= 0 || h <= 0)
      return;
    _posX = clampX(_posX, w);
    _posY = clampY(_posY, h);
    int[] loc = new int[2];
    container.getLocationOnScreen(loc);
    float naturalLeft = loc[0] - container.getTranslationX();
    float naturalTop = loc[1] - container.getTranslationY();
    container.setTranslationX(_posX - naturalLeft);
    container.setTranslationY(_posY - naturalTop);
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
   * contentTopInsets = screenHeight so the app window keeps full height.
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
    outInsets.contentTopInsets = _screenHeight;
    outInsets.visibleTopInsets = _screenHeight;
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

  // --- Resize handling ---

  private boolean onResizeTouch(MotionEvent event)
  {
    if (_container == null) return false;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
      {
        _resizeStartRawX = event.getRawX();
        _resizeStartRawY = event.getRawY();
        _resizeStartWidthPx = _container.getWidth();
        _resizeStartHeightPx = _container.getHeight();
        int rows = _config.keyboard_rows_height_pixels;
        if (rows > 0)
          _rowsHeightScale = _resizeStartHeightPx / (float)rows;
        _isResizing = false;
        return true;
      }
      case MotionEvent.ACTION_MOVE:
      {
        float dx = event.getRawX() - _resizeStartRawX;
        float dy = event.getRawY() - _resizeStartRawY;
        if (!_isResizing && (Math.abs(dx) > 8 || Math.abs(dy) > 8))
        {
          _isResizing = true;
          // Keep the translation corrected after every layout pass while
          // the container's size changes.
          start_resize_translation_sync();
        }
        if (_isResizing)
        {
          // Raw pixel size, clamped to the min/max bounds. Do not quantize
          // to integer percents during the drag: one percent is tens of
          // pixels, which makes slow drags jump.
          int newWidthPx = Math.max(_screenWidth * MIN_WIDTH_PCT / 100,
              Math.min(_resizeStartWidthPx + (int)dx,
                _screenWidth * MAX_WIDTH_PCT / 100));
          int newHeightPx = Math.max(_screenHeight * MIN_HEIGHT_PCT / 100,
              Math.min(_resizeStartHeightPx + (int)dy,
                _screenHeight * MAX_HEIGHT_PCT / 100));

          // Convert the dragged height into the rows height using the ratio
          // measured at gesture start, so the achieved height matches the
          // finger during the whole gesture. Percent config values are only
          // written when the gesture ends.
          _config.keyboard_rows_height_pixels =
            Math.max(1, (int)(newHeightPx / _rowsHeightScale));
          _floatWidth = newWidthPx;

          applyContainerWidth(_container);
          _ime.refresh_floating_keyboard();
          _container.requestApplyInsets();
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_isResizing)
        {
          _isResizing = false;
          stop_resize_translation_sync();
          // Persist the size as integer percents derived from the final
          // values used during the drag, so re-deriving them from the
          // percents lands back on (nearly) the same size instead of
          // snapping to a quantized value.
          _config.floating_keyboard_height = clampPercent(
              Math.round(_config.keyboard_rows_height_pixels * 395f
                / _screenHeight),
              MIN_HEIGHT_PCT, MAX_HEIGHT_PCT);
          _config.floating_keyboard_width = clampPercent(
              Math.round(_floatWidth * 100f / _screenWidth),
              MIN_WIDTH_PCT, MAX_WIDTH_PCT);
          _config.save_floating_size(_config.floating_keyboard_width,
              _config.floating_keyboard_height);
        }
        return true;
    }
    return false;
  }

  private static int clampPercent(int pct, int min, int max)
  {
    return Math.max(min, Math.min(pct, max));
  }
}
