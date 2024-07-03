package com.example.deteoemtemporeal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.deteoemtemporeal.ml.AutoModel1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class TelaDeteccao : AppCompatActivity() {
    // Declarações de variáveis globais:
    lateinit var textureView: TextureView // View para exibir o feed da câmera
    lateinit var cameraManager: CameraManager // Gerenciador de câmera do sistema
    lateinit var handler: Handler // Handler para processamento assíncrono
    lateinit var cameraDevice: CameraDevice // Representação da câmera do dispositivo
    lateinit var imageView: ImageView // ImageView para exibir o resultado da detecção
    lateinit var bitmap: Bitmap // Bitmap para armazenar o frame da câmera capturado
    lateinit var model: AutoModel1 // Modelo TensorFlow Lite para detecção de objetos
    lateinit var imageProcessor: ImageProcessor // Processador de imagens TensorFlow Lite
    val paint = Paint() // Objeto Paint para desenhar na tela
    var colors = listOf( // Lista de cores para desenhar caixas delimitadoras
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )
    lateinit var labels: List<String> // Lista de rótulos (classes) para as detecções
    var previewSize: Size? = null // Tamanho da visualização da câmera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teladeteccao)

        // Configuração para ajustar o layout sob as barras do sistema:
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.teladeteccao)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Solicita permissão para acessar a câmera
        get_permission()

        labels = FileUtil.loadLabels(this, "labels.txt") // Carrega rótulos (classes) a partir de um arquivo de texto
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build() // Configura o processador de imagens TensorFlow Lite
        model = AutoModel1.newInstance(this) // Inicializa o modelo TensorFlow Lite

        // Inicializa e da start em um thread para manipulação de vídeo (câmera)
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // Configura a ImageView para exibir o resultado da detecção
        imageView = findViewById(R.id.imageView)

        // Configura a TextureView para exibir o feed da câmera
        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            // Chamado quando a TextureView está pronta para ser usada
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                open_camera()
            }

            // Chamado quando o tamanho da TextureView é alterado
            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                adjustAspectRatio(textureView, previewSize, width, height) // Chama metodo que ajusta o aspect ratio da câmera
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            // Chamado quando a TextureView é atualizada com um novo frame da câmera
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                bitmap = textureView.bitmap!! // Captura o frame atual da câmera

                handler.post {
                    processImage(bitmap) // Processa a imagem capturada pelo modelo TensorFlow Lite
                }
            }
        }

        // Obtém o serviço de gerenciamento de câmera do sistema
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        model.close()
    }

    // Método para abrir a câmera
    @SuppressLint("MissingPermission")
    fun open_camera() {
        val cameraId = cameraManager.cameraIdList[0] // Obtém o ID da câmera
        val characteristics = cameraManager.getCameraCharacteristics(cameraId) // Características da câmera
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) // Configurações de stream da câmera
        previewSize = map!!.getOutputSizes(SurfaceTexture::class.java)[0] // Tamanho da visualização da câmera

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val surfaceTexture = textureView.surfaceTexture
                surfaceTexture!!.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
                val surface = Surface(surfaceTexture)

                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                // Cria uma sessão de captura para exibir o feed da câmera na TextureView
                cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(captureRequest.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Toast.makeText(this@TelaDeteccao, "Câmera desconectada!", Toast.LENGTH_SHORT).show()
                closeCamera()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Toast.makeText(this@TelaDeteccao, "Erro ao abrir a camera!", Toast.LENGTH_SHORT).show()
                closeCamera()
            }
        }, handler)
    }

    fun closeCamera() {
        try {
            cameraDevice.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun get_permission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_permission()
        }
    }

    // Método para ajustar o aspect ratio da TextureView conforme o tamanho da visualização da câmera
    private fun adjustAspectRatio(textureView: TextureView, previewSize: Size?, width: Int, height: Int) {
        previewSize?.let {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val aspectRatio = it.height.toFloat() / it.width.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (viewHeight > viewWidth * aspectRatio) {
                newWidth = viewWidth.toInt()
                newHeight = (viewWidth * aspectRatio).toInt()
            } else {
                newWidth = (viewHeight / aspectRatio).toInt()
                newHeight = viewHeight.toInt()
            }
            val xOff = (viewWidth - newWidth) / 2
            val yOff = (viewHeight - newHeight) / 2
            val tx = Matrix()
            textureView.getTransform(tx)
            tx.setScale(newWidth / viewWidth, newHeight / viewHeight)
            tx.postTranslate(xOff, yOff)
            textureView.setTransform(tx)
        }
    }

    // Método para processar a imagem capturada pela câmera
    private fun processImage(bitmap: Bitmap) {
        var image = TensorImage.fromBitmap(bitmap)
        image = imageProcessor.process(image) // Processa a imagem com o processador TensorFlow Lite

        // Executa a inferência do modelo TensorFlow Lite na imagem processada
        val outputs = model.process(image)
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray
        val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray

        // Cria um bitmap mutável para desenhar as detecções
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val h = mutable.height
        val w = mutable.width

        // Configurações de pintura para desenhar caixas delimitadoras e texto
        paint.textSize = h / 15f
        paint.strokeWidth = h / 85f
        var x = 0
        scores.forEachIndexed { index, fl ->
            x = index
            x *= 4
            if (fl > 0.5) { // Desenha apenas as detecções com confiança acima de 0.5
                paint.color = colors[index]
                paint.style = Paint.Style.STROKE
                canvas.drawRect(
                    RectF(
                        locations[x + 1] * w,
                        locations[x] * h,
                        locations[x + 3] * w,
                        locations[x + 2] * h
                    ), paint
                )
                paint.style = Paint.Style.FILL
                canvas.drawText(
                    "${labels[classes[index].toInt()]} $fl",
                    locations[x + 1] * w,
                    locations[x] * h,
                    paint
                )
            }
        }

        // Atualiza a ImageView com o bitmap contendo as detecções
        runOnUiThread {
            imageView.setImageBitmap(mutable)
        }
    }
}
