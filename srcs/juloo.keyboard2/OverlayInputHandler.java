package juloo.keyboard2;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.KeyEvent;
import juloo.keyboard2.voice.VoiceInputController;

/** Routes key events from the overlay keyboard's [Keyboard2View] to the
    focused editor of other apps through [OverlayAccessibilityService]. This
    replaces [KeyEventHandler], which requires an [InputConnection] and thus
    only works while being the active input method.

    Latched modifiers are honored for the operations that accessibility node
    actions can express: Ctrl+C/X/V/A, Ctrl+Left/Right (word-wise moves),
    Ctrl+Backspace/Del (word-wise deletes) and Shift+Left/Right (selection). */
class OverlayInputHandler implements Config.IKeyEventHandler
{
  public interface Ui
  {
    /** Collapse the overlay to the bubble ([KeyValue.Event.HIDE_SELF]). */
    void hide_overlay();
    void set_compose_pending(boolean pending);
    void cycle_text_layout(int delta);
    void show_text_layout();
    void show_special_layout(int xml_id);
  }

  final Ui _ui;

  public OverlayInputHandler(Ui ui)
  {
    _ui = ui;
  }

  @Override
  public void key_down(KeyValue kv, boolean is_swipe)
  {
    if (kv == null)
      return;
    if (VoiceInputController.is_mic_key(kv))
      VoiceInputController.mic_down();
    else
      VoiceInputController.key_interrupted();
  }

  @Override
  public void key_up(KeyValue kv, Pointers.Modifiers mods)
  {
    if (kv == null)
      return;
    if (!VoiceInputController.is_mic_key(kv))
      VoiceInputController.key_interrupted();
    boolean ctrl = mods.has(KeyValue.Modifier.CTRL);
    switch (kv.getKind())
    {
      case Char:
      {
        char ch = kv.getChar();
        if (ctrl && handle_ctrl_char(ch))
          break;
        OverlayAccessibilityService.commit_text(String.valueOf(ch));
        break;
      }
      case String:
        OverlayAccessibilityService.commit_text(kv.getString());
        break;
      case Keyevent: handle_keyevent(kv.getKeyevent(), ctrl); break;
      case Editing: handle_editing(kv.getEditing(), ctrl); break;
      case Event:
        if (kv.getEvent() == KeyValue.Event.SWITCH_VOICE_TYPING)
          VoiceInputController.mic_up();
        else
          handle_event(kv.getEvent());
        break;
      case Modifier: break;
      case Compose_pending: _ui.set_compose_pending(true); break;
      case Slider: handle_slider(kv.getSlider(), kv.getSliderRepeat(), ctrl); break;
      case Macro: evaluate_macro(kv.getMacro()); break;
      default: break;
    }
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
  }

  @Override
  public void suggestion_entered(String text)
  {
    OverlayAccessibilityService.commit_text(text);
  }

  /** Word learning is irrelevant to the overlay keyboard; removing a
      learned word does nothing. */
  @Override
  public void suggestion_removed(String text)
  {
  }

  // Hold-to-talk voice input. The overlay keyboard has no candidates bar
  // and accessibility node actions cannot set composing text, so only the
  // final transcript is injected.

  @Override
  public void voice_started()
  {
  }

  @Override
  public void voice_partial(String text)
  {
  }

  @Override
  public void voice_final(String text)
  {
    if (text != null && !text.isEmpty())
      OverlayAccessibilityService.commit_text(text);
  }

  /** PC-style Ctrl combos that accessibility node actions can express.
      Returns whether [ch] was recognized as a command (the character is not
      typed); unrecognized combos fall through to typing the character.
      COPY and CUT need Android 11, on older versions the action silently
      does nothing. */
  boolean handle_ctrl_char(char ch)
  {
    switch (ch)
    {
      case 'c':
        OverlayAccessibilityService.clipboard_action(
            AccessibilityNodeInfo.ACTION_COPY);
        return true;
      case 'x':
        OverlayAccessibilityService.clipboard_action(
            AccessibilityNodeInfo.ACTION_CUT);
        return true;
      case 'v':
        OverlayAccessibilityService.clipboard_action(
            AccessibilityNodeInfo.ACTION_PASTE);
        return true;
      case 'a':
        OverlayAccessibilityService.select_all();
        return true;
      default:
        return false;
    }
  }

  void handle_keyevent(int code, boolean ctrl)
  {
    if (ctrl)
    {
      // KeyModifier turned Ctrl+letter into a letter keycode; map the
      // combos that accessibility node actions can express. Unsupported
      // combos (Ctrl+Z...) have no equivalent and do nothing.
      switch (code)
      {
        case KeyEvent.KEYCODE_A:
          OverlayAccessibilityService.select_all();
          return;
        case KeyEvent.KEYCODE_C:
          OverlayAccessibilityService.clipboard_action(
              AccessibilityNodeInfo.ACTION_COPY);
          return;
        case KeyEvent.KEYCODE_X:
          OverlayAccessibilityService.clipboard_action(
              AccessibilityNodeInfo.ACTION_CUT);
          return;
        case KeyEvent.KEYCODE_V:
          OverlayAccessibilityService.clipboard_action(
              AccessibilityNodeInfo.ACTION_PASTE);
          return;
        default: return;
      }
    }
    switch (code)
    {
      case KeyEvent.KEYCODE_ENTER: OverlayAccessibilityService.commit_text("\n"); break;
      case KeyEvent.KEYCODE_DEL:
        if (ctrl)
          OverlayAccessibilityService.delete_word(true);
        else
          OverlayAccessibilityService.backspace(1);
        break;
      case KeyEvent.KEYCODE_FORWARD_DEL:
        OverlayAccessibilityService.forward_delete(1);
        break;
      case KeyEvent.KEYCODE_SPACE: OverlayAccessibilityService.commit_text(" "); break;
      case KeyEvent.KEYCODE_DPAD_LEFT: OverlayAccessibilityService.move_cursor(-1); break;
      case KeyEvent.KEYCODE_DPAD_RIGHT: OverlayAccessibilityService.move_cursor(1); break;
      default: break;
    }
  }

  void handle_editing(KeyValue.Editing ev, boolean ctrl)
  {
    switch (ev)
    {
      case BACKSPACE:
        if (ctrl)
          OverlayAccessibilityService.delete_word(true);
        else
          OverlayAccessibilityService.backspace(1);
        break;
      case SPACE_BAR: OverlayAccessibilityService.commit_text(" "); break;
      case DELETE_WORD: OverlayAccessibilityService.delete_word(true); break;
      case FORWARD_DELETE_WORD: OverlayAccessibilityService.delete_word(false); break;
      default: break;
    }
  }

  void handle_event(KeyValue.Event ev)
  {
    switch (ev)
    {
      case SWITCH_FORWARD: _ui.cycle_text_layout(1); break;
      case SWITCH_BACKWARD: _ui.cycle_text_layout(-1); break;
      case SWITCH_TEXT: _ui.show_text_layout(); break;
      case SWITCH_NUMERIC: _ui.show_special_layout(R.xml.numeric); break;
      case SWITCH_GREEKMATH: _ui.show_special_layout(R.xml.greekmath); break;
      case HIDE_SELF: _ui.hide_overlay(); break;
      default: break;
    }
  }

  /** [ctrl] turns cursor moves into word-wise moves. Shift is not needed
      here: latching shift turns [Cursor_left] into [Selection_cursor_left]
      through key modification. */
  void handle_slider(KeyValue.Slider s, int repeat, boolean ctrl)
  {
    switch (s)
    {
      case Cursor_left: move_horizontally(-repeat, ctrl, false); break;
      case Cursor_right: move_horizontally(repeat, ctrl, false); break;
      case Selection_cursor_left: move_horizontally(-repeat, ctrl, true); break;
      case Selection_cursor_right: move_horizontally(repeat, ctrl, true); break;
      default: break;
    }
  }

  void move_horizontally(int d, boolean by_word, boolean extend)
  {
    if (by_word)
      OverlayAccessibilityService.move_word(d > 0, extend);
    else if (extend)
      OverlayAccessibilityService.extend_selection(d);
    else
      OverlayAccessibilityService.move_cursor(d);
  }

  void evaluate_macro(KeyValue[] keys)
  {
    for (KeyValue k : keys)
      key_up(KeyModifier.modify_no_modmap(k, Pointers.Modifiers.EMPTY),
          Pointers.Modifiers.EMPTY);
  }
}
