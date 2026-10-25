package juloo.keyboard2;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Performs text edits on behalf of the overlay keyboard into the focused
    editor of other apps. Must be enabled in the system accessibility
    settings; only the text and selection of the focused editor are read. */
public class OverlayAccessibilityService extends AccessibilityService
{
  private static OverlayAccessibilityService _instance = null;

  /** Last editor the system reported as focused; used as a fallback when the
      window list doesn't expose the input focus (some apps don't). */
  private AccessibilityNodeInfo _last_focused_editor;

  public static boolean is_running()
  {
    return _instance != null;
  }

  @Override
  protected void onServiceConnected()
  {
    _instance = this;
  }

  @Override
  public void onDestroy()
  {
    _instance = null;
    _last_focused_editor = null;
    super.onDestroy();
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event)
  {
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED)
    {
      AccessibilityNodeInfo node = event.getSource();
      if (node != null && can_receive_text(node))
        _last_focused_editor = node;
    }
  }

  @Override
  public void onInterrupt()
  {
  }

  /** Insert [text] at the cursor of the currently focused editor. */
  public static void commit_text(CharSequence text)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
    {
      svc.toast(R.string.overlay_no_target);
      return;
    }
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text != null && sel_start >= 0 && sel_end >= sel_start)
    {
      StringBuilder b = new StringBuilder(node_text);
      b.replace(sel_start, sel_end, text.toString());
      if (svc.set_node_text(node, b, sel_start + text.length()))
        return;
    }
    // The editor can't be written directly (e.g. a password field): paste
    // from the clipboard instead, the system inserts at the cursor.
    if (!paste(node, text))
      svc.toast(R.string.overlay_unreadable_target);
  }

  /** Remove the selection if any, else [n] characters before the cursor. */
  public static void backspace(int n)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
    {
      svc.toast(R.string.overlay_no_target);
      return;
    }
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text == null || sel_start < 0 || sel_end < sel_start)
    {
      svc.toast(R.string.overlay_unreadable_target);
      return;
    }
    int start, end;
    if (sel_end > sel_start)
    {
      start = sel_start;
      end = sel_end;
    }
    else
    {
      start = Math.max(0, sel_start - n);
      end = sel_start;
    }
    StringBuilder b = new StringBuilder(node_text);
    b.delete(start, end);
    svc.set_node_text(node, b, start);
  }

  /** Remove [n] characters after the cursor. */
  public static void forward_delete(int n)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return;
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text == null || sel_start < 0 || sel_end < sel_start)
      return;
    int start, end;
    if (sel_end > sel_start)
    {
      start = sel_start;
      end = sel_end;
    }
    else
    {
      start = sel_start;
      end = Math.min(node_text.length(), sel_start + n);
    }
    StringBuilder b = new StringBuilder(node_text);
    b.delete(start, end);
    svc.set_node_text(node, b, start);
  }

  /** Remove the word before the cursor ([before] is true) or after it. */
  public static void delete_word(boolean before)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return;
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text == null || sel_start < 0 || sel_end < sel_start)
      return;
    int start = sel_start, end = sel_end;
    if (sel_end == sel_start)
    {
      if (before)
      {
        start = sel_start;
        while (start > 0 && Character.isWhitespace(node_text.charAt(start - 1)))
          start--;
        while (start > 0 && !Character.isWhitespace(node_text.charAt(start - 1)))
          start--;
      }
      else
      {
        end = sel_start;
        while (end < node_text.length() && Character.isWhitespace(node_text.charAt(end)))
          end++;
        while (end < node_text.length() && !Character.isWhitespace(node_text.charAt(end)))
          end++;
      }
    }
    StringBuilder b = new StringBuilder(node_text);
    b.delete(start, end);
    svc.set_node_text(node, b, start);
  }

  /** Move the cursor by [d] characters, collapsing any selection. */
  public static void move_cursor(int d)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return;
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text == null || sel_start < 0 || sel_end < sel_start)
      return;
    int cursor = Math.max(0, Math.min(node_text.length(), sel_end + d));
    Bundle args = new Bundle();
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
  }

  /** Perform a clipboard node action (ACTION_COPY / ACTION_CUT /
      ACTION_PASTE) on the focused editor. COPY and CUT require Android 11.
      Returns whether the editor performed the action. */
  public static boolean clipboard_action(int action)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return false;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return false;
    return node.performAction(action);
  }

  /** Select the whole text of the focused editor (Ctrl+A). */
  public static boolean select_all()
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return false;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return false;
    CharSequence node_text = node.getText();
    if (node_text == null)
      return false;
    Bundle args = new Bundle();
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
        node_text.length());
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
  }

  /** Move the cursor by a whole word (Ctrl+Left/Right); [extend] keeps the
      selection growing instead (Ctrl+Shift+Left/Right). */
  public static boolean move_word(boolean forward, boolean extend)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return false;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return false;
    Bundle args = new Bundle();
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
    args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
        extend);
    return node.performAction(forward
        ? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        : AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args);
  }

  /** Move the right side of the selection by [d] characters
      (Shift+Left/Right). */
  public static void extend_selection(int d)
  {
    OverlayAccessibilityService svc = _instance;
    if (svc == null)
      return;
    AccessibilityNodeInfo node = svc.find_editable();
    if (node == null)
      return;
    CharSequence node_text = node.getText();
    int sel_start = node.getTextSelectionStart();
    int sel_end = node.getTextSelectionEnd();
    if (node_text == null || sel_start < 0 || sel_end < sel_start)
      return;
    int end = Math.max(0, Math.min(node_text.length(), sel_end + d));
    int start = sel_start;
    if (end < start)
    {
      int tmp = start;
      start = end;
      end = tmp;
    }
    Bundle args = new Bundle();
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
  }

  /** Replace the editor's text with [new_text] and place the cursor at
      [new_cursor]. Returns [false] if the editor doesn't support
      [ACTION_SET_TEXT]. */
  boolean set_node_text(AccessibilityNodeInfo node, CharSequence new_text,
      int new_cursor)
  {
    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
        new_text);
    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
      return false;
    Bundle sel = new Bundle();
    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, new_cursor);
    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, new_cursor);
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    return true;
  }

  /** Paste [text] into [node] through the clipboard. Returns whether the
      node performed the paste action. */
  static boolean paste(AccessibilityNodeInfo node, CharSequence text)
  {
    ClipboardManager clip = (ClipboardManager)
      _instance.getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData prev = clip.getPrimaryClip();
    clip.setPrimaryClip(ClipData.newPlainText(null, text));
    if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE))
      return true;
    if (prev != null)
      clip.setPrimaryClip(prev); // Restore the clipboard if pasting failed
    return false;
  }

  /** The editable node to type into.

      While the overlay keyboard is touched, it is the "active window" itself,
      so [getRootInActiveWindow] points at the keyboard and not at the app the
      user is typing into. The window list is searched instead, preferring the
      window that holds the input focus; the editor last reported focused by
      the system and the active window are used as fallbacks. */
  AccessibilityNodeInfo find_editable()
  {
    List<AccessibilityWindowInfo> targets = target_windows();
    for (int pass = 0; pass < 2; pass++)
      for (int i = 0; i < targets.size(); i++)
      {
        AccessibilityNodeInfo root = targets.get(i).getRoot();
        if (root == null)
          continue;
        AccessibilityNodeInfo node;
        if (pass == 0)
        {
          node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
          if (node != null && can_receive_text(node))
            return node;
        }
        else
        {
          node = find_editable_dfs(root, 0);
          if (node != null)
            return node;
        }
      }
    AccessibilityNodeInfo last = last_focused_editor();
    if (last != null)
      return last;
    // getWindows can be empty on some setups: fall back to the active window.
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null)
      return null;
    AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    if (focus != null && can_receive_text(focus))
      return focus;
    return find_editable_dfs(root, 0);
  }

  /** Whether [node] can receive the text typed from the overlay. Editors
      report [isEditable], but some apps ship focused editors that don't
      (hardened or custom views); for those, accept nodes that declare a text
      action or that look like an EditText. */
  boolean can_receive_text(AccessibilityNodeInfo node)
  {
    if (node.isEditable())
      return true;
    if ((node.getActions() & (AccessibilityNodeInfo.ACTION_SET_TEXT
          | AccessibilityNodeInfo.ACTION_PASTE)) != 0)
      return true;
    CharSequence cls = node.getClassName();
    return cls != null && cls.toString().contains("EditText");
  }

  /** Windows that can hold the target editor: every window but the input
      method and this app's own overlay windows, the one holding the input
      focus first. */
  List<AccessibilityWindowInfo> target_windows()
  {
    List<AccessibilityWindowInfo> targets = new ArrayList<>();
    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows == null)
      return targets;
    for (int i = 0; i < windows.size(); i++)
    {
      AccessibilityWindowInfo w = windows.get(i);
      if (!is_own_or_ime_window(w))
        targets.add(w);
    }
    for (int i = 1; i < targets.size(); i++)
      if (targets.get(i).isFocused())
      {
        targets.add(0, targets.remove(i));
        break;
      }
    return targets;
  }

  /** Whether [window] is the input method or one of this app's overlay
      windows, which never hold the editor to type into. */
  boolean is_own_or_ime_window(AccessibilityWindowInfo window)
  {
    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
      return true;
    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION)
      return false; // This app's own activities also live in such windows.
    AccessibilityNodeInfo root = window.getRoot();
    return root != null && getPackageName().equals(root.getPackageName());
  }

  /** The editor last reported focused by the system, refreshed so a stale
      node is detected and dropped. */
  AccessibilityNodeInfo last_focused_editor()
  {
    AccessibilityNodeInfo node = _last_focused_editor;
    if (node == null)
      return null;
    if (!node.refresh() || !can_receive_text(node))
    {
      _last_focused_editor = null;
      return null;
    }
    return node;
  }

  static AccessibilityNodeInfo find_editable_dfs(AccessibilityNodeInfo node,
      int depth)
  {
    if (depth > 40)
      return null;
    if (node.isEditable() && node.isFocusable())
      return node;
    for (int i = 0; i < node.getChildCount(); i++)
    {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null)
        continue;
      AccessibilityNodeInfo found = find_editable_dfs(child, depth + 1);
      if (found != null)
        return found;
    }
    return null;
  }

  void toast(int msg_id)
  {
    Toast.makeText(this, getString(msg_id), Toast.LENGTH_SHORT).show();
  }
}
