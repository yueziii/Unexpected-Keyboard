package juloo.keyboard2;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import juloo.keyboard2.suggestions.Suggestions;
import juloo.keyboard2.pinyin.PinyinInput;
import juloo.keyboard2.pinyin.PinyinSchemes;
import juloo.keyboard2.pinyin.PinyinUserDict;

public final class KeyEventHandler
  implements Config.IKeyEventHandler,
             ClipboardHistoryService.ClipboardPasteCallback,
             CurrentlyTypedWord.Callback
{
  IReceiver _recv;
  Autocapitalisation _autocap;
  Suggestions _suggestions;
  CurrentlyTypedWord _typedword;
  /** Pinyin input state machine. Non-null when pinyin mode is active. */
  PinyinInput _pinyin = null;
  /** State of the system modifiers. It is updated whether a modifier is down
      or up and a corresponding key event is sent. */
  Pointers.Modifiers _mods;
  /** Consistent with [_mods]. This is a mutable state rather than computed
      from [_mods] to ensure that the meta state is correct while up and down
      events are sent for the modifier keys. */
  int _meta_state = 0;
  /** Whether to force sending arrow keys to move the cursor when
      [setSelection] could be used instead. */
  boolean _move_cursor_force_fallback = false;
  /** Whether the space bar automatically enters the best suggestion. */
  boolean _space_bar_auto_complete = false;
  /** Compatibility mode for remote desktop apps. When true, key events that
      involve Ctrl/Alt/Meta are sent like physical keyboard input (no
      FLAG_SOFT_KEYBOARD / FLAG_KEEP_TOUCH_MODE, real keyboard device id and
      scancode, see [HardwareKeyMap]) so apps such as UU Remote forward them.
      Other keys keep the soft-keyboard channel: they are already forwarded
      through the editor's text handling, and hardware-like synthetic events
      would be filtered out entirely. */
  boolean _keyevent_compat = false;
  /** Modifier keycodes whose ACTION_DOWN was sent through the hardware-like
      channel in compat mode. Their ACTION_UP must use the same channel even
      if the other modifiers of the combo were released in between. */
  final Set<Integer> _hw_modifier_downs = new HashSet<Integer>();
  /** Time of the ACTION_DOWN of each hardware-like key event, by keycode, so
      that the matching ACTION_UP reports a realistic hold duration. */
  final Map<Integer, Long> _hw_key_down_times = new HashMap<Integer, Long>();
  /** Remember the action that was handled. This is used by autocorrect. */
  LastAction _last_action = null;
  LastAction _next_last_action = null;

  public KeyEventHandler(IReceiver recv, Suggestions sg)
  {
    _recv = recv;
    Handler handler = recv.getHandler();
    _autocap = new Autocapitalisation(handler,
        this.new Autocapitalisation_callback());
    _mods = Pointers.Modifiers.EMPTY;
    _suggestions = sg;
    _typedword = new CurrentlyTypedWord(handler, this);
  }

  /** Editing just started. */
  public void started(Config conf)
  {
    InputConnection ic = _recv.getCurrentInputConnection();
    _autocap.started(conf, ic);
    _typedword.started(conf, ic);
    _suggestions.started();
    _move_cursor_force_fallback =
      conf.editor_config.should_move_cursor_force_fallback;
    _space_bar_auto_complete = conf.space_bar_auto_complete;
    _keyevent_compat = conf.keyevent_compat;
    _last_action = null;
    // Initialize pinyin mode if enabled in the config
    if (conf.pinyin_enabled)
    {
      if (_pinyin == null)
        _pinyin = new PinyinInput();
      _pinyin.started(pinyin_scheme(conf), _suggestions);
    }
    else
      _pinyin = null;
  }

  /** The pinyin scheme from the config: null for full pinyin (the engine
      segments the letters itself), the scheme tables for double pinyin. */
  static PinyinSchemes.Scheme pinyin_scheme(Config conf)
  {
    return conf.pinyin_scheme == 0 ? null : PinyinSchemes.get(conf.pinyin_scheme);
  }

  /** Called when the language mode is switched (pinyin on/off toggle).
      Flushes any pending pinyin state. */
  public void lang_switched(Config conf)
  {
    if (_pinyin != null)
    {
      flush_pinyin();
      // If pinyin was just disabled, clear the reference
      if (!conf.pinyin_enabled)
        _pinyin = null;
    }
    else if (conf.pinyin_enabled)
    {
      // Pinyin was just enabled — initialize it
      _pinyin = new PinyinInput();
      _pinyin.started(pinyin_scheme(conf), _suggestions);
    }
  }

  /** Selection has been updated. */
  public void selection_updated(int oldSelStart, int newSelStart, int newSelEnd)
  {
    _autocap.selection_updated(oldSelStart, newSelStart);
    _typedword.selection_updated(oldSelStart, newSelStart, newSelEnd);
  }

  /** A key is being pressed. There will not necessarily be a corresponding
      [key_up] event. */
  @Override
  public void key_down(KeyValue key, boolean isSwipe)
  {
    if (key == null)
      return;
    // Stop auto capitalisation when pressing some keys
    switch (key.getKind())
    {
      case Modifier:
        switch (key.getModifier())
        {
          case CTRL:
          case ALT:
          case META:
            _autocap.stop();
            break;
        }
        break;
      case Compose_pending:
        _autocap.stop();
        break;
      case Slider:
        // Don't wait for the next key_up and move the cursor right away. This
        // is called after the trigger distance have been travelled.
        handle_slider(key.getSlider(), key.getSliderRepeat(), true);
        break;
      default: break;
    }
  }

  /** A key has been released. */
  @Override
  public void key_up(KeyValue key, Pointers.Modifiers mods)
  {
    if (key == null)
      return;
    _next_last_action = LastAction.OTHER;
    Pointers.Modifiers old_mods = _mods;
    update_meta_state(mods);
    switch (key.getKind())
    {
      case Char:
        // In pinyin mode, lowercase letters build the pinyin buffer. The
        // ';' key is the "ing" final in Microsoft double pinyin. Punctuation
        // and digits commit the current composition first, like mainstream
        // Chinese IMEs.
        char ch = key.getChar();
        char lower = Character.toLowerCase(ch);
        if (_pinyin != null && lower >= 'a' && lower <= 'z' && ch == lower)
        {
          _pinyin.key_typed(lower);
          _pinyin.query_candidates();
          update_pinyin_composing();
        }
        else if (_pinyin != null && lower >= 'a' && lower <= 'z')
        {
          // Uppercase letter: commit the conversion, then type the letter
          // itself (e.g. shift for an acronym).
          accept_first_candidate();
          send_text(String.valueOf(ch));
        }
        else if (_pinyin != null && ch == ';'
            && _pinyin.is_active() && _pinyin.pending_final())
        {
          // Double pinyin "ing" final.
          _pinyin.key_typed(';');
          _pinyin.query_candidates();
          update_pinyin_composing();
        }
        else if (_pinyin != null && ch >= '1' && ch <= '9'
            && _pinyin.is_active())
        {
          // Digits select the matching candidate while composing; otherwise
          // they are typed normally (phone numbers...).
          List<PinyinInput.Candidate> cands = _pinyin.get_candidates();
          int idx = ch - '1';
          if (idx < cands.size())
          {
            suggestion_entered(cands.get(idx).text);
            break;
          }
          commit_pinyin_raw();
          send_text(String.valueOf(ch));
        }
        else if (_pinyin != null && _pinyin.is_active())
        {
          // Punctuation while composing: commit the best candidate, then
          // send the Chinese form of the punctuation mark.
          accept_first_candidate();
          send_text(chinese_punctuation(ch));
        }
        else if (_pinyin != null)
        {
          // Chinese mode, idle: punctuation produces full-width marks.
          send_text(chinese_punctuation(ch));
        }
        else
          send_text(String.valueOf(ch));
        break;
      case String:
      {
        String str = key.getString();
        // In double pinyin mode, ";" is the "ing" final; intercept it when a
        // final is pending, same as the Char branch above.
        if (_pinyin != null && str.equals(";")
            && _pinyin.is_active() && _pinyin.pending_final())
        {
          _pinyin.key_typed(';');
          _pinyin.query_candidates();
          update_pinyin_composing();
        }
        else if (_pinyin != null && str.length() == 1)
        {
          char sch = str.charAt(0);
          if (_pinyin.is_active())
          {
            accept_first_candidate();
            send_text(chinese_punctuation(sch));
          }
          else
            send_text(chinese_punctuation(sch));
        }
        else
        {
          flush_pinyin();
          send_text(str);
        }
        break;
      }
      case Event: flush_pinyin(); _recv.handle_event_key(key.getEvent()); break;
      case Keyevent:
        if (_pinyin != null && _pinyin.is_active()
            && key.getKeyevent() == KeyEvent.KEYCODE_ENTER)
        {
          // Enter while composing commits the raw pinyin letters (like
          // mainstream IMEs); a second Enter adds the newline.
          commit_pinyin_raw();
        }
        else
        {
          flush_pinyin();
          send_key_down_up(key.getKeyevent());
        }
        break;
      case Modifier: break;
      case Editing:
        // In pinyin mode, BACKSPACE and DELETE_WORD should edit the pinyin
        // buffer directly rather than flushing (committing) it first.
        // Flushing before backspace was the bug: it committed the entire
        // pinyin string to the editor, then deleted only one character,
        // leaving the rest "上屏" (committed on screen).
        if (_pinyin != null && _pinyin.is_active())
        {
          KeyValue.Editing ed = key.getEditing();
          if (ed == KeyValue.Editing.BACKSPACE
              || ed == KeyValue.Editing.DELETE_WORD)
          {
            handle_editing_key(ed);
            break;
          }
        }
        flush_pinyin();
        handle_editing_key(key.getEditing());
        break;
      case Compose_pending: _recv.set_compose_pending(true); break;
      case Slider: handle_slider(key.getSlider(), key.getSliderRepeat(), false); break;
      case Macro: evaluate_macro(key.getMacro()); break;
      case Stateful: handle_stateful(key.getStateful()); break;
    }
    update_meta_state(old_mods);
    _last_action = _next_last_action;
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
    update_meta_state(mods);
  }

  @Override
  public void suggestion_entered(String text)
  {
    if (_pinyin != null && _pinyin.is_active())
    {
      InputConnection conn = _recv.getCurrentInputConnection();
      // Accept the candidate: it consumes its keystrokes, the rest of the
      // buffer stays for continued input (sentence input).
      int consumed = _pinyin.accept_candidate(text);
      if (consumed < 0)
      {
        // Not a current candidate (stale view?): commit raw pinyin.
        commit_pinyin_raw();
        return;
      }
      if (conn != null)
      {
        conn.beginBatchEdit();
        // Committing the candidate replaces the current composing text
        conn.commitText(text, 1);
        conn.endBatchEdit();
        // Learn the committed word so it ranks first in future queries
        PinyinUserDict.record(text);
      }
      boolean has_remaining = _pinyin.is_active();
      if (has_remaining)
      {
        _pinyin.query_candidates();
        update_pinyin_composing();
      }
      else
      {
        _suggestions.clear();
        _recv.set_suggestions(_suggestions);
      }
      _next_last_action = LastAction.OTHER;
      return;
    }
    String old = _typedword.get();
    int cur_rel = _typedword.cursor_relative();
    replace_surrounding_text(old.length() + cur_rel, -cur_rel, text);
    last_replaced_word = old;
    last_replacement_word_len = text.length();
    _next_last_action = LastAction.SUGGESTION_ENTERED;
  }

  /** Commit the first (best) candidate, if any. Used before punctuation and
      uppercase letters, matching mainstream IME behavior. */
  void accept_first_candidate()
  {
    List<PinyinInput.Candidate> cands = _pinyin.get_candidates();
    if (!cands.isEmpty())
      suggestion_entered(cands.get(0).text);
    else
      flush_pinyin();
  }

  /** Commit the raw pinyin letters (what the user actually typed) and clear
      the buffer. Used by the Enter key, like mainstream IMEs. */
  void commit_pinyin_raw()
  {
    if (_pinyin == null || !_pinyin.is_active())
      return;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn != null)
      conn.commitText(_pinyin.get_buffer(), 1);
    _pinyin.reset();
    _suggestions.clear();
    _recv.set_suggestions(_suggestions);
  }

  /** The full-width form of a punctuation character in Chinese mode.
      Characters without a Chinese form are returned unchanged. */
  static String chinese_punctuation(char c)
  {
    switch (c)
    {
      case ',': return "，";
      case '.': return "。";
      case '?': return "？";
      case '!': return "！";
      case ':': return "：";
      case ';': return "；";
      case '(': return "（";
      case ')': return "）";
      case '<': return "《";
      case '>': return "》";
      case '[': return "【";
      case ']': return "】";
      case '~': return "～";
      case '_': return "——";
      case '\'': return "‘";
      case '"': return "“";
      default: return String.valueOf(c);
    }
  }

  @Override
  public void paste_from_clipboard_pane(String content)
  {
    send_text(content);
  }

  @Override
  public void currently_typed_word(String word)
  {
    _suggestions.currently_typed_word(word);
  }

  public void ime_subtype_changed()
  {
    // Refresh the suggestions immediately after dictionary changed.
    _suggestions.currently_typed_word(_typedword.get());
  }

  /** Update [_mods] to be consistent with the [mods], sending key events if
      needed. */
  void update_meta_state(Pointers.Modifiers mods)
  {
    // Released modifiers
    Iterator<KeyValue> it = _mods.diff(mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), false);
    // Activated modifiers
    it = mods.diff(_mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), true);
    _mods = mods;
  }

  // private void handleDelKey(int before, int after)
  // {
  //  CharSequence selection = getCurrentInputConnection().getSelectedText(0);

  //  if (selection != null && selection.length() > 0)
  //  getCurrentInputConnection().commitText("", 1);
  //  else
  //  getCurrentInputConnection().deleteSurroundingText(before, after);
  // }

  void sendMetaKey(int eventCode, int meta_flags, boolean down)
  {
    if (down)
    {
      _meta_state = _meta_state | meta_flags;
      send_keyevent(KeyEvent.ACTION_DOWN, eventCode, _meta_state);
    }
    else
    {
      send_keyevent(KeyEvent.ACTION_UP, eventCode, _meta_state);
      _meta_state = _meta_state & ~meta_flags;
    }
  }

  void sendMetaKeyForModifier(KeyValue kv, boolean down)
  {
    switch (kv.getKind())
    {
      case Modifier:
        switch (kv.getModifier())
        {
          case CTRL:
            sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON, down);
            break;
          case ALT:
            sendMetaKey(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON, down);
            break;
          case SHIFT:
            sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON, down);
            break;
          case META:
            sendMetaKey(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_ON, down);
            break;
          default:
            break;
        }
        break;
    }
  }

  void send_key_down_up(int keyCode)
  {
    send_key_down_up(keyCode, _meta_state);
  }

  /** Ignores currently pressed system modifiers. */
  void send_key_down_up(int keyCode, int metaState)
  {
    send_keyevent(KeyEvent.ACTION_DOWN, keyCode, metaState);
    send_keyevent(KeyEvent.ACTION_UP, keyCode, metaState);
  }

  void send_keyevent(int eventAction, int eventCode, int metaState)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    // In compat mode, events that involve Ctrl/Alt/Meta are sent like they
    // come from a physical keyboard: no soft-keyboard flags, the device id
    // of a real keyboard and a non-zero scancode. Some remote desktop apps
    // (e.g. UU Remote) discard such synthetic events otherwise. Plain keys
    // such as Enter and Backspace keep the soft-keyboard channel, which is
    // how these apps already forward them (through their editor's text
    // handling); hardware-like synthetic events would be filtered out.
    boolean hardware_like = _keyevent_compat
      && HardwareKeyMap.needs_hardware_channel(eventCode, metaState);
    if (HardwareKeyMap.is_modifier_keycode(eventCode))
    {
      if (eventAction == KeyEvent.ACTION_DOWN)
      {
        if (hardware_like)
          _hw_modifier_downs.add(eventCode);
        else
          _hw_modifier_downs.remove(eventCode);
      }
      else if (_hw_modifier_downs.contains(eventCode))
      {
        hardware_like = true;
        _hw_modifier_downs.remove(eventCode);
      }
    }
    KeyEvent event;
    if (hardware_like)
    {
      // Mimic a physical keyboard event as closely as possible: real
      // timestamps and the keyboard source. Synthetic-looking fields
      // (downtime 1, unknown source) are another thing remote apps can
      // filter on.
      long now = SystemClock.uptimeMillis();
      long down_time;
      if (eventAction == KeyEvent.ACTION_DOWN)
      {
        down_time = now;
        _hw_key_down_times.put(eventCode, now);
      }
      else
      {
        Long t = _hw_key_down_times.remove(eventCode);
        down_time = t == null ? now : t;
      }
      event = new KeyEvent(down_time, now, eventAction, eventCode, 0,
          metaState, HardwareKeyMap.keyboard_device_id(),
          HardwareKeyMap.scancode_for(eventCode), 0);
      event.setSource(InputDevice.SOURCE_KEYBOARD);
    }
    else
    {
      event = new KeyEvent(1, 1, eventAction, eventCode, 0, metaState,
          KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
          KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    }
    conn.sendKeyEvent(event);
    if (eventAction == KeyEvent.ACTION_UP)
    {
      _autocap.event_sent(eventCode, metaState);
      _typedword.event_sent(eventCode, metaState);
    }
  }

  void send_text(String text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    _autocap.typed(text);
    _typedword.typed(text);
    conn.commitText(text, 1);
  }

  void replace_surrounding_text(int remove_before, int remove_after,
      String new_text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.beginBatchEdit();
    conn.deleteSurroundingText(remove_before, remove_after);
    conn.commitText(new_text, 1);
    _typedword.remove_surrounding_text(remove_before, remove_after);
    _typedword.typed(new_text);
    conn.endBatchEdit();
  }

  /** See {!InputConnection.performContextMenuAction}. */
  void send_context_menu_action(int id)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(id);
  }

  @SuppressLint("InlinedApi")
  void handle_editing_key(KeyValue.Editing ev)
  {
    switch (ev)
    {
      case COPY: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.copy); break;
      case PASTE: send_context_menu_action(android.R.id.paste); break;
      case CUT: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.cut); break;
      case SELECT_ALL: send_context_menu_action(android.R.id.selectAll); break;
      case SHARE: send_context_menu_action(android.R.id.shareText); break;
      case PASTE_PLAIN: send_context_menu_action(android.R.id.pasteAsPlainText); break;
      case UNDO: send_context_menu_action(android.R.id.undo); break;
      case REDO: send_context_menu_action(android.R.id.redo); break;
      case REPLACE: send_context_menu_action(android.R.id.replaceText); break;
      case ASSIST: send_context_menu_action(android.R.id.textAssist); break;
      case AUTOFILL: send_context_menu_action(android.R.id.autofill); break;
      case DELETE_WORD:
        if (_pinyin != null && _pinyin.is_active())
        {
          // Clear the entire pinyin buffer on word-delete
          _pinyin.reset();
          finish_pinyin_composing();
          _suggestions.clear();
          _recv.set_suggestions(_suggestions);
        }
        else
          send_key_down_up(KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        break;
      case FORWARD_DELETE_WORD:
        if (_pinyin != null && _pinyin.is_active())
        {
          // Clear the entire pinyin buffer on forward word-delete
          _pinyin.reset();
          finish_pinyin_composing();
          _suggestions.clear();
          _recv.set_suggestions(_suggestions);
        }
        else
          send_key_down_up(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        break;
      case SELECTION_CANCEL: cancel_selection(); break;
      case SPACE_BAR: handle_space_bar(); break;
      case BACKSPACE: handle_backspace(); break;
    }
  }

  static ExtractedTextRequest _move_cursor_req = null;

  /** Query the cursor position. The extracted text is empty. Returns [null] if
      the editor doesn't support this operation. */
  ExtractedText get_cursor_pos(InputConnection conn)
  {
    if (_move_cursor_req == null)
    {
      _move_cursor_req = new ExtractedTextRequest();
      _move_cursor_req.hintMaxChars = 0;
    }
    return conn.getExtractedText(_move_cursor_req, 0);
  }

  /** [r] might be negative, in which case the direction is reversed. */
  void handle_slider(KeyValue.Slider s, int r, boolean key_down)
  {
    switch (s)
    {
      case Cursor_left: move_cursor(-r); break;
      case Cursor_right: move_cursor(r); break;
      case Cursor_up: move_cursor_vertical(-r); break;
      case Cursor_down: move_cursor_vertical(r); break;
      case Selection_cursor_left: move_cursor_sel(r, true, key_down); break;
      case Selection_cursor_right: move_cursor_sel(r, false, key_down); break;
    }
  }

  void handle_stateful(KeyValue.Stateful st)
  {
    switch (st)
    {
      case Complete_first:
      case Complete_second:
      case Complete_third:
      case Complete_emoji:
        suggestion_entered(st.toString());
        break;
      case Switch_lang:
        _recv.handle_event_key(KeyValue.Event.SWITCH_LANG);
        break;
    }
  }

  /** Move the cursor right or left, if possible without sending key events.
      Unlike arrow keys, the selection is not removed even if shift is not on.
      Falls back to sending arrow keys events if the editor do not support
      moving the cursor or a modifier other than shift is pressed. */
  void move_cursor(int d)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Continue expanding the selection even if shift is not pressed
      if (sel_end != sel_start)
      {
        sel_end += d;
        if (sel_end == sel_start) // Avoid making the selection empty
          sel_end += d;
      }
      else
      {
        sel_end += d;
        // Leave 'sel_start' where it is if shift is pressed
        if ((_meta_state & KeyEvent.META_SHIFT_ON) == 0)
          sel_start = sel_end;
      }
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Move one of the two side of a selection. If [sel_left] is true, the left
      position is moved, otherwise the right position is moved. */
  void move_cursor_sel(int d, boolean sel_left, boolean key_down)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Reorder the selection when the slider has just been pressed. The
      // selection might have been reversed if one end crossed the other end
      // with a previous slider.
      if (key_down && sel_start > sel_end)
      {
        sel_start = et.selectionEnd;
        sel_end = et.selectionStart;
      }
      do
      {
        if (sel_left)
          sel_start += d;
        else
          sel_end += d;
        // Move the cursor twice if moving it once would make the selection
        // empty and stop selection mode.
      } while (sel_start == sel_end);
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Returns whether the selection can be set using [conn.setSelection()].
      This can happen on Termux or when system modifiers are activated for
      example. */
  boolean can_set_selection(InputConnection conn)
  {
    final int system_mods =
      KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;
    return !_move_cursor_force_fallback && (_meta_state & system_mods) == 0;
  }

  void move_cursor_fallback(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_LEFT, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_RIGHT, d);
  }

  /** Move the cursor up and down. This sends UP and DOWN key events that might
      make the focus exit the text box. */
  void move_cursor_vertical(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_UP, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_DOWN, d);
  }

  void evaluate_macro(KeyValue[] keys)
  {
    if (keys.length == 0)
      return;
    // Ignore modifiers that are activated at the time the macro is evaluated
    mods_changed(Pointers.Modifiers.EMPTY);
    evaluate_macro_loop(keys, 0, Pointers.Modifiers.EMPTY, _autocap.pause());
  }

  /** Evaluate the macro asynchronously to make sure event are processed in the
      right order. */
  void evaluate_macro_loop(final KeyValue[] keys, int i, Pointers.Modifiers mods, final boolean autocap_paused)
  {
    boolean should_delay = false;
    KeyValue kv = KeyModifier.modify_no_modmap(keys[i], mods);
    if (kv != null)
    {
      if (kv.hasFlagsAny(KeyValue.FLAG_LATCH))
      {
        // Non-special latchable keys clear latched modifiers
        if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
          mods = Pointers.Modifiers.EMPTY;
        mods = mods.with_extra_mod(kv);
      }
      else
      {
        key_down(kv, false);
        key_up(kv, mods);
        mods = Pointers.Modifiers.EMPTY;
      }
      should_delay = wait_after_macro_key(kv);
    }
    i++;
    if (i >= keys.length) // Stop looping
    {
      _autocap.unpause(autocap_paused);
    }
    else if (should_delay)
    {
      // Add a delay before sending the next key to avoid race conditions
      // causing keys to be handled in the wrong order. Notably, KeyEvent keys
      // handling is scheduled differently than the other edit functions.
      final int i_ = i;
      final Pointers.Modifiers mods_ = mods;
      _recv.getHandler().postDelayed(new Runnable() {
        public void run()
        {
          evaluate_macro_loop(keys, i_, mods_, autocap_paused);
        }
      }, 1000/30);
    }
    else
      evaluate_macro_loop(keys, i, mods, autocap_paused);
  }

  boolean wait_after_macro_key(KeyValue kv)
  {
    switch (kv.getKind())
    {
      case Keyevent:
      case Editing:
      case Event:
        return true;
      case Slider:
        return _move_cursor_force_fallback;
      default:
        return false;
    }
  }

  /** Repeat calls to [send_key_down_up]. */
  void send_key_down_up_repeat(int event_code, int repeat)
  {
    while (repeat-- > 0)
      send_key_down_up(event_code);
  }

  void cancel_selection()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et == null) return;
    final int curs = et.selectionStart;
    // Notify the receiver as Android's [onUpdateSelection] is not triggered.
    if (conn.setSelection(curs, curs))
      _recv.selection_state_changed(false);
  }

  /** The word that was replaced by a suggestion when the last action was to
      enter a suggestion (with the space bar or the candidates view) or [null]
      otherwise. */
  String last_replaced_word = null;
  /** Length of the text before the cursor that should be replaced by
      backspace. */
  int last_replacement_word_len = 0;

  /** Implement autocorrect when enabled in the settings. */
  /** Flush pending pinyin buffer by committing the raw pinyin string to the
      editor. Does nothing if pinyin is not active. */
  void flush_pinyin()
  {
    if (_pinyin == null || !_pinyin.is_active())
      return;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn != null)
    {
      // The composing text already shows the pinyin preview (e.g. "ni hao").
      // Finishing composing commits it as ordinary text — no duplicate
      // commitText needed.
      conn.finishComposingText();
    }
    _pinyin.reset();
    _suggestions.clear();
    _recv.set_suggestions(_suggestions);
  }

  /** Update the editor's composing text to show the current best
      conversion (e.g. 今天天气很好), falling back to the pinyin preview when
      nothing converts. This is what mainstream IMEs underline in the
      editor. The raw pinyin stays visible in the candidates bar. */
  void update_pinyin_composing()
  {
    if (_pinyin == null || !_pinyin.is_active())
      return;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn != null)
      conn.setComposingText(_pinyin.composing_text(), 1);
  }

  /** Clear the pinyin composing text from the editor without leaving any
      committed text behind. Used when the buffer is emptied by backspace. */
  void finish_pinyin_composing()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn != null)
    {
      // Committing empty text replaces the composing preview with nothing,
      // effectively deleting it. commitText also finishes composing.
      conn.commitText("", 1);
    }
  }

  void handle_space_bar()
  {
    if (_pinyin != null && _pinyin.is_active())
    {
      // In pinyin mode, space accepts the first pinyin candidate. If no
      // candidate is available, commit the conversion as-is.
      accept_first_candidate();
      return;
    }
    if (_space_bar_auto_complete && _suggestions.count > 0
        && !_typedword.is_selection_not_empty()
        && _typedword.cursor_relative() == 0)
      suggestion_entered(_suggestions.suggestions[0] + " ");
    else
      send_text(" ");
  }

  /** Undo the last autocorrect. */
  void handle_backspace()
  {
    if (_pinyin != null && _pinyin.is_active())
    {
      // Backspace in pinyin mode: remove last keystroke from buffer.
      // Only the composing text is updated — nothing is committed to the
      // editor, matching mainstream IME behavior.
      _pinyin.backspace();
      if (_pinyin.is_active())
      {
        _pinyin.query_candidates();
        update_pinyin_composing();
      }
      else
      {
        // Buffer emptied: finish composing (clear the preview in editor)
        finish_pinyin_composing();
        _suggestions.clear();
        _recv.set_suggestions(_suggestions);
      }
      return;
    }
    if (_last_action == LastAction.SUGGESTION_ENTERED
        && last_replaced_word != null)
    {
      replace_surrounding_text(last_replacement_word_len, 0, last_replaced_word);
      last_replaced_word = null;
    }
    else
    {
      send_key_down_up(KeyEvent.KEYCODE_DEL);
    }
  }

  public static interface IReceiver extends Suggestions.Callback
  {
    public void handle_event_key(KeyValue.Event ev);
    public void set_shift_state(boolean state, boolean lock);
    public void set_compose_pending(boolean pending);
    public void selection_state_changed(boolean selection_is_ongoing);
    public InputConnection getCurrentInputConnection();
    public Handler getHandler();
  }

  class Autocapitalisation_callback implements Autocapitalisation.Callback
  {
    @Override
    public void update_shift_state(boolean should_enable, boolean should_disable)
    {
      if (should_enable)
        _recv.set_shift_state(true, false);
      else if (should_disable)
        _recv.set_shift_state(false, false);
    }
  }

  public static enum LastAction
  {
    SUGGESTION_ENTERED,
    OTHER
  }
}
