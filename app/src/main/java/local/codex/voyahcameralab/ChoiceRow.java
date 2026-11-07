package local.codex.voyahcameralab;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A settings row in the style of system settings: the title over the current value. A tap opens
 * a separate list to pick from, so a finger scrolling the settings can at most open that list,
 * never change a value on its way.
 */
public final class ChoiceRow extends LinearLayout {
  private final String title;
  private final String[] values;
  private final TextView value;
  private int selected;

  ChoiceRow(Context context, String title, int id, String[] values, int selected) {
    super(context);
    this.title = title;
    this.values = values;
    this.selected = Math.max(0, Math.min(values.length - 1, selected));
    setId(id);
    setOrientation(VERTICAL);
    float density = getResources().getDisplayMetrics().density;
    int pad = Math.round(8 * density);
    setPadding(pad, Math.round(10 * density), pad, Math.round(10 * density));
    setMinimumHeight(Math.round(64 * density));
    TypedValue ripple = new TypedValue();
    if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) setBackgroundResource(ripple.resourceId);
    TextView name = new TextView(context);
    name.setText(title);
    name.setTextColor(Color.WHITE);
    name.setTextSize(15);
    addView(name);
    value = new TextView(context);
    value.setTextColor(0xFF8AB4F8);
    value.setTextSize(14);
    value.setText(values[this.selected]);
    addView(value);
    setContentDescription(title);
    setOnClickListener(v -> open());
  }

  private void open() {
    new AlertDialog.Builder(getContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title)
        .setSingleChoiceItems(values, selected, (dialog, which) -> { selected = which; value.setText(values[which]); dialog.dismiss(); })
        .setNegativeButton("Отмена", null).show();
  }

  public int getSelectedItemPosition() { return selected; }

  @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); setAlpha(enabled ? 1f : .45f); }
}
