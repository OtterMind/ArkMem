package io.arkmem.memory.embedding;

import java.util.List;

public interface EmbeddingClient {

    List<Double> embed(String text);

    default List<List<Double>> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
