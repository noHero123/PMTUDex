package com.example.pmtu

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

fun View.setSize(width: Int, height: Int) {
    val params = layoutParams ?: ViewGroup.LayoutParams(width, height)
    params.width = width
    params.height = height
    layoutParams = params
}

fun View.setMargins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(left, top, right, bottom)
}

fun ImageView.setAssetImage(path: String) {
    try {
        context.assets.open(path).use { inputStream ->
            setImageBitmap(BitmapFactory.decodeStream(inputStream))
        }
    } catch (e: Exception) {
        // Silently fail or log if asset is missing
    }
}
