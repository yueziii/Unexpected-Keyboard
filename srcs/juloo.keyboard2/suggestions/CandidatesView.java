package juloo.keyboard2.suggestions;

import android.content.Context;
import android.graphics.Color;
import android.os.Build.VERSION;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import juloo.keyboard2.Config;
import juloo.keyboard2.R;

/** The candidates bar.

    English mode shows up to [NUM_FIXED_CANDIDATES] fixed candidate slots plus
    an emoji slot, exactly as before.

    Pinyin (Chinese) mode shows a separate pinyin-preview label followed by a
    horizontally scrollable strip of candidate words. Because a single pinyin
    syllable can map to dozens of characters, the strip can hold many
    candidates (up to [Suggestions.PINYIN_MAX_COUNT]) and the user scrolls
    left/right to reveal more. This replaces the old 3-slot layout that only
    ever showed two real candidates. */
public class CandidatesView extends LinearLayout
{
  /** Fixed candidate slots for English mode. */
  static final int NUM_FIXED_CANDIDATES = 4;

  /** Fixed candidate slots (English mode). Entries can be [null] when there
      are fewer than [NUM_FIXED_CANDIDATES] suggestions.
      - Entries at indexes [0] to [2] are word suggestions.
      - Entry at index [3] is the emoji suggestion. */
  String[] _items = new String[NUM_FIXED_CANDIDATES];

  /** Text views showing the candidates in [_items] (English mode). */
  TextView[] _item_views = new TextView[NUM_FIXED_CANDIDATES];

  /** Message when no dictionary is installed. */
  View _status_no_dict = null;

  /** Pinyin mode views. */
  TextView _pinyin_preview_view = null;
  HorizontalScrollView _pinyin_scroll = null;
  LinearLayout _pinyin_candidates_container = null;
  /** Reusable TextViews for pinyin candidates. */
  List<TextView> _pinyin_views = new ArrayList<TextView>();

  /** The last [Suggestions] object passed to [set_candidates]. Used to look
      up pinyin candidates when a candidate view is tapped. */
  Suggestions _last_suggestions = null;

  /** Current text size used for both English and pinyin candidate views. */
  float _text_size = 0f;
  /** Current row height used for both English and pinyin candidate views. */
  float _row_height = 0f;

  public CandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate()
  {
    super.onFinishInflate();
    setup_item_view(0, R.id.candidates_middle);
    setup_item_view(1, R.id.candidates_right);
    setup_item_view(2, R.id.candidates_left);
    setup_item_view(3, R.id.candidates_emoji);
    _pinyin_preview_view = (TextView)findViewById(R.id.candidates_pinyin_preview);
    _pinyin_scroll = (HorizontalScrollView)findViewById(R.id.candidates_pinyin_scroll);
    _pinyin_candidates_container =
      (LinearLayout)findViewById(R.id.candidates_pinyin_container);
  }

  public void set_candidates(Suggestions s)
  {
    _last_suggestions = s;
    // Pinyin mode path: render preview + scrollable candidate list.
    if (s.pinyin_preview != null || !s.pinyin_candidates.isEmpty())
    {
      show_pinyin(s);
    }
    else
    {
      // English mode path (legacy).
      hide_pinyin();
      int s_count = s.count;
      for (int i = 0; i < Suggestions.MAX_COUNT; i++)
        _items[i] = (i < s_count) ? s.suggestions[i] : null;
      _items[3] = s.emoji_suggestion;
      // Hide the status message when showing candidates.
      if (s_count != 0 && _status_no_dict != null)
        _status_no_dict.setVisibility(View.GONE);
      for (int i = 0; i < _item_views.length; i++)
      {
        TextView v = _item_views[i];
        if (_items[i] != null)
        {
          v.setText(_items[i]);
          v.setVisibility(View.VISIBLE);
        }
        else
        {
          v.setVisibility(View.GONE);
        }
      }
    }
    update_visibility();
  }

  /** Render the pinyin preview label and the scrollable candidate strip. */
  void show_pinyin(Suggestions s)
  {
    // Hide English-mode fixed slots.
    for (int i = 0; i < _item_views.length; i++)
      _item_views[i].setVisibility(View.GONE);
    if (_status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);

    // Preview label.
    if (_pinyin_preview_view != null)
    {
      _pinyin_preview_view.setVisibility(View.VISIBLE);
      _pinyin_preview_view.setText(s.pinyin_preview != null
          ? s.pinyin_preview : "");
      apply_text_size(_pinyin_preview_view);
    }
    if (_pinyin_scroll != null)
      _pinyin_scroll.setVisibility(View.VISIBLE);
    if (_pinyin_candidates_container == null)
      return;

    int n = s.pinyin_candidates.size();
    // Ensure enough reusable TextViews.
    while (_pinyin_views.size() < n)
    {
      TextView v = create_pinyin_candidate_view();
      _pinyin_candidates_container.addView(v);
      _pinyin_views.add(v);
    }
    // Populate / show the first [n] views, hide the rest. The first
    // candidate (what space commits) is bold, like mainstream IMEs.
    for (int i = 0; i < _pinyin_views.size(); i++)
    {
      TextView v = _pinyin_views.get(i);
      if (i < n)
      {
        v.setText(s.pinyin_candidates.get(i));
        v.setTypeface(null,
            i == 0 ? android.graphics.Typeface.BOLD
              : android.graphics.Typeface.NORMAL);
        v.setVisibility(View.VISIBLE);
      }
      else
        v.setVisibility(View.GONE);
    }
    // Scroll back to the start so the most frequent candidates are visible.
    if (_pinyin_scroll != null)
      _pinyin_scroll.post(new Runnable()
        {
          @Override
          public void run()
          {
            _pinyin_scroll.fullScroll(HorizontalScrollView.FOCUS_LEFT);
          }
        });
  }

  /** Hide the pinyin views (switch back to English mode). */
  void hide_pinyin()
  {
    if (_pinyin_preview_view != null)
      _pinyin_preview_view.setVisibility(View.GONE);
    if (_pinyin_scroll != null)
      _pinyin_scroll.setVisibility(View.GONE);
    for (TextView v : _pinyin_views)
      v.setVisibility(View.GONE);
  }

  /** Whether the keyboard currently allows the candidates bar at all.
      Owned by [Keyboard2.refresh_candidates_view], which derives it from the
      suggestions setting, the editor type and the pinyin state. */
  boolean _show_allowed = false;

  /** Config-level gate on top of the content check: when disallowed the bar
      stays hidden even if content is set. */
  public void set_shown(boolean shown)
  {
    _show_allowed = shown;
    update_visibility();
  }

  /** Hide the bar entirely when it has nothing to display — an empty bar
      would otherwise sit between the app and the keyboard taking a row of
      height. The container is bottom-anchored, so when the first candidate
      brings the bar back the keyboard keys themselves never move; only the
      app content above shifts, once per composition. */
  void update_visibility()
  {
    boolean has_content =
      (_status_no_dict != null
        && _status_no_dict.getVisibility() == View.VISIBLE)
      || (_pinyin_preview_view != null
        && _pinyin_preview_view.getVisibility() == View.VISIBLE)
      || (_pinyin_scroll != null
        && _pinyin_scroll.getVisibility() == View.VISIBLE);
    for (int i = 0; !has_content && i < _item_views.length; i++)
      has_content = _item_views[i].getVisibility() == View.VISIBLE;
    int visibility = (_show_allowed && has_content)
      ? View.VISIBLE : View.GONE;
    if (visibility != getVisibility())
      setVisibility(visibility);
  }

  /** Create a TextView for a pinyin candidate, styled like the English
      candidate items. */
  TextView create_pinyin_candidate_view()
  {
    TextView v = new TextView(getContext());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.MATCH_PARENT);
    float gap = getResources().getDimension(R.dimen.candidates_gap);
    lp.setMarginStart((int)gap);
    lp.setMarginEnd(0);
    v.setLayoutParams(lp);
    int pad = (int)getResources().getDimension(
        R.dimen.candidates_suggestions_padding);
    v.setPadding(pad, pad, pad, pad);
    v.setGravity(android.view.Gravity.CENTER);
    v.setMaxLines(1);
    v.setBackgroundResource(R.drawable.suggestions_item_background);
    v.setTextColor(getColorAttribute(R.attr.colorLabel));
    apply_text_size(v);
    final int idx = _pinyin_views.size();
    v.setOnClickListener(new View.OnClickListener()
        {
          @Override
          public void onClick(View _v)
          {
            Suggestions sug = _last_suggestions;
            if (sug != null && idx < sug.pinyin_candidates.size())
            {
              Config cfg = Config.globalConfig();
              if (cfg != null && cfg.handler != null)
                cfg.handler.suggestion_entered(sug.pinyin_candidates.get(idx));
            }
          }
        });
    return v;
  }

  /** Resolve a theme color attribute to a color int. */
  int getColorAttribute(int attr)
  {
    TypedValue tv = new TypedValue();
    if (getContext().getTheme().resolveAttribute(attr, tv, true))
      return tv.data;
    return Color.WHITE;
  }

  void apply_text_size(TextView v)
  {
    float text_size = _text_size;
    if (text_size <= 0f)
      return;
    if (VERSION.SDK_INT < 26)
      v.setTextSize(TypedValue.COMPLEX_UNIT_PX, text_size);
    else
      v.setAutoSizeTextTypeUniformWithConfiguration(
          (int)(text_size / 2.), (int)text_size, 1, TypedValue.COMPLEX_UNIT_PX);
  }

  void clear_candidates()
  {
    for (int i = 0; i < _item_views.length; i++)
    {
      _items[i] = null;
      _item_views[i].setVisibility(View.GONE);
    }
    hide_pinyin();
  }

  public void refresh_config(Config config)
  {
    clear_candidates();
    // The status message indicates whether the dictionaries should be
    // installed. In pinyin mode, the built-in dictionary is used so no
    // download prompt is shown.
    if (config.current_dictionary == null && !config.pinyin_enabled)
      inflate_status_no_dict(config);
    else if (_status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);
    set_sizes(config);
    update_visibility();
  }

  /** Set the height of the suggestion row and the text size. Public because
      the floating keyboard resize path updates the sizes during the drag. */
  public void set_sizes(Config config)
  {
    // Make the candidates view shorter than a keyboard row to save space.
    float row_height = config.keyboard_rows_height_pixels
      * (1 - config.key_vertical_margin) * 0.72f;
    _row_height = row_height;
    ViewGroup.MarginLayoutParams p =
      (ViewGroup.MarginLayoutParams)getLayoutParams();
    p.height = (int)row_height;
    setLayoutParams(p);
    // Compute the text size the same way the keyboard keys do: take the
    // minimum of a height-based size and a width-based size. Without the
    // width constraint, the candidates text becomes unreadably small in
    // landscape and on large/unfolded screens where the screen height (and
    // thus the keyboard row height) is much smaller relative to the width.
    // The width term estimates a key height from the screen width assuming a
    // 10-column layout with a 3/2 key aspect ratio, matching Keyboard2View.
    float height_based = row_height;
    float width_based = (config.screenWidthPixels / 10f
        - config.key_horizontal_margin * config.screenWidthPixels / 10f)
      * 3f / 2f;
    float text_size = Math.min(height_based, width_based)
      * config.characterSize * config.labelTextSize;
    _text_size = text_size;
    for (int i = 0; i < NUM_FIXED_CANDIDATES; i++)
    {
      TextView v = _item_views[i];
      if (VERSION.SDK_INT < 26)
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, text_size);
      else
        v.setAutoSizeTextTypeUniformWithConfiguration(
            (int)(text_size / 2.), (int)text_size, 1, TypedValue.COMPLEX_UNIT_PX);
    }
    if (_pinyin_preview_view != null)
      apply_text_size(_pinyin_preview_view);
    for (TextView v : _pinyin_views)
      apply_text_size(v);
  }

  /** Show or hide a status view and inflate it if needed. */
  View inflate_and_show(View v, boolean show, int layout_id)
  {
    if (!show)
    {
      if (v != null)
        v.setVisibility(View.GONE);
    }
    else
    {
      if (v == null)
      {
        v = View.inflate(getContext(), layout_id, null);
        addView(v);
      }
      v.setVisibility(View.VISIBLE);
    }
    return v;
  }

  void inflate_status_no_dict(Config config)
  {
    if (_status_no_dict == null)
    {
      _status_no_dict = View.inflate(getContext(),
          R.layout.candidates_status_no_dict, null);
      addView(_status_no_dict);
    }
    Locale current_locale = (config.device_locales.default_ != null) ?
      Locale.forLanguageTag(config.device_locales.default_.lang_tag) : null;
    TextView tv = _status_no_dict.findViewById(android.R.id.text1);
    if (tv != null && current_locale != null)
      tv.setText(getResources().getString(
            R.string.candidates_status_click_to_install,
            current_locale.getDisplayName()));
    _status_no_dict.setVisibility(View.VISIBLE);
  }

  private void setup_item_view(final int item_index, int item_id)
  {
    TextView v = (TextView)findViewById(item_id);
    v.setOnClickListener(new View.OnClickListener()
        {
          @Override
          public void onClick(View _v)
          {
            String it = _items[item_index];
            if (it != null)
              Config.globalConfig().handler.suggestion_entered(it);
          }
        });
    v.setVisibility(View.GONE);
    _item_views[item_index] = v;
  }

  /** Whether the candidates view should be shown for a given editor. */
  public static boolean should_show(EditorInfo info)
  {
    int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
    int flags = info.inputType & InputType.TYPE_MASK_FLAGS;
    switch (info.inputType & InputType.TYPE_MASK_CLASS)
    {
      case InputType.TYPE_CLASS_TEXT:
        switch (variation)
        {
          case InputType.TYPE_TEXT_VARIATION_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            return false;
          default:
            /* Editor requested that we don't show suggestions. Enable
               suggestions anyway when the flags [NO_SUGGESTIONS] and
               [AUTO_CORRECT] are present at the same time. This happens with
               Google Keep. */
            if ((flags &
                  (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                   | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
                == InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
              return false;
            return true;
        }
      case InputType.TYPE_CLASS_NUMBER:
        // Beware of TYPE_NUMBER_VARIATION_PASSWORD
        return false;
      default: return false;
    }
  }
}
