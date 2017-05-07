@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.soywiz.korui.light.html

import com.jtransc.JTranscSystem
import com.jtransc.js.*
import com.soywiz.korag.AG
import com.soywiz.korag.agFactory
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.NativeImage
import com.soywiz.korim.color.RGBA
import com.soywiz.korim.html.HtmlImage
import com.soywiz.korio.coroutine.korioSuspendCoroutine
import com.soywiz.korio.stream.AsyncStream
import com.soywiz.korio.stream.AsyncStreamBase
import com.soywiz.korio.stream.toAsyncStream
import com.soywiz.korio.util.Cancellable
import com.soywiz.korio.util.cancellable
import com.soywiz.korio.util.closeable
import com.soywiz.korio.vfs.Vfs
import com.soywiz.korio.vfs.VfsFile
import com.soywiz.korio.vfs.VfsOpenMode
import com.soywiz.korio.vfs.VfsStat
import com.soywiz.korio.vfs.js.JsStat
import com.soywiz.korui.light.*
import java.io.Closeable
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException

class HtmlLightComponentsFactory : LightComponentsFactory() {
	override val available: Boolean = JTranscSystem.isJs()
	override fun create(): LightComponents = HtmlLightComponents()
}

@Suppress("unused")
class HtmlLightComponents : LightComponents() {
	val tDevicePixelRatio = window["devicePixelRatio"].toDouble();
	val devicePixelRatio = when {
		tDevicePixelRatio <= 0.0 -> 1.0
		tDevicePixelRatio.isNaN() -> 1.0
		tDevicePixelRatio.isInfinite() -> 1.0
		else -> tDevicePixelRatio
	}

	init {
		addStyles("""
			body {
				font: 11pt Arial;
			}
			.BUTTON {
				-moz-box-shadow:inset 0px 1px 0px 0px #ffffff;
				-webkit-box-shadow:inset 0px 1px 0px 0px #ffffff;
				box-shadow:inset 0px 1px 0px 0px #ffffff;
				background:linear-gradient(to bottom, #ffffff 5%, #f6f6f6 100%);
				background-color:#ffffff;
				-moz-border-radius:6px;
				-webkit-border-radius:6px;
				border-radius:6px;
				border:1px solid #dcdcdc;
				display:inline-block;
				cursor:pointer;
				color:#666666;
				font-family:Arial;
				font-size:15px;
				font-weight:bold;
				padding:6px 24px;
				text-decoration:none;
				text-shadow:0px 1px 0px #ffffff;
			}
			.BUTTON:hover {
				background:linear-gradient(to bottom, #f6f6f6 5%, #ffffff 100%);
				background-color:#f6f6f6;
			}
			.BUTTON:active {
				padding-top: 7px;
				padding-bottom: 5px;

				background:linear-gradient(to bottom, #f0f0f0 5%, #f6f6f6 100%);
				background-color:#f6f6f6;
			}
			.BUTTON:focus {
				/*outline: auto 5px -webkit-focus-ring-color;*/
				outline: auto 1px black;
			}
			.TEXT_AREA {
				white-space: nowrap;
				resize: none;
			}
		""")

		document["body"]["style"]["background"] = "#f0f0f0"
		val inputFile = document.call("createElement", "input")
		inputFile["type"] = "file"
		inputFile["style"]["visibility"] = "hidden"
		window["inputFile"] = inputFile
		window["selectedFiles"] = jsArray()
		document["body"].call("appendChild", inputFile)
	}

	fun addStyles(css: String) {
		val head = document["head"] ?: document.method("getElementsByTagName")("head")[0]
		val style = document.method("createElement")("style")

		style["type"] = "text/css"
		if (style["styleSheet"] != null) {
			style["styleSheet"]["cssText"] = css
		} else {
			style.method("appendChild")(document.method("createTextNode")(css))
		}

		head.method("appendChild")(style)
	}

	override fun create(type: LightType): LightComponentInfo {
		var agg: AG? = null
		val handle = when (type) {
			LightType.FRAME -> {
				document.method("createElement")("article")!!.apply {
					this["className"] = "FRAME"
					document["body"].method("appendChild")(this)
					window["mainFrame"] = this
					window["mainFrame"]["style"]["visibility"] = "hidden"
				}
			}
			LightType.CONTAINER -> {
				document.method("createElement")("div")!!.apply {
					this["className"] = "CONTAINER"
				}
			}
			LightType.SCROLL_PANE -> {
				document.method("createElement")("div")!!.apply {
					this["className"] = "SCROLL_PANE"
				}
			}
			LightType.BUTTON -> {
				document.method("createElement")("input")!!.apply {
					this["className"] = "BUTTON"
					this["type"] = "button"
				}
			}
			LightType.PROGRESS -> {
				document.method("createElement")("progress")!!.apply {
					this["className"] = "PROGRESS"
				}
			}
			LightType.IMAGE -> {
				document.method("createElement")("canvas")!!.apply {
					this["className"] = "IMAGE"
					this["style"]["imageRendering"] = "pixelated"
				}
			}
			LightType.LABEL -> {
				document.method("createElement")("label")!!.apply {
					this["className"] = "LABEL"
				}
			}
			LightType.TEXT_FIELD -> {
				document.method("createElement")("input")!!.apply {
					this["className"] = "TEXT_FIELD"
					this["type"] = "text"
				}
			}
			LightType.TEXT_AREA -> {
				document.method("createElement")("textarea")!!.apply {
					this["className"] = "TEXT_AREA"
					//this["type"] = "text"
				}
			}
			LightType.CHECK_BOX -> {
				document.method("createElement")("label")!!.apply {
					this["className"] = "CHECK_BOX"
					this["data-type"] = "checkbox"
					this.call("appendChild", document.method("createElement")("input")!!.apply {
						this["className"] = "TEXT_FIELD"
						this["type"] = "checkbox"
					})
					this.call("appendChild", document.method("createElement")("span")!!)
				}
			}
			LightType.AGCANVAS -> {
				agg = agFactory.create()
				agg.nativeComponent.asJsDynamic()!!
			}
			else -> {
				document.method("createElement")("div")!!.apply {
					this["className"] = "UNKNOWN"
				}
			}
		}.apply {
			val style = this["style"]
			style["position"] = "absolute"

			val overflow = when (type) {
				LightType.SCROLL_PANE, LightType.TEXT_AREA, LightType.TEXT_FIELD -> true
				else -> false
			}

			style["overflow-y"] = if (overflow) "auto" else "hidden"
			style["overflow-x"] = if (overflow) "auto" else "hidden"
			style["left"] = "0px"
			style["top"] = "0px"
			style["width"] = "100px"
			style["height"] = "100px"
		}
		return LightComponentInfo(handle).apply {
			if (agg != null) this.ag = agg
		}
	}

	override fun setParent(c: Any, parent: Any?) {
		val child = c.asJsDynamic()
		if (child["parentNode"] != null) {
			child["parentNode"].method("removeChild")(child)
		}
		if (parent != null) {
			(parent.asJsDynamic()).method("appendChild")(child)
		}
	}

	private fun JsDynamic?.addEventListener(name: String, func: JsDynamic?): Closeable {
		this.call("addEventListener", name, func)
		return Closeable { this.call("removeEventListener", name, func) }
	}

	override fun addHandler(c: Any, listener: LightMouseHandler): Closeable {
		val node = c.asJsDynamic()

		val info = LightMouseHandler.Info()
		fun process(e: JsDynamic?, buttons: Int) = info.apply {
			this.x = (e["offsetX"].toInt() * devicePixelRatio).toInt()
			this.y = (e["offsetY"].toInt() * devicePixelRatio).toInt()
			this.buttons = buttons
		}

		return listOf(
			node.addEventListener("click", jsFunctionRaw1 { listener.click(process(it, 1)) }),
			node.addEventListener("mouseover", jsFunctionRaw1 { listener.over(process(it, 0)) }),
			node.addEventListener("mousemove", jsFunctionRaw1 { listener.over(process(it, 0)) }),
			node.addEventListener("mouseup", jsFunctionRaw1 { listener.up(process(it, 0)) }),
			node.addEventListener("mousedown", jsFunctionRaw1 { listener.down(process(it, 0)) })
		).closeable()
	}

	override fun addHandler(c: Any, listener: LightChangeHandler): Closeable {
		val node = c.asJsDynamic()
		val info = LightChangeHandler.Info()

		return listOf(
			node.addEventListener("change", jsFunctionRaw1 { listener.changed(info) }),
			node.addEventListener("keypress", jsFunctionRaw1 { listener.changed(info) }),
			node.addEventListener("input", jsFunctionRaw1 { listener.changed(info) }),
			node.addEventListener("textInput", jsFunctionRaw1 { listener.changed(info) }),
			node.addEventListener("paste", jsFunctionRaw1 { listener.changed(info) })
		).closeable()
	}

	override fun addHandler(c: Any, listener: LightResizeHandler): Closeable {
		val node = window
		val info = LightResizeHandler.Info()

		fun send() {
			if (window["mainFrame"] != null) {
				window["mainFrame"]["style"]["width"] = "${window["innerWidth"].toInt()}px"
				window["mainFrame"]["style"]["height"] = "${window["innerHeight"].toInt()}px"
			}

			listener.resized(info.apply {
				width = window["innerWidth"].toInt()
				height = window["innerHeight"].toInt()
			})
		}

		send()

		return listOf(
			node.addEventListener("resize", jsFunctionRaw1 { send() })
		).closeable()
	}

	override fun addHandler(c: Any, listener: LightKeyHandler): Closeable {
		val node = c.asJsDynamic()
		val info = LightKeyHandler.Info()

		fun process(e: JsDynamic?) = info.apply {
			this.keyCode = e["keyCode"].toInt()
		}

		return listOf(
			node.addEventListener("keydown", jsFunctionRaw1 { listener.down(process(it)) }),
			node.addEventListener("keyup", jsFunctionRaw1 { listener.up(process(it)) }),
			node.addEventListener("keypress", jsFunctionRaw1 { listener.typed(process(it)) })
		).closeable()
	}

	override fun addHandler(c: Any, listener: LightGamepadHandler): Closeable {
		return super.addHandler(c, listener)
	}

	override fun addHandler(c: Any, listener: LightTouchHandler): Closeable {
		val node = c.asJsDynamic()

		fun process(e: JsDynamic?): List<LightTouchHandler.Info> {
			val out = arrayListOf<LightTouchHandler.Info>()
			val touches = e["changedTouches"]
			for (n in 0 until touches["length"].toInt()) {
				val touch = touches[n]
				out += LightTouchHandler.Info().apply {
					this.x = (touch["pageX"].toInt() * devicePixelRatio).toInt()
					this.y = (touch["pageY"].toInt() * devicePixelRatio).toInt()
					this.id = touch["identifier"].toInt()
				}
			}
			e.call("preventDefault")
			return out
		}

		return listOf(
			node.addEventListener("touchstart", jsFunctionRaw1 { for (info in process(it)) listener.start(info) }),
			node.addEventListener("touchend", jsFunctionRaw1 { for (info in process(it)) listener.end(info) }),
			node.addEventListener("touchmove", jsFunctionRaw1 { for (info in process(it)) listener.move(info) })
		).closeable()
	}

	override fun <T> setProperty(c: Any, key: LightProperty<T>, value: T) {
		val child = c.asJsDynamic()
		val childOrDocumentBody = if (child["nodeName"].toJavaString().toLowerCase() == "article") document["body"] else child
		val nodeName = child["nodeName"].toJavaString().toLowerCase()
		when (key) {
			LightProperty.TEXT -> {
				val v = key[value]
				if (nodeName == "article") {
					document["title"] = v
				} else if (nodeName == "input" || nodeName == "textarea") {
					child["value"] = v
				} else {
					if (child["data-type"].toJavaString() == "checkbox") {
						child.call("querySelector", "span")["innerText"] = v
					} else {
						child["innerText"] = v
					}
				}
			}
			LightProperty.PROGRESS_CURRENT -> {
				val v = key[value]
				child["value"] = v
			}
			LightProperty.PROGRESS_MAX -> {
				val v = key[value]
				child["max"] = v
			}
			LightProperty.BGCOLOR -> {
				val v = key[value]
				childOrDocumentBody["style"]["background"] = colorString(v)
			}
			LightProperty.IMAGE_SMOOTH -> {
				val v = key[value]
				child["style"]["imageRendering"] = if (v) "auto" else "pixelated"
			}
			LightProperty.ICON -> {
				val v = key[value]
				if (v != null) {
					val href = HtmlImage.htmlCanvasToDataUrl(HtmlImage.bitmapToHtmlCanvas(v.toBMP32()))

					var link = document.getMethod("querySelector")("link[rel*='icon']")
					if (link == null) {
						link = document.getMethod("createElement")("link")
					}
					link["type"] = "image/x-icon"
					link["rel"] = "shortcut icon"
					link["href"] = href
					document.getMethod("getElementsByTagName")("head")[0].getMethod("appendChild")(link)
				}
			}
			LightProperty.IMAGE -> {
				val bmp = key[value]
				if (bmp is NativeImage) {
					setCanvas(c, bmp.data.asJsDynamic())
				} else {
					setImage32(c, bmp?.toBMP32())
				}
			}
			LightProperty.VISIBLE -> {
				val v = key[value]
				if (child != null) child["style"]["display"] = if (v) "block" else "none"
			}
			LightProperty.CHECKED -> {
				val v = key[value]
				child.call("querySelector", "input[type=checkbox]")["checked"] = v
			}
		}
	}

	@Suppress("UNCHECKED_CAST")
	override fun <T> getProperty(c: Any, key: LightProperty<T>): T {
		val child = c.asJsDynamic()

		when (key) {
			LightProperty.TEXT -> {
				return child["value"].toJavaString() as T
			}
			LightProperty.CHECKED -> {
				val input = child.call("querySelector", "input[type=checkbox]")
				return input["checked"].toBool() as T
			}
		}
		return super.getProperty(c, key)
	}


	fun colorString(c: Int) = "RGBA(${RGBA.getR(c)},${RGBA.getG(c)},${RGBA.getB(c)},${RGBA.getAf(c)})"

	private fun setCanvas(c: Any, bmp: JsDynamic?) {
		val targetCanvas = c.asJsDynamic()!!
		if (bmp != null) {
			targetCanvas["width"] = bmp["width"]
			targetCanvas["height"] = bmp["height"]
			val ctx = targetCanvas.call("getContext", "2d")
			HtmlImage.htmlCanvasClear(targetCanvas)
			ctx.call("drawImage", bmp, 0, 0)
		} else {
			HtmlImage.htmlCanvasClear(targetCanvas)
		}
	}

	private fun setImage32(c: Any, bmp: Bitmap32?) {
		if (bmp != null) {
			HtmlImage.htmlCanvasSetSize(c.asJsDynamic()!!, bmp.width, bmp.height)
			HtmlImage.renderToHtmlCanvas(bmp, c.asJsDynamic()!!)
		} else {
			HtmlImage.htmlCanvasClear(c.asJsDynamic()!!)
		}
	}

	override fun setBounds(c: Any, x: Int, y: Int, width: Int, height: Int) {
		val child = c.asJsDynamic()
		val childStyle = child["style"]
		childStyle["left"] = "${x}px"
		childStyle["top"] = "${y}px"
		childStyle["width"] = "${width}px"
		childStyle["height"] = "${height}px"

		child["width"] = width * devicePixelRatio
		child["height"] = height * devicePixelRatio
	}

	override fun repaint(c: Any) {
		window["mainFrame"]["style"]["visibility"] = "visible"
	}

	suspend override fun dialogAlert(c: Any, message: String) = korioSuspendCoroutine<Unit> { c ->
		window.getMethod("alert")(message)
		window.getMethod("setTimeout")({
			c.resume(Unit)
		}.toJsDynamic(), 0)
	}

	suspend override fun dialogPrompt(c: Any, message: String): String = korioSuspendCoroutine { c ->
		val result = window.getMethod("prompt")(message).toJavaStringOrNull()
		window.getMethod("setTimeout")({
			if (result == null) {
				c.resumeWithException(CancellationException())
			} else {
				c.resume(result)
			}
		}.toJsDynamic(), 0)
	}

	suspend override fun dialogOpenFile(c: Any, filter: String): VfsFile = korioSuspendCoroutine { continuation ->
		val inputFile = window["inputFile"]
		var completedOnce = false
		var files = jsArray()

		val completed = {
			if (!completedOnce) {
				completedOnce = true

				window["selectedFiles"] = files

				//console.log('completed', files);
				if (files["length"].toInt() > 0) {
					val fileName = files[0]["name"].toJavaString()
					continuation.resume(SelectedFilesVfs[fileName])
				} else {
					continuation.resumeWithException(CancellationException())
				}
			}
		}

		inputFile["value"] = ""

		inputFile["onclick"] = jsFunctionRaw1 {
			document["body"]["onfocus"] = jsFunctionRaw1 {
				document["body"]["onfocus"] = null
				global.call("setTimeout", jsFunctionRaw1 {
					completed()
				}, 2000)
			}
			Unit
		}

		inputFile["onchange"] = jsFunctionRaw1 { e ->
			files = e["target"]["files"]
			//var v = this.value;
			//console.log(v);
			completed()
		}

		inputFile.call("click")
	}

	override fun openURL(url: String): Unit {
		window.call("open", url, "_blank")
	}

	override fun getDpi(): Double {
		return (window["devicePixelRatio"].toInt() * 96).toDouble()
	}
}

internal object SelectedFilesVfs : Vfs() {
	private fun _locate(name: String): JsDynamic? {
		val selectedFiles = window["selectedFiles"]
		val length = selectedFiles["length"].toInt()
		for (n in 0 until length) {
			val file = selectedFiles[n]
			if (file["name"]!!.eq(name.toJavaScriptString())) {
				return file
			}
		}
		return null
	}

	private fun jsstat(file: JsDynamic?): JsStat {
		return JsStat(file["size"].toDouble())
	}

	private fun locate(path: String): JsDynamic? = _locate(path.trim('/'))

	suspend override fun open(path: String, mode: VfsOpenMode): AsyncStream {
		val jsfile = locate(path) ?: throw FileNotFoundException(path)
		val jsstat = jsstat(jsfile)
		return object : AsyncStreamBase() {
			suspend fun _read(jsfile: JsDynamic, position: Double, len: Int): ByteArray = korioSuspendCoroutine { c ->
				val reader = jsNew("FileReader")
				val slice = jsfile.method("slice")(position, position + len)

				reader["onload"] = jsFunctionRaw1 {
					val result = reader["result"]
					//val u8array = window["Uint8Array"].new2(result)
					val u8array = jsNew("Uint8Array", result)
					val out = ByteArray(u8array["length"].toInt())
					(out.asJsDynamic()).method("setArraySlice")(0, u8array)
					c.resume(out)
				}

				reader["onerror"] = jsFunctionRaw1 {
					c.resumeWithException(RuntimeException("error reading file"))
				}
				reader.method("readAsArrayBuffer")(slice)
			}

			suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
				val data = _read(jsfile, position.toDouble(), len)
				System.arraycopy(data, 0, buffer, offset, data.size)
				return data.size
			}

			suspend override fun getLength(): Long = jsstat.size.toLong()
			suspend override fun close() = Unit
		}.toAsyncStream()
	}

	suspend override fun stat(path: String): VfsStat {
		return jsstat(locate(path)).toStat(path, this)
	}
}
