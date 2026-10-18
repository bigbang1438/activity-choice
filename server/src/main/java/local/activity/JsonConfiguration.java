package local.activity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Version numbers and invitation durations must keep their submitted JSON type. */
@Configuration
class JsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictNumbers() {
        return builder -> builder.featuresToDisable(
            DeserializationFeature.ACCEPT_FLOAT_AS_INT,
            MapperFeature.ALLOW_COERCION_OF_SCALARS
        );
    }
}
