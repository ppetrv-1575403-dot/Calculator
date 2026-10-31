package com.google.android.calculator1.utils

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager

/**fun sendDataIntent(data: String, context: Context) {
    // После записи в файл
    Intent share = new Intent(Intent.ACTION_SEND)
    share.setType("text/plain")
    share.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
    this, getPackageName() + ".fileprovider", data))
    
    context.startActivity(Intent.createChooser(share, "Отправить лог"))
}**/

object AndroidTools {
  
    fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }    
        return null
    }
    
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("vault_text", text)
        clipboard.setPrimaryClip(clip)
    }

    fun performClipboardCopy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("vault_text", text)
        clipboard.setPrimaryClip(clip)
    }

}
