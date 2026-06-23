package io.arkmem.memory.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class LocalHashEmbeddingClient implements EmbeddingClient {

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{Alnum}]+");

    private final int dimensions;

    public LocalHashEmbeddingClient(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[dimensions];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : TOKEN_SEPARATOR.split(normalized)) {
            if (token.isBlank()) {
                continue;
            }
            long hash = fnv1a64(token);
            int index = (int) Math.floorMod(hash, dimensions);
            double sign = (hash & 1L) == 0L ? 1.0d : -1.0d;
            vector[index] += sign * tokenWeight(token);
        }
        normalize(vector);
        List<Double> result = new ArrayList<>(dimensions);
        for (double value : vector) {
            result.add(value);
        }
        return result;
    }

    private static double tokenWeight(String token) {
        return 1.0d + Math.min(token.length(), 24) / 24.0d;
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= current;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void normalize(double[] vector) {
        double squaredSum = 0.0d;
        for (double value : vector) {
            squaredSum += value * value;
        }
        if (squaredSum == 0.0d) {
            return;
        }
        double norm = Math.sqrt(squaredSum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
