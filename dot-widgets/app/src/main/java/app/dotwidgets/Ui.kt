package app.dotwidgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Small programmatic view builders shared by the two screens. */
object Ui {
    const val BLACK = 0xFF000000.toInt()
    const val SURFACE = 0xFF161616.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF8A8A8A.toInt()
    const val RED = 0xFFD71921.toInt()

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun column(context: Context, padding: Int = 20): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(padding)
        setPadding(p, p, p, p)
    }

    fun title(context: Context, text: String, size: Float = 36f): TextView = TextView(context).apply {
        this.text = text
        typeface = context.resources.getFont(R.font.doto_bold)
        textSize = size
        setTextColor(WHITE)
        isAllCaps = true
    }

    fun section(context: Context, text: Int): TextView = TextView(context).apply {
        setText(text)
        typeface = context.resources.getFont(R.font.doto_bold)
        textSize = 15f
        letterSpacing = 0.15f
        setTextColor(RED)
        isAllCaps = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(24)
            bottomMargin = context.dp(8)
        }
    }

    fun body(context: Context, text: CharSequence, color: Int = MUTED): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(color)
    }

    /** Horizontally scrolling single-choice row. */
    fun choices(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(RED)
                setPadding(0, 0, context.dp(14), 0)
            }
            group.addView(button)
            if (index == selected) group.check(button.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            onSelect(g.indexOfChild(g.findViewById(checkedId)))
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        }
    }

    /** [min], [max] and [value] are in steps of [step]; [onChange] receives the real value. */
    fun slider(context: Context, label: Int, min: Int, max: Int, value: Int, unit: String, step: Int = 1, onChange: (Int) -> Unit): View {
        val caption = body(context, "", WHITE)
        fun show(v: Int) {
            caption.text = context.getString(label) + ": " + "%,d".format(v * step) + unit
        }
        show(value)
        val bar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(RED)
            thumbTintList = android.content.res.ColorStateList.valueOf(RED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    show(progress)
                    if (fromUser) onChange(progress * step)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(6), 0, context.dp(6))
            addView(caption)
            addView(bar)
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(context: Context, label: Int, checked: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(context).apply {
            setText(label)
            textSize = 14f
            setTextColor(WHITE)
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(RED, MUTED),
            )
            setPadding(0, context.dp(8), 0, context.dp(8))
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean -> onChange(isChecked) }
        }

    fun palettes(context: Context, palettes: List<Palette>, selected: String, onSelect: (Palette) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatches = palettes.map { palette ->
            SwatchView(context, palette).apply {
                isSelected = palette.id == selected
                contentDescription = palette.name
                layoutParams = LinearLayout.LayoutParams(context.dp(54), context.dp(54)).apply {
                    rightMargin = context.dp(8)
                }
            }
        }
        val name = body(context, palettes.firstOrNull { it.id == selected }?.name ?: "", WHITE)
        swatches.forEach { swatch ->
            swatch.setOnClickListener {
                swatches.forEach { it.isSelected = it === swatch }
                name.text = swatch.palette.name
                onSelect(swatch.palette)
            }
            row.addView(swatch)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            addView(name)
        }
    }
}

/** A colour swatch: palette background with a foreground and an accent dot; ringed when selected. */
class SwatchView(context: Context, val palette: Palette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (isSelected) {
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.color = 0xFF3A3A3A.toInt()
        canvas.drawCircle(cx, cy, r * 0.86f, paint)
        paint.color = palette.bg
        canvas.drawCircle(cx, cy, r * 0.82f, paint)
        paint.color = palette.fg
        canvas.drawCircle(cx - r * 0.25f, cy, r * 0.17f, paint)
        paint.color = palette.accent
        canvas.drawCircle(cx + r * 0.25f, cy, r * 0.17f, paint)
    }
}
