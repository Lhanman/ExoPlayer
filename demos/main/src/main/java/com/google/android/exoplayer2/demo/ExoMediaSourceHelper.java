package com.google.android.exoplayer2.demo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.AssetDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.Util;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

public final class ExoMediaSourceHelper {

    private static final String TAG = "ExoMediaSourceHelper";

    public static final int TYPE_RTMP = 14;

    private static ExoMediaSourceHelper sInstance;

    private final String mUserAgent;
    private final Context mAppContext;
    private HttpDataSource.Factory mHttpDataSourceFactory;
    private Cache mCache;
    private Map<String, String> mMapHeadData;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
        mUserAgent = Util.getUserAgent(mAppContext, mAppContext.getApplicationInfo().name);
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        this.mMapHeadData = headers;
        MediaSource mediaSource = null;
        Uri contentUri = Uri.parse(uri);
        MediaItem mediaItem = MediaItem.fromUri(contentUri);
        int contentType = inferContentType(uri);
        String uerAgent = null;
        if (headers != null) {
            uerAgent = headers.get("User-Agent");
        }
        if ("android.resource".equals(contentUri.getScheme())) {
            DataSpec dataSpec = new DataSpec(contentUri);
            final RawResourceDataSource rawResourceDataSource = new RawResourceDataSource(mAppContext);
            try {
                rawResourceDataSource.open(dataSpec);
            } catch (RawResourceDataSource.RawResourceDataSourceException e) {
                e.printStackTrace();
            }
            DataSource.Factory factory = new DataSource.Factory() {
                @Override
                public DataSource createDataSource() {
                    return rawResourceDataSource;
                }
            };
            return new ProgressiveMediaSource.Factory(
                    factory).createMediaSource(mediaItem);

        } else if ("assets".equals(contentUri.getScheme())) {
            DataSpec dataSpec = new DataSpec(contentUri);
            final AssetDataSource rawResourceDataSource = new AssetDataSource(mAppContext);
            try {
                rawResourceDataSource.open(dataSpec);
            } catch (Exception e) {
                e.printStackTrace();
            }
            DataSource.Factory factory = new DataSource.Factory() {
                @Override
                public DataSource createDataSource() {
                    return rawResourceDataSource;
                }
            };
            return new ProgressiveMediaSource.Factory(
                    factory).createMediaSource(mediaItem);
        }

        DataSource.Factory factory;
        if (isCache) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }

        switch (contentType) {
            case C.CONTENT_TYPE_SS:
                mediaSource = new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(factory),
                        new DefaultDataSource.Factory(mAppContext,
                                getHttpDataSourceFactory())).createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_RTSP:
                RtspMediaSource.Factory rtspFactory = new RtspMediaSource.Factory();
                if (uerAgent != null) {
                    rtspFactory.setUserAgent(uerAgent);
                }
                rtspFactory.setForceUseRtpTcp(true);
                mediaSource = rtspFactory.createMediaSource(mediaItem);
                break;

            case C.CONTENT_TYPE_DASH:
                mediaSource = new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(factory),
                        new DefaultDataSource.Factory(mAppContext,
                                getHttpDataSourceFactory())).createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_HLS:
                mediaSource = new HlsMediaSource.Factory(factory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_OTHER:
            default:
                mediaSource = new ProgressiveMediaSource.Factory(factory, new DefaultExtractorsFactory())
                        .createMediaSource(mediaItem);
                break;
        }

        if (mHttpDataSourceFactory != null) {
            setHeaders(headers);
        }
        return mediaSource;
    }

    private int inferContentType(String fileName) {
        fileName = fileName.toLowerCase();
        if (fileName.startsWith("rtmp:")) {
            return TYPE_RTMP;
        } else {
            return inferContentType(Uri.parse(fileName));
        }
    }

    private int inferContentType(Uri uri) {
        return Util.inferContentType(uri);
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private Cache newCache() {
        return new SimpleCache(
                new File(mAppContext.getExternalCacheDir(), "exo-video-cache"),//缓存目录
                new LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024),//缓存大小，默认512M，使用LRU算法实现
                new ExoDatabaseProvider(mAppContext));
    }

    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        return new DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory());
    }

    private DataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            int connectTimeout = DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS;
            int readTimeout = DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS;
//            boolean allowCrossProtocolRedirects = false;
//            if (mMapHeadData != null && mMapHeadData.size() > 0) {
//                allowCrossProtocolRedirects = "true".equals(mMapHeadData.get("allowCrossProtocolRedirects"));
//            }
            DefaultHttpDataSource.Factory dataSourceFactory = null;
            dataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(connectTimeout)
                    .setReadTimeoutMs(readTimeout)
                    .setUserAgent(mUserAgent);
            if (mMapHeadData != null && mMapHeadData.size() > 0) {
                dataSourceFactory.setDefaultRequestProperties(mMapHeadData);
            }
            mHttpDataSourceFactory = dataSourceFactory;
        }
        return mHttpDataSourceFactory;
    }

    private void setHeaders(Map<String, String> headers) {
        if (headers != null && headers.size() > 0) {
            //如果发现用户通过header传递了UA，则强行将HttpDataSourceFactory里面的userAgent字段替换成用户的
            if (headers.containsKey("User-Agent")) {
                String value = headers.remove("User-Agent");
                if (!TextUtils.isEmpty(value)) {
                    try {
                        Field userAgentField = mHttpDataSourceFactory.getClass().getDeclaredField("userAgent");
                        userAgentField.setAccessible(true);
                        userAgentField.set(mHttpDataSourceFactory, value);
                    } catch (Exception e) {
                        //ignore
                    }
                }
            }
            mHttpDataSourceFactory.setDefaultRequestProperties(headers);
        }
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
