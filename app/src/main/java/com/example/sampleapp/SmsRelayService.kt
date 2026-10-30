package com.example.sampleapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class SmsRelayService : Service() {

    private lateinit var sharedPreferences: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()

        // Initialisation sécurisée qui posait problème
        try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            sharedPreferences = EncryptedSharedPreferences.create(
                applicationContext,
                "secret_shared_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SmsRelayService", "Erreur initialisation sécurité", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // C'est ici que tu mettras ta logique pour envoyer le SMS.
        // Exemple d'appel réseau avec OkHttp :
        /*
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://ton-api.com/webhook")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("SmsRelay", "Erreur réseau", e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.d("SmsRelay", "Succès: ${response.body?.string()}")
            }
        })
        */

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
