package com.otaliastudios.transcoder.strategy;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import com.otaliastudios.transcoder.strategy.size.AtMostResizer;
import com.otaliastudios.transcoder.strategy.size.ExactResizer;
import com.otaliastudios.transcoder.strategy.size.ExactSize;
import com.otaliastudios.transcoder.strategy.size.FractionResizer;
import com.otaliastudios.transcoder.strategy.size.MultiResizer;
import com.otaliastudios.transcoder.strategy.size.PassThroughResizer;
import com.otaliastudios.transcoder.strategy.size.Size;
import com.otaliastudios.transcoder.strategy.size.Resizer;
import com.otaliastudios.transcoder.utils.Logger;
import com.otaliastudios.transcoder.utils.MediaFormatConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An {@link OutputStrategy} for video that converts it AVC with the given size.
 * The input and output aspect ratio must match.
 */
public class DefaultVideoStrategy implements OutputStrategy {
    private final static String TAG = "DefaultVideoStrategy";
    private final static Logger LOG = new Logger(TAG);

    private final static String MIME_TYPE = MediaFormatConstants.MIMETYPE_VIDEO_AVC;
    public final static long BITRATE_UNKNOWN = Long.MIN_VALUE;
    public final static float DEFAULT_I_FRAME_INTERVAL = 3;
    public final static int DEFAULT_FRAME_RATE = 30;

    /**
     * Holds configuration values.
     */
    public static class Options {
        private Options() {}
        private Resizer resizer;
        private long targetBitRate;
        private int targetFrameRate;
        private float targetIFrameInterval;
    }

    /**
     * Creates a new {@link Builder} with an {@link ExactResizer}
     * using given dimensions.
     *
     * @param firstSize the exact first size
     * @param secondSize the exact second size
     * @return a strategy builder
     */
    public static Builder exact(int firstSize, int secondSize) {
        return new Builder(new ExactResizer(firstSize, secondSize));
    }

    /**
     * Creates a new {@link Builder} with a {@link FractionResizer}
     * using given downscale fraction.
     *
     * @param fraction the downscale fraction
     * @return a strategy builder
     */
    public static Builder fraction(float fraction) {
        return new Builder(new FractionResizer(fraction));
    }

    /**
     * Creates a new {@link Builder} with an {@link AtMostResizer}
     * using given constraint.
     *
     * @param atMostSize size constraint
     * @return a strategy builder
     */
    public static Builder atMost(int atMostSize) {
        return new Builder(new AtMostResizer(atMostSize));
    }

    /**
     * Creates a new {@link Builder} with an {@link AtMostResizer}
     * using given constraints.
     *
     * @param atMostMajor constraint for the major dimension
     * @param atMostMinor constraint for the minor dimension
     * @return a strategy builder
     */
    public static Builder atMost(int atMostMinor, int atMostMajor) {
        return new Builder(new AtMostResizer(atMostMinor, atMostMajor));
    }

    public static class Builder {
        private MultiResizer resizer = new MultiResizer();
        private int targetFrameRate = DEFAULT_FRAME_RATE;
        private long targetBitRate = BITRATE_UNKNOWN;
        private float targetIFrameInterval = DEFAULT_I_FRAME_INTERVAL;

        public Builder() {
        }

        public Builder(@NonNull Resizer resizer) {
            this.resizer.addResizer(resizer);
        }

        /**
         * Adds another resizer to the resizer chain. By default, we use
         * a {@link MultiResizer} so you can add more than one resizer in chain.
         * @param resizer new resizer for backed {@link MultiResizer}
         * @return this for chaining
         */
        public Builder addResizer(@NonNull Resizer resizer) {
            this.resizer.addResizer(resizer);
            return this;
        }

        /**
         * The desired bit rate. Can optionally be {@link #BITRATE_UNKNOWN},
         * in which case the strategy will try to estimate the bitrate.
         * @param bitRate desired bit rate (bits per second)
         * @return this for chaining
         */
        public Builder bitRate(long bitRate) {
            targetBitRate = bitRate;
            return this;
        }

        /**
         * The desired frame rate. It will never be bigger than
         * the input frame rate, if that information is available.
         * @param frameRate desired frame rate (frames per second)
         * @return this for chaining
         */
        public Builder frameRate(int frameRate) {
            targetFrameRate = frameRate;
            return this;
        }

        /**
         * The interval between I-frames in seconds.
         * @param iFrameInterval desired i-frame interval
         * @return this for chaining
         */
        public Builder iFrameInterval(float iFrameInterval) {
            targetIFrameInterval = iFrameInterval;
            return this;
        }

        public Options options() {
            Options options = new Options();
            options.resizer = resizer;
            options.targetFrameRate = targetFrameRate;
            options.targetBitRate = targetBitRate;
            options.targetIFrameInterval = targetIFrameInterval;
            return options;
        }

        public DefaultVideoStrategy build() {
            return new DefaultVideoStrategy(options());
        }
    }

    private final Options options;

    public DefaultVideoStrategy(@NonNull Options options) {
        this.options = options;
    }

    @Nullable
    @Override
    public MediaFormat createOutputFormat(@NonNull MediaFormat inputFormat) throws OutputStrategyException {
        boolean typeDone = inputFormat.getString(MediaFormat.KEY_MIME).equals(MIME_TYPE);

        // Compute output size.
        int inWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        LOG.i("Input width&height: " + inWidth + "x" + inHeight);
        Size inSize = new ExactSize(inWidth, inHeight);
        Size outSize;
        try {
            outSize = options.resizer.getOutputSize(inSize);
        } catch (Exception e) {
            throw OutputStrategyException.unavailable(e);
        }
        int outWidth, outHeight;
        if (outSize instanceof ExactSize) {
            outWidth = ((ExactSize) outSize).getWidth();
            outHeight = ((ExactSize) outSize).getHeight();
        } else if (inWidth >= inHeight) {
            outWidth = outSize.getMajor();
            outHeight = outSize.getMinor();
        } else {
            outWidth = outSize.getMinor();
            outHeight = outSize.getMajor();
        }
        LOG.i("Output width&height: " + outWidth + "x" + outHeight);
        boolean sizeDone = inSize.getMinor() <= outSize.getMinor();

        // Compute output frame rate. It can't be bigger than input frame rate.
        int inputFrameRate, outFrameRate;
        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            inputFrameRate = inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            outFrameRate = Math.min(inputFrameRate, options.targetFrameRate);
        } else {
            inputFrameRate = -1;
            outFrameRate = options.targetFrameRate;
        }
        boolean frameRateDone = inputFrameRate <= outFrameRate;

        // Compute i frame.
        int inputIFrameInterval = -1;
        if (inputFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
            inputIFrameInterval = inputFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL);
        }
        boolean frameIntervalDone = inputIFrameInterval >= options.targetIFrameInterval;

        // See if we should go on.
        if (typeDone && sizeDone && frameRateDone && frameIntervalDone) {
            throw OutputStrategyException.alreadyCompressed(
                    "Input minSize: " + inSize.getMinor() + ", desired minSize: " + outSize.getMinor() +
                    "\nInput frameRate: " + inputFrameRate + ", desired frameRate: " + outFrameRate +
                    "\nInput iFrameInterval: " + inputIFrameInterval + ", desired iFrameInterval: " + options.targetIFrameInterval);
        }

        // Create the actual format.
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, outWidth, outHeight);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, outFrameRate);
        if (Build.VERSION.SDK_INT >= 25) {
            format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, options.targetIFrameInterval);
        } else {
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, (int) Math.ceil(options.targetIFrameInterval));
        }
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int outBitRate = (int) (options.targetBitRate == BITRATE_UNKNOWN ?
                estimateBitRate(outWidth, outHeight, outFrameRate) : options.targetBitRate);
        format.setInteger(MediaFormat.KEY_BIT_RATE, outBitRate);
        return format;
    }

    // Depends on the codec, but for AVC this is a reasonable default ?
    // https://stackoverflow.com/a/5220554/4288782
    private static long estimateBitRate(int width, int height, int frameRate) {
        return (long) (0.07F * 2 * width * height * frameRate);
    }

    private static void copyInteger(@NonNull MediaFormat input, @NonNull MediaFormat output,
                                    @NonNull String key, @Nullable Integer fallback) {
        if (input.containsKey(key)) {
            output.setInteger(key, input.getInteger(key));
        } else if (fallback != null) {
            output.setInteger(key, fallback);
        }
    }
}
