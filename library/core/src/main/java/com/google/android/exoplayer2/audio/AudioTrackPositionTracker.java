/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.audio;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Util.castNonNull;
import static com.google.android.exoplayer2.util.Util.durationUsToSampleCount;
import static com.google.android.exoplayer2.util.Util.sampleCountToDurationUs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.SystemClock;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

/**
 * Wraps an {@link AudioTrack}, exposing a position based on {@link
 * AudioTrack#getPlaybackHeadPosition()} and {@link AudioTrack#getTimestamp(AudioTimestamp)}.
 *
 * <p>Call {@link #setAudioTrack(AudioTrack, boolean, int, int, int)} to set the audio track to
 * wrap. Call {@link #mayHandleBuffer(long)} if there is input data to write to the track. If it
 * returns false, the audio track position is stabilizing and no data may be written. Call {@link
 * #start()} immediately before calling {@link AudioTrack#play()}. Call {@link #pause()} when
 * pausing the track. Call {@link #handleEndOfStream(long)} when no more data will be written to the
 * track. When the audio track will no longer be used, call {@link #reset()}.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class AudioTrackPositionTracker {

  /** Listener for position tracker events. */
  public interface Listener {

    /**
     * Called when the position tracker's position has increased for the first time since it was
     * last paused or reset.
     *
     * @param playoutStartSystemTimeMs The approximate derived {@link System#currentTimeMillis()} at
     *     which playout started.
     */
    void onPositionAdvancing(long playoutStartSystemTimeMs);

    /**
     * Called when the frame position is too far from the expected frame position.
     *
     * @param audioTimestampPositionFrames The frame position of the last known audio track
     *     timestamp.
     * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
     *     timestamp, in microseconds.
     * @param systemTimeUs The current time.
     * @param playbackPositionUs The current playback head position in microseconds.
     */
    void onPositionFramesMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs);

    /**
     * Called when the system time associated with the last known audio track timestamp is
     * unexpectedly far from the current time.
     *
     * @param audioTimestampPositionFrames The frame position of the last known audio track
     *     timestamp.
     * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
     *     timestamp, in microseconds.
     * @param systemTimeUs The current time.
     * @param playbackPositionUs The current playback head position in microseconds.
     */
    void onSystemTimeUsMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs);

    /**
     * Called when the audio track has provided an invalid latency.
     *
     * @param latencyUs The reported latency in microseconds.
     */
    void onInvalidLatency(long latencyUs);

    /**
     * Called when the audio track runs out of data to play.
     *
     * @param bufferSize The size of the sink's buffer, in bytes.
     * @param bufferSizeMs The size of the sink's buffer, in milliseconds, if it is configured for
     *     PCM output. {@link C#TIME_UNSET} if it is configured for encoded audio output, as the
     *     buffered media can have a variable bitrate so the duration may be unknown.
     */
    void onUnderrun(int bufferSize, long bufferSizeMs);
  }

  /** {@link AudioTrack} playback states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({PLAYSTATE_STOPPED, PLAYSTATE_PAUSED, PLAYSTATE_PLAYING})
  private @interface PlayState {}
  /**
   * @see AudioTrack#PLAYSTATE_STOPPED
   */
  private static final int PLAYSTATE_STOPPED = AudioTrack.PLAYSTATE_STOPPED;
  /**
   * @see AudioTrack#PLAYSTATE_PAUSED
   */
  private static final int PLAYSTATE_PAUSED = AudioTrack.PLAYSTATE_PAUSED;
  /**
   * @see AudioTrack#PLAYSTATE_PLAYING
   */
  private static final int PLAYSTATE_PLAYING = AudioTrack.PLAYSTATE_PLAYING;

  /**
   * AudioTrack timestamps are deemed spurious if they are offset from the system clock by more than
   * this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_AUDIO_TIMESTAMP_OFFSET_US = 5 * C.MICROS_PER_SECOND;

  /**
   * AudioTrack latencies are deemed impossibly large if they are greater than this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_LATENCY_US = 5 * C.MICROS_PER_SECOND;
  /** The duration of time used to smooth over an adjustment between position sampling modes. */
  private static final long MODE_SWITCH_SMOOTHING_DURATION_US = C.MICROS_PER_SECOND;

  /** Minimum update interval for getting the raw playback head position, in milliseconds. */
  private static final long RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS = 5;

  private static final long FORCE_RESET_WORKAROUND_TIMEOUT_MS = 200;

  private static final int MAX_PLAYHEAD_OFFSET_COUNT = 10;
  private static final int MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US = 30_000;
  private static final int MIN_LATENCY_SAMPLE_INTERVAL_US = 50_0000;

  private final Listener listener;
  private final long[] playheadOffsets;

  @Nullable private AudioTrack audioTrack;
  private int outputPcmFrameSize;
  private int bufferSize;
  @Nullable private AudioTimestampPoller audioTimestampPoller;
  private int outputSampleRate;
  private boolean needsPassthroughWorkarounds;
  private long bufferSizeUs;
  private float audioTrackPlaybackSpeed;
  private boolean notifiedPositionIncreasing;

  private long smoothedPlayheadOffsetUs;
  private long lastPlayheadSampleTimeUs;

  @Nullable private Method getLatencyMethod;
  private long latencyUs;
  private boolean hasData;

  private boolean isOutputPcm;
  private long lastLatencySampleTimeUs;
  private long lastRawPlaybackHeadPositionSampleTimeMs;
  private long rawPlaybackHeadPosition;
  private long rawPlaybackHeadWrapCount;
  private long passthroughWorkaroundPauseOffset;
  private int nextPlayheadOffsetIndex;
  private int playheadOffsetCount;
  private long stopTimestampUs;
  private long forceResetWorkaroundTimeMs;
  private long stopPlaybackHeadPosition;
  private long endPlaybackHeadPosition;

  // Results from the previous call to getCurrentPositionUs.
  private long lastPositionUs;
  private long lastSystemTimeUs;
  private boolean lastSampleUsedGetTimestampMode;

  // Results from the last call to getCurrentPositionUs that used a different sample mode.
  private long previousModePositionUs;
  private long previousModeSystemTimeUs;

  /**
   * Creates a new audio track position tracker.
   *
   * @param listener A listener for position tracking events.
   */
  public AudioTrackPositionTracker(Listener listener) {
    this.listener = checkNotNull(listener);
    if (Util.SDK_INT >= 18) {
      try {
        getLatencyMethod = AudioTrack.class.getMethod("getLatency", (Class<?>[]) null);
      } catch (NoSuchMethodException e) {
        // There's no guarantee this method exists. Do nothing.
      }
    }
    playheadOffsets = new long[MAX_PLAYHEAD_OFFSET_COUNT];
  }

  /**
   * Sets the {@link AudioTrack} to wrap. Subsequent method calls on this instance relate to this
   * track's position, until the next call to {@link #reset()}.
   *
   * @param audioTrack The audio track to wrap.
   * @param isPassthrough Whether passthrough mode is being used.
   * @param outputEncoding The encoding of the audio track.
   * @param outputPcmFrameSize For PCM output encodings, the frame size. The value is ignored
   *     otherwise.
   * @param bufferSize The audio track buffer size in bytes.
   */
  public void setAudioTrack(
      AudioTrack audioTrack,
      boolean isPassthrough,
      @C.Encoding int outputEncoding,
      int outputPcmFrameSize,
      int bufferSize) {
    this.audioTrack = audioTrack;
    this.outputPcmFrameSize = outputPcmFrameSize;
    this.bufferSize = bufferSize;
    audioTimestampPoller = new AudioTimestampPoller(audioTrack);
    outputSampleRate = audioTrack.getSampleRate();
    needsPassthroughWorkarounds = isPassthrough && needsPassthroughWorkarounds(outputEncoding);
    isOutputPcm = Util.isEncodingLinearPcm(outputEncoding);
    bufferSizeUs =
        isOutputPcm
            ? sampleCountToDurationUs(bufferSize / outputPcmFrameSize, outputSampleRate)
            : C.TIME_UNSET;
    rawPlaybackHeadPosition = 0;
    rawPlaybackHeadWrapCount = 0;
    passthroughWorkaroundPauseOffset = 0;
    hasData = false;
    stopTimestampUs = C.TIME_UNSET;
    forceResetWorkaroundTimeMs = C.TIME_UNSET;
    lastLatencySampleTimeUs = 0;
    latencyUs = 0;
    audioTrackPlaybackSpeed = 1f;
  }

  public void setAudioTrackPlaybackSpeed(float audioTrackPlaybackSpeed) {
    this.audioTrackPlaybackSpeed = audioTrackPlaybackSpeed;
    // Extrapolation from the last audio timestamp relies on the audio rate being constant, so we
    // reset audio timestamp tracking and wait for a new timestamp.
    if (audioTimestampPoller != null) {
      audioTimestampPoller.reset();
    }
    resetSyncParams();
  }

  public long getCurrentPositionUs(boolean sourceEnded) {
    if (checkNotNull(this.audioTrack).getPlayState() == PLAYSTATE_PLAYING) {
      /* 三件重要的事：*/
      /* 1.根据AudioTrack.getPlaybackHeadPosition的值来计算平滑抖动值  */
      /* 2.校验timestamp 是否有效*/
      /* 3.校验latency */
      maybeSampleSyncParams();
    }

    // If the device supports it, use the playback timestamp from AudioTrack.getTimestamp.
    // Otherwise, derive a smoothed position by sampling the track's frame position.
    // 如果设备支持，请使用 AudioTrack.getTimestamp 中的播放时间戳。
    // 否则，通过采样轨道的帧位置来导出平滑位置。
    long systemTimeUs = System.nanoTime() / 1000;
    long positionUs;
    AudioTimestampPoller audioTimestampPoller = checkNotNull(this.audioTimestampPoller);
    // 判断是否 AudioTrack.getTimestamp 更新了时间戳
    boolean useGetTimestampMode = audioTimestampPoller.hasAdvancingTimestamp();
    // if分支为设备支持的timestamp模式,else为AudioTrack.getPlaybackHeadPosition获取的处理方式
    if (useGetTimestampMode) {
      // Calculate the speed-adjusted position using the timestamp (which may be in the future).
      // 得到最新调用getTimestamp()接口时拿到的写入帧数
      long timestampPositionFrames = audioTimestampPoller.getTimestampPositionFrames();
      // 将总帧数转化为持续时间，即当前音频播放位置的时间戳
      long timestampPositionUs = sampleCountToDurationUs(timestampPositionFrames, outputSampleRate);
      // 计算当前系统时间与底层音频更新帧数时系统时间的差值
      long elapsedSinceTimestampUs = systemTimeUs - audioTimestampPoller.getTimestampSystemTimeUs();
      // 对差值时间做一个校准，基于倍速进行校准。（毕竟 audioTimestampPoller.getTimestampSystemTimeUs 也是一个估计值，需要和当前时间进行一个矫正）
      elapsedSinceTimestampUs =
          Util.getMediaDurationForPlayoutDuration(elapsedSinceTimestampUs, audioTrackPlaybackSpeed);
      // 得到最新的音频时间戳（当前播放位置的时间戳 + 校正值 即为当前的播放位置时间戳）
      positionUs = timestampPositionUs + elapsedSinceTimestampUs;
    } else {
      // 走 AudioTrack.getPlaybackHeadPosition 获取播放位置
      if (playheadOffsetCount == 0) {
        // The AudioTrack has started, but we don't have any samples to compute a smoothed position.
        // AudioTrack 已经开始了，但是我们没有任何样本来计算平滑位置。直接获取
        positionUs = getPlaybackHeadPositionUs();
      } else {
        // getPlaybackHeadPositionUs() only has a granularity of ~20 ms, so we base the position off
        // the system clock (and a smoothed offset between it and the playhead position) so as to
        // prevent jitter in the reported positions.

        // getPlaybackHeadPositionUs() 的粒度只保证有约 20 毫秒，
        // 因此，我们根据系统时钟确定位置（以及它与播放头位置之间的平滑偏差）
        // 从而防止报告位置的抖动。
        positionUs =
            Util.getMediaDurationForPlayoutDuration(
                systemTimeUs + smoothedPlayheadOffsetUs, audioTrackPlaybackSpeed);
      }
      if (!sourceEnded) {
        //获取到的position还要再减去一个latency
        positionUs = max(0, positionUs - latencyUs);
      }
    }

    //如果模式有切换，做下保存
    if (lastSampleUsedGetTimestampMode != useGetTimestampMode) {
      // We've switched sampling mode.
      previousModeSystemTimeUs = lastSystemTimeUs;
      previousModePositionUs = lastPositionUs;
    }
    long elapsedSincePreviousModeUs = systemTimeUs - previousModeSystemTimeUs;
    if (elapsedSincePreviousModeUs < MODE_SWITCH_SMOOTHING_DURATION_US) {
      // Use a ramp to smooth between the old mode and the new one to avoid introducing a sudden
      // jump if the two modes disagree.
      //使用斜坡在旧模式和新模式之间进行平滑，以避免在两种模式不一致时引入突然跳跃。(模式切换的平滑处理）

      //计算旧模式下的预计位置
      long previousModeProjectedPositionUs =
          previousModePositionUs
              + Util.getMediaDurationForPlayoutDuration(
                  elapsedSincePreviousModeUs, audioTrackPlaybackSpeed);
      // A ramp consisting of 1000 points distributed over MODE_SWITCH_SMOOTHING_DURATION_US.
      //创建一个由1000个点组成的斜坡
      long rampPoint = (elapsedSincePreviousModeUs * 1000) / MODE_SWITCH_SMOOTHING_DURATION_US;
      //使用线性插值来平滑过渡从旧模式到新模式的位置（通过加权平均实现，线性插值）
      positionUs *= rampPoint;
      positionUs += (1000 - rampPoint) * previousModeProjectedPositionUs;
      positionUs /= 1000;
    }

    if (!notifiedPositionIncreasing && positionUs > lastPositionUs) {
      notifiedPositionIncreasing = true;
      long mediaDurationSinceLastPositionUs = Util.usToMs(positionUs - lastPositionUs);
      long playoutDurationSinceLastPositionUs =
          Util.getPlayoutDurationForMediaDuration(
              mediaDurationSinceLastPositionUs, audioTrackPlaybackSpeed);
      long playoutStartSystemTimeMs =
          System.currentTimeMillis() - Util.usToMs(playoutDurationSinceLastPositionUs);
      listener.onPositionAdvancing(playoutStartSystemTimeMs);
    }

    lastSystemTimeUs = systemTimeUs;
    lastPositionUs = positionUs;
    lastSampleUsedGetTimestampMode = useGetTimestampMode;

    return positionUs;
  }

  /** Starts position tracking. Must be called immediately before {@link AudioTrack#play()}. */
  public void start() {
    checkNotNull(audioTimestampPoller).reset();
  }

  /** Returns whether the audio track is in the playing state. */
  public boolean isPlaying() {
    return checkNotNull(audioTrack).getPlayState() == PLAYSTATE_PLAYING;
  }

  /**
   * Checks the state of the audio track and returns whether the caller can write data to the track.
   * Notifies {@link Listener#onUnderrun(int, long)} if the track has underrun.
   *
   * @param writtenFrames The number of frames that have been written.
   * @return Whether the caller can write data to the track.
   */
  public boolean mayHandleBuffer(long writtenFrames) {
    @PlayState int playState = checkNotNull(audioTrack).getPlayState();
    if (needsPassthroughWorkarounds) {
      // An AC-3 audio track continues to play data written while it is paused. Stop writing so its
      // buffer empties. See [Internal: b/18899620].
      if (playState == PLAYSTATE_PAUSED) {
        // We force an underrun to pause the track, so don't notify the listener in this case.
        hasData = false;
        return false;
      }

      // A new AC-3 audio track's playback position continues to increase from the old track's
      // position for a short time after is has been released. Avoid writing data until the playback
      // head position actually returns to zero.
      if (playState == PLAYSTATE_STOPPED && getPlaybackHeadPosition() == 0) {
        return false;
      }
    }

    boolean hadData = hasData;
    hasData = hasPendingData(writtenFrames);
    if (hadData && !hasData && playState != PLAYSTATE_STOPPED) {
      listener.onUnderrun(bufferSize, Util.usToMs(bufferSizeUs));
    }

    return true;
  }

  /**
   * Returns an estimate of the number of additional bytes that can be written to the audio track's
   * buffer without running out of space.
   *
   * <p>May only be called if the output encoding is one of the PCM encodings.
   *
   * @param writtenBytes The number of bytes written to the audio track so far.
   * @return An estimate of the number of bytes that can be written.
   */
  public int getAvailableBufferSize(long writtenBytes) {
    int bytesPending = (int) (writtenBytes - (getPlaybackHeadPosition() * outputPcmFrameSize));
    return bufferSize - bytesPending;
  }

  /** Returns whether the track is in an invalid state and must be recreated. */
  public boolean isStalled(long writtenFrames) {
    return forceResetWorkaroundTimeMs != C.TIME_UNSET
        && writtenFrames > 0
        && SystemClock.elapsedRealtime() - forceResetWorkaroundTimeMs
            >= FORCE_RESET_WORKAROUND_TIMEOUT_MS;
  }

  /**
   * Records the writing position at which the stream ended, so that the reported position can
   * continue to increment while remaining data is played out.
   *
   * @param writtenFrames The number of frames that have been written.
   */
  public void handleEndOfStream(long writtenFrames) {
    stopPlaybackHeadPosition = getPlaybackHeadPosition();
    stopTimestampUs = SystemClock.elapsedRealtime() * 1000;
    endPlaybackHeadPosition = writtenFrames;
  }

  /**
   * Returns whether the audio track has any pending data to play out at its current position.
   *
   * @param writtenFrames The number of frames written to the audio track.
   * @return Whether the audio track has any pending data to play out.
   */
  public boolean hasPendingData(long writtenFrames) {
    long currentPositionUs = getCurrentPositionUs(/* sourceEnded= */ false);
    return writtenFrames > durationUsToSampleCount(currentPositionUs, outputSampleRate)
        || forceHasPendingData();
  }

  /**
   * Pauses the audio track position tracker, returning whether the audio track needs to be paused
   * to cause playback to pause. If {@code false} is returned the audio track will pause without
   * further interaction, as the end of stream has been handled.
   */
  public boolean pause() {
    resetSyncParams();
    if (stopTimestampUs == C.TIME_UNSET) {
      // The audio track is going to be paused, so reset the timestamp poller to ensure it doesn't
      // supply an advancing position.
      checkNotNull(audioTimestampPoller).reset();
      return true;
    }
    // We've handled the end of the stream already, so there's no need to pause the track.
    return false;
  }

  /**
   * Resets the position tracker. Should be called when the audio track previously passed to {@link
   * #setAudioTrack(AudioTrack, boolean, int, int, int)} is no longer in use.
   */
  public void reset() {
    resetSyncParams();
    audioTrack = null;
    audioTimestampPoller = null;
  }

  private void maybeSampleSyncParams() {
    long systemTimeUs = System.nanoTime() / 1000;
    // 每 30ms 调用一次
    if (systemTimeUs - lastPlayheadSampleTimeUs >= MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US) {
      // 从 AudioTrack 获取播放时长
      long playbackPositionUs = getPlaybackHeadPositionUs();
      if (playbackPositionUs == 0) {
        // The AudioTrack hasn't output anything yet.
        return;
      }
      // Take a new sample and update the smoothed offset between the system clock and the playhead.
      // 用获取到的音频 pts - 系统时间作为基准差值
      playheadOffsets[nextPlayheadOffsetIndex] =
          Util.getPlayoutDurationForMediaDuration(playbackPositionUs, audioTrackPlaybackSpeed)
              - systemTimeUs;
      // 记录 10 次的差值
      nextPlayheadOffsetIndex = (nextPlayheadOffsetIndex + 1) % MAX_PLAYHEAD_OFFSET_COUNT;
      if (playheadOffsetCount < MAX_PLAYHEAD_OFFSET_COUNT) {
        playheadOffsetCount++;
      }
      lastPlayheadSampleTimeUs = systemTimeUs;
      smoothedPlayheadOffsetUs = 0;
      //将近 10次 的所有偏差平均到每次偏差中进行计算再累加，即得到最新的平滑抖动偏差值
      for (int i = 0; i < playheadOffsetCount; i++) {
        smoothedPlayheadOffsetUs += playheadOffsets[i] / playheadOffsetCount;
      }
    }

    if (needsPassthroughWorkarounds) {
      // Don't sample the timestamp and latency if this is an AC-3 passthrough AudioTrack on
      // platform API versions 21/22, as incorrect values are returned. See [Internal: b/21145353].
      return;
    }

    // 校验从 AudioTrack 获取的timestamp和系统时间及 getPlaybackHeadPosition 获取的时间
    maybePollAndCheckTimestamp(systemTimeUs);
    maybeUpdateLatency(systemTimeUs);
  }


  /**
   * 1. 确认底层是否更新了timestamp：
   *         首先，函数会检查底层系统是否已经更新了时间戳（timestamp）。时间戳通常用于记录特定事件的发生时间，这里指的是音频数据的播放或处理时间。
   * 2. 系统时间对比：如果底层系统更新了时间戳，函数会将这个最新的时间戳中的系统时间与应用层当前的系统时间进行对比。
   * 3. 时间差值判断：如果这两个时间的差值超过5秒，那么这个时间戳将不被接收。这意味着，如果时间戳记录的时间与当前时间相差超过5秒，这个时间戳可能已经过时或不准确，因此不被采用。
   * 4. 对比AudioTrack的时间差值：此外，函数还会对比AudioTrack.getTimeStamp和AudioTrack.getPlaybackHeadPosition这两个方法获取的时间差值。
   *         这两个方法都与音频播放的时间管理相关，getTimeStamp获取的是音频播放的时间戳，而getPlaybackHeadPosition获取的是当前播放头的位置。
   *         两个方法获取差距理论上不应该相差巨大，相差巨大会导致后续计算音频播放位置出现异常。
   * 5. 关键点：
   *     函数的核心在于理解底层系统是如何确定时间戳是否已经更新的，以及在 AudioTrack 支持时间戳模式的情况下，10秒的限制时间是如何确定的（maybePollTimestamp方法）。这可能涉及到底层音频处理机制和系统对时间戳更新的频率或策略。
   * @param systemTimeUs 当前系统时间
   */
  private void maybePollAndCheckTimestamp(long systemTimeUs) {
    //确认平台底层是否更新了timestamp
    AudioTimestampPoller audioTimestampPoller = checkNotNull(this.audioTimestampPoller);
    // 检查是否 AudioTrack 是否更新了时间戳
    if (!audioTimestampPoller.maybePollTimestamp(systemTimeUs)) {
      return;
    }

    // Check the timestamp and accept/reject it.
    long timestampSystemTimeUs = audioTimestampPoller.getTimestampSystemTimeUs();
    long timestampPositionFrames = audioTimestampPoller.getTimestampPositionFrames();
    // 确认 timestamp 更新时的系统时间和当前的系统时间是否差距大于 5s
    long playbackPositionUs = getPlaybackHeadPositionUs();
    if (Math.abs(timestampSystemTimeUs - systemTimeUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
      listener.onSystemTimeUsMismatch(
          timestampPositionFrames, timestampSystemTimeUs, systemTimeUs, playbackPositionUs);
      audioTimestampPoller.rejectTimestamp();
      //确认AudioTrack.getTimeStamp和AudioTrack.getPlaybackHeadPosition获取的时间差值是否大于5s
    } else if (Math.abs(
            sampleCountToDurationUs(timestampPositionFrames, outputSampleRate) - playbackPositionUs)
        > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
      listener.onPositionFramesMismatch(
          timestampPositionFrames, timestampSystemTimeUs, systemTimeUs, playbackPositionUs);
      audioTimestampPoller.rejectTimestamp();
    } else {
      audioTimestampPoller.acceptTimestamp();
    }
  }

  private void maybeUpdateLatency(long systemTimeUs) {
    if (isOutputPcm
        && getLatencyMethod != null
        && systemTimeUs - lastLatencySampleTimeUs >= MIN_LATENCY_SAMPLE_INTERVAL_US) {
      try {
        // Compute the audio track latency, excluding the latency due to the buffer (leaving
        // latency due to the mixer and audio hardware driver).
        // 这里减去 bufferSizeUs 是为了排除缓冲区造成的延迟（留下混音器和音频硬件驱动程序造成的延迟）。
        latencyUs =
            castNonNull((Integer) getLatencyMethod.invoke(checkNotNull(audioTrack))) * 1000L
                - bufferSizeUs;
        // Check that the latency is non-negative.
        latencyUs = max(latencyUs, 0);
        // Check that the latency isn't too large.
        if (latencyUs > MAX_LATENCY_US) {
          listener.onInvalidLatency(latencyUs);
          latencyUs = 0;
        }
      } catch (Exception e) {
        // The method existed, but doesn't work. Don't try again.
        getLatencyMethod = null;
      }
      lastLatencySampleTimeUs = systemTimeUs;
    }
  }

  private void resetSyncParams() {
    smoothedPlayheadOffsetUs = 0;
    playheadOffsetCount = 0;
    nextPlayheadOffsetIndex = 0;
    lastPlayheadSampleTimeUs = 0;
    lastSystemTimeUs = 0;
    previousModeSystemTimeUs = 0;
    notifiedPositionIncreasing = false;
  }

  /**
   * If passthrough workarounds are enabled, pausing is implemented by forcing the AudioTrack to
   * underrun. In this case, still behave as if we have pending data, otherwise writing won't
   * resume.
   */
  private boolean forceHasPendingData() {
    return needsPassthroughWorkarounds
        && checkNotNull(audioTrack).getPlayState() == AudioTrack.PLAYSTATE_PAUSED
        && getPlaybackHeadPosition() == 0;
  }

  /**
   * Returns whether to work around problems with passthrough audio tracks. See [Internal:
   * b/18899620, b/19187573, b/21145353].
   */
  private static boolean needsPassthroughWorkarounds(@C.Encoding int outputEncoding) {
    return Util.SDK_INT < 23
        && (outputEncoding == C.ENCODING_AC3 || outputEncoding == C.ENCODING_E_AC3);
  }

  private long getPlaybackHeadPositionUs() {
    return sampleCountToDurationUs(getPlaybackHeadPosition(), outputSampleRate);
  }

  /**
   * {@link AudioTrack#getPlaybackHeadPosition()} returns a value intended to be interpreted as an
   * unsigned 32 bit integer, which also wraps around periodically. This method returns the playback
   * head position as a long that will only wrap around if the value exceeds {@link Long#MAX_VALUE}
   * (which in practice will never happen).
   *
   * @return The playback head position, in frames.
   *
   * AudioTrack.getPlaybackHeadPosition()返回一个旨在解释为无符号 32 位整数的值，该值也会定期回绕。此方法将播放头位置返回为一个长整型，只有当该值超过Long.MAX_VALUE时才会回绕（实际上永远不会发生这种情况）
   */
  private long getPlaybackHeadPosition() {
    long currentTimeMs = SystemClock.elapsedRealtime();
    // 如果处于暂停状态，则计算暂停中到暂停结束的时间间隔内计算出的帧数，作为当前的播放位置。
    if (stopTimestampUs != C.TIME_UNSET) {
      // Simulate the playback head position up to the total number of frames submitted.
      long elapsedTimeSinceStopUs = (currentTimeMs * 1000) - stopTimestampUs;
      long mediaTimeSinceStopUs =
          Util.getMediaDurationForPlayoutDuration(elapsedTimeSinceStopUs, audioTrackPlaybackSpeed);
      long framesSinceStop = durationUsToSampleCount(mediaTimeSinceStopUs, outputSampleRate);
      return min(endPlaybackHeadPosition, stopPlaybackHeadPosition + framesSinceStop);
    }
    // 间隔5ms以上调用
    if (currentTimeMs - lastRawPlaybackHeadPositionSampleTimeMs
        >= RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS) {
      updateRawPlaybackHeadPosition(currentTimeMs);
      lastRawPlaybackHeadPositionSampleTimeMs = currentTimeMs;
    }
    // 返回播放帧数，这里处理是防止越界（AudioTrack的getPlaybackHeadPosition 是无符号32位，超过32位后，从0计数。大概率不会发生）
    return rawPlaybackHeadPosition + (rawPlaybackHeadWrapCount << 32);
  }

  private void updateRawPlaybackHeadPosition(long currentTimeMs) {
    AudioTrack audioTrack = checkNotNull(this.audioTrack);
    int state = audioTrack.getPlayState();
    if (state == PLAYSTATE_STOPPED) {
      // The audio track hasn't been started. Keep initial zero timestamp.
      return;
    }
    long rawPlaybackHeadPosition = 0xFFFFFFFFL & audioTrack.getPlaybackHeadPosition();
    if (needsPassthroughWorkarounds) {
      // Work around an issue with passthrough/direct AudioTracks on platform API versions 21/22
      // where the playback head position jumps back to zero on paused passthrough/direct audio
      // tracks. See [Internal: b/19187573].
      if (state == PLAYSTATE_PAUSED && rawPlaybackHeadPosition == 0) {
        passthroughWorkaroundPauseOffset = this.rawPlaybackHeadPosition;
      }
      rawPlaybackHeadPosition += passthroughWorkaroundPauseOffset;
    }

    if (Util.SDK_INT <= 29) {
      if (rawPlaybackHeadPosition == 0
          && this.rawPlaybackHeadPosition > 0
          && state == PLAYSTATE_PLAYING) {
        // If connecting a Bluetooth audio device fails, the AudioTrack may be left in a state
        // where its Java API is in the playing state, but the native track is stopped. When this
        // happens the playback head position gets stuck at zero. In this case, return the old
        // playback head position and force the track to be reset after
        // {@link #FORCE_RESET_WORKAROUND_TIMEOUT_MS} has elapsed.
        if (forceResetWorkaroundTimeMs == C.TIME_UNSET) {
          forceResetWorkaroundTimeMs = currentTimeMs;
        }
        return;
      } else {
        forceResetWorkaroundTimeMs = C.TIME_UNSET;
      }
    }

    if (this.rawPlaybackHeadPosition > rawPlaybackHeadPosition) {
      // The value must have wrapped around.
      rawPlaybackHeadWrapCount++;
    }
    this.rawPlaybackHeadPosition = rawPlaybackHeadPosition;
  }
}
