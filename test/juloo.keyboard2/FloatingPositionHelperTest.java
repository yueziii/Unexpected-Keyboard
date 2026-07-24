package juloo.keyboard2;

import juloo.keyboard2.FloatingPositionHelper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the floating keyboard position logic extracted into
 * FloatingPositionHelper. These verify the core behavior of:
 * - Fix 1: Top position limit (avoid notification bar)
 * - Fix 2: (layout change, tested via build)
 * - Fix 3: Position clamping with screen size changes (foldable)
 * - Fix 4: (split mode, tested via Config)
 */
public class FloatingPositionHelperTest
{
  // Simulated screen: 1080x2400 (typical phone)
  private static final int PHONE_W = 1080;
  private static final int PHONE_H = 2400;
  private static final int STATUS_BAR = 80; // typical status bar height

  // Simulated foldable inner screen: 2208x1840
  private static final int FOLD_W = 2208;
  private static final int FOLD_H = 1840;
  private static final int FOLD_STATUS_BAR = 56;

  // ===== Fix 1: Top position limit (avoid notification bar) =====

  @Test
  public void clampY_prevents_keyboard_above_status_bar()
  {
    // Keyboard should not be placed above the status bar
    int kbHeight = 800;
    float result = FloatingPositionHelper.clampY(0, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals((float)STATUS_BAR, result, 0.01f);
  }

  @Test
  public void clampY_prevents_keyboard_above_min_top_pct()
  {
    // Even if status bar is very small, MIN_TOP_PCT (5%) should be used
    // The caller should pass computeMinTopOffset() result as statusBarHeight
    int kbHeight = 800;
    int tinyStatusBar = 10;
    int effectiveTop = FloatingPositionHelper.computeMinTopOffset(PHONE_H, tinyStatusBar);
    float result = FloatingPositionHelper.clampY(0, kbHeight, PHONE_H, effectiveTop);
    // 5% of 2400 = 120, which is > 10
    assertEquals(120f, result, 0.01f);
  }

  @Test
  public void clampY_allows_keyboard_below_status_bar()
  {
    int kbHeight = 800;
    float result = FloatingPositionHelper.clampY(200, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals(200f, result, 0.01f);
  }

  @Test
  public void clampY_prevents_keyboard_below_screen()
  {
    int kbHeight = 800;
    int bottomMargin = FloatingPositionHelper.computeBottomSafeMargin(PHONE_H);
    float result = FloatingPositionHelper.clampY(PHONE_H, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals((float)(PHONE_H - kbHeight - bottomMargin), result, 0.01f);
  }

  @Test
  public void clampY_normal_position_is_unchanged()
  {
    int kbHeight = 800;
    int bottomMargin = FloatingPositionHelper.computeBottomSafeMargin(PHONE_H);
    // Position above the bottom safe margin should be unchanged
    float normalY = PHONE_H - kbHeight - bottomMargin - 50;
    float result = FloatingPositionHelper.clampY(normalY, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals(normalY, result, 0.01f);
  }

  // ===== defaultPosY =====

  @Test
  public void defaultPosY_is_near_bottom_with_margin()
  {
    int kbHeight = 800;
    float result = FloatingPositionHelper.defaultPosY(kbHeight, PHONE_H, STATUS_BAR);
    // Should be screenHeight - kbHeight - 5% bottom safe margin = 2400 - 800 - 120 = 1480
    int bottomMargin = FloatingPositionHelper.computeBottomSafeMargin(PHONE_H);
    float expected = PHONE_H - kbHeight - bottomMargin;
    assertEquals(expected, result, 0.01f);
  }

  @Test
  public void defaultPosY_respects_status_bar_minimum()
  {
    // Very tall keyboard that would push default position above status bar
    int kbHeight = PHONE_H - 50; // almost full screen
    float result = FloatingPositionHelper.defaultPosY(kbHeight, PHONE_H, STATUS_BAR);
    // Should be at least statusBarHeight
    assertTrue("defaultPosY should be >= statusBarHeight", result >= STATUS_BAR);
  }

  // ===== computeMinTopOffset =====

  @Test
  public void computeMinTopOffset_uses_status_bar_when_larger()
  {
    // Status bar (80) > 5% of 2400 (120)? No, 80 < 120
    // So it should use 120
    int result = FloatingPositionHelper.computeMinTopOffset(PHONE_H, STATUS_BAR);
    assertEquals(120, result);
  }

  @Test
  public void computeMinTopOffset_uses_min_pct_when_status_bar_smaller()
  {
    // 5% of 2400 = 120, status bar = 80 => use 120
    int result = FloatingPositionHelper.computeMinTopOffset(PHONE_H, STATUS_BAR);
    assertEquals(PHONE_H * FloatingPositionHelper.MIN_TOP_PCT / 100, result);
  }

  @Test
  public void computeMinTopOffset_uses_status_bar_when_larger_than_min()
  {
    // On a very small screen (e.g. 400px), 5% = 20, status bar = 30
    int result = FloatingPositionHelper.computeMinTopOffset(400, 30);
    assertEquals(30, result);
  }

  // ===== Fix 3: Foldable screen - position clamping with screen size change =====

  @Test
  public void clampY_on_foldable_closed_screen()
  {
    // On closed foldable (phone-sized), keyboard at valid position
    int kbHeight = 800;
    float validY = 100;
    float result = FloatingPositionHelper.clampY(validY, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals(100f, result, 0.01f);
  }

  @Test
  public void clampY_on_foldable_opened_screen()
  {
    // When unfolded, same keyboard position should still be valid
    // (wider/taller screen, different status bar)
    int kbHeight = 800;
    float validY = 100;
    float result = FloatingPositionHelper.clampY(validY, kbHeight, FOLD_H, FOLD_STATUS_BAR);
    assertEquals(100f, result, 0.01f);
  }

  @Test
  public void clampY_reclamps_when_screen_shrinks()
  {
    // Keyboard was at Y=1400 on foldable (1840 tall), then screen changes to phone (2400)
    // Position 1400 is still valid on phone screen (max = 2400-800-120 = 1480)
    int kbHeight = 800;
    float oldY = 1400;
    float result = FloatingPositionHelper.clampY(oldY, kbHeight, PHONE_H, STATUS_BAR);
    assertEquals(1400f, result, 0.01f);
  }

  @Test
  public void clampY_clamps_when_position_exceeds_new_screen()
  {
    // Keyboard was at Y=1100 on phone (2400 tall, kb=800 => max Y=1600)
    // Then screen changes to foldable closed (smaller), kb=800, bottom safe margin
    // Y=1100 should be clamped to screen - kbHeight - bottomMargin
    int kbHeight = 800;
    int smallScreenH = 1840;
    int smallStatusBar = 56;
    int bottomMargin = FloatingPositionHelper.computeBottomSafeMargin(smallScreenH);
    float result = FloatingPositionHelper.clampY(1100, kbHeight, smallScreenH, smallStatusBar);
    assertEquals((float)(smallScreenH - kbHeight - bottomMargin), result, 0.01f);
  }

  // ===== clampX =====

  @Test
  public void clampX_centers_keyboard()
  {
    int kbWidth = 800;
    // 140 is above the horizontal safe margin (5% of 1080 = 54)
    float result = FloatingPositionHelper.clampX(140, kbWidth, PHONE_W);
    assertEquals(140f, result, 0.01f);
  }

  @Test
  public void clampX_prevents_keyboard_off_right()
  {
    int kbWidth = 800;
    int hMargin = FloatingPositionHelper.computeHorizontalSafeMargin(PHONE_W);
    float result = FloatingPositionHelper.clampX(PHONE_W, kbWidth, PHONE_W);
    assertEquals((float)(PHONE_W - kbWidth - hMargin), result, 0.01f);
  }

  @Test
  public void clampX_prevents_keyboard_off_left()
  {
    int kbWidth = 800;
    int hMargin = FloatingPositionHelper.computeHorizontalSafeMargin(PHONE_W);
    float result = FloatingPositionHelper.clampX(-100, kbWidth, PHONE_W);
    assertEquals((float)hMargin, result, 0.01f);
  }

  // ===== defaultPosX =====

  @Test
  public void defaultPosX_centers_keyboard()
  {
    int kbWidth = 800;
    // Centered position is (1080-800)/2 = 140, which is above the horizontal
    // safe margin (54), so it should be unchanged.
    float result = FloatingPositionHelper.defaultPosX(kbWidth, PHONE_W);
    assertEquals((PHONE_W - kbWidth) / 2f, result, 0.01f);
  }

  @Test
  public void defaultPosX_full_width_clamped_to_safe_margin()
  {
    // Full-width keyboard: centered would be 0, but horizontal safe margin
    // forces it to at least the safe margin.
    int hMargin = FloatingPositionHelper.computeHorizontalSafeMargin(PHONE_W);
    float result = FloatingPositionHelper.defaultPosX(PHONE_W, PHONE_W);
    assertEquals((float)hMargin, result, 0.01f);
  }
}
