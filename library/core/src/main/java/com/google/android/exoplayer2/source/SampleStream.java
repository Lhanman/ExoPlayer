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
package com.google.android.exoplayer2.source;

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer.InsufficientCapacityException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stream of media samples (and associated format information).
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public interface SampleStream {

  /**
   * Flags that can be specified when calling {@link #readData}. Possible flag values are {@link
   * #FLAG_PEEK}, {@link #FLAG_REQUIRE_FORMAT} and {@link #FLAG_OMIT_SAMPLE_DATA}.
   *
   * 这些标志可以单独使用，也可以组合使用（通过按位或操作）。
   * 例如，FLAG_PEEK | FLAG_OMIT_SAMPLE_DATA 可以用来预览样本元数据而不移动读取位置或加载实际数据。
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {FLAG_PEEK, FLAG_REQUIRE_FORMAT, FLAG_OMIT_SAMPLE_DATA})
  @interface ReadFlags {}
  /** Specifies that the read position should not be advanced if a sample buffer is read.
   *
   * 指示读取操作不应该前进读取位置。
   * 用于预览数据而不实际消耗它。
   * */
  int FLAG_PEEK = 1;
  /**
   * Specifies that if a sample buffer would normally be read next, the format of the stream should
   * be read instead. In detail, the effect of this flag is as follows:
   *
   * <ul>
   *   <li>If a sample buffer would be read were the flag not set, then the stream format will be
   *       read instead.
   *   <li>If nothing would be read were the flag not set, then the stream format will be read if
   *       it's known. If the stream format is not known then behavior is unchanged.
   *   <li>If an end of stream buffer would be read were the flag not set, then behavior is
   *       unchanged.
   * </ul>
   *
   * 要求读取流的格式信息，而不是样本数据。
   * 如果正常情况下会读取样本缓冲区，这个标志会使其读取流格式信息。
   * 如果正常情况下不会读取任何内容，但流格式已知，则会读取流格式信息。
   */
  int FLAG_REQUIRE_FORMAT = 1 << 1;
  /**
   * Specifies that {@link DecoderInputBuffer#data}, {@link DecoderInputBuffer#supplementalData} and
   * {@link DecoderInputBuffer#cryptoInfo} should not be populated when reading a sample buffer.
   *
   * <p>This flag is useful for efficiently reading or (when combined with {@link #FLAG_PEEK})
   * peeking sample metadata. It can also be used for efficiency by a caller wishing to skip a
   * sample buffer.
   *
   * 指示在读取样本缓冲区时不填充实际的样本数据。
   * 用于高效地读取或预览样本元数据，或者用于跳过样本数据。
   */
  int FLAG_OMIT_SAMPLE_DATA = 1 << 2;

  /** Return values of {@link #readData}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({C.RESULT_NOTHING_READ, C.RESULT_FORMAT_READ, C.RESULT_BUFFER_READ})
  @interface ReadDataResult {}

  /**
   * Returns whether data is available to be read.
   *
   * <p>Note: If the stream has ended then a buffer with the end of stream flag can always be read
   * from {@link #readData}. Hence an ended stream is always ready.
   *
   * @return Whether data is available to be read.
   */
  boolean isReady();

  /**
   * Throws an error that's preventing data from being read. Does nothing if no such error exists.
   *
   * @throws IOException The underlying error.
   */
  void maybeThrowError() throws IOException;

  /**
   * Attempts to read from the stream.
   *
   * <p>If the stream has ended then {@link C#BUFFER_FLAG_END_OF_STREAM} flag is set on {@code
   * buffer} and {@link C#RESULT_BUFFER_READ} is returned. Else if no data is available then {@link
   * C#RESULT_NOTHING_READ} is returned. Else if the format of the media is changing or if {@code
   * formatRequired} is set then {@code formatHolder} is populated and {@link C#RESULT_FORMAT_READ}
   * is returned. Else {@code buffer} is populated and {@link C#RESULT_BUFFER_READ} is returned.
   *
   * @param formatHolder A {@link FormatHolder} to populate in the case of reading a format.
   * @param buffer A {@link DecoderInputBuffer} to populate in the case of reading a sample or the
   *     end of the stream. If the end of the stream has been reached, the {@link
   *     C#BUFFER_FLAG_END_OF_STREAM} flag will be set on the buffer.
   * @param readFlags Flags controlling the behavior of this read operation.
   * @return The {@link ReadDataResult result} of the read operation.
   * @throws InsufficientCapacityException If the {@code buffer} has insufficient capacity to hold
   *     the data of a sample being read. The buffer {@link DecoderInputBuffer#timeUs timestamp} and
   *     flags are populated if this exception is thrown, but the read position is not advanced.
   */
  @ReadDataResult
  int readData(FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags);

  /**
   * Attempts to skip to the keyframe before the specified position, or to the end of the stream if
   * {@code positionUs} is beyond it.
   *
   * @param positionUs The specified time.
   * @return The number of samples that were skipped.
   */
  int skipData(long positionUs);
}
