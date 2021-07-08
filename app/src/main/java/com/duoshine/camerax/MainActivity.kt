package com.duoshine.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.internal.utils.ImageUtil
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File


class MainActivity : AppCompatActivity() {
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var TAG = "MainActivity_"
    private var avcEncoder: AvcEncoder? = null
    private var imageAnalysis: ImageAnalysis? = null

    /**
     * 默认分辨率。他最好是一个比较适配主流机型的值，否则CameraX将会自动降低他,
     * 注意别写死了导致编码失败 demo中[customRecording]方法处理了size不支持的情况
     *
     */
    private var size: Size = Size(1920, 1080)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestPermission()
        initCamera()
        initView()
    }

    private fun initView() {
        h264Record.setOnClickListener {
            customRecording()
        }

        disableH264Record.setOnClickListener {
            customDisableStopRecording()
        }
    }

    /**
     * 结束录制
     */
    private fun customDisableStopRecording() {
        //取消分析器
        imageAnalysis!!.clearAnalyzer()
        //停止h264编码
        avcEncoder?.stop()
        avcEncoder = null
    }

    /**
     * 开始录制
     */
    @SuppressLint("RestrictedApi")
    private fun customRecording() {
        //保存为h264
        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            "${System.currentTimeMillis()}.h264"
        )
        if (!file.exists()) {
            file.createNewFile()
        }

        imageAnalysis?.let {
            it.setAnalyzer(mainExecutor, ImageAnalysis.Analyzer { image ->
                if (avcEncoder == null) {
                    avcEncoder = AvcEncoder()
                    //如果设置的默认分辨率不支持，则使用自动选择的分辨率，否则编码器将会报错
                    if (image.width != size.width) {
                        Log.d(TAG, "customRecording: 不支持默认分辨率，新的分辨率为：${image.width }")
                        size = Size(image.width,image.height)
                    }
                    avcEncoder?.config(size, file)
                    avcEncoder?.start()
                }
                Log.d(TAG, "width: ${image.width}")
                Log.d(TAG, "height: ${image.height}")
                //编码为h624
                avcEncoder?.addPlanes(ImageUtil.yuv_420_888toNv21(image))
                image.close()
            })
        }
    }

    private fun initCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        //请求 CameraProvider 后，请验证它能否在视图创建后成功初始化。以下代码展示了如何执行此操作：
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    fun bindPreview(cameraProvider: ProcessCameraProvider) {
        //预览
        var preview: Preview = Preview.Builder()
            .build()

//        选择相机
        var cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK) //后置
            .build()

//        提供previewView预览控件
        preview.setSurfaceProvider(previewView.surfaceProvider)

//        图片分析
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(size) //设置分辨率
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)  //阻塞模式，setAnalyzer过于耗时会导致预览卡顿
            .setTargetRotation(Surface.ROTATION_90)
            .build()

//        在绑定之前 你应该先解绑
        cameraProvider.unbindAll()
        var camera = cameraProvider.bindToLifecycle(
            this as LifecycleOwner,
            cameraSelector,
            imageAnalysis,
            preview
        )
    }

    /*
      请求授权
       */
    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            val permissions = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO

            )
            val permissionsList = ArrayList<String>()
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionsList.add(permission)
                }
            }
            if (permissionsList.size != 0) {
                val permissionsArray = permissionsList.toTypedArray()
                ActivityCompat.requestPermissions(
                    this, permissionsArray,
                    22
                )
            }
        }
    }
}