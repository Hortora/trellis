package io.hortora.trellis;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;

@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    @Override
    public void customize(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var module = new SimpleModule("trellis");
        module.addSerializer(Path.class, new PathSerializer());
        module.addDeserializer(Path.class, new PathDeserializer());
        mapper.registerModule(module);
    }

    static class PathSerializer extends JsonSerializer<Path> {
        @Override
        public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toString());
        }
    }

    static class PathDeserializer extends JsonDeserializer<Path> {
        @Override
        public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Path.of(p.getText());
        }
    }
}
