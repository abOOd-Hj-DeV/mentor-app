package dev.k230.mentor_app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class LayoutFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = "Local layout fixture: three images" })
        for (index in 0..2) {
            column.addView(ImageView(this).apply {
                contentDescription = "Fixture image ${index + 1}"
                setImageDrawable(ColorDrawable(listOf(Color.RED, Color.GREEN, Color.BLUE)[index]))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400))
        }
        setContentView(column)
    }
}
