package com.example.kb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Apre direttamente le impostazioni delle tastiere per attivare Space Keyboard. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        finish()
    }
}
