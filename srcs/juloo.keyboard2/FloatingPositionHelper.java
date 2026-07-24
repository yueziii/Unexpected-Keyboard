package juloo.keyboard2;

/**
 * Pure-logic helper for floating keyboard position clamping.
 * Extracted from FloatingKeyboardManager for testability.
 */
public final class FloatingPositionHelper
{
  /** Minimum top margin as percentage of screen height to avoid the notification bar. */
  public static final int MIN_TOP_PCT = 5;
  /** Minimum bottom margin as percentage of screen height to avoid system gestures. */
  public static final int MIN_BOTTOM_PCT = 5;
  /** Horizontal safe margin as percentage of screen width to avoid edge system gestures. */
  public static final int HORIZONTAL_SAFE_PCT = 5;

  /**
   * Clamp the Y position so the keyboard doesn't go above the safe area
   * (status/notification bar) or below the screen minus a bottom safe margin
   * that avoids triggering system-level gestures (e.g. home/navigation).
   * @param y Desired top position in pixels
   * @param kbHeight Keyboard height in pixels
   * @param screenHeight Screen height in pixels
   * @param statusBarHeight Height of the status bar area to avoid
   * @return Clamped Y position
   */
  public static float clampY(float y, int kbHeight, int screenHeight, int statusBarHeight)
  {
    int bottomMargin = computeBottomSafeMargin(screenHeight);
    return Math.max(statusBarHeight, Math.min(y, screenHeight - kbHeight - bottomMargin));
  }

  /**
   * Clamp the X position so the keyboard stays within screen bounds, with
   * horizontal safe margins on both sides to avoid triggering system back
   * gestures at screen edges.
   * @param x Desired left position in pixels
   * @param kbWidth Keyboard width in pixels
   * @param screenWidth Screen width in pixels
   * @return Clamped X position
   */
  public static float clampX(float x, int kbWidth, int screenWidth)
  {
    int hMargin = computeHorizontalSafeMargin(screenWidth);
    return Math.max(hMargin, Math.min(x, screenWidth - kbWidth - hMargin));
  }

  /**
   * Compute the default Y position (near bottom of screen, with margin).
   * @param kbHeight Keyboard height in pixels
   * @param screenHeight Screen height in pixels
   * @param statusBarHeight Height of the status bar area to avoid
   * @return Default Y position
   */
  public static float defaultPosY(int kbHeight, int screenHeight, int statusBarHeight)
  {
    int bottomMargin = computeBottomSafeMargin(screenHeight);
    return Math.max(statusBarHeight, screenHeight - kbHeight - bottomMargin);
  }

  /**
   * Compute the default X position (centered, respecting horizontal safe margins).
   * @param kbWidth Keyboard width in pixels
   * @param screenWidth Screen width in pixels
   * @return Default X position
   */
  public static float defaultPosX(int kbWidth, int screenWidth)
  {
    int hMargin = computeHorizontalSafeMargin(screenWidth);
    return Math.max(hMargin, (screenWidth - kbWidth) / 2f);
  }

  /**
   * Compute the minimum top offset to avoid the notification bar.
   * Uses the actual status bar height if available, with a minimum
   * of MIN_TOP_PCT of screen height.
   * @param screenHeight Screen height in pixels
   * @param statusBarHeight Actual status bar height from system resources
   * @return The effective top offset
   */
  public static int computeMinTopOffset(int screenHeight, int statusBarHeight)
  {
    int minTopPx = screenHeight * MIN_TOP_PCT / 100;
    return Math.max(statusBarHeight, minTopPx);
  }

  /**
   * Compute the bottom safe margin to avoid system gestures.
   * Uses MIN_BOTTOM_PCT of screen height.
   * @param screenHeight Screen height in pixels
   * @return The bottom safe margin in pixels
   */
  public static int computeBottomSafeMargin(int screenHeight)
  {
    return screenHeight * MIN_BOTTOM_PCT / 100;
  }

  /**
   * Compute the horizontal safe margin to avoid system edge gestures.
   * Uses HORIZONTAL_SAFE_PCT of screen width.
   * @param screenWidth Screen width in pixels
   * @return The horizontal safe margin in pixels
   */
  public static int computeHorizontalSafeMargin(int screenWidth)
  {
    return screenWidth * HORIZONTAL_SAFE_PCT / 100;
  }
}
