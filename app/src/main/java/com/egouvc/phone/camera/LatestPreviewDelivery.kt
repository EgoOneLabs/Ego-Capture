package com.egouvc.phone.camera

import android.graphics.Bitmap
import android.os.Handler

/** At most one queued UI runnable and one waiting bitmap, including during UI stalls. */
class LatestPreviewDelivery(private val handler: Handler, private val display: (Bitmap) -> Unit) {
    private var pending: Bitmap? = null
    private var scheduled = false
    private var closed = false
    private val drain = Runnable {
        val bitmap = synchronized(this) {
            scheduled = false
            pending.also { pending = null }
        }
        if (bitmap != null) display(bitmap)
    }
    @Synchronized fun offer(bitmap: Bitmap) {
        if (closed) { bitmap.recycle(); return }
        pending?.recycle()
        pending = bitmap
        if (!scheduled) {
            scheduled = true
            if (!handler.post(drain)) { pending?.recycle(); pending = null; scheduled = false }
        }
    }
    @Synchronized fun close() {
        closed = true
        handler.removeCallbacks(drain)
        pending?.recycle()
        pending = null
        scheduled = false
    }
}
