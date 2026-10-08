package com.android.server.jarvis.inference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Turns recorded audio into what the speech model takes: 16 kHz mono floats in [-1, 1]. */
public final class Pcm {

    public static final int SAMPLE_RATE = 16_000;

    private Pcm() {}

    /** From 16 kHz mono 16-bit little-endian samples, as IJarvisService.processQueryWithAudio takes. */
    public static float[] fromPcm16(byte[] pcm) {
        return fromPcm16(pcm, 0, pcm.length, 1);
    }

    /**
     * From a 16-bit PCM WAV file of any sample rate and channel count.
     *
     * @throws IllegalArgumentException if it is not such a file
     */
    public static float[] fromWav(byte[] wav) {
        if (wav.length < 12 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            throw new IllegalArgumentException("not a WAV file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0, rate = 0, bits = 0, format = 0;
        for (int at = 12; at + 8 <= wav.length; ) {
            int size = buffer.getInt(at + 4);
            int body = at + 8;
            if (tag(wav, at, "fmt ") && body + 16 <= wav.length) {
                format = buffer.getShort(body) & 0xffff;
                channels = buffer.getShort(body + 2);
                rate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14);
            } else if (tag(wav, at, "data")) {
                if (format != 1 || bits != 16 || channels < 1 || rate < 1) {
                    throw new IllegalArgumentException("only 16-bit PCM WAV is supported");
                }
                // A streamed WAV may claim more data than it holds.
                int length = Math.min(size < 0 ? Integer.MAX_VALUE : size, wav.length - body);
                return resample(fromPcm16(wav, body, length, channels), rate);
            }
            if (size < 0) break;
            at = body + size + (size & 1);
        }
        throw new IllegalArgumentException("WAV file has no audio data");
    }

    private static float[] fromPcm16(byte[] pcm, int offset, int length, int channels) {
        ByteBuffer buffer = ByteBuffer.wrap(pcm, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[length / (2 * channels)];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += buffer.getShort();
            out[i] = sum / (32768f * channels);
        }
        return out;
    }

    /** Linear interpolation: enough for speech recognition, not for listening to. */
    private static float[] resample(float[] in, int rate) {
        if (rate == SAMPLE_RATE || in.length == 0) return in;
        float[] out = new float[(int) ((long) in.length * SAMPLE_RATE / rate)];
        final double step = (double) rate / SAMPLE_RATE;
        for (int i = 0; i < out.length; i++) {
            double position = i * step;
            int left = (int) position;
            int right = Math.min(left + 1, in.length - 1);
            float fraction = (float) (position - left);
            out[i] = in[left] * (1 - fraction) + in[right] * fraction;
        }
        return out;
    }

    private static boolean tag(byte[] data, int at, String tag) {
        for (int i = 0; i < 4; i++) {
            if (data[at + i] != tag.charAt(i)) return false;
        }
        return true;
    }
}
