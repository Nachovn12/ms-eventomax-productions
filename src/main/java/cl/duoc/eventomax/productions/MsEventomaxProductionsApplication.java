package cl.duoc.eventomax.productions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MsEventomaxProductionsApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsEventomaxProductionsApplication.class, args);
	}

}
