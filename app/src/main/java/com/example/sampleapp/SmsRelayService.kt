package com.example.sampleapp

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SmsRelayService : Service() {

    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

    private var heartbeatJob: Job? = null
    private lateinit var smsObserver: ContentObserver
    private var lastSmsId: Long = 0
    private lateinit var queueFile: File

    companion object {
        private const val CHANNEL_ID = "sms_relay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "sms_relay_secure_prefs"
        const val KEY_TOKEN = "relay_token"
        const val KEY_DEVICE = "device_name"
        const val KEY_OPERATOR = "operator"
        const val KEY_ENDPOINT = "relay_endpoint"
        const val KEY_HEARTBEAT = "heartbeat_interval"
        const val KEY_STAY_AWAKE = "stay_awake"
        const val KEY_LAST_SMS_ID = "last_sms_id"
        const val KEY_PROCESSED_REFS = "processed_refs"
    }

    override fun onCreate() {
        super.onCreate()
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            this, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        queueFile = File(applicationContext.filesDir, "sms_queue.json")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Relais SMS actif"))
        prefs.edit().putBoolean("service_running", true).apply()
        
        registerSmsObserver()
        startHeartbeat()
        scope.launch { processQueue() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri != null && uri.toString().contains("content://sms/inbox")) {
                    scope.launch { readAndProcessNewSms() }
                }
            }
        }
        contentResolver.registerContentObserver(Uri.parse("content://sms/inbox"), true, smsObserver)
        lastSmsId = prefs.getLong(KEY_LAST_SMS_ID, 0)
        if (lastSmsId == 0L) { scope.launch { setInitialCursor() } }
    }

    private fun setInitialCursor() {
        val cursor = contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"), null, null, "date DESC LIMIT 1")
        cursor?.use {
            if (it.moveToFirst()) {
                lastSmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                prefs.edit().putLong(KEY_LAST_SMS_ID, lastSmsId).apply()
            }
        }
    }

    private suspend fun readAndProcessNewSms() {
        val cursor = contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("_id", "address", "body", "date"),
            "_id > ?", arrayOf(lastSmsId.toString()), "date ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val smsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                val receivedAt = it.getLong(it.getColumnIndexOrThrow("date"))

                if (smsId > lastSmsId) {
                    lastSmsId = smsId
                    prefs.edit().putLong(KEY_LAST_SMS_ID, lastSmsId).apply()
                    
                    val parsedData = parseSms(body, sender)
                    if (parsedData != null) {
                        if (!isAlreadyProcessed(parsedData.reference)) {
                            val payload = buildJsonPayload(smsId, sender, body, receivedAt, parsedData)
                            handleNewTransaction(payload)
                        }
                    }
                }
            }
        }
    }

    data class ParsedSms(val amount: String, val reference: String, val payerPhone: String)

    private fun parseSms(body: String, sender: String): ParsedSms? {
        val moovRegex = Regex("""Montant:\s*([\d\s,]+)\s*FCFA.*Txn ID:\s*(\w+).*Numero du client:\s*(?:228)?([79]\d{7})""", RegexOption.DOT_MATCHES_ALL)
        val mixxRegex = Regex("""Paiement de\s*([\d\s]+)\s*FCFA effectué par\s*(?:228)?([79]\d{7}).*Ref:\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)

        moovRegex.find(body)?.let { match ->
            return ParsedSms(match.groupValues[1].replace(",", ".").trim(), match.groupValues[2].trim(), match.groupValues[3].trim())
        }
        mixxRegex.find(body)?.let { match ->
            return ParsedSms(match.groupValues[1].trim(), match.groupValues[3].trim(), match.groupValues[2].trim())
        }
        return null 
    }

    private fun isAlreadyProcessed(reference: String): Boolean {
        val processed = prefs.getStringSet(KEY_PROCESSED_REFS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (processed.contains(reference)) return true
        
        processed.add(reference)
        if (processed.size > 500) {
            val list = processed.toList()
            processed.clear()
            processed.addAll(list.takeLast(500))
        }
        prefs.edit().putStringSet(KEY_PROCESSED_REFS, processed).apply()
        return false
    }

    private fun buildJsonPayload(smsId: Long, sender: String, body: String, receivedAt: Long, data: ParsedSms): JSONObject {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return JSONObject().apply {
            put("device", prefs.getString(KEY_DEVICE, "unknown"))
            put("operator", prefs.getString(KEY_OPERATOR, "unknown"))
            put("sender", sender)
            put("amount", data.amount)
            put("reference", data.reference)
            put("payer_phone", data.payerPhone)
            put("body", body)
            put("sms_id", "apk-$smsId-${System.currentTimeMillis()}")
            put("received_at", isoFormat.format(Date(receivedAt)))
            put("captured_at", isoFormat.format(Date()))
            put("token", prefs.getString(KEY_TOKEN, ""))
        }
    }

    private fun handleNewTransaction(payload: JSONObject) {
        scope.launch {
            acquireWakeLock()
            try {
                if (!sendToServer(payload)) saveToLocalQueue(payload)
            } finally { releaseWakeLock() }
        }
    }

    private fun saveToLocalQueue(payload: JSONObject) {
        try {
            val queue = if (queueFile.exists()) JSONArray(queueFile.readText()) else JSONArray()
            if (queue.length() < 200) {
                queue.put(payload)
                queueFile.writeText(queue.toString())
            }
        } catch (e: Exception) { Log.e("SmsRelay", "Erreur file", e) }
    }

    private suspend fun processQueue() {
        if (!queueFile.exists()) return
        try {
            val queue = JSONArray(queueFile.readText())
            val newQueue = JSONArray()
            for (i in 0 until queue.length()) {
                val payload = queue.getJSONObject(i)
                if (!sendToServer(payload)) newQueue.put(payload)
            }
            if (newQueue.length() > 0) queueFile.writeText(newQueue.toString()) else queueFile.delete()
        } catch (e: Exception) { Log.e("SmsRelay", "Erreur queue", e) }
    }

    private suspend fun sendToServer(payload: JSONObject): Boolean {
        val endpoint = prefs.getString(KEY_ENDPOINT, "") ?: return false
        if (endpoint.isBlank()) return false
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).post(requestBody).build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response -> 
                    response.isSuccessful || response.code == 409 
                }
            } catch (e: IOException) { false }
        }
    }

    private fun startHeartbeat() {
        val interval = prefs.getLong(KEY_HEARTBEAT, 5000L).coerceAtLeast(1000L)
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(interval)
                sendHeartbeat()
                if (isActive) processQueue()
            }
        }
    }

    private suspend fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("device", prefs.getString(KEY_DEVICE, "unknown"))
            put("operator", prefs.getString(KEY_OPERATOR, "unknown"))
            put("heartbeat", true)
            put("app_version", "1.2")
            put("token", prefs.getString(KEY_TOKEN, ""))
        }
        sendToServer(payload)
    }

    private fun acquireWakeLock() {
        if (prefs.getBoolean(KEY_STAY_AWAKE, false)) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsRelay::WakeLock").apply { acquire(20_000L) }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Relais SMS", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("NYGMA Relais SMS").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()
    }

    override fun onDestroy() {
        scope.cancel()
        contentResolver.unregisterContentObserver(smsObserver)
        heartbeatJob?.cancel()
        releaseWakeLock()
        prefs.edit().putBoolean("service_running", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
