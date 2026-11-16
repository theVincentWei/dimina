package com.didi.dimina.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.SoftwareKeyboardControllerCompat

/** Connect DOM editing focus to Android's input connection, including focus before window ready. */
@SuppressLint("ViewConstructor")
internal class MiniProgramWebView(context: Context) : WebView(context) {
    private var focusGeneration = 0
    private var keyboardPending = false

    /**
     * [game-touch] 游戏触摸快路委托（微维测/AB 期，整块可删）。非空时 dispatchTouchEvent
     * 把事件直接转给快路并消费掉（return true），WebView 内部与 DOM 完全看不到事件；
     * pageFrame 的 DOM touch/mouse 监听由 __diminaNativeGameTouch 能力标志跳过。
     * 为空（开关关闭/非 game 运行时）走 super，行为与旧版完全一致。
     */
    var gameTouchDelegate: ((MotionEvent) -> Unit)? = null

    /** 只摘除 owner 自己挂的委托，避免误清新桥刚挂上的快路（WebView 复用场景）。 */
    fun clearGameTouchDelegate(owner: Any) {
        if (gameTouchDelegate === owner) gameTouchDelegate = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val delegate = gameTouchDelegate
        if (delegate != null) {
            delegate(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    var isPageActive = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                if (isAttachedToWindow) requestFocus()
                showPendingKeyboard()
            } else {
                cancelPendingKeyboard()
            }
        }

    init {
        addJavascriptInterface(object {
            @JavascriptInterface
            fun focusChanged(editing: Boolean) {
                post {
                    focusGeneration++
                    keyboardPending = editing && isPageActive
                    showPendingKeyboard()
                }
            }
        }, "DiminaKeyboardBridge")
    }

    fun resetKeyboardFocus() = cancelPendingKeyboard()

    private val editingExpression = """
        (function () {
            var el = document.activeElement;
            return !!el && !el.disabled && !el.readOnly &&
                (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' &&
                /^(text|search|tel|url|email|password|number)$/.test(el.type)));
        })()
    """.trimIndent()

    fun installKeyboardFocusObserver() {
        evaluateJavascript("""
            (function () {
                if (window.__diminaKeyboardFocusObserver) return;
                window.__diminaKeyboardFocusObserver = true;
                function syncFocus() {
                    DiminaKeyboardBridge.focusChanged($editingExpression);
                }
                document.addEventListener('focusin', syncFocus);
                document.addEventListener('focusout', function () {
                    DiminaKeyboardBridge.focusChanged(false);
                });
                syncFocus();
            })();
        """.trimIndent(), null)
    }

    private fun cancelPendingKeyboard() {
        focusGeneration++
        keyboardPending = false
    }

    private fun showPendingKeyboard() {
        if (!keyboardPending || !isPageActive || !isAttachedToWindow || !hasWindowFocus() || !isShown) return
        val generation = focusGeneration
        // Recheck after crossing the JS/UI thread boundary: blur, unmount or navigation may have won.
        evaluateJavascript(editingExpression) { editing ->
            if (generation != focusGeneration || !keyboardPending || !isPageActive ||
                !isAttachedToWindow || !hasWindowFocus() || !isShown) return@evaluateJavascript
            keyboardPending = false
            if (editing == "true") {
                requestFocus()
                SoftwareKeyboardControllerCompat(this).show()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Without native focus, Chromium can change activeElement without dispatching focusin.
        // Only the selected page owns focus; pooled and background tab WebViews must not take it.
        if (isPageActive) requestFocus()
        showPendingKeyboard()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) showPendingKeyboard()
    }

    override fun onDetachedFromWindow() {
        cancelPendingKeyboard()
        super.onDetachedFromWindow()
    }

    override fun destroy() {
        cancelPendingKeyboard()
        removeJavascriptInterface("DiminaKeyboardBridge")
        super.destroy()
    }
}
