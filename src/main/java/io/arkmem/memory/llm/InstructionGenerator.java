package io.arkmem.memory.llm;

public interface InstructionGenerator {

    InstructionSuggestion generateInstructions(String useCase);
}
