package com.soywiz.korui.ui

import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.NativeImage
import com.soywiz.korim.vector.Context2d
import com.soywiz.korio.async.asyncFun
import com.soywiz.korui.Application
import com.soywiz.korui.geom.len.pt

suspend inline fun Container.vectorImage(vector: Context2d.Drawable, width: Int, height: Int) = asyncFun {
	add(VectorImage(this.app).apply {
		setVector(vector, width, height)
	})
}

suspend inline fun Container.vectorImage(vector: Context2d.Drawable, width: Int, height: Int, crossinline callback: VectorImage.() -> Unit) = asyncFun {
	add(VectorImage(this.app).apply {
		setVector(vector, width, height)
		callback(this)
	})
}

class VectorImage(app: Application) : Container(app, LayeredLayout(app)) {
	lateinit var d: Context2d.Drawable
	lateinit var img: Image
	var targetWidth: Int = 512
	var targetHeight: Int = 512

	suspend fun setVector(d: Context2d.Drawable, width: Int, height: Int) = asyncFun {
		this.d = d
		this.targetWidth = width
		this.targetHeight = height
		this.style.defaultSize.setTo(width.pt, height.pt)
		//img = image(raster(width, height))
		img = image(NativeImage(1, 1))
	}

	override fun onResized(x: Int, y: Int, width: Int, height: Int) {
		//println("onResized: $x, $y, $width, $height")
		img.image = raster(width, height)
	}

	fun raster(width: Int, height: Int): NativeImage {
		return NativeImage(
			width, height, d,
			width.toDouble() / this.targetWidth.toDouble(),
			height.toDouble() / this.targetHeight.toDouble()
		)
	}
}