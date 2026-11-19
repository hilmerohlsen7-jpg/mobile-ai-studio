package com.mobileaistudio.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Home-screen entry "File Manager": opens the app straight on the File Manager, then goes away. */
class FilesLauncher : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("open", "files"))
        finish()
    }
}
