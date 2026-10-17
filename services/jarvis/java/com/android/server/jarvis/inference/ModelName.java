package com.android.server.jarvis.inference;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The name a person would use for an installed model, for status displays.
 * Models are installed as role.gguf, so the file name says nothing; a .gguf
 * file carries its own name in its metadata ("general.name", plus
 * "general.size_label" such as "1.7B", and "general.file_type", the
 * quantisation). A Cactus bundle is a folder and keeps
 * its folder name.
 */
public final class ModelName {

    private static final String TAG = "ModelName";
    private static final int GGUF_MAGIC = 0x46554747;   // "GGUF", little-endian
    private static final int TYPE_UINT32 = 4;
    private static final int TYPE_STRING = 8;
    private static final int TYPE_ARRAY = 9;
    /** Bytes per value of GGUF types 0-12; 0 for the variable-length string and array. */
    private static final int[] TYPE_SIZE = {1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8};

    private static String sPath;
    private static long sStamp;
    private static String sName;

    private ModelName() {}

    /** The model's name, or null if there is no model at {@code path}. Cached until the file changes. */
    public static synchronized String of(String path) {
        if (path == null) return null;
        File file = new File(path);
        if (!file.exists()) return null;
        final long stamp = file.lastModified() ^ file.length();
        if (path.equals(sPath) && stamp == sStamp) return sName;

        String name = file.isDirectory() ? file.getName() : null;
        if (name == null) {
            try {
                name = readGguf(file);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not read the metadata of " + path, e);
            }
            if (name == null) name = file.getName();
        }
        sPath = path;
        sStamp = stamp;
        sName = name;
        return name;
    }

    /** llama.cpp's llama_ftype numbers for the quantisations in common use. */
    private static String quantisation(int fileType) {
        switch (fileType) {
            case 0:  return "F32";
            case 1:  return "F16";
            case 2:  return "Q4_0";
            case 3:  return "Q4_1";
            case 7:  return "Q8_0";
            case 8:  return "Q5_0";
            case 9:  return "Q5_1";
            case 10: return "Q2_K";
            case 12: return "Q3_K_M";
            case 14: return "Q4_K_S";
            case 15: return "Q4_K_M";
            case 17: return "Q5_K_M";
            case 18: return "Q6_K";
            case 32: return "BF16";
            default: return null;
        }
    }

    /** "Name Size · Quant" from a GGUF header, or null if there is no name. */
    private static String readGguf(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            if (int32(in) != GGUF_MAGIC) return null;
            int32(in);                       // version
            int64(in);                       // tensor count
            final long keys = int64(in);
            String name = null;
            String size = null;
            String quant = null;
            for (long i = 0; i < keys && (name == null || size == null || quant == null); i++) {
                final String key = string(in);
                final int type = int32(in);
                if (type == TYPE_STRING && key.equals("general.name")) {
                    name = string(in);
                } else if (type == TYPE_STRING && key.equals("general.size_label")) {
                    size = string(in);
                } else if (type == TYPE_UINT32 && key.equals("general.file_type")) {
                    quant = quantisation(int32(in));
                } else {
                    skip(in, type);
                }
            }
            if (name == null) return null;
            if (size != null && !name.contains(size)) name += " " + size;
            return quant != null ? name + " · " + quant : name;
        }
    }

    private static void skip(DataInputStream in, int type) throws IOException {
        if (type == TYPE_STRING) {
            skipBytes(in, int64(in));
        } else if (type == TYPE_ARRAY) {
            final int element = int32(in);
            final long count = int64(in);
            for (long i = 0; i < count; i++) skip(in, element);
        } else if (type >= 0 && type < TYPE_SIZE.length) {
            skipBytes(in, TYPE_SIZE[type]);
        } else {
            throw new IOException("unknown GGUF value type " + type);
        }
    }

    private static void skipBytes(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) throw new EOFException();
            n -= skipped;
        }
    }

    private static String string(DataInputStream in) throws IOException {
        final long length = int64(in);
        if (length < 0 || length > 1 << 16) throw new IOException("GGUF string too long");
        byte[] bytes = new byte[(int) length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int int32(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static long int64(DataInputStream in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }
}
