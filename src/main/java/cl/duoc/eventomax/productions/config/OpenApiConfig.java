package cl.duoc.eventomax.productions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventoMax Productions API")
                        .version("1.0")
                        .description("Microservicio de dominio de EventoMax responsable de la gestión de eventos y producciones.")
                        .contact(new Contact()
                                .name("Equipo EventoMax")
                                .email("soporte@eventomax.com")));
    }
}
