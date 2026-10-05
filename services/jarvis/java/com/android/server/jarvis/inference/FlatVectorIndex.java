package com.android.server.jarvis.inference;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small vector index for engines that do not bring their own: every vector
 * is kept in memory and a query compares against all of them. Exact, and fast
 * enough for the tens of thousands of chunks one phone holds; not meant for
 * more.
 *
 * Stored as one file, {@code vectors.bin}, rewritten on every change:
 * magic, dimension, count, then (id, floats) per entry. Vectors are expected
 * to be normalised, so the dot product is the cosine similarity.
 */
final class FlatVectorIndex {

    private static final String TAG = "FlatVectorIndex";
    private static final int MAGIC = 0x4A565831;   // "JVX1"
    private static final String FILE_NAME = "vectors.bin";

    private final File mFile;
    private final int mDim;
    private final Map<Integer, float[]> mVectors = new LinkedHashMap<>();

    private FlatVectorIndex(File file, int dim) {
        mFile = file;
        mDim = dim;
    }

    /**
     * Opens the index in {@code dir}, creating it if absent. An existing file
     * with another dimension was written by a different embedding model and
     * is useless to this one, so it is discarded.
     *
     * @return the index, or null if the directory cannot be used
     */
    static FlatVectorIndex open(String dir, int dim) {
        if (dir == null || dim <= 0) return null;
        File folder = new File(dir);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            Log.e(TAG, "Cannot create " + dir);
            return null;
        }
        FlatVectorIndex index = new FlatVectorIndex(new File(folder, FILE_NAME), dim);
        index.load();
        return index;
    }

    private void load() {
        if (!mFile.isFile()) return;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(mFile)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a Jarvis vector file");
            final int dim = in.readInt();
            final int count = in.readInt();
            if (dim != mDim) {
                Log.w(TAG, mFile + " holds " + dim + "-d vectors, model gives " + mDim
                        + "-d; starting empty");
                return;
            }
            for (int i = 0; i < count; i++) {
                final int id = in.readInt();
                float[] vector = new float[dim];
                for (int j = 0; j < dim; j++) vector[j] = in.readFloat();
                mVectors.put(id, vector);
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not read " + mFile + "; starting empty", e);
            mVectors.clear();
        }
    }

    private boolean save() {
        File tmp = new File(mFile.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(mDim);
            out.writeInt(mVectors.size());
            for (Map.Entry<Integer, float[]> entry : mVectors.entrySet()) {
                out.writeInt(entry.getKey());
                for (float value : entry.getValue()) out.writeFloat(value);
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not write " + tmp, e);
            return false;
        }
        // Rename over the old file so a crash never leaves half an index.
        return tmp.renameTo(mFile);
    }

    synchronized int size() {
        return mVectors.size();
    }

    /** Adds or replaces the vector for {@code id}. */
    synchronized boolean add(int id, float[] vector) {
        if (vector == null || vector.length != mDim) return false;
        mVectors.put(id, vector.clone());
        return save();
    }

    synchronized boolean delete(int[] ids) {
        boolean changed = false;
        for (int id : ids) changed |= mVectors.remove(id) != null;
        return !changed || save();
    }

    /** IDs of the {@code topK} most similar vectors, best first. */
    synchronized int[] query(float[] query, int topK) {
        if (query == null || query.length != mDim || topK <= 0) return new int[0];
        // Best-first insertion into a short list: topK is single digits.
        List<Integer> ids = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (Map.Entry<Integer, float[]> entry : mVectors.entrySet()) {
            float[] vector = entry.getValue();
            float score = 0f;
            for (int i = 0; i < mDim; i++) score += query[i] * vector[i];
            int at = scores.size();
            while (at > 0 && scores.get(at - 1) < score) at--;
            if (at < topK) {
                ids.add(at, entry.getKey());
                scores.add(at, score);
                if (ids.size() > topK) {
                    ids.remove(topK);
                    scores.remove(topK);
                }
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }
}
