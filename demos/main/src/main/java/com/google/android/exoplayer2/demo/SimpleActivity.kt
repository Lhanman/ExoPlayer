package com.google.android.exoplayer2.demo

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.MediaSource
import java.io.File

class SimpleActivity : AppCompatActivity() {
    private var player: ExoPlayer?=null
    private var mRenderersFactory: DefaultRenderersFactory? = null
    private var mMediaSource: MediaSource? = null
    private var path = "https://lhanman2.oss-cn-shenzhen.aliyuncs.com/%E7%83%A4%E9%B8%AD%20clip.mp4?OSSAccessKeyId=LTAI5t9sSTU59t8ZnwiRuyLt&Expires=1727427540&Signature=iHFOaDhe62xxCvGg792b9MbkJnE%3D"
    private var surfaceView: SurfaceView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)
        initRenderFactoryIfNeed()
        surfaceView = findViewById(R.id.surfaceView)
        surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player = ExoPlayer.Builder(
                        this@SimpleActivity,
                        mRenderersFactory!!)
                        .build();
                player?.setVideoSurface(holder.surface)
                //不采用缓存机制
                path = this@SimpleActivity.cacheDir.absolutePath + File.separator + "kaoya.mp4"
                mMediaSource = ExoMediaSourceHelper.getInstance(this@SimpleActivity).getMediaSource(path, false)
                player?.setMediaSource(mMediaSource!!)
                player?.playWhenReady = true
                player?.prepare()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })
    }

    private fun initRenderFactoryIfNeed() {
        if (mRenderersFactory == null) {
            mRenderersFactory = DefaultRenderersFactory(this)
            //当API >21 时强制走异步解码器实现、否则走同步解码器实现
            (mRenderersFactory as DefaultRenderersFactory).forceEnableMediaCodecAsynchronousQueueing()
            (mRenderersFactory as DefaultRenderersFactory).setEnableDecoderFallback(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }


}