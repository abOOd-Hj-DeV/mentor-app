package dev.k230.mentor_app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView

internal object MvpInterventionView {
    private fun background(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun create(context: Context, reason: String, done: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val usage = reason == "usage"
        val answer = if (usage) 12 else 9
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(36), dp(28), dp(36))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(239, 248, 244), Color.rgb(245, 241, 255)),
            )
            isClickable = true
        }
        root.addView(TextView(context).apply {
            text = if (usage) "☕" else "✦"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(42, 94, 85))
        })
        root.addView(TextView(context).apply {
            text = if (usage) "وقت استراحة لطيفة" else "لنغيّر المشهد قليلاً"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 53, 63))
            setPadding(0, dp(10), 0, dp(8))
        })
        root.addView(TextView(context).apply {
            text = if (usage)
                "مرّت 20 دقيقة متواصلة. حرّك جسمك، اشرب بعض الماء، ثم حلّ اللغز للمتابعة."
            else "أُغلق التطبيق لأن محتواه احتاج تدخلاً. خذ نفساً هادئاً وحلّ اللغز الصغير."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(73, 86, 91))
        })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = background(Color.WHITE, dp(24).toFloat())
        }
        val question = TextView(context).apply {
            text = if (usage) "كم يساوي 7 + 5؟" else "كم يساوي 3 × 3؟"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 53, 63))
        }
        val feedback = TextView(context).apply {
            text = "اختر الإجابة الصحيحة"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(91, 104, 109))
        }
        card.addView(question)
        card.addView(feedback)
        val choices = LinearLayout(context).apply { gravity = Gravity.CENTER }
        listOf(answer - 2, answer, answer + 3).forEach { option ->
            choices.addView(Button(context).apply {
                text = option.toString()
                contentDescription = "الإجابة $option"
                setOnClickListener {
                    if (option == answer) done() else feedback.text = "ليست هذه؛ جرّب مرة أخرى بهدوء"
                }
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), dp(12), dp(4), 0) })
        }
        card.addView(choices, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(28), 0, 0) })
        return ScrollView(context).apply {
            isFillViewport = true
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
