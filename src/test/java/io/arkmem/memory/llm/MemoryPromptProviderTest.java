package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPromptProviderTest {

    @Test
    void rendersEnglishAndChineseTemplatesByLanguage() {
        MemoryExtractionRequest request = new MemoryExtractionRequest(
                List.of(new ChatMessage("user", "I prefer concise answers.", null)),
                List.of(memory("11111111-1111-1111-1111-111111111111", "User likes short summaries.")),
                "Capture stable preferences.",
                null,
                false
        );

        MemoryPromptProvider english = new MemoryPromptProvider("en");
        MemoryPromptProvider chinese = new MemoryPromptProvider("zh");

        assertThat(english.extractionSystemPrompt(request)).contains("Personal Information Organizer");
        assertThat(english.extractionUserPrompt(request)).contains("Additional extraction instructions");
        assertThat(english.extractionUserPrompt(request)).contains("must not require the original message to identify the subject");
        assertThat(english.extractionUserPrompt(request)).contains("Zhang San does not eat cilantro");
        assertThat(chinese.extractionSystemPrompt(request)).contains("个人信息整理器");
        assertThat(chinese.extractionUserPrompt(request)).contains("额外抽取指令");
        assertThat(chinese.extractionUserPrompt(request)).contains("不能依赖原始消息才能知道主语是谁");
        assertThat(chinese.extractionUserPrompt(request)).contains("张三不吃香菜");
    }

    @Test
    void supportsLanguageAliases() {
        assertThat(new MemoryPromptProvider("zh-cn").language()).isEqualTo("zh");
        assertThat(new MemoryPromptProvider("english").language()).isEqualTo("en");
    }

    private MemoryRecord memory(String id, String text) {
        return new MemoryRecord(id, text, Map.of(), List.of(0.1d, 0.2d), Instant.now(), Instant.now(), false);
    }
}
