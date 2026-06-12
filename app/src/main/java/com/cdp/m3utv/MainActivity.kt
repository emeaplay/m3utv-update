package com.cdp.m3utv

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class Canal(val nombre: String, val url: String)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var listaCanales = listOf<Canal>()
    private var indiceActual = 0

    private var forzarSoftwareCanalActual = false

    // Componentes UI
    private lateinit var tivimateInfoBar: LinearLayout
    private lateinit var txtInfoNumber: TextView
    private lateinit var txtInfoName: TextView
    private lateinit var badgeResolution: TextView
    private lateinit var badgeAudio: TextView
    private lateinit var badgeStatus: TextView

    private lateinit var channelsListView: ListView
    private lateinit var inputLayout: LinearLayout
    private lateinit var settingsLayout: LinearLayout
    private lateinit var urlInput: EditText

    private val handlerUI = Handler(Looper.getMainLooper())
    private var bufferNumeros = ""
    private val ocultarInfoRunnable = Runnable { tivimateInfoBar.visibility = View.GONE }

    private val listaColores = listOf("#0D6EFD", "#FFC107", "#198754", "#6C757D")
    private var indiceColorActual = 0

    private val cambiarPorNumeroRunnable = Runnable {
        val numeroAbsoluto = bufferNumeros.toIntOrNull()
        if (numeroAbsoluto != null && numeroAbsoluto > 0 && numeroAbsoluto <= listaCanales.size) {
            indiceActual = numeroAbsoluto - 1
            reproducirCanalActual()
        } else {
            Toast.makeText(this, "Canal no válido", Toast.LENGTH_SHORT).show()
        }
        bufferNumeros = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        verificarActualizacionRemota()

        val playerView = findViewById<PlayerView>(R.id.player_view)

        // Vincular componentes del nuevo estilo TiviMate
        tivimateInfoBar = findViewById(R.id.tivimate_info_bar)
        txtInfoNumber = findViewById(R.id.txt_info_number)
        txtInfoName = findViewById(R.id.txt_info_name)
        badgeResolution = findViewById(R.id.badge_resolution)
        badgeAudio = findViewById(R.id.badge_audio)
        badgeStatus = findViewById(R.id.badge_status)

        channelsListView = findViewById(R.id.channels_list_view)
        inputLayout = findViewById(R.id.input_layout)
        settingsLayout = findViewById(R.id.settings_layout)
        urlInput = findViewById(R.id.url_input)

        val playButton = findViewById<Button>(R.id.play_button)
        val btnMenuM3u = findViewById<Button>(R.id.btn_menu_m3u)
        val btnMenuColor = findViewById<Button>(R.id.btn_menu_color)
        val btnMenuSalir = findViewById<Button>(R.id.btn_menu_salir)

        // Configuración avanzada de reproducción
        val atributosAudio = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val factoriaInternet = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val factoriaMedios = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factoriaInternet)

        // 1. Crear un selector de códecs que decide en tiempo real si usa HW o SW
        val selectorDeCodecPersonalizado = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decodificadoresDisponibles = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)

            if (forzarSoftwareCanalActual) {
                // Si el canal está en modo SW, ordenamos la lista poniendo los decodificadores por software primero
                decodificadoresDisponibles.sortedBy { decoder ->
                    val esSoftware = decoder.name.startsWith("OMX.google.") ||
                            decoder.name.startsWith("c2.android.") ||
                            !decoder.hardwareAccelerated
                    if (esSoftware) 0 else 1 // 0 va primero, 1 va después
                }
            } else {
                // Modo Hardware por defecto (usa lo que el chip de la TV Box mande)
                decodificadoresDisponibles
            }
        }

// 2. Inyectar el selector en la configuración de renderizadores
        val factoriaRenderizadores = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setMediaCodecSelector(selectorDeCodecPersonalizado)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, factoriaRenderizadores)
            .setMediaSourceFactory(factoriaMedios)
            .setAudioAttributes(atributosAudio, true)
            .build()

        playerView.player = player

        // ESCUCHADOR INTELIGENTE DE METADATOS TÉCNICOS
        player?.addListener(object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                super.onVideoSizeChanged(videoSize)
                val width = videoSize.width
                val height = videoSize.height

                val resolucionTexto = when {
                    width >= 3840 || height >= 2160 -> "4K UHD"
                    width >= 1920 || height >= 1080 -> "1080p FHD"
                    width >= 1280 || height >= 720 -> "720p HD"
                    width > 0 -> "${height}p SD"
                    else -> "SD"
                }
                badgeResolution.text = resolucionTexto
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                super.onTracksChanged(tracks)

                var codecAudioClean = "AUDIO"

                // CORREGIDO: Buscamos de forma segura el formato del track de audio activo
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val formatoAudio = group.getTrackFormat(i)
                                val mime = formatoAudio.sampleMimeType ?: ""
                                codecAudioClean = when {
                                    mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
                                    mime.contains("mpeg-L2", true) || mime.contains("mp2", true) || mime.contains("mpeg", true) -> "MPGA"
                                    mime.contains("ac3", true) || mime.contains("dolby", true) -> "AC3"
                                    else -> "AUDIO"
                                }
                                break
                            }
                        }
                    }
                }
                badgeAudio.text = codecAudioClean
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                handlerUI.removeCallbacks(ocultarInfoRunnable)
                badgeStatus.text = "ERROR"
                txtInfoName.text = "⚠️ CANAL SIN SEÑAL O FORMATO NO SOPORTADO"
                badgeResolution.text = "ERR"
                badgeAudio.text = "ERR"
                tivimateInfoBar.visibility = View.VISIBLE
            }
        })

        // Preferencias de almacenamiento local
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val urlGuardada = sharedPreferences.getString("m3u_url", "")
        val colorGuardado = sharedPreferences.getString("theme_color", "#0D6EFD")

        channelsListView.selector = ColorDrawable(android.graphics.Color.parseColor(colorGuardado))
        txtInfoNumber.setTextColor(android.graphics.Color.parseColor(colorGuardado))
        indiceColorActual = listaColores.indexOf(colorGuardado).let { if (it == -1) 0 else it }

        if (!urlGuardada.isNullOrEmpty()) {
            inputLayout.visibility = View.GONE
            cargarLista(urlGuardada)
        } else {
            inputLayout.visibility = View.VISIBLE
            urlInput.requestFocus()
        }

        btnMenuM3u.setOnClickListener {
            settingsLayout.visibility = View.GONE
            urlInput.setText(sharedPreferences.getString("m3u_url", ""))
            inputLayout.visibility = View.VISIBLE
            urlInput.requestFocus()
        }

        btnMenuColor.setOnClickListener {
            indiceColorActual = (indiceColorActual + 1) % listaColores.size
            val nuevoColor = listaColores[indiceColorActual]
            sharedPreferences.edit().putString("theme_color", nuevoColor).apply()

            channelsListView.selector = ColorDrawable(android.graphics.Color.parseColor(nuevoColor))
            txtInfoNumber.setTextColor(android.graphics.Color.parseColor(nuevoColor))
            Toast.makeText(this, "Color del reproductor actualizado", Toast.LENGTH_SHORT).show()
        }

        btnMenuSalir.setOnClickListener { finish() }

        playButton.setOnClickListener {
            val nuevaUrl = urlInput.text.toString().trim()
            if (nuevaUrl.isNotEmpty()) {
                sharedPreferences.edit().putString("m3u_url", nuevaUrl).apply()
                inputLayout.visibility = View.GONE
                cargarLista(nuevaUrl)
            }
        }

        channelsListView.setOnItemClickListener { _, _, position, _ ->
            indiceActual = position
            reproducirCanalActual()
            channelsListView.visibility = View.GONE
        }
    }

    private fun cargarLista(url: String) {
        lifecycleScope.launch {
            listaCanales = obtenerCanalesM3U(url)
            if (listaCanales.isNotEmpty()) {
                val nombres = listaCanales.map { "${listaCanales.indexOf(it) + 1}. ${it.nombre}" }

                val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, nombres) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val vista = super.getView(position, convertView, parent) as TextView
                        vista.setTextColor(android.graphics.Color.WHITE)
                        vista.textSize = 20f
                        vista.setPadding(25, 20, 25, 20)
                        return vista
                    }
                }

                channelsListView.adapter = adapter
                indiceActual = 0
                reproducirCanalActual()
            } else {
                Toast.makeText(this@MainActivity, "Error al procesar la lista M3U", Toast.LENGTH_LONG).show()
                inputLayout.visibility = View.VISIBLE
                urlInput.requestFocus()
            }
        }
    }

    private fun reproducirCanalActual() {
        if (listaCanales.isEmpty()) return
        val canal = listaCanales[indiceActual]

        // LEER PREFERENCIA DEL CODEC: ¿Este canal específico usa Software?
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        forzarSoftwareCanalActual = sharedPreferences.getBoolean("sw_${canal.url}", false)

        // Actualizar el estado visual en la barra TiviMate
        badgeResolution.text = "Cargando..."
        badgeAudio.text = "..."
        badgeStatus.text = if (forzarSoftwareCanalActual) "LIVE STREAM (SW)" else "LIVE STREAM (HW)"

        val mediaItem = MediaItem.fromUri(canal.url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()

        handlerUI.removeCallbacks(ocultarInfoRunnable)
        txtInfoNumber.text = (indiceActual + 1).toString()
        txtInfoName.text = canal.nombre

        tivimateInfoBar.visibility = View.VISIBLE
        handlerUI.postDelayed(ocultarInfoRunnable, 5000)
    }

    private suspend fun obtenerCanalesM3U(url: String): List<Canal> {
        return withContext(Dispatchers.IO) {
            val resultado = mutableListOf<Canal>()
            try {
                val lines = URL(url).readText().lines()
                var nombreActual = "Canal Desconocido"
                for (line in lines) {
                    val lineaLimpia = line.trim()
                    if (lineaLimpia.startsWith("#EXTINF")) {
                        val comaIndex = lineaLimpia.lastIndexOf(",")
                        if (comaIndex != -1) nombreActual = lineaLimpia.substring(comaIndex + 1).trim()
                    } else if (lineaLimpia.startsWith("http")) {
                        resultado.add(Canal(nombreActual, lineaLimpia))
                        nombreActual = "Canal Desconocido"
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            resultado
        }
    }

    private fun verificarActualizacionRemota() {
        val urlApiGithub = "https://api.github.com/repos/emeaplay/m3utv-update/releases/latest"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(urlApiGithub)
                val conexion = url.openConnection() as java.net.HttpURLConnection

                conexion.setRequestProperty("User-Agent", "M3UTV-App-Updater")
                conexion.connectTimeout = 5000
                conexion.readTimeout = 5000

                if (conexion.responseCode == 200) {
                    val infoRemota = conexion.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(infoRemota)

                    val tagVersion = json.getString("tag_name").replace("v", "").trim()
                    val versionNuevaCode = tagVersion.toIntOrNull() ?: 0

                    val assets = json.getJSONArray("assets")
                    var urlApk = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val nombreArchivo = asset.getString("name")
                        if (nombreArchivo.endsWith(".apk")) {
                            urlApk = asset.getString("browser_download_url")
                            break
                        }
                    }

                    val infoPaquete = packageManager.getPackageInfo(packageName, 0)
                    val versionActualCode = PackageInfoCompat.getLongVersionCode(infoPaquete).toInt()

                    if (versionNuevaCode > versionActualCode && urlApk.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            mostrarDialogoActualizacion(urlApk)
                        }
                    }
                }
                conexion.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun mostrarDialogoActualizacion(urlApk: String) {
        AlertDialog.Builder(this)
            .setTitle("Actualización Disponible")
            .setMessage("Hay una nueva versión de M3U TV disponible. ¿Deseas descargarla e instalarla ahora?")
            .setPositiveButton("Actualizar") { _, _ ->
                descargarEInstalarApk(urlApk)
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun descargarEInstalarApk(urlApk: String) {
        Toast.makeText(this, "Descargando actualización...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val archivoApk = java.io.File(externalCacheDir, "update.apk")
                URL(urlApk).openStream().use { input -> java.io.FileOutputStream(archivoApk).use { output -> input.copyTo(output) } }
                withContext(Dispatchers.Main) {
                    val uriApk = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", archivoApk)
                    val intentInstalar = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uriApk, "application/vnd.android.package-archive")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(intentInstalar)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Esto le dice al sistema que empiece a contar el tiempo para un posible LongPress
        event?.startTracking()
        if (inputLayout.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                inputLayout.visibility = View.GONE
                settingsLayout.visibility = View.VISIBLE
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (settingsLayout.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                settingsLayout.visibility = View.GONE
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (channelsListView.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    channelsListView.visibility = View.GONE
                    settingsLayout.visibility = View.VISIBLE
                    settingsLayout.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    val actual = channelsListView.selectedItemPosition
                    if (actual > 0) channelsListView.setSelection(actual - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    val actual = channelsListView.selectedItemPosition
                    if (actual < listaCanales.size - 1) channelsListView.setSelection(actual + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (listaCanales.isNotEmpty()) {
                        // SI ES UNA PULSACIÓN LARGA: Cambia el codec de este canal
                        if (event?.isLongPress == true) {
                            val canal = listaCanales[indiceActual]
                            val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

                            // Invertir el estado actual
                            val nuevoEstadoSW = !forzarSoftwareCanalActual
                            sharedPreferences.edit().putBoolean("sw_${canal.url}", nuevoEstadoSW).apply()

                            Toast.makeText(this, "Cambiando a modo ${if (nuevoEstadoSW) "SOFTWARE (SW)" else "HARDWARE (HW)"}...", Toast.LENGTH_SHORT).show()

                            // Reiniciar el canal para aplicar el nuevo codec
                            reproducirCanalActual()
                            return true
                        }

                        // SI ES UNA PULSACIÓN CORTA: Abre la lista de canales (comportamiento normal)
                        if (channelsListView.visibility != View.VISIBLE) {
                            channelsListView.visibility = View.VISIBLE
                            channelsListView.requestFocus()
                            channelsListView.setSelection(indiceActual)
                        }
                    }
                    return true
                }
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                settingsLayout.visibility = View.VISIBLE
                settingsLayout.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (listaCanales.isNotEmpty()) {
                    indiceActual = (indiceActual + 1) % listaCanales.size
                    reproducirCanalActual()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (listaCanales.isNotEmpty()) {
                    indiceActual = if (indiceActual - 1 < 0) listaCanales.size - 1 else indiceActual - 1
                    reproducirCanalActual()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (listaCanales.isNotEmpty()) {
                    channelsListView.visibility = View.VISIBLE
                    channelsListView.requestFocus()
                    channelsListView.setSelection(indiceActual)
                }
                return true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (listaCanales.isNotEmpty()) {
                    val digito = keyCode - KeyEvent.KEYCODE_0
                    bufferNumeros += digito
                    handlerUI.removeCallbacks(ocultarInfoRunnable)

                    txtInfoNumber.text = bufferNumeros
                    txtInfoName.text = "Buscando canal..."
                    badgeResolution.text = "---"
                    badgeAudio.text = "---"
                    tivimateInfoBar.visibility = View.VISIBLE

                    handlerUI.removeCallbacks(cambiarPorNumeroRunnable)
                    handlerUI.postDelayed(cambiarPorNumeroRunnable, 2000)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}