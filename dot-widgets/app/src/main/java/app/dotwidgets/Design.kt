package app.dotwidgets

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.dotwidgets.Ui.dp

/**
 * The app's visual language (approved mockup): dark rounded cards, the dot font only for titles and
 * names, the system font for everything else, and red reserved for active states and the main action.
 */
object Design {
    const val CARD = 0xFF141414.toInt()
    const val SHEET = 0xFF181818.toInt()
    const val LINE = 0xFF262626.toInt()
    const val TRACK = 0xFF222222.toInt()
    const val SELECTED = 0xFF464646.toInt()
    const val GRAY = 0xFF9A9A9A.toInt()
    const val DIM = 0xFF5F5F5F.toInt()

    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dots(context: Context): Typeface = context.resources.getFont(R.font.doto_bold)

    fun rounded(color: Int, radiusDp: Float, context: Context, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            if (strokeDp > 0) setStroke(context.dp(strokeDp), strokeColor)
        }

    /** A tappable surface: [background] with a subtle white ripple on press. */
    fun pressable(view: View, background: Drawable, onClick: () -> Unit) {
        // A transparent background can't mask the ripple, so give it an opaque mask of the same bounds.
        val mask = if (background is ColorDrawable && background.color == Color.TRANSPARENT) ColorDrawable(Color.WHITE) else background
        view.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), background, mask)
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, color: Int = Ui.WHITE, medium: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = if (medium) sansMedium else sans
        }

    /** A name in the dot font, always on one line. */
    fun dotName(context: Context, value: String, sizeSp: Float): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(Ui.WHITE)
        typeface = dots(context)
        isAllCaps = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun chevron(context: Context): TextView = text(context, "›", 26f, DIM).apply { setPadding(context.dp(8), 0, 0, 0) }

    /** Small grey caps label above a group of rows. */
    fun groupLabel(context: Context, value: String): TextView = text(context, value.uppercase(), 12f, GRAY, medium = true).apply {
        letterSpacing = 0.06f
        setPadding(context.dp(16), context.dp(20), 0, context.dp(8))
    }

    /** A rounded card holding rows separated by thin lines. */
    fun group(context: Context, rows: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 14f, context)
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                    marginStart = context.dp(16)
                    marginEnd = context.dp(16)
                })
            }
            addView(row)
        }
    }

    /** Settings row: title, current value underneath, chevron on the right. */
    fun row(context: Context, title: String, value: String, valueColor: Int = GRAY, onClick: () -> Unit): View {
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, 16f, medium = true))
            addView(text(context, value, 13f, valueColor).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(12), context.dp(14))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron(context))
            pressable(this, ColorDrawable(Color.TRANSPARENT), onClick)
        }
    }

    /** Main action: red pill. */
    fun primaryButton(context: Context, label: String, onClick: () -> Unit): TextView =
        text(context, label, 16f, Ui.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(14))
            pressable(this, rounded(Ui.RED, 28f, context), onClick)
        }

    /** Secondary action: grey outline pill. */
    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): TextView =
        text(context, label, 15f, Ui.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
            pressable(this, rounded(Color.TRANSPARENT, 24f, context, 0xFF4A4A4A.toInt(), 1), onClick)
        }

    /** Quiet text-only action. */
    fun textButton(context: Context, label: String, onClick: () -> Unit): TextView =
        text(context, label, 15f, GRAY, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            pressable(this, ColorDrawable(Color.TRANSPARENT), onClick)
        }

    /** Pill-shaped single-choice control that never overflows: options share the width equally. */
    fun segmented(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(TRACK, 22f, context)
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        }
        val items = labels.map { label -> text(context, label, 13f, GRAY).apply { gravity = Gravity.CENTER } }
        fun paint(index: Int) {
            items.forEachIndexed { i, item ->
                item.setTextColor(if (i == index) Ui.WHITE else GRAY)
                item.typeface = if (i == index) sansMedium else sans
                item.background = if (i == index) rounded(SELECTED, 19f, context) else null
            }
        }
        items.forEachIndexed { i, item ->
            item.setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
            item.setOnClickListener {
                paint(i)
                onSelect(i)
            }
            track.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return track
    }

    /** A panel that slides up from the bottom, with a grab handle and a title. */
    fun sheet(activity: Activity, title: String, subtitle: String?, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(10), activity.dp(24), activity.dp(24))
            addView(View(activity).apply { background = rounded(0xFF464646.toInt(), 2f, activity) },
                LinearLayout.LayoutParams(activity.dp(40), activity.dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = activity.dp(18)
                })
            addView(text(activity, title, 22f, medium = true))
            if (subtitle != null) addView(text(activity, subtitle, 14f, GRAY).apply { setPadding(0, activity.dp(4), 0, 0) })
        }
        build(body, dialog)
        val scroll = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                setColor(SHEET)
                val r = 24 * activity.resources.displayMetrics.density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(body)
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        dialog.show()
        return dialog
    }
}
