package com.duoshine.camerax

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

/**
 *Created by duoShine on 2021/7/6
 */
class AvcEncoder {

    private lateinit var mediaCodec: MediaCodec
    private val TIMEOUT_USEC = 12000
    private lateinit var configByte: ByteArray
    private var width: Int = 0
    private var height: Int = 0
    private var isRunning: Boolean = false
    private val YUVQueue = ArrayBlockingQueue<ByteArray>(20)

    private var fs: FileOutputStream? = null

    fun config(size: Size, file: File) {
        fs = FileOutputStream(file)

        width = size.width
        height = size.height
        val mediaFormat = MediaFormat.createVideoFormat("video/avc", height, width) //因为图片旋转了，所以宽高互换
        mediaFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8 * 1024 * 1024) //码率，码率越高，视频越大，清晰度越高。
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30) //帧率，每秒帧数。帧数越高则越流畅。视频越大。
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        //配置编码器参数
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        //启动编码器
        mediaCodec.start()
        YUVQueue.clear()
        startEncoderThread()
    }

    fun addPlanes(data: ByteArray) {
        if (isRunning && YUVQueue.size <= 19) {
            YUVQueue.add(data)
        }
    }

    fun stop() {
        //停止编码器
        isRunning = false
    }

    /**
     * nv21 to h264
     */
    private fun startEncoderThread() {
        val encoderThread = Thread {
            isRunning = true
            var nv12: ByteArray? = null
            var pts: Long = 0
            while (isRunning) {
                if (YUVQueue.size > 0) {
                    //从缓冲队列中取出一帧
                    val yuv = YUVQueue.poll()
                    //旋转90度 元数据默认90度旋转
                    val rotateYUV420Degree90 = rotateYUV420Degree90(yuv, width, height)
                    nv12 = ByteArray(rotateYUV420Degree90.size)
                    //nv21 to nv12
                    NV21ToNV12(rotateYUV420Degree90, nv12, width, height)
                    try {
                        //编码器输入缓冲区
                        val inputBuffers = mediaCodec.inputBuffers
                        //编码器输出缓冲区
                        val outputBuffers = mediaCodec.outputBuffers
                        val inputBufferIndex = mediaCodec.dequeueInputBuffer(-1)
                        if (inputBufferIndex >= 0) {
                            pts = System.currentTimeMillis()
                            val inputBuffer = inputBuffers[inputBufferIndex]
                            inputBuffer.clear()
                            //把转换后的YUV420格式的视频帧放到编码器输入缓冲区中
                            inputBuffer.put(nv12)
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, nv12.size, pts, 0)
                        }
                        val bufferInfo = MediaCodec.BufferInfo()
                        var outputBufferIndex =
                            mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC.toLong())
                        while (outputBufferIndex >= 0) {
                            val outputBuffer = outputBuffers[outputBufferIndex]
                            val outData = ByteArray(bufferInfo.size)
                            outputBuffer[outData]
                            if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                                configByte = ByteArray(bufferInfo.size)
                                configByte = outData
                            } else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                                val keyframe = ByteArray(bufferInfo.size + configByte.size)
                                System.arraycopy(
                                    configByte,
                                    0,
                                    keyframe,
                                    0,
                                    configByte.size
                                )
                                //把编码后的视频帧从编码器输出缓冲区中拷贝出来
                                System.arraycopy(
                                    outData,
                                    0,
                                    keyframe,
                                    configByte.size,
                                    outData.size
                                )
                                Log.d("duoshine", "startEncoderThread: ${keyframe.size}")
                                fs?.write(keyframe)
                            } else {
                                fs?.write(outData)
                            }
                            mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                            outputBufferIndex =
                                mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC.toLong())
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }
            fs?.close()
            stopEncoder()
        }
        encoderThread.start()
    }

    private fun stopEncoder() {
        try {
            mediaCodec.stop()
            mediaCodec.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun NV21ToNV12(nv21: ByteArray?, nv12: ByteArray?, width: Int, height: Int) {
        if (nv21 == null || nv12 == null) return
        val framesize = width * height
        var i = 0
        var j = 0
        System.arraycopy(nv21, 0, nv12, 0, framesize)
        i = 0
        while (i < framesize) {
            nv12[i] = nv21[i]
            i++
        }
        j = 0
        while (j < framesize / 2) {
            nv12[framesize + j - 1] = nv21[j + framesize]
            j += 2
        }
        j = 0
        while (j < framesize / 2) {
            nv12[framesize + j] = nv21[j + framesize - 1]
            j += 2
        }
    }

    /**
     * 对相机输出图片90旋转
     *
     * @param data
     * @param imageWidth
     * @param imageHeight
     * @return
     */
    fun rotateYUV420Degree90(data: ByteArray, imageWidth: Int, imageHeight: Int): ByteArray {
        val yuv = ByteArray(imageWidth * imageHeight * 3 / 2)
        // Rotate the Y luma
        var i = 0
        for (x in 0 until imageWidth) {
            for (y in imageHeight - 1 downTo 0) {
                yuv[i] = data[y * imageWidth + x]
                i++
            }
        }
        // Rotate the U and V color components
        i = imageWidth * imageHeight * 3 / 2 - 1
        var x = imageWidth - 1
        while (x > 0) {
            for (y in 0 until imageHeight / 2) {
                yuv[i] = data[imageWidth * imageHeight + y * imageWidth + x]
                i--
                yuv[i] = data[imageWidth * imageHeight + y * imageWidth + (x - 1)]
                i--
            }
            x = x - 2
        }
        return yuv
    }
}