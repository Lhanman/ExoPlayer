/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.util;

import com.google.android.exoplayer2.PlaybackParameters;

/**
 * A {@link MediaClock} whose position advances with real time based on the playback parameters when
 * started.
 *
 * MediaClock ，其位置根据启动时的播放参数实时前进。
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class StandaloneMediaClock implements MediaClock {

  private final Clock clock;

  private boolean started;
  private long baseUs;
  private long baseElapsedMs;
  private PlaybackParameters playbackParameters;

  /**
   * Creates a new standalone media clock using the given {@link Clock} implementation.
   *
   * @param clock A {@link Clock}.
   */
  public StandaloneMediaClock(Clock clock) {
    this.clock = clock;
    playbackParameters = PlaybackParameters.DEFAULT;
  }

  /** Starts the clock. Does nothing if the clock is already started. */
  public void start() {
    if (!started) {
      // 存储 clock.elapsedRealtime() 的值作为 baseElapsedMs
      baseElapsedMs = clock.elapsedRealtime();
      started = true;
    }
  }

  /** Stops the clock. Does nothing if the clock is already stopped. */
  public void stop() {
    if (started) {
      resetPosition(getPositionUs());
      started = false;
    }
  }

  /**
   * Resets the clock's position.
   *
   * @param positionUs The position to set in microseconds.
   */
  public void resetPosition(long positionUs) {
    Log.i("StandaloneMediaClock", "resetPosition: positionUs = " + positionUs);
    baseUs = positionUs;
    if (started) {
      baseElapsedMs = clock.elapsedRealtime();
    }
  }

  @Override
  public long getPositionUs() {
    long positionUs = baseUs;
    if (started) {
      // 计算和 baseElapsedMs 之间的时间差
      long elapsedSinceBaseMs = clock.elapsedRealtime() - baseElapsedMs;
      if (playbackParameters.speed == 1f) {
        positionUs += Util.msToUs(elapsedSinceBaseMs);
      } else {
        // Add the media time in microseconds that will elapse in elapsedSinceBaseMs milliseconds of
        // wallclock time
        positionUs += playbackParameters.getMediaTimeUsForPlayoutTimeMs(elapsedSinceBaseMs);
      }
    }
    return positionUs;
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    // Store the current position as the new base, in case the playback speed has changed.
    if (started) {
      resetPosition(getPositionUs());
    }
    this.playbackParameters = playbackParameters;
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return playbackParameters;
  }
}
