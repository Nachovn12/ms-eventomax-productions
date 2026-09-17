package cl.duoc.eventomax.productions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventomaxOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventoMax Productions API")
                        .description("Microservicio de dominio de EventoMax responsable de la gestión " +
                                "de eventos, estados y coordinación de producción.")
                        .version("1.0"));
    }
}

